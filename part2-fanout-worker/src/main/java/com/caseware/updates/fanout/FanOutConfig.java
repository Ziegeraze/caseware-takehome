package com.caseware.updates.fanout;

import java.time.Duration;

/**
 * Operational knobs. Defaults encode the capacity arithmetic from the design document: the downstream
 * costs ~1 min per call, so concurrency — not code — decides how much of another team's capacity we
 * consume. 20 concurrent loads ≈ the whole 800k estate in under a month; per publish we take much less.
 */
public final class FanOutConfig {
    private int pageSize = 500;
    /** Hard ceiling on simultaneous in-flight downstream loads. Our share of the other team's capacity. */
    private int maxConcurrentLoads = 8;
    /** Per-job budget: how many loads a single publish may spend before deferring the rest to backfill. */
    private int maxLoadsPerJob = 200;
    /** How long to wait for a permit before declaring the row deferred rather than queueing unboundedly. */
    private Duration permitWait = Duration.ofSeconds(5);
    private int maxAttemptsPerLoad = 3;
    private Duration retryBaseBackoff = Duration.ofSeconds(2);
    /** Consecutive downstream failures after which we stop issuing loads for this job. */
    private int circuitBreakerThreshold = 10;

    public int pageSize() { return pageSize; }
    public int maxConcurrentLoads() { return maxConcurrentLoads; }
    public int maxLoadsPerJob() { return maxLoadsPerJob; }
    public Duration permitWait() { return permitWait; }
    public int maxAttemptsPerLoad() { return maxAttemptsPerLoad; }
    public Duration retryBaseBackoff() { return retryBaseBackoff; }
    public int circuitBreakerThreshold() { return circuitBreakerThreshold; }

    public FanOutConfig pageSize(int v) { this.pageSize = requirePositive(v, "pageSize"); return this; }
    public FanOutConfig maxConcurrentLoads(int v) { this.maxConcurrentLoads = requirePositive(v, "maxConcurrentLoads"); return this; }
    public FanOutConfig maxLoadsPerJob(int v) { this.maxLoadsPerJob = requirePositive(v, "maxLoadsPerJob"); return this; }
    public FanOutConfig permitWait(Duration v) { this.permitWait = v; return this; }
    public FanOutConfig maxAttemptsPerLoad(int v) { this.maxAttemptsPerLoad = requirePositive(v, "maxAttemptsPerLoad"); return this; }
    public FanOutConfig retryBaseBackoff(Duration v) { this.retryBaseBackoff = v; return this; }
    public FanOutConfig circuitBreakerThreshold(int v) { this.circuitBreakerThreshold = requirePositive(v, "circuitBreakerThreshold"); return this; }

    private static int requirePositive(int v, String name) {
        if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
        return v;
    }
}
