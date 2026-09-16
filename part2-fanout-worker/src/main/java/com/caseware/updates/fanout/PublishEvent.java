package com.caseware.updates.fanout;

import java.time.Instant;
import java.util.Objects;

/**
 * A template version publication, delivered to a region.
 *
 * <p>This is a <em>thin</em> event: it identifies what changed, and the worker re-reads authoritative
 * state (the version graph) rather than trusting payload contents. That makes the worker immune to
 * out-of-order delivery (e.g. "withdrawn" arriving before "published").
 *
 * <p>{@code eventId} is stable across redeliveries of the same publication and is the root of all
 * idempotency keys in this component. It is NOT a new value per delivery attempt.
 */
public record PublishEvent(String eventId, String templateId, String version, String marketId,
                           Instant publishedAt) {

    public PublishEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(marketId, "marketId");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    @Override
    public String toString() {
        return "PublishEvent{" + templateId + "@" + version + ", market=" + marketId + ", eventId=" + eventId + '}';
    }
}
