package com.caseware.updates.fanout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** In-memory doubles. Deliberately simple; the behaviour under test is the worker, not these. */
final class Fakes {

    /** Projection with a stable-order scan and recorded writes. */
    static final class InMemoryProjection implements EngagementProjection {
        final Map<String, EngagementRow> rows = new LinkedHashMap<>();
        final List<PendingUpdate> pendingWrites = new CopyOnWriteArrayList<>();
        final Map<String, String> confirmedVersions = new ConcurrentHashMap<>();
        volatile RuntimeException failPendingWriteWith;

        void add(EngagementRow row) { rows.put(row.engagementId(), row); }

        @Override
        public Page<EngagementRow> scanCandidates(String templateId, String marketId, String cursor, int pageSize) {
            List<EngagementRow> all = new ArrayList<>();
            for (EngagementRow r : rows.values()) {
                if (r.templateId().equals(templateId) && r.marketId().equals(marketId)) all.add(r);
            }
            int from = cursor == null ? 0 : Integer.parseInt(cursor);
            int to = Math.min(from + pageSize, all.size());
            String next = to < all.size() ? String.valueOf(to) : null;
            return new Page<>(new ArrayList<>(all.subList(from, to)), next);
        }

        @Override
        public void markPendingUpdate(PendingUpdate update) {
            if (failPendingWriteWith != null) throw failPendingWriteWith;
            pendingWrites.add(update);
        }

        @Override
        public void recordConfirmedVersion(String engagementId, String templateId, String version, Instant observedAt) {
            confirmedVersions.put(engagementId, version);
        }
    }

    /**
     * Loader that records peak concurrency (so capacity limits can be asserted) and lets each engagement
     * be scripted to succeed, fail transiently N times, or fail terminally.
     */
    static final class RecordingLoader implements EngagementLoader {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peakConcurrency = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final List<String> loadedIds = new CopyOnWriteArrayList<>();
        final Map<String, AtomicInteger> transientFailuresLeft = new ConcurrentHashMap<>();
        final Set<String> terminalFailures = ConcurrentHashMap.newKeySet();
        final Set<String> alwaysUnavailable = ConcurrentHashMap.newKeySet();
        Function<String, String> versionByEngagement = id -> "v3";
        String templateId = "T1";
        long artificialLatencyMillis = 20;

        @Override
        public LoadedEngagement loadTemplateState(String engagementId)
                throws DownstreamUnavailableException, EngagementUnloadableException {
            calls.incrementAndGet();
            int now = inFlight.incrementAndGet();
            peakConcurrency.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(artificialLatencyMillis);
                if (terminalFailures.contains(engagementId)) {
                    throw new EngagementUnloadableException("corrupt engagement " + engagementId);
                }
                if (alwaysUnavailable.contains(engagementId)) {
                    throw new DownstreamUnavailableException("downstream at capacity");
                }
                AtomicInteger left = transientFailuresLeft.get(engagementId);
                if (left != null && left.getAndDecrement() > 0) {
                    throw new DownstreamUnavailableException("transient blip");
                }
                loadedIds.add(engagementId);
                return new LoadedEngagement(engagementId, templateId, versionByEngagement.apply(engagementId));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownstreamUnavailableException("interrupted", e);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    static final class InMemoryIdempotencyStore implements IdempotencyStore {
        final Set<String> claimed = ConcurrentHashMap.newKeySet();
        final Set<String> completed = ConcurrentHashMap.newKeySet();

        @Override
        public boolean tryClaim(String key) {
            if (completed.contains(key)) return false;
            return claimed.add(key);
        }

        @Override public void markCompleted(String key) { completed.add(key); }
        @Override public void release(String key) { claimed.remove(key); }
    }

    static final class InMemoryCheckpointStore implements CheckpointStore {
        final Map<String, String> cursors = new ConcurrentHashMap<>();
        final List<String> saves = new CopyOnWriteArrayList<>();

        @Override public String loadCursor(String jobId) { return cursors.get(jobId); }
        @Override public void saveCursor(String jobId, String cursor) { cursors.put(jobId, cursor); saves.add(cursor); }
        @Override public void clear(String jobId) { cursors.remove(jobId); }
    }

    static final class RecordingDeadLetters implements DeadLetterSink {
        final List<String> entries = new CopyOnWriteArrayList<>();

        @Override
        public void record(String engagementId, PublishEvent event, String reason, Throwable cause) {
            entries.add(engagementId + ": " + reason);
        }
    }

    /**
     * Version graph built from explicit parent edges, so branching and withdrawal are modelled rather
     * than implied by version numbering.
     */
    static final class SimpleVersionGraph implements VersionGraph {
        private final Map<String, String> parentOf = new HashMap<>(); // "T1/v7" -> "T1/v5"
        private final Set<String> withdrawn = new HashSet<>();

        SimpleVersionGraph edge(String templateId, String parent, String child) {
            parentOf.put(templateId + '/' + child, templateId + '/' + parent);
            return this;
        }

        SimpleVersionGraph withdraw(String templateId, String version) {
            withdrawn.add(templateId + '/' + version);
            return this;
        }

        @Override
        public boolean isAncestor(String templateId, String ancestor, String descendant) {
            String target = templateId + '/' + ancestor;
            String walk = parentOf.get(templateId + '/' + descendant);
            int guard = 0;
            while (walk != null && guard++ < 1000) {
                if (walk.equals(target)) return true;
                walk = parentOf.get(walk);
            }
            return false;
        }

        @Override
        public boolean isWithdrawn(String templateId, String version) {
            return withdrawn.contains(templateId + '/' + version);
        }
    }

    private Fakes() { }
}
