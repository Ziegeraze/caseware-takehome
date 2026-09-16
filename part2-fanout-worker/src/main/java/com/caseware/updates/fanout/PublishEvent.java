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
public final class PublishEvent {
    private final String eventId;
    private final String templateId;
    private final String version;
    private final String marketId;
    private final Instant publishedAt;

    public PublishEvent(String eventId, String templateId, String version, String marketId, Instant publishedAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.templateId = Objects.requireNonNull(templateId, "templateId");
        this.version = Objects.requireNonNull(version, "version");
        this.marketId = Objects.requireNonNull(marketId, "marketId");
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
    }

    public String eventId() { return eventId; }
    public String templateId() { return templateId; }
    public String version() { return version; }
    public String marketId() { return marketId; }
    public Instant publishedAt() { return publishedAt; }

    @Override
    public String toString() {
        return "PublishEvent{" + templateId + "@" + version + ", market=" + marketId + ", eventId=" + eventId + '}';
    }
}
