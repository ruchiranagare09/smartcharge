# SmartCharge — requirements, assumptions and traceability

Source of truth: the SmartCharge project brief (sections referenced as §n). Every requirement below is
numbered, testable, and traced to the component that implements it and the evidence that verifies it.

## 1. Functional requirements

| ID | Requirement (the system shall…) | Brief |
|---|---|---|
| FR-01 | accept charging requests containing request ID, account ID, priority level, requested energy (kWh) and charging rate category. | §3 |
| FR-02 | reject a request whose request ID or account ID is blank, whose priority is not 1, 2 or 3, whose rate is missing, or whose energy is not a positive finite number ≤ 100 kWh (A-08). | §3 |
| FR-03 | reject a request whose request ID has already been accepted by the station. | §3 |
| FR-04 | queue every accepted request that cannot start immediately. | §3.2 |
| FR-05 | order queued requests by priority (1 fleet/emergency, 2 staff, 3 visitor) and, within equal effective priority, by arrival order (FIFO). | §3.3, §4 |
| FR-06 | raise a waiting request's effective priority by one level per 30 simulated minutes waited, never above level 1 (A-04). | §4 starvation |
| FR-07 | when the front request cannot obtain its resources, start the next queued request that can (backfill) (A-05). | §4 |
| FR-08 | stop backfilling once the same front request has been overtaken by backfill for 30 simulated minutes (measured from the first overtake), until it starts (A-06). | §4 starvation |
| FR-09 | start a session only when it holds both a free bay and its complete power reservation (7 kW standard, 22 kW fast); never hold a partial allocation. | §4 |
| FR-10 | never have more than 4 sessions charging, and never reserve more than 60 kW in total. | §3, §4 |
| FR-11 | execute charging sessions concurrently on worker threads, with duration = energy (kWh) / rate (kW), on a documented time scale (A-02). | §3.5 |
| FR-12 | track each session's state (QUEUED, CHARGING, COMPLETED, BILLED, BILLING_FAILED, CANCELLED), start/end times and reserved power. | §3.6 |
| FR-13 | record exactly one billing entry, through the adapter, for every normally completed session. | §5 |
| FR-14 | never bill a cancelled session, and ignore any repeated billing attempt for the same request. | §5 |
| FR-15 | allow cancellation of a queued request and of a charging session; a cancelled session is not billed. | §4, §9 |
| FR-16 | handle a billing failure by marking the session BILLING_FAILED, logging it, and continuing (A-07). | §5 |
| FR-17 | release a session's bay and power on completion, cancellation, billing failure, interruption or unexpected exception. | §4 |
| FR-18 | report station and session status through thread-named log lines and periodic status lines. | §3.9 |
| FR-19 | support graceful shutdown (finish queued and active sessions) and immediate shutdown (cancel queued, cancel active), terminate all worker threads, and verify all resources are released. | §3.10, §4 |
| FR-20 | reconcile billing records against completed and cancelled sessions. | §5 |
| FR-21 | generate a workload of at least 200 requests from a fixed random seed with custom generation logic. | §3, §8 |
| FR-22 | benchmark worker pools of 1, 4 and 16 threads and a thread-per-request baseline on the identical workload, ≥ 3 runs each, recording the five required metrics. | §8 |
| FR-23 | provide a separate, deliberately deadlock-prone demonstration with detection evidence and a corrected equivalent, outside the normal execution path. | §7 |

## 2. Non-functional requirements

| ID | Requirement | Brief |
|---|---|---|
| NFR-01 | Safety: the invariants *active bays ≤ 4* and *reserved kW ≤ 60* hold at every instant, checked by assertions under the power lock. | §4 |
| NFR-02 | Liveness: the normal execution path cannot deadlock (no hold-and-wait, fixed lock order). | §4, §7 |
| NFR-03 | Fairness: no queued request waits indefinitely (aging + starvation guard). | §4 |
| NFR-04 | Reproducibility: identical seed ⇒ identical workload, proven by a SHA-256 fingerprint printed for every run. | §8 |
| NFR-05 | Portability: Java 17, standard library only, no third-party concurrency or test frameworks, compiled and run with `javac`/`java` from the command line. | §4, §11B |
| NFR-06 | Observability: every log line carries a timestamp and the thread name; tests capture logs as evidence. | §9, §11C |
| NFR-07 | Verifiability: design contracts are enforced with exceptions and Java assertions; tests refuse to run without `-ea`. | §9 |
| NFR-08 | Isolation of legacy code: `LegacyBillingService` is not modified; all access is serialised by the adapter. | §5 |
| NFR-09 | Simplicity: no database, network, GUI, cloud service or build tool is required. | §1 |

## 3. Assumptions (decisions where the brief is silent)

| ID | Assumption | Rationale |
|---|---|---|
| A-01 | A session reserves its rate's full power (7 or 22 kW) from start to end; there is no tapering. | Brief gives fixed rates; simplest model that matches "complete reservation". |
| A-02 | Time scale: 1 simulated minute = 1 real millisecond (1 simulated hour = 60 ms). Same scale in every run. | Keeps a 200-request run at ~8 s (pool-4) while sleeps (≥ 7 ms) stay well above timer resolution. |
| A-03 | Priority 1 covers both fleet and emergency vehicles; they are not further distinguished. | Brief lists them as one level. |
| A-04 | Aging: one level per 30 simulated minutes waited, capped at level 1; ties on effective priority are broken by arrival order. | Prevents indefinite waiting of visitors under sustained higher-priority load. |
| A-05 | Backfill: if the front request cannot fit, a later request that fits may start. | Avoids idling a bay and power that a smaller request could use. |
| A-06 | Starvation guard clock starts at the first backfill that overtakes the current front request (not at its arrival, and not at the first failed check). Accepted cost: in the worst reachable state with a free bay, up to 16 kW and 2 bays stay idle (two fast sessions = 44 kW). | Measuring from arrival would trigger the guard for almost every request under load (strict head-of-line blocking). Measuring from the first failed check made the policy depend on how many idle threads poll: the first implementation recorded ~100 activations per run for pool-16 and thread-per-request but 0 for pool-4 (see `docs/design.md` §4). |
| A-07 | Billing failure: the session becomes BILLING_FAILED, the failure is logged and counted, resources are released; there is no automatic retry. | Retry policy is a business decision outside the brief; failure is visible in reconciliation. |
| A-08 | Maximum single request: 100 kWh. | Larger than common EV batteries; rejects obviously invalid input. |
| A-09 | Workload: exponential inter-arrival times (mean 35 sim-min); priority mix 20 % P1 / 30 % P2 / 50 % P3; 35 % fast; energy uniform 5–40 kWh in 0.5 kWh steps; 40 accounts; seed 42. | Load close to station capacity at 4 bays so queues form and drain. |
| A-10 | A session cancelled while charging is not billed for the energy already delivered. | Brief: "cancelled sessions are not billed". |
| A-11 | Thread-per-request baseline obeys the same 4-bay / 60 kW limits; only thread creation differs. | Makes the comparison about threading, not about resource rules. |
| A-12 | Waiting time = session start − request submission; total completion time = first scheduled arrival → last session end. | Same definitions for every configuration (§8). |

## 4. Traceability matrix

| Req | Implemented in | Verified by |
|---|---|---|
| FR-01 | `model/ChargingRequest` | T01–T05 |
| FR-02 | `core/RequestValidator` | T05 |
| FR-03 | `ChargingStation.submit` (`seenRequestIds`) | T05 |
| FR-04 | `PriorityScheduler.enqueue` (per-priority FIFO lanes) | T04, T06, T07 |
| FR-05 | `PriorityScheduler.MergedOrder` | T04 |
| FR-06 | `PriorityScheduler.effectivePriority` | T16 |
| FR-07 | `PriorityScheduler.selectNext` (loop past the front) | T17 |
| FR-08 | `PriorityScheduler.selectNext` (`blockedHead` timer) | T17 |
| FR-09 | `StationResources.tryAllocate` | T06, T07, T20 |
| FR-10 | `StationResources` (semaphore + power lock + assertions) | T03, T06, T07, T18, benchmark (12 runs) |
| FR-11 | `ChargingStation.workerLoop/runSession`, `TimeScale` | T01, T02, normal run log |
| FR-12 | `core/ChargingSession`, `model/SessionState` | T08–T11 |
| FR-13 | `BillingAdapter.bill` | T01–T03, T12, normal run reconciliation |
| FR-14 | `BillingAdapter.bill` (`billedRequestIds`), `ChargingSession` state guard | T08, T09, T10, T15 |
| FR-15 | `ChargingStation.cancel`, `PriorityScheduler.cancelQueued`, `ChargingSession.requestCancel` | T08, T09 |
| FR-16 | `BillingAdapter.bill` catch path | T14 |
| FR-17 | `ChargingStation.runSession` `finally` | T09, T10, T14 |
| FR-18 | `core/Log`, `Main` status monitor | `evidence/normal-run-pool4.txt` |
| FR-19 | `ChargingStation.shutdown` | T10, T11 |
| FR-20 | `BillingAdapter.reconcile` | T03, T08, T14, T18, benchmark |
| FR-21 | `workload/WorkloadGenerator` | benchmark fingerprint |
| FR-22 | `app/BenchmarkRunner` | `evidence/benchmark-*` |
| FR-23 | `demo/DeadlockDemo` | T19, T20, `evidence/deadlock-demo.txt` |
| NFR-01 | assertions in `StationResources` | all station tests run with `-ea`; benchmark |
| NFR-02 | `StationResources` (non-blocking `tryAcquire` inside the power lock), lock order in `docs/design.md` | T18, T20, benchmark |
| NFR-03 | aging + guard | T16, T17 |
| NFR-04 | `WorkloadGenerator.fingerprint` | benchmark output |
| NFR-05 | `scripts/*.sh`, README | clean-checkout verification (`evidence/clean-checkout-verification.txt`) |
| NFR-06 | `core/Log`, `TestRunner` log capture | `evidence/test-logs/` |
| NFR-07 | assertions, `TestRunner` `-ea` guard | test report header |
| NFR-08 | `billing/LegacyBillingService` unchanged, `BillingAdapter` | T12, T13 |
