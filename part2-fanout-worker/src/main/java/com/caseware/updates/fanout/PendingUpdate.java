package com.caseware.updates.fanout;

import java.util.Objects;

/**
 * The write this worker exists to produce: "engagement E, sitting on fromVersion, has toVersion pending".
 *
 * <p>Deliberately a state assignment rather than an increment or an append, so replaying the same publish
 * event any number of times converges on the same projection state.
 */
public final class PendingUpdate {
    private final String engagementId;
    private final String templateId;
    private final String fromVersion;
    private final String toVersion;
    private final String detectedByEventId;

    public PendingUpdate(String engagementId, String templateId, String fromVersion, String toVersion,
                         String detectedByEventId) {
        this.engagementId = Objects.requireNonNull(engagementId);
        this.templateId = Objects.requireNonNull(templateId);
        this.fromVersion = Objects.requireNonNull(fromVersion);
        this.toVersion = Objects.requireNonNull(toVersion);
        this.detectedByEventId = Objects.requireNonNull(detectedByEventId);
    }

    public String engagementId() { return engagementId; }
    public String templateId() { return templateId; }
    public String fromVersion() { return fromVersion; }
    public String toVersion() { return toVersion; }
    public String detectedByEventId() { return detectedByEventId; }

    @Override
    public String toString() {
        return "PendingUpdate{" + engagementId + ": " + templateId + " " + fromVersion + "->" + toVersion + '}';
    }
}
