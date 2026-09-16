package com.caseware.updates.fanout;

/**
 * Per-(event, engagement) claim, so that redelivery of a publish event neither double-spends the
 * 1-minute downstream call nor re-notifies a user.
 *
 * <p>Claims are released on retryable failure (so the work is picked up again) and kept on success or on
 * terminal failure (so it is never repeated). Implementations should give claims a TTL longer than the
 * worst-case load, so a worker that dies mid-flight does not strand an engagement forever.
 */
public interface IdempotencyStore {

    /** Atomically claims the key. Returns false if some attempt already claimed or completed it. */
    boolean tryClaim(String key);

    /** Marks the key permanently done: success, or a terminal failure that must not be retried. */
    void markCompleted(String key);

    /** Releases a claim after a retryable failure, making the work eligible again. */
    void release(String key);
}
