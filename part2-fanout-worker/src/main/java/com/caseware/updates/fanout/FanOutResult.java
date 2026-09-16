package com.caseware.updates.fanout;

/**
 * Outcome of one fan-out job. Partial completion is a first-class, expected result: a publish that
 * exhausts its downstream budget is neither a success nor a failure, and the numbers below are what tell
 * operators (and the freshness/coverage SLOs) which one it was.
 */
public final class FanOutResult {
    public enum Status {
        /** Whole candidate set scanned and resolved. */
        COMPLETED,
        /** Scan finished but some engagements were left unverified (budget, permits, or breaker). */
        COMPLETED_WITH_DEFERRALS,
        /** Scan did not finish; resume from {@link #resumeCursor()} on the next run. */
        INCOMPLETE,
        /** Target version was withdrawn (possibly before we processed it): nothing to fan out. */
        SKIPPED_WITHDRAWN
    }

    private final Status status;
    private final String resumeCursor;
    private final int scanned;
    private final int markedPending;
    private final int alreadyCurrent;
    private final int notInLineage;
    private final int skippedArchived;
    private final int duplicatesSuppressed;
    private final int loadsPerformed;
    private final int deferred;
    private final int deadLettered;

    FanOutResult(Status status, String resumeCursor, int scanned, int markedPending, int alreadyCurrent,
                 int notInLineage, int skippedArchived, int duplicatesSuppressed, int loadsPerformed,
                 int deferred, int deadLettered) {
        this.status = status;
        this.resumeCursor = resumeCursor;
        this.scanned = scanned;
        this.markedPending = markedPending;
        this.alreadyCurrent = alreadyCurrent;
        this.notInLineage = notInLineage;
        this.skippedArchived = skippedArchived;
        this.duplicatesSuppressed = duplicatesSuppressed;
        this.loadsPerformed = loadsPerformed;
        this.deferred = deferred;
        this.deadLettered = deadLettered;
    }

    public Status status() { return status; }
    public String resumeCursor() { return resumeCursor; }
    public int scanned() { return scanned; }
    public int markedPending() { return markedPending; }
    public int alreadyCurrent() { return alreadyCurrent; }
    public int notInLineage() { return notInLineage; }
    public int skippedArchived() { return skippedArchived; }
    public int duplicatesSuppressed() { return duplicatesSuppressed; }
    public int loadsPerformed() { return loadsPerformed; }
    public int deferred() { return deferred; }
    public int deadLettered() { return deadLettered; }

    @Override
    public String toString() {
        return "FanOutResult{" + status + ", scanned=" + scanned + ", pending=" + markedPending
                + ", current=" + alreadyCurrent + ", outOfLineage=" + notInLineage
                + ", archived=" + skippedArchived + ", duplicates=" + duplicatesSuppressed
                + ", loads=" + loadsPerformed + ", deferred=" + deferred
                + ", deadLettered=" + deadLettered + '}';
    }
}
