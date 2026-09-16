package com.caseware.updates.fanout;

/**
 * Outcome of one fan-out job. Partial completion is a first-class, expected result: a publish that
 * exhausts its downstream budget is neither a success nor a failure, and the counters below are what tell
 * operators (and the freshness/coverage SLOs) which one it was.
 */
public record FanOutResult(Status status, String resumeCursor, int scanned, int markedPending,
                           int alreadyCurrent, int notInLineage, int skippedArchived,
                           int duplicatesSuppressed, int loadsPerformed, int deferred, int deadLettered) {

    public enum Status {
        /** Whole candidate set scanned and resolved. */
        COMPLETED,
        /** Scan finished but some engagements were left unverified (budget, permits, or breaker). */
        COMPLETED_WITH_DEFERRALS,
        /** Scan did not finish; resume from {@link FanOutResult#resumeCursor()} on the next run. */
        INCOMPLETE,
        /** Target version was withdrawn (possibly before we processed it): nothing to fan out. */
        SKIPPED_WITHDRAWN
    }

    @Override
    public String toString() {
        return "FanOutResult{" + status + ", scanned=" + scanned + ", pending=" + markedPending
                + ", current=" + alreadyCurrent + ", outOfLineage=" + notInLineage
                + ", archived=" + skippedArchived + ", duplicates=" + duplicatesSuppressed
                + ", loads=" + loadsPerformed + ", deferred=" + deferred
                + ", deadLettered=" + deadLettered + '}';
    }
}
