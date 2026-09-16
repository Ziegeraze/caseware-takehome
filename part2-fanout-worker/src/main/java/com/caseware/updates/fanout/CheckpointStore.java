package com.caseware.updates.fanout;

/**
 * Scan cursor persistence, so a fan-out over ~40,000 engagements survives a restart or a deploy
 * (there is no maintenance window) without rescanning from the beginning.
 *
 * <p>The cursor is saved only after a page is fully processed, which makes recovery replay at most one
 * page — safe, because every write in this component is idempotent.
 */
public interface CheckpointStore {
    String loadCursor(String jobId);
    void saveCursor(String jobId, String cursor);
    void clear(String jobId);
}
