package com.caseware.updates.fanout;

/**
 * Where engagements that could not be processed go, so failure is visible instead of silent.
 *
 * <p>A silently dropped engagement shows the user "up to date" when an update is in fact pending — the
 * failure mode this design treats as most damaging. DLQ depth is an alerting signal, not a log line.
 */
public interface DeadLetterSink {
    void record(String engagementId, PublishEvent event, String reason, Throwable cause);
}
