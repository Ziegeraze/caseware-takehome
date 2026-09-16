package com.caseware.updates.fanout;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the properties that matter in production: the expensive downstream is used only
 * where it is unavoidable, capacity is respected, redelivery is free, lineage is honoured, failures are
 * visible, and a partial run resumes.
 */
class TemplatePublishFanoutWorkerTest {

    // Lineage: v1 - v2 - v3 - v4 - v5 - v7  (EU),  v3 - v6 (CA branch)
    private static final String T = "T1";

    private Fakes.InMemoryProjection projection;
    private Fakes.RecordingLoader loader;
    private Fakes.SimpleVersionGraph graph;
    private Fakes.InMemoryIdempotencyStore idempotency;
    private Fakes.InMemoryCheckpointStore checkpoints;
    private Fakes.RecordingDeadLetters deadLetters;
    private TemplatePublishFanoutWorker worker;

    @BeforeEach
    void setUp() {
        projection = new Fakes.InMemoryProjection();
        loader = new Fakes.RecordingLoader();
        graph = new Fakes.SimpleVersionGraph()
                .edge(T, "v1", "v2").edge(T, "v2", "v3").edge(T, "v3", "v4")
                .edge(T, "v4", "v5").edge(T, "v5", "v7").edge(T, "v3", "v6");
        idempotency = new Fakes.InMemoryIdempotencyStore();
        checkpoints = new Fakes.InMemoryCheckpointStore();
        deadLetters = new Fakes.RecordingDeadLetters();
    }

    @AfterEach
    void tearDown() {
        if (worker != null) worker.close();
    }

    private void build(FanOutConfig config) {
        worker = new TemplatePublishFanoutWorker(projection, loader, graph, idempotency, checkpoints,
                deadLetters, Metrics.NOOP, config, Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC),
                d -> { /* no real backoff in tests */ });
    }

    private static FanOutConfig config() {
        return new FanOutConfig().retryBaseBackoff(Duration.ZERO).permitWait(Duration.ofSeconds(2));
    }

    private static PublishEvent publishV7() {
        return new PublishEvent("evt-1", T, "v7", "EU", Instant.parse("2026-03-10T09:59:00Z"));
    }

    private static EngagementRow row(String id, String version, VersionSource source) {
        return new EngagementRow(id, "firm-1", T, version, "EU", source, false);
    }

    @Test
    @DisplayName("confirmed rows are resolved from the projection alone: no engagement loads")
    void confirmedRowsNeverTouchDownstream() {
        for (int i = 0; i < 50; i++) {
            projection.add(row("e" + i, "v3", VersionSource.CONFIRMED));
        }
        build(config());

        FanOutResult result = worker.process(publishV7());

        assertEquals(FanOutResult.Status.COMPLETED, result.status());
        assertEquals(50, result.markedPending());
        assertEquals(0, loader.calls.get(), "steady state must cost zero downstream minutes");
        assertEquals(50, projection.pendingWrites.size());
        assertEquals("v3", projection.pendingWrites.get(0).fromVersion());
        assertEquals("v7", projection.pendingWrites.get(0).toVersion());
    }

    @Test
    @DisplayName("only the backfill frontier (UNKNOWN/INFERRED) spends downstream capacity")
    void onlyUnverifiedRowsAreLoaded() {
        projection.add(row("confirmed-1", "v3", VersionSource.CONFIRMED));
        projection.add(row("unknown-1", null, VersionSource.UNKNOWN));
        projection.add(row("inferred-1", "v4", VersionSource.INFERRED));
        loader.versionByEngagement = id -> "v4"; // truth for both unverified rows
        build(config());

        FanOutResult result = worker.process(publishV7());

        assertEquals(2, loader.calls.get());
        assertTrue(loader.loadedIds.containsAll(java.util.Arrays.asList("unknown-1", "inferred-1")));
        assertFalse(loader.loadedIds.contains("confirmed-1"));
        assertEquals(3, result.markedPending(), "all three sit on ancestors of v7");
        assertEquals("v4", projection.confirmedVersions.get("unknown-1"), "a load also heals the projection");
    }

    @Test
    @DisplayName("ancestry, not version ordering, decides: a CA-branch engagement is not offered an EU descendant")
    void branchesAreNotOfferedForeignDescendants() {
        projection.add(row("eu-on-v5", "v5", VersionSource.CONFIRMED));
        projection.add(row("ca-on-v6", "v6", VersionSource.CONFIRMED)); // 6 < 7 but not an ancestor of v7
        projection.add(row("already-v7", "v7", VersionSource.CONFIRMED));
        projection.add(new EngagementRow("archived-v3", "firm-1", T, "v3", "EU", VersionSource.CONFIRMED, true));
        build(config());

        FanOutResult result = worker.process(publishV7());

        assertEquals(1, result.markedPending());
        assertEquals("eu-on-v5", projection.pendingWrites.get(0).engagementId());
        assertEquals(1, result.notInLineage());
        assertEquals(1, result.alreadyCurrent());
        assertEquals(1, result.skippedArchived());
    }

    @Test
    @DisplayName("redelivery of the same event neither rewrites nor re-spends a downstream minute")
    void redeliveryIsIdempotent() {
        projection.add(row("e1", "v3", VersionSource.CONFIRMED));
        projection.add(row("e2", null, VersionSource.UNKNOWN));
        build(config());

        FanOutResult first = worker.process(publishV7());
        FanOutResult second = worker.process(publishV7()); // same eventId

        assertEquals(2, first.markedPending());
        assertEquals(0, second.markedPending());
        assertEquals(2, second.duplicatesSuppressed());
        assertEquals(1, loader.calls.get(), "the ~1 minute load must not be paid twice");
        assertEquals(2, projection.pendingWrites.size());
    }

    @Test
    @DisplayName("in-flight downstream calls never exceed the configured share of capacity")
    void downstreamConcurrencyIsCapped() {
        for (int i = 0; i < 40; i++) {
            projection.add(row("u" + i, null, VersionSource.UNKNOWN));
        }
        loader.artificialLatencyMillis = 40;
        build(config().maxConcurrentLoads(4).pageSize(40));

        worker.process(publishV7());

        assertEquals(40, loader.loadedIds.size());
        assertTrue(loader.peakConcurrency.get() <= 4,
                "peak concurrency was " + loader.peakConcurrency.get());
    }

    @Test
    @DisplayName("per-job load budget defers the remainder to backfill instead of starving the other team")
    void loadBudgetDefersRatherThanOverruns() {
        for (int i = 0; i < 20; i++) {
            projection.add(row("u" + i, null, VersionSource.UNKNOWN));
        }
        build(config().maxLoadsPerJob(5).maxConcurrentLoads(2));

        FanOutResult result = worker.process(publishV7());

        assertEquals(FanOutResult.Status.COMPLETED_WITH_DEFERRALS, result.status());
        assertEquals(5, loader.calls.get());
        assertEquals(15, result.deferred());
        assertEquals(20, result.scanned());
    }

    @Test
    @DisplayName("transient downstream failures are retried; terminal ones are dead-lettered and not retried")
    void failuresAreClassifiedAndVisible() {
        projection.add(row("flaky", null, VersionSource.UNKNOWN));
        projection.add(row("corrupt", null, VersionSource.UNKNOWN));
        projection.add(row("down", null, VersionSource.UNKNOWN));
        loader.transientFailuresLeft.put("flaky", new AtomicInteger(2)); // succeeds on attempt 3
        loader.terminalFailures.add("corrupt");
        loader.alwaysUnavailable.add("down");
        build(config().maxAttemptsPerLoad(3));

        FanOutResult result = worker.process(publishV7());

        assertTrue(loader.loadedIds.contains("flaky"), "retry must recover a transient blip");
        assertEquals(1, result.markedPending());
        assertEquals(2, result.deadLettered());
        assertEquals(2, deadLetters.entries.size());
        // Terminal failure keeps its claim (never retried); transient failure releases it (retryable later).
        assertFalse(idempotency.tryClaim("evt-1|corrupt"));
        assertTrue(idempotency.tryClaim("evt-1|down"));
    }

    @Test
    @DisplayName("a withdrawn target version produces no fan-out, whenever we learn about it")
    void withdrawnVersionIsNotFannedOut() {
        projection.add(row("e1", "v3", VersionSource.CONFIRMED));
        graph.withdraw(T, "v7");
        build(config());

        FanOutResult result = worker.process(publishV7());

        assertEquals(FanOutResult.Status.SKIPPED_WITHDRAWN, result.status());
        assertEquals(0, result.markedPending());
        assertTrue(projection.pendingWrites.isEmpty());
    }

    @Test
    @DisplayName("the scan is checkpointed per page and resumes rather than restarting")
    void scanIsCheckpointedAndResumable() {
        for (int i = 0; i < 25; i++) {
            projection.add(row("e" + i, "v3", VersionSource.CONFIRMED));
        }
        build(config().pageSize(10));

        // Simulate a crash after the first page: the cursor a previous run left behind.
        checkpoints.saveCursor("fanout:evt-1", "10");
        FanOutResult result = worker.process(publishV7());

        assertEquals(15, result.scanned(), "resumes from the checkpoint instead of rescanning page 1");
        assertEquals(15, result.markedPending());
        assertEquals(null, checkpoints.loadCursor("fanout:evt-1"), "cursor cleared on completion");
    }

    @Test
    @DisplayName("a projection write failure is dead-lettered and leaves the engagement retryable")
    void projectionWriteFailureIsVisibleAndRetryable() {
        projection.add(row("e1", "v3", VersionSource.CONFIRMED));
        projection.failPendingWriteWith = new IllegalStateException("dynamo throttled");
        build(config());

        FanOutResult result = worker.process(publishV7());

        assertEquals(1, result.deadLettered());
        assertEquals(0, result.markedPending());
        assertTrue(idempotency.tryClaim("evt-1|e1"), "claim released so the row is eligible again");
    }
}
