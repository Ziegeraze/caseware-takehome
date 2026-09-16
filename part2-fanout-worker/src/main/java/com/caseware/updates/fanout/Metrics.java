package com.caseware.updates.fanout;

/** Minimal metrics port: counters and durations, so the worker stays free of a vendor SDK. */
public interface Metrics {
    void increment(String name, long delta);
    void observeMillis(String name, long millis);

    default void increment(String name) { increment(name, 1); }

    Metrics NOOP = new Metrics() {
        @Override public void increment(String name, long delta) { }
        @Override public void observeMillis(String name, long millis) { }
    };
}
