# SmartCharge — architecture and concurrency design

This document describes the implemented design. Every claim about behaviour links to a test or an
evidence file produced by actually running the code (see `docs/testing.md`, `docs/benchmark.md`).

## 1. Decomposition

| Package / class | Responsibility | Owns shared state? |
|---|---|---|
| `model` — `ChargingRequest`, `Priority`, `ChargingRate`, `SessionState` | Immutable request data and enums | No |
| `core.RequestValidator` | Field validation at intake (FR-02) | No (stateless) |
| `core.ChargingStation` | Intake, duplicate check, worker threads, session execution, cancellation, shutdown, status | `sessions` map, `seenRequestIds`, lifecycle, worker pool |
| `core.PriorityScheduler` | Pending queue (3 FIFO lanes), dispatch policy (priority, aging, backfill, guard), worker hand-off | Lanes, pending count, blocked-head timer — guarded by the **scheduler lock** |
| `core.StationResources` | Bays (counting `Semaphore(4, fair)`) and power budget (`int reservedKw`) | Guarded by the **power lock** |
| `core.ChargingSession` | One session's lifecycle state machine and timestamps | Its own fields — guarded by the **session monitor** |
| `billing.LegacyBillingService` | Brownfield stand-in, unsynchronised `ArrayList` + `HashMap`. Never modified | Its collections — only touched under the billing lock |
| `billing.BillingAdapter` | Serialises all legacy calls, exactly-once billing, reconciliation | `billedRequestIds`, failures — guarded by the **billing lock** |
| `workload.WorkloadGenerator`, `WorkloadDriver` | Seeded workload, replay on the `intake` thread | No |
| `app.Main`, `app.BenchmarkRunner`, `app.RunMetrics` | Normal run, experiment, metric definitions | No |
| `demo.DeadlockDemo` | Separate deadlock demonstration; not referenced by the station | Demo-local only |

Seven production classes carry all concurrent behaviour; the rest are data or drivers. Each piece of
shared state has exactly one guarding lock, named above.

## 2. Threads

| Thread name | Created by | Does |
|---|---|---|
| `main` / `bench` / `test-main` | JVM | Configures and shuts down the station |
| `intake` | `WorkloadDriver` | Submits requests at their scheduled arrival times |
| `worker-1..N` | fixed `ExecutorService` (pool modes) | Loop: `scheduler.awaitNext()` → `runSession()` |
| `req-thread-K` | `ChargingStation.submit` (thread-per-request mode) | One `awaitNext()` → `runSession()`, then exits |
| `monitor` | `Main` (daemon, scheduled) | Prints a `STATUS` line every 240 simulated minutes |

Workers *pull* work. A worker is handed a session only after its bay and power are already reserved, so
a waiting worker holds nothing. Consequently pool size bounds concurrency from above (pool-1 can never
use more than one bay), while the bay semaphore and power budget bound it independently of threads.

## 3. Synchronisation and lock order

| Lock | Guards | Acquired while holding |
|---|---|---|
| scheduler lock (`ReentrantLock` + `Condition workAvailable`) | queue lanes, blocked-head timer | nothing |
| power lock (`ReentrantLock`) | `reservedKw`, peaks; bay semaphore is only changed inside it | scheduler lock (during `selectNext`) or nothing (during `release`) |
| session monitor (`synchronized`) | session state | scheduler lock (`markCharging`, `cancelIfQueued`) or billing lock (`markBilled`) |
| billing lock (`ReentrantLock`) | legacy service, billed IDs | nothing |

The global order is **scheduler → power → session** and **billing → session**. No code path acquires them
in the reverse direction, and the session monitor never calls out while held, so the wait-for graph of
the production code is acyclic. The bay semaphore is only touched with the non-blocking `tryAcquire()`
or `release()`, both inside the power lock, so no thread ever blocks on a bay.

Visibility: all mutable shared fields are read and written under their lock; cross-thread hand-offs use
`ConcurrentHashMap`, `CountDownLatch` (cancellation signal), `volatile` (worker pool reference, log
flag) and `ExecutorService.awaitTermination`/`Thread.join` (happens-before at shutdown).

Wake-ups: `enqueue` and `capacityReleased` call `signal()` (one waiter), and a worker that successfully
takes a session chains a further `signal()` if work remains. This avoids a thundering herd when 200
`req-thread-K` threads are waiting. Because aging and the guard depend on time, not only on events,
waiters also re-evaluate after a bounded `awaitNanos` (≤ ¼ of the shorter of aging step and guard,
clamped to 1–100 ms), so no time-based decision is missed.

## 4. Scheduling policy

The queue is three FIFO lanes (one per base priority). `selectNext(now)` merges the lane heads by
`(effective priority, arrival sequence)`; each lane is already sorted by that key because an earlier
arrival in the same lane has waited at least as long, so the merge needs no sorting.

1. **Effective priority** = `max(1, base − ⌊waited / 30 sim-min⌋)` (FR-06).
2. Walk the merged order; the first request whose allocation succeeds starts (FR-05, FR-07).
3. When a later request starts ahead of a front request that could not fit (a backfill), record
   that front request's *first overtake time* if it has none yet (`ChargingSession.overtakenSinceNanos`).
   Whenever a request is at the front, has been overtaken, and its first overtake was at least
   30 sim-min ago, return nothing until it fits itself (FR-08). Running sessions are unaffected and
   finish normally, which is what eventually frees the power it needs.

The clock is stored on the request, not on "whichever request is currently at the front". The
previous version kept a single scheduler-wide clock that restarted whenever the front request
changed. Aging could briefly put an older visitor in front, wiping the blocked fleet request's
accumulated time. T25 checks the fix with an explicit clock: F is overtaken at t=2; an aged visitor
becomes front at t=61 and starts at t=62; at t=63 F is front again and backfill is refused at once.
Observed: `[S1, S2, V, none]`.

Why the clock starts at the first overtake (a design change made during evaluation): the first
version started it at the first *failed check* of the front request. Spare threads (pool-16,
thread-per-request) re-check every few milliseconds even when all 4 bays are busy, so the clock started
for requests that nobody had overtaken; pool-4 has no spare threads and only checked at releases. The
benchmark exposed this as ~100 guard activations per run for pool-16 / thread-per-request versus 0 for
pool-4 — the same workload was being scheduled by a different policy. Overtakes only happen at
release or arrival events, which every multi-worker configuration observes identically, so the policy is
now thread-count independent. The activation counter also only counts when the guard actually stops a
request that `wouldFit`. After the change: 0 activations in 11 of 12 benchmark runs, 1 in one
thread-per-request run (`evidence/benchmark-runs.csv`).

Worked example (from T17): a P1 fast request F cannot fit (only 9 kW free); P2 standard S1, S2 can.
At t=10 S1 backfills; at t=39 S2 backfills; at t=40 F has been the blocked front for 30 → nothing starts
even though S3 would fit; once 22 kW is free F starts, then S3. Observed: `[S1, S2, none, F, S3]`.

The same behaviour on a running station (T24, `evidence/test-logs/T24.txt`):
- **Setup:** two fast sessions are charging (44 kW) and a third fast request F3 is blocked.
- **Backfill:** S1 and S2 (standard) backfill. The guard then engages about 40 ms (≈ 40 sim-min) later.
- **Cost:** while it holds, 2 bays and 16 kW stay idle — the accepted cost in A-06.
- **Outcome:** when a fast session ends, F3 starts within a fraction of a millisecond. Only then do
  the standards S3–S8 that were waiting start.

Design note from the benchmark: under the evaluated load average waits are ~10 simulated hours, far
longer than the 30-minute aging step, so almost every queued request is aged to level 1 and order
becomes near-FIFO (median P1/P2/P3 waits 539/606/637 sim-min for pool-4). The aging step is therefore
the main tuning knob for how much priority matters; a longer step would preserve more differentiation
at the cost of more visitor waiting.

## 5. Concurrency hazards

### H1 — Race condition / lost update on the power budget (prevented)

Unsafe version: `if (reservedKw + kw <= 60) { reservedKw += kw; }` with no lock.
State: one fast (22 kW) and one standard (7 kW) session running → `reservedKw = 29`, 2 bays free.

| Step | worker-1 (fast, 22 kW) | worker-2 (fast, 22 kW) | `reservedKw` |
|---|---|---|---|
| 1 | reads 29; 29+22=51 ≤ 60 ✓ | | 29 |
| 2 | | reads 29; 29+22=51 ≤ 60 ✓ | 29 |
| 3 | `bays.acquire()` succeeds | | 29 |
| 4 | | `bays.acquire()` succeeds | 29 |
| 5 | writes 29+22 = 51 | | 51 |
| 6 | | writes 29+22 = 51 (lost update) | 51 |

Result: four sessions draw 22+7+22+22 = **73 kW > 60 kW**, yet the ledger says 51 kW, so later checks
admit even more. Both the check-then-act and the read-modify-write are non-atomic.
Prevention: `tryAllocate` performs check, bay acquisition and update as one critical section under the
power lock. Evidence: T03, T06, T07, T18 and all 12 benchmark runs report peak ≤ 58 kW with assertions on.

The same hazard exists in the legacy billing service (`HashMap` get/put and `ArrayList.add`).
Observed in T13 without the adapter (`evidence/test-logs/T13.txt`): 5/5 trials corrupted, with
7,035–7,989 of 8,000 entries surviving and 6–322 kWh of totals lost. The amount varies strongly between
executions (an earlier development run lost ~3,500 entries per trial) because it depends on how threads
interleave — which is exactly why the race cannot be "tested away". With the adapter (T12): 8,001/8,001
entries, all totals exact, in every run.

### H2 — Deadlock (demonstrated separately, prevented in production)

See §6.

### H3 — Starvation

Two forms: (a) visitors overtaken forever by a stream of higher-priority requests — addressed by aging
(T16); (b) a large (fast) request overtaken forever by smaller backfilled requests — aging cannot help a
P1 request, so the starvation guard bounds it (T17, T24, T25). Limitation: bays, not power, are usually the
binding constraint for this workload, so backfill is rare (4 backfills in a 200-request run) and the
guard activated in only 1 of 12 benchmark runs; its behaviour is primarily evidenced by the
deterministic test T17.

### H4 — Priority inversion

Lock-level inversion is negligible: no lock is held across a blocking call, and critical sections are
a few field updates. The real inversion is physical and non-preemptive: a lower-priority session that
started by backfill keeps its bay and power while a higher-priority request waits. It is bounded by the
guard (backfill stops) and by session length; sessions are not pre-empted (A-01).

### H5 — Cancellation and completion race

A cancel may arrive exactly when charging ends. Both `requestCancel()` and `finishCharging()` are
`synchronized` on the session: if the cancel is applied first the session ends CANCELLED (not billed);
otherwise it ends COMPLETED and the cancel returns false. A queued-cancel and a dispatch race on the
scheduler lock instead: `markCharging` and `cancelIfQueued` both run under it. T08, T09.

## 6. Deadlock analysis

**Deadlock-prone design** (`DeadlockDemo.runDeadlockProne`): fast sessions lock the power ledger and
then block on `bays.acquire()`; standard sessions hold a bay and then block on the power ledger lock.
Latches force the interleaving: 4 standard threads take all 4 bays, `fast-1` takes the ledger.

```
Resource-allocation graph            Wait-for graph
  bay permit x4 ──held by──> std-1..4     std-1..4 ──> fast-1   (owner of ledger)
  power ledger  ──held by──> fast-1       fast-1   ──> std-1..4 (holders of all permits)
  std-1..4 ──requests──> power ledger     => cycle
  fast-1   ──requests──> bay permit
```

| Coffman condition | Present in demo | Production design |
|---|---|---|
| Mutual exclusion | yes (lock, permits) | yes — unavoidable for physical bays and the ledger |
| Hold and wait | yes | **removed**: `tryAcquire()` never blocks; a refused allocation holds nothing |
| No pre-emption | yes | yes — sessions are not pre-empted |
| Circular wait | yes | **removed**: single fixed order power lock → bay, never reversed |

Strategy: **prevention** (break hold-and-wait and circular wait), chosen over avoidance (Banker's
algorithm needs maximum claims and adds complexity for two resources) and detection/recovery (would
require aborting sessions and undoing reservations). Evidence: `evidence/deadlock-demo.txt` shows the
watchdog detecting no progress for 2 s, the thread dump (`std-k WAITING on ReentrantLock owner=fast-1`,
`fast-1 WAITING on Semaphore owner=null`), and that `ThreadMXBean.findDeadlockedThreads()` returns
`null` — a semaphore has no owner, so the JVM detector cannot see this cycle; a timeout watchdog can.
The corrected variant with the same threads and interleaving completes (T20).

## 7. Design contracts (as enforced in code)

| # | Operation | Preconditions | Postconditions | Invariant |
|---|---|---|---|---|
| C1 | `StationResources.tryAllocate(kw)` | kw > 0 | true ⇒ reservedKw′ = reservedKw + kw ∧ activeBays′ = activeBays + 1; false ⇒ no change | 0 ≤ reservedKw ≤ 60 ∧ 0 ≤ activeBays ≤ 4 |
| C2 | `StationResources.release(kw)` | a matching allocation of kw is held | reservedKw′ = reservedKw − kw ∧ activeBays′ = activeBays − 1 | as C1 |
| C3 | `BillingAdapter.bill(s)` | s is COMPLETED, or s was already billed | first call: exactly one new legacy entry and s is BILLED, or no entry and s is BILLING_FAILED; later calls: no entry (DUPLICATE_IGNORED) | legacy entries = number of BILLED sessions |
| C4 | `PriorityScheduler.selectNext(now, alloc)` | caller may already hold the scheduler lock (it is re-entrant) | returns null, or a session that has been removed from the queue and whose allocation succeeded; no queued session ahead of it in (effective priority, sequence) order could start, unless the guard blocks backfill | pending = Σ lane sizes |
| C5 | `ChargingSession` transitions | current state matches the transition's source state | state follows the state machine in §8 | terminal states never change |
| C6 | `ChargingStation.shutdown(mode)` | station RUNNING | all station threads terminated ∧ reservedKw = 0 ∧ activeBays = 0 ∧ no session QUEUED/CHARGING | — |

**Traceability** (requirement → model element → enforcement → behaviour on violation → test):

| # | Requirements | Model elements (§8) | Code enforcement | On violation | Tests: contract holds / violation rejected |
|---|---|---|---|---|---|
| C1 | FR-09, FR-10, NFR-01 | Class: `StationResources.tryAllocate`; Sequence: `tryAllocate(kW) [power lock, bays.tryAcquire]`; State: QUEUED → CHARGING; Use case: UC4 | precondition check; power lock; non-blocking `bays.tryAcquire()`; `assert invariantHolds()` | kw ≤ 0 → `IllegalArgumentException`, ledger unchanged; a broken invariant → `AssertionError` (with `-ea`) | holds: T03, T06, T07, T18, T20, benchmark peaks / rejected: T21 |
| C2 | FR-17 | Class: `StationResources.release`; Sequence: `release(kW) [finally]`; State: leaving COMPLETED / BILLED / BILLING_FAILED / CANCELLED after CHARGING; Use case: UC4 | precondition check under the power lock; `assert invariantHolds()`; only called from the `finally` in `runSession` | unmatched or oversized release → `IllegalStateException`, ledger unchanged | holds: T09, T10, T14 / rejected: T21 |
| C3 | FR-13, FR-14, FR-16, NFR-08 | Class: `BillingAdapter.bill` → `LegacyBillingService`; Sequence: `bill(session)`, `recordSession(...)`, `alt` billing failure; State: COMPLETED → BILLED / BILLING_FAILED; Use case: UC5 | billing lock; `billedRequestIds` set; state precondition; `assert` entry count grew by exactly 1 | repeat call → `DUPLICATE_IGNORED`, no entry; non-COMPLETED session → `IllegalStateException`, no entry; legacy failure → BILLING_FAILED, no entry | holds: T12, T14, T15 (T13 for contrast) / rejected: T22 |
| C4 | FR-05–FR-08 | Class: `PriorityScheduler.selectNext`, `awaitNext`, `Allocator`; Sequence: `awaitNext(allocator)`; State: QUEUED → CHARGING; Use case: UC1 | scheduler lock; `MergedOrder` ordering; aging in `effectivePriority`; per-request guard clock `overtakenSinceNanos` | not an input contract: the postcondition is guaranteed by construction and checked by ordering tests | T04, T16, T17, T24, T25 |
| C5 | FR-12, FR-14, FR-15 | Class: `ChargingSession`; State: the whole session state machine; Use cases: UC2, UC4 | `synchronized` transitions with a source-state check | illegal transition → `IllegalStateException`, state unchanged | holds: T08, T09, T10, T11 / rejected: T22 |
| C6 | FR-19 | Class: `ChargingStation.shutdown`; State: QUEUED / CHARGING → CANCELLED (IMMEDIATE), → COMPLETED → BILLED (GRACEFUL); Use case: UC8 | lifecycle state check; executor `awaitTermination` / `join`; `assert` resources are 0 after termination | shutdown when not RUNNING, or submit after shutdown → `IllegalStateException`, nothing queued | holds: T10, T11, `SHUTDOWN complete` lines in `evidence/normal-run-*.txt` / rejected: T23 |

## 8. Models (implementation-aligned)

Four UML models follow: class, session state machine, sequence, and use case. Each is also exported to
`docs/diagrams/` as Mermaid source (`.mmd`) and a rendered PNG, ready for documents that cannot render
Mermaid. To regenerate the PNGs, run `mmdc -i <file>.mmd -o <file>.png` (Mermaid CLI).

### Class model

```mermaid
classDiagram
    class ChargingStation {
      -Config config
      -Map~String,ChargingSession~ sessions
      -Set~String~ seenRequestIds
      -ExecutorService workerPool
      -Allocator allocator
      +start()
      +submit(ChargingRequest) ChargingSession
      +cancel(String) boolean
      +shutdown(ShutdownMode, long, TimeUnit) boolean
      +status() Status
    }
    class PriorityScheduler {
      -ReentrantLock lock
      -Condition workAvailable
      -List~ArrayDeque~ lanes
      -long agingStepNanos
      -long starvationGuardNanos
      +enqueue(ChargingRequest) ChargingSession
      +awaitNext(Allocator) ChargingSession
      +selectNext(long, Allocator) ChargingSession
      +cancelQueued(ChargingSession, long) boolean
      +close(boolean) List
    }
    class StationResources {
      -Semaphore bays
      -ReentrantLock powerLock
      -int reservedKw
      +tryAllocate(int) boolean
      +wouldFit(int) boolean
      +release(int)
      +snapshot() Snapshot
    }
    class ChargingSession {
      -SessionState state
      -long sequence
      -long arrivalNanos
      -CountDownLatch cancelSignal
      long overtakenSinceNanos
      +markCharging(long, String)
      +requestCancel() boolean
      +finishCharging(long) SessionState
      +markBilled()
    }
    class ChargingRequest {
      <<record>>
      requestId
      accountId
      priority
      energyKwh
      rate
    }
    class BillingAdapter {
      -ReentrantLock lock
      -Set~String~ billedRequestIds
      +bill(ChargingSession) Result
      +reconcile(Collection) Reconciliation
    }
    class LegacyBillingService {
      +recordSession(String, double, long, long)
      +getTotal(String) double
      +getEntryCount() int
    }
    class RequestValidator {
      +validate(ChargingRequest)
    }
    class Allocator {
      <<interface>>
      +tryAllocate(ChargingSession) boolean
      +wouldFit(ChargingSession) boolean
    }
    class Config {
      <<record>>
      workers
      timeScale
      agingStepSimMinutes
      starvationGuardSimMinutes
    }
    class SessionState {
      <<enumeration>>
      QUEUED
      CHARGING
      COMPLETED
      BILLED
      BILLING_FAILED
      CANCELLED
    }
    class Priority {
      <<enumeration>>
      FLEET_EMERGENCY
      STAFF
      VISITOR
    }
    class ChargingRate {
      <<enumeration>>
      STANDARD
      FAST
    }
    ChargingStation "1" *-- "1" Config
    ChargingStation "1" *-- "1" Allocator : adapts StationResources
    PriorityScheduler ..> Allocator : uses
    ChargingSession "*" --> "1" SessionState
    ChargingSession "*" --> "1" Priority
    ChargingRequest "*" --> "1" ChargingRate
    ChargingStation "1" *-- "1" PriorityScheduler
    ChargingStation "1" o-- "1" StationResources
    ChargingStation "1" o-- "1" BillingAdapter
    ChargingStation "1" *-- "1" RequestValidator
    ChargingStation "1" --> "0..*" ChargingSession : tracks
    PriorityScheduler "1" --> "0..*" ChargingSession : queues
    ChargingSession "*" --> "1" ChargingRequest
    BillingAdapter "1" --> "1" LegacyBillingService : serialises
```

### Session state machine

```mermaid
stateDiagram-v2
    [*] --> QUEUED : submit() valid and unique
    QUEUED --> CHARGING : selectNext() allocated bay + power
    QUEUED --> CANCELLED : cancel() / IMMEDIATE shutdown
    CHARGING --> COMPLETED : duration elapsed, no cancel requested
    CHARGING --> CANCELLED : cancel() / IMMEDIATE shutdown / interrupt
    COMPLETED --> BILLED : adapter recorded one entry
    COMPLETED --> BILLING_FAILED : legacy threw
    BILLED --> [*]
    BILLING_FAILED --> [*]
    CANCELLED --> [*]
```

Resources are held exactly while the session is CHARGING or COMPLETED (billing happens before the
`finally` that releases them).

### Sequence: request arrival → billing → release

```mermaid
sequenceDiagram
    participant I as intake
    participant St as ChargingStation
    participant Sc as PriorityScheduler
    participant W as worker-k
    participant R as StationResources
    participant B as BillingAdapter
    participant L as LegacyBillingService
    I->>St: submit(request)
    St->>St: validate, duplicate check
    St->>Sc: enqueue(request) [scheduler lock]
    Sc-->>W: signal(workAvailable)
    W->>Sc: awaitNext(allocator)
    Sc->>R: tryAllocate(kW) [power lock, bays.tryAcquire]
    R-->>Sc: true
    Sc-->>W: session (CHARGING)
    W->>W: awaitCancellation(duration)
    alt cancel() or IMMEDIATE shutdown during charging
        W->>W: finishCharging() -> CANCELLED (not billed)
    else duration elapsed
        W->>W: finishCharging() -> COMPLETED
        W->>B: bill(session) [billing lock]
        B->>L: recordSession(...)
        alt legacy call succeeds
            B-->>W: BILLED
        else legacy throws
            B-->>W: FAILED (session BILLING_FAILED, no entry)
        end
    end
    W->>R: release(kW) [finally, every path]
    W->>Sc: capacityReleased() -> signal
```

### Use case model

Mermaid has no native use case notation, so the diagram adapts flowchart notation:
- actors are nodes outside the system boundary;
- use cases are rounded nodes inside it;
- `«include»` relationships are dashed arrows labelled with the UML stereotype.

```mermaid
flowchart LR
    Driver["👤 Driver<br/>(fleet/emergency, staff, visitor)"]
    Operator["👤 Station operator"]
    Engineer["👤 Engineer"]
    Billing["👤 Legacy billing system<br/>(secondary actor)"]

    subgraph SmartCharge["SmartCharge system"]
        UC1(["UC1 Submit charging request"])
        UC2(["UC2 Cancel request"])
        UC3(["UC3 Validate request"])
        UC4(["UC4 Charge vehicle"])
        UC5(["UC5 Record billing"])
        UC6(["UC6 Start station"])
        UC7(["UC7 View station status"])
        UC8(["UC8 Shut down station"])
        UC9(["UC9 Run tests / deadlock demo / benchmark"])
    end

    Driver --- UC1
    Driver --- UC2
    Operator --- UC6
    Operator --- UC7
    Operator --- UC8
    UC9 --- Engineer
    UC1 -. "«include»" .-> UC3
    UC1 -. "«include»" .-> UC4
    UC4 -. "«include»" .-> UC5
    UC5 --- Billing
```

| ID | Use case | Primary actor | Main flow | Requirements |
|---|---|---|---|---|
| UC1 | Submit charging request | Driver | request validated (UC3), queued, scheduled, charged (UC4) | FR-01, FR-04–FR-11 |
| UC2 | Cancel request | Driver | queued → CANCELLED, or charging → CANCELLED; never billed | FR-14, FR-15 |
| UC3 | Validate request | (included) | reject invalid fields or duplicate ID | FR-02, FR-03 |
| UC4 | Charge vehicle | (included) | bay + full power reserved, session runs, resources released | FR-09–FR-12, FR-17 |
| UC5 | Record billing | Legacy billing system | exactly one entry per completed session via the adapter | FR-13, FR-14, FR-16, FR-20 |
| UC6 | Start station | Station operator | worker pool created, intake opens | FR-11 |
| UC7 | View station status | Station operator | periodic STATUS lines and final summary | FR-18 |
| UC8 | Shut down station | Station operator | graceful or immediate; threads end, resources released | FR-19 |
| UC9 | Run tests / deadlock demo / benchmark | Engineer | separate entry points, never the normal run path | FR-21–FR-23 |

## 9. Known limitations

- Sessions are not pre-empted; a running low-priority session keeps its bay (H4).
- Billing failure has no retry (A-07); failed sessions are reported, not re-billed.
- Timing uses timed waits on a scaled clock; every session overruns its nominal length by ~3.2–3.9
  sim-min (ms) on the test machine (`avg_overrun_sim_min` in the benchmark CSV), equally across
  configurations.
- The starvation guard rarely activates under the evaluated benchmark workload. It is verified by a
  policy test with an explicit clock (T17, T25) and by an integrated station test that forces the
  situation (T24).
- `start()` must be called once by the owning thread; it is not designed for concurrent callers.
