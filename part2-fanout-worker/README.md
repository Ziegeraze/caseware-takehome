# Part 2 — Template-publish fan-out worker

```bash
mvn test
```

Requires JDK 17+. Java 17 (records, switch expressions), JUnit 5, no other dependencies. 10 tests, ~1s.
Entry point: [`TemplatePublishFanoutWorker.process(PublishEvent)`](src/main/java/com/caseware/updates/fanout/TemplatePublishFanoutWorker.java).

## What it does

Given "template T version v7 was published in market M", it marks every affected engagement in that
region as having a pending update — without loading engagements, except where it has no choice.

```
PublishEvent ──▶ isWithdrawn? ──▶ scan projection page (indexed, ms)
                                        │
                     ┌──────────────────┼───────────────────────────┐
              CONFIRMED version                     INFERRED / UNKNOWN version
                     │                                              │
          ancestry test in the version graph              claim → budget → permit
                     │                                              │
          markPendingUpdate (idempotent)               load engagement (~1 min, retried)
                                                                    │
                                                    recordConfirmedVersion, re-classify
                                                                    │
                                                 deferred / dead-lettered on failure
                                        │
                              checkpoint cursor, next page
```

## Key tradeoffs

**1. The downstream call is the exception, not the mechanism.** A publish touching 20,000 engagements
through a 1-minute call costs ~333 hours. So the projection answers `CONFIRMED` rows with an ancestry
test, and the load is reserved for the backfill frontier (`UNKNOWN` / `INFERRED`). That set shrinks to
~0 as backfill completes: **in steady state this worker performs zero loads** (asserted in
`confirmedRowsNeverTouchDownstream`). The cost is that the worker is only as correct as the projection's
hooks — which is why the design pairs it with reconciliation and with healing on every engagement open.

**2. Deferral over queueing.** Capacity is bounded twice: `maxConcurrentLoads` (a semaphore — our share
of another team's capacity) and `maxLoadsPerJob` (how much one publish may spend). When either is
exhausted, rows are **deferred**, not queued: the claim is released, the row stays "Verifying", and the
background backfill picks it up through the same limiter. `COMPLETED_WITH_DEFERRALS` is a success state.
I would rather be slow and predictable than fill an unbounded queue in front of a team that never agreed
to the load.

**3. Idempotency is claim-based, but writes converge anyway.** The key is `(eventId, engagementId)`, so
redelivery neither re-notifies a user nor re-spends a minute. Belt and braces: every projection write is
a state assignment (`pending_to_version = v7`), never an increment, so even a lost or expired claim
converges instead of corrupting. Claim lifecycle encodes the retry decision:

| Outcome | Claim | Why |
|---|---|---|
| Success | completed | Never repeat |
| `EngagementUnloadableException` (corrupt, deleted) | completed | Terminal — retrying burns a scarce minute every time |
| `DownstreamUnavailableException` after retries | released | Genuinely retryable later |
| Deferred (budget/permit/breaker) | released | Not attempted at all |
| Projection write failure | released | Infrastructure blip, retryable |

**4. Ancestry, never `version < newVersion`.** Lineage is a DAG: an engagement on v6 of a CA branch
satisfies `6 < 7` but v7 (EU) is not its descendant, so offering it would push another market's content
into the file. The worker asks `VersionGraph.isAncestor` and treats the graph — not the event payload —
as the authority. Same reason withdrawal is re-checked **between pages**: a fan-out over 40,000
engagements can outlive the validity of its own target version.

**5. Resumable by page, at-least-once by design.** The cursor is checkpointed after each completed page,
so a restart (no maintenance window: restarts are routine) replays at most one page. Replay is safe
precisely because of (3).

**6. Failure is visible, never silent.** Terminal failures go to a dead-letter sink and DLQ depth is an
alert. A silently dropped engagement would show "up to date" while an update is pending — the failure
mode the design document names as most damaging, because nobody notices it until a regulatory review.

**7. Partial results instead of exceptions.** `process` does not throw for production conditions
(downstream down, budget exhausted, scan interrupted). It returns a `FanOutResult` with counters and a
resume cursor, so the caller decides whether to ack, re-drive, or let reconciliation finish — and the
counters feed the freshness/coverage SLOs directly.

## Deliberately out of scope

Real infrastructure adapters (DynamoDB/SQS/S3 implementations of the ports), summary generation, the
decline/apply path, the backfill scheduler that shares this limiter, and a distributed leader election so
only one worker owns a given event. All are named in the design doc; none change these contracts.

`Metrics` is a port rather than a vendor SDK, and `Clock` and `Sleeper` are injected so retry policy is
testable without real backoff. The domain types are records: they are values, and immutability is what
makes them safe to hand to the worker pool.

## Tests

| Test | Property |
|---|---|
| `confirmedRowsNeverTouchDownstream` | Steady state costs zero downstream minutes |
| `onlyUnverifiedRowsAreLoaded` | Loads only the backfill frontier; a load heals the projection |
| `branchesAreNotOfferedForeignDescendants` | Ancestry, current-version and archived handling |
| `redeliveryIsIdempotent` | Same event twice: no rewrites, no second load |
| `downstreamConcurrencyIsCapped` | Peak in-flight ≤ configured permits |
| `loadBudgetDefersRatherThanOverruns` | Budget exhaustion defers instead of overrunning |
| `failuresAreClassifiedAndVisible` | Retry, terminal vs transient, DLQ, claim lifecycle |
| `withdrawnVersionIsNotFannedOut` | Withdrawal short-circuits the job |
| `scanIsCheckpointedAndResumable` | Resume from cursor; cursor cleared on completion |
| `projectionWriteFailureIsVisibleAndRetryable` | Write failure dead-lettered and left retryable |
