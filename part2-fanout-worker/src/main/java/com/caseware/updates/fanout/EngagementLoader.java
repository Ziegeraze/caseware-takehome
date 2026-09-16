package com.caseware.updates.fanout;

/**
 * The expensive downstream: loading an engagement to read its true applied template version.
 *
 * <p>~1 minute per call, owned by another team with limited capacity. This component treats it as a
 * scarce, rate-limited, failure-prone resource — never as something to call per engagement per publish.
 * A publish that touched 20,000 engagements through this call would cost ~333 hours.
 *
 * <p>Implementations must distinguish the two failure classes, because the worker treats them very
 * differently: {@link DownstreamUnavailableException} (retry later, do not consume the idempotency claim)
 * versus {@link EngagementUnloadableException} (terminal for this engagement, dead-letter it).
 */
public interface EngagementLoader {

    LoadedEngagement loadTemplateState(String engagementId)
            throws DownstreamUnavailableException, EngagementUnloadableException;

    /** Minimal projection of what a load tells us. */
    record LoadedEngagement(String engagementId, String templateId, String appliedVersion) { }

    /** Transient: capacity exhausted, timeout, throttling, deploy. Retryable. */
    class DownstreamUnavailableException extends Exception {
        public DownstreamUnavailableException(String message) { super(message); }
        public DownstreamUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    /** Terminal for this engagement: corrupt file, deleted mid-scan, unsupported schema. Not retryable. */
    class EngagementUnloadableException extends Exception {
        public EngagementUnloadableException(String message) { super(message); }
        public EngagementUnloadableException(String message, Throwable cause) { super(message, cause); }
    }
}
