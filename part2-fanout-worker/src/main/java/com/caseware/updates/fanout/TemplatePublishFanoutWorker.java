package com.caseware.updates.fanout;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fans a template publication out over the engagements that may be affected by it.
 *
 * <h2>What this component is for</h2>
 * A publish can affect tens of thousands of engagements, and the only way to learn an engagement's true
 * applied version is a downstream load that costs ~1 minute and belongs to another team. Answering the
 * publish by loading engagements would cost ~333 hours per publish (20,000 x 1 min), so the projection —
 * not the engagement — answers the common case in milliseconds. The downstream call is reserved for the
 * rows the projection cannot answer for yet: {@code INFERRED} or {@code UNKNOWN} versions, i.e. the
 * backfill frontier. That set shrinks to ~0 as backfill completes; the steady state performs no loads.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>Idempotent</b>: at-least-once redelivery of the same {@code eventId} performs each
 *       engagement's work once. All projection writes are state assignments, so even a lost claim
 *       converges rather than corrupting.</li>
 *   <li><b>Bounded downstream pressure</b>: concurrency is capped by a semaphore and total spend by a
 *       per-job load budget. Exceeding either defers rows to backfill instead of queueing without bound
 *       or starving the owning team.</li>
 *   <li><b>Resumable</b>: the scan cursor is checkpointed per page, so a restart replays at most one
 *       page. There is no maintenance window; restarts are normal.</li>
 *   <li><b>Order-independent</b>: lineage and withdrawal are re-read from the version graph rather than
 *       trusted from the event payload, and withdrawal is re-checked between pages.</li>
 *   <li><b>Visible failure</b>: terminal failures are dead-lettered, never dropped. A dropped engagement
 *       would tell a user "up to date" when an update is pending — the worst outcome in this domain.</li>
 * </ul>
 *
 * <p>Thread-safe; one instance is meant to be shared and reused (it owns a pool sized to our share of
 * downstream capacity).
 */
public final class TemplatePublishFanoutWorker implements AutoCloseable {

    /** Injected so retry backoff is instant in tests without weakening the production policy. */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;

        Sleeper REAL = d -> Thread.sleep(Math.max(0L, d.toMillis()));
    }

    private final EngagementProjection projection;
    private final EngagementLoader loader;
    private final VersionGraph versionGraph;
    private final IdempotencyStore idempotency;
    private final CheckpointStore checkpoints;
    private final DeadLetterSink deadLetters;
    private final Metrics metrics;
    private final FanOutConfig config;
    private final Clock clock;
    private final Sleeper sleeper;

    private final Semaphore downstreamPermits;
    private final ExecutorService executor;

    public TemplatePublishFanoutWorker(EngagementProjection projection,
                                       EngagementLoader loader,
                                       VersionGraph versionGraph,
                                       IdempotencyStore idempotency,
                                       CheckpointStore checkpoints,
                                       DeadLetterSink deadLetters,
                                       Metrics metrics,
                                       FanOutConfig config,
                                       Clock clock,
                                       Sleeper sleeper) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.versionGraph = Objects.requireNonNull(versionGraph, "versionGraph");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
        this.metrics = metrics == null ? Metrics.NOOP : metrics;
        this.config = Objects.requireNonNull(config, "config");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.sleeper = sleeper == null ? Sleeper.REAL : sleeper;
        this.downstreamPermits = new Semaphore(config.maxConcurrentLoads());
        this.executor = Executors.newFixedThreadPool(config.maxConcurrentLoads(), namedDaemonFactory());
    }

    /**
     * Processes one publish event to completion, partial completion, or resumable interruption.
     *
     * <p>Never throws for expected production conditions (downstream down, budget exhausted, partial
     * scan): those are reported in the {@link FanOutResult} so the caller can decide whether to ack the
     * message, re-drive it, or let reconciliation finish the job.
     */
    public FanOutResult process(PublishEvent event) {
        Objects.requireNonNull(event, "event");
        long startedAt = clock.millis();
        String jobId = "fanout:" + event.eventId();
        Job job = new Job(event, config.maxLoadsPerJob());

        // Withdrawal may have happened before this event was processed; "withdrawn" and "published" can
        // arrive in any order, so the graph — not the payload — is the authority.
        if (versionGraph.isWithdrawn(event.templateId(), event.version())) {
            metrics.increment("fanout.skipped_withdrawn");
            checkpoints.clear(jobId);
            return job.result(FanOutResult.Status.SKIPPED_WITHDRAWN, null);
        }

        String cursor = checkpoints.loadCursor(jobId);
        try {
            while (true) {
                Page<EngagementRow> page =
                        projection.scanCandidates(event.templateId(), event.marketId(), cursor, config.pageSize());

                List<Future<?>> inFlight = new ArrayList<>();
                for (EngagementRow row : page.rows()) {
                    dispatch(row, job, inFlight);
                }
                awaitAll(inFlight);

                // Re-check between pages: a long fan-out over 40,000 engagements can outlive the validity
                // of its own target version.
                if (versionGraph.isWithdrawn(event.templateId(), event.version())) {
                    metrics.increment("fanout.aborted_withdrawn_midflight");
                    checkpoints.clear(jobId);
                    return job.result(FanOutResult.Status.SKIPPED_WITHDRAWN, null);
                }

                cursor = page.nextCursor();
                if (cursor == null) {
                    checkpoints.clear(jobId);
                    break;
                }
                checkpoints.saveCursor(jobId, cursor);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.increment("fanout.interrupted");
            return job.result(FanOutResult.Status.INCOMPLETE, cursor);
        } catch (RuntimeException e) {
            // Scan or projection infrastructure failed. The cursor of the last completed page is durable,
            // so re-driving this event resumes rather than restarting.
            metrics.increment("fanout.job_failed");
            deadLetters.record("<job>", event, "fan-out job failed mid-scan", e);
            return job.result(FanOutResult.Status.INCOMPLETE, cursor);
        } finally {
            metrics.observeMillis("fanout.job_duration_ms", clock.millis() - startedAt);
        }

        FanOutResult.Status status = job.deferred.get() > 0
                ? FanOutResult.Status.COMPLETED_WITH_DEFERRALS
                : FanOutResult.Status.COMPLETED;
        return job.result(status, null);
    }

    // ---------------------------------------------------------------- dispatch

    private void dispatch(EngagementRow row, Job job, List<Future<?>> inFlight) throws InterruptedException {
        job.scanned.incrementAndGet();
        PublishEvent event = job.event;

        Action action = classify(row, event);
        if (action == Action.SKIP_ARCHIVED) {
            job.skippedArchived.incrementAndGet();
            return;
        }
        if (action == Action.ALREADY_CURRENT) {
            job.alreadyCurrent.incrementAndGet();
            return;
        }
        if (action == Action.OUT_OF_LINEAGE) {
            job.notInLineage.incrementAndGet();
            return;
        }

        // Claim before doing anything observable: a duplicate delivery must not re-notify a user and must
        // not spend a second minute of another team's capacity.
        String key = idempotencyKey(event, row.engagementId());
        if (!idempotency.tryClaim(key)) {
            job.duplicatesSuppressed.incrementAndGet();
            metrics.increment("fanout.duplicate_suppressed");
            return;
        }

        if (action == Action.MARK_PENDING) {
            try {
                markPending(row.engagementId(), row.appliedVersion(), job);
                idempotency.markCompleted(key);
            } catch (RuntimeException e) {
                idempotency.release(key);
                job.deadLettered.incrementAndGet();
                deadLetters.record(row.engagementId(), event, "projection write failed", e);
                metrics.increment("fanout.projection_write_failed");
            }
            return;
        }

        // NEEDS_VERIFICATION: the expensive path. Guarded by breaker, budget, then a permit.
        if (job.breakerOpen(config.circuitBreakerThreshold())) {
            defer(job, key, "circuit_breaker_open");
            return;
        }
        if (!job.tryTakeLoadBudget()) {
            defer(job, key, "job_load_budget_exhausted");
            return;
        }
        if (!downstreamPermits.tryAcquire(config.permitWait().toMillis(), TimeUnit.MILLISECONDS)) {
            job.returnLoadBudget();
            defer(job, key, "no_downstream_permit");
            return;
        }

        inFlight.add(executor.submit(() -> {
            try {
                verifyAndResolve(row, job, key);
            } finally {
                downstreamPermits.release();
            }
        }));
    }

    /**
     * Deferral is a normal outcome, not an error: the row keeps its "Verifying" state and the background
     * backfill (same downstream, same limiter) resolves it. We would rather be slow than starve the team
     * that owns engagement loading.
     */
    private void defer(Job job, String idempotencyKey, String reason) {
        idempotency.release(idempotencyKey);
        job.deferred.incrementAndGet();
        metrics.increment("fanout.deferred." + reason);
    }

    // ---------------------------------------------------------------- expensive path

    private void verifyAndResolve(EngagementRow row, Job job, String key) {
        PublishEvent event = job.event;
        try {
            EngagementLoader.LoadedEngagement loaded = loadWithRetry(row.engagementId());
            job.loadsPerformed.incrementAndGet();
            job.resetBreaker();

            projection.recordConfirmedVersion(loaded.engagementId(), loaded.templateId(),
                    loaded.appliedVersion(), clock.instant());

            // The load is the source of truth and may contradict the projection (e.g. a roll-forward that
            // inherited another template). Re-classify against what we actually read.
            EngagementRow confirmed = new EngagementRow(loaded.engagementId(), row.firmId(),
                    loaded.templateId(), loaded.appliedVersion(), row.marketId(), VersionSource.CONFIRMED,
                    row.archived());

            switch (classify(confirmed, event)) {
                case MARK_PENDING:
                    markPending(confirmed.engagementId(), confirmed.appliedVersion(), job);
                    break;
                case ALREADY_CURRENT:
                    job.alreadyCurrent.incrementAndGet();
                    break;
                default:
                    job.notInLineage.incrementAndGet();
                    break;
            }
            idempotency.markCompleted(key);

        } catch (EngagementLoader.EngagementUnloadableException e) {
            // Terminal for this engagement: retrying wastes a scarce minute every time. Keep the claim so
            // redelivery does not repeat it, and make it visible.
            idempotency.markCompleted(key);
            job.deadLettered.incrementAndGet();
            job.resetBreaker();
            deadLetters.record(row.engagementId(), event, "engagement permanently unloadable", e);
            metrics.increment("fanout.load_terminal_failure");

        } catch (EngagementLoader.DownstreamUnavailableException e) {
            // Transient: release the claim so this engagement is eligible again on redelivery or backfill.
            idempotency.release(key);
            job.deadLettered.incrementAndGet();
            job.recordBreakerFailure();
            deadLetters.record(row.engagementId(), event, "downstream unavailable after retries", e);
            metrics.increment("fanout.load_retries_exhausted");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            idempotency.release(key);
            job.deferred.incrementAndGet();

        } catch (RuntimeException e) {
            idempotency.release(key);
            job.deadLettered.incrementAndGet();
            job.recordBreakerFailure();
            deadLetters.record(row.engagementId(), event, "unexpected failure during verification", e);
            metrics.increment("fanout.verification_unexpected_failure");
        }
    }

    private EngagementLoader.LoadedEngagement loadWithRetry(String engagementId)
            throws EngagementLoader.DownstreamUnavailableException,
                   EngagementLoader.EngagementUnloadableException,
                   InterruptedException {
        EngagementLoader.DownstreamUnavailableException last = null;
        for (int attempt = 1; attempt <= config.maxAttemptsPerLoad(); attempt++) {
            long started = clock.millis();
            try {
                EngagementLoader.LoadedEngagement loaded = loader.loadTemplateState(engagementId);
                metrics.observeMillis("fanout.load_duration_ms", clock.millis() - started);
                return loaded;
            } catch (EngagementLoader.DownstreamUnavailableException e) {
                last = e;
                metrics.increment("fanout.load_retryable_failure");
                if (attempt < config.maxAttemptsPerLoad()) {
                    sleeper.sleep(backoffFor(attempt));
                }
            }
        }
        throw last;
    }

    /** Exponential backoff with full jitter: a downstream with limited capacity must not be stampeded. */
    private Duration backoffFor(int attempt) {
        long base = config.retryBaseBackoff().toMillis() * (1L << (attempt - 1));
        long jittered = base == 0 ? 0 : ThreadLocalRandom.current().nextLong(base / 2 + 1, base + 1);
        return Duration.ofMillis(jittered);
    }

    private void markPending(String engagementId, String fromVersion, Job job) {
        projection.markPendingUpdate(new PendingUpdate(engagementId, job.event.templateId(), fromVersion,
                job.event.version(), job.event.eventId()));
        job.markedPending.incrementAndGet();
        metrics.increment("fanout.marked_pending");
    }

    // ---------------------------------------------------------------- classification

    private enum Action { SKIP_ARCHIVED, ALREADY_CURRENT, OUT_OF_LINEAGE, MARK_PENDING, NEEDS_VERIFICATION }

    /**
     * Decides what a row needs, without touching the engagement.
     *
     * <p>"Pending" is an ancestry test, never {@code version < newVersion}: markets branch, so an
     * engagement on a CA branch must not be offered an EU descendant even though its version number is
     * lower. Market on the row only narrows the scan; lineage is what decides.
     */
    private Action classify(EngagementRow row, PublishEvent event) {
        if (row.archived()) {
            return Action.SKIP_ARCHIVED;
        }
        if (!row.templateId().equals(event.templateId())) {
            return Action.OUT_OF_LINEAGE;
        }
        if (row.versionSource() != VersionSource.CONFIRMED || row.appliedVersion() == null) {
            return Action.NEEDS_VERIFICATION;
        }
        if (row.appliedVersion().equals(event.version())) {
            return Action.ALREADY_CURRENT;
        }
        return versionGraph.isAncestor(event.templateId(), row.appliedVersion(), event.version())
                ? Action.MARK_PENDING
                : Action.OUT_OF_LINEAGE;
    }

    private static String idempotencyKey(PublishEvent event, String engagementId) {
        return event.eventId() + '|' + engagementId;
    }

    private static void awaitAll(List<Future<?>> futures) throws InterruptedException {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (java.util.concurrent.ExecutionException e) {
                // Tasks handle their own failures; anything here is a defect, not a production condition.
                throw new IllegalStateException("fan-out task escaped its error handling", e.getCause());
            }
        }
    }

    private static ThreadFactory namedDaemonFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "template-fanout-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(90, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Per-job mutable state: counters, load budget and circuit breaker. */
    private static final class Job {
        final PublishEvent event;
        final AtomicInteger scanned = new AtomicInteger();
        final AtomicInteger markedPending = new AtomicInteger();
        final AtomicInteger alreadyCurrent = new AtomicInteger();
        final AtomicInteger notInLineage = new AtomicInteger();
        final AtomicInteger skippedArchived = new AtomicInteger();
        final AtomicInteger duplicatesSuppressed = new AtomicInteger();
        final AtomicInteger loadsPerformed = new AtomicInteger();
        final AtomicInteger deferred = new AtomicInteger();
        final AtomicInteger deadLettered = new AtomicInteger();
        private final AtomicInteger loadBudget;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();

        Job(PublishEvent event, int budget) {
            this.event = event;
            this.loadBudget = new AtomicInteger(budget);
        }

        boolean tryTakeLoadBudget() {
            while (true) {
                int current = loadBudget.get();
                if (current <= 0) return false;
                if (loadBudget.compareAndSet(current, current - 1)) return true;
            }
        }

        void returnLoadBudget() { loadBudget.incrementAndGet(); }
        void recordBreakerFailure() { consecutiveFailures.incrementAndGet(); }
        void resetBreaker() { consecutiveFailures.set(0); }
        boolean breakerOpen(int threshold) { return consecutiveFailures.get() >= threshold; }

        FanOutResult result(FanOutResult.Status status, String resumeCursor) {
            return new FanOutResult(status, resumeCursor, scanned.get(), markedPending.get(),
                    alreadyCurrent.get(), notInLineage.get(), skippedArchived.get(),
                    duplicatesSuppressed.get(), loadsPerformed.get(), deferred.get(), deadLettered.get());
        }
    }
}
