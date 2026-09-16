package com.caseware.updates.fanout;

import java.util.Objects;

/**
 * The write this worker exists to produce: "engagement E, sitting on fromVersion, has toVersion pending".
 *
 * <p>Deliberately a state assignment rather than an increment or an append, so replaying the same publish
 * event any number of times converges on the same projection state.
 */
public record PendingUpdate(String engagementId, String templateId, String fromVersion, String toVersion,
                            String detectedByEventId) {

    public PendingUpdate {
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(fromVersion, "fromVersion");
        Objects.requireNonNull(toVersion, "toVersion");
        Objects.requireNonNull(detectedByEventId, "detectedByEventId");
    }

    @Override
    public String toString() {
        return "PendingUpdate{" + engagementId + ": " + templateId + " " + fromVersion + "->" + toVersion + '}';
    }
}
