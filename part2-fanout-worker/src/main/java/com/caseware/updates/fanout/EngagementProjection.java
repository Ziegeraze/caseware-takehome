package com.caseware.updates.fanout;

import java.time.Instant;

/**
 * Regional read model. Cheap to query, derived, rebuildable — and never leaves its region.
 *
 * <p>Implementations must make {@link #markPendingUpdate} and {@link #recordConfirmedVersion} idempotent
 * upserts: the worker is at-least-once and may legitimately issue the same write twice.
 */
public interface EngagementProjection {

    /**
     * Scans engagements that could be affected by a publish on {@code (templateId, marketId)}.
     *
     * <p>Contract: rows are returned in a stable order so that {@code cursor} is resumable across
     * process restarts. Implementations are expected to be backed by an index on
     * {@code (template_id, market_id)}; this call must not load engagements.
     */
    Page<EngagementRow> scanCandidates(String templateId, String marketId, String cursor, int pageSize);

    /** Idempotent upsert of the pending-update state for one engagement. */
    void markPendingUpdate(PendingUpdate update);

    /**
     * Idempotent upsert of a version learned from the engagement itself, flipping the row to
     * {@link VersionSource#CONFIRMED}.
     */
    void recordConfirmedVersion(String engagementId, String templateId, String version, Instant observedAt);
}
