# SmartCharge — requirements, assumptions and traceability

**Source of truth:** the CO3408 assignment brief *SmartCharge: Modelling and Building a Concurrent EV
Charging Station Controller* (2026–27). "p.n" refers to its page numbers.

Every requirement below is numbered and testable. Each one is traced to the component that
implements it and to the evidence that verifies it (§5).

Quality attribute key, shown in the **Supports** column:

- **S** — safety: limits and invariants are never broken.
- **R** — reliability: correct results, including under failure and concurrency.
- **P** — performance: throughput and responsiveness.
- **F** — fairness: every request is eventually served, in a defensible order.

## 1. Functional requirements

| ID | Requirement (the system shall…) | Supports | Why it supports it | Brief |
|---|---|---|---|---|
| FR-01 | accept charging requests containing request ID, account ID, priority level, requested energy (kWh) and charging rate category. | R | One well-defined input shape that every later stage can rely on. | p.6 |
| FR-02 | reject a request whose request ID or account ID is blank, whose priority is not 1, 2 or 3, whose rate is missing, or whose energy is not a positive finite number ≤ 100 kWh (A-08). | R, S | Invalid input never reaches scheduling. A zero or negative energy would otherwise create a zero-length or negative session. | p.6, p.10 |
| FR-03 | reject a request whose request ID has already been accepted by the station. | R | Request IDs key billing, so a duplicate could be billed twice. | p.6–7 |
| FR-04 | queue every accepted request that cannot start immediately. | R | No request is lost when the station is full. | p.6 |
| FR-05 | order queued requests by priority (1 fleet/emergency, 2 staff, 3 visitor) and, within equal effective priority, by arrival order (FIFO). | F, P | Urgent vehicles first, and a predictable order among equals. | p.7 |
| FR-06 | raise a waiting request's effective priority by one level per 30 simulated minutes waited, never above level 1 (A-04). | F | Lower priorities cannot be overtaken forever. | p.7, p.10 |
| FR-07 | when the front request cannot obtain its resources, start the next queued request that can (backfill) (A-05). | P | A free bay and spare power are not left idle behind a large request. | p.7 |
| FR-08 | once the front request's first overtake by a backfill was at least 30 simulated minutes ago, start nothing else until it starts (A-06). | F | Bounds how long backfill can starve a large (fast) request, which aging cannot help when it is already P1. | p.7 |
| FR-09 | start a session only when it holds both a free bay and its complete power reservation (7 kW standard, 22 kW fast); never hold a partial allocation. | S | A session can never draw power it has not reserved, and never blocks a bay while waiting for power. | p.7 |
| FR-10 | never have more than 4 sessions charging, and never reserve more than 60 kW in total. | S | The physical limits of the car park and its grid connection. | p.6–7 |
| FR-11 | execute charging sessions concurrently on worker threads, with duration = energy (kWh) / rate (kW), on a documented time scale (A-02). | P | Requests arriving at the same time are served in parallel up to the station's limits. | p.6, p.10 |
| FR-12 | track each session's state (QUEUED, CHARGING, COMPLETED, BILLED, BILLING_FAILED, CANCELLED), start/end times and reserved power. | R | Billing, cancellation and shutdown decisions depend on a single authoritative state. | p.6 |
| FR-13 | record exactly one billing entry, through the adapter, for every normally completed session. | R | Correct revenue; no lost or doubled charges. | p.7, p.10 |
| FR-14 | never bill a cancelled session, and ignore any repeated billing attempt for the same request. | R | Drivers are never charged for a cancelled session or charged twice. | p.7 |
| FR-15 | allow cancellation of a queued request and of a charging session; a cancelled session is not billed. | R, P | Drivers can leave; freed capacity is reused quickly. | p.7, p.10 |
| FR-16 | handle a billing failure by marking the session BILLING_FAILED, logging it, and continuing (A-07). | R | A failure in the legacy system cannot stall the station or leak a bay. | p.7 |
| FR-17 | release a session's bay and power on completion, cancellation, billing failure, interruption or unexpected exception. | S, R | Capacity is never lost permanently, whatever path a session takes. | p.7 |
| FR-18 | report station and session status through thread-named log lines and periodic status lines. | R | Concurrent behaviour can be followed and audited. | p.6, p.10 |
| FR-19 | support graceful shutdown (finish queued and active sessions) and immediate shutdown (cancel queued, cancel active), terminate all worker threads, and verify all resources are released. | S, R | Clean stop with no leaked threads, bays or power. | p.7 |
| FR-20 | reconcile billing records against completed and cancelled sessions. | R | Detects any lost, duplicated or wrongly billed entry. | p.7 |
| FR-21 | generate a workload of at least 200 requests from a fixed random seed with custom generation logic. | P | A repeatable load for fair performance comparison. | p.6, p.10 |
| FR-22 | benchmark worker pools of 1, 4 and 16 threads and a thread-per-request baseline on the identical workload, ≥ 3 runs each, recording the five required metrics. | P | Evidence of how pool size interacts with the bay and power limits. | p.10 |
| FR-23 | provide a separate, deliberately deadlock-prone demonstration with detection evidence and a corrected equivalent, outside the normal execution path. | S | Shows the hazard the production design prevents. | p.10 |

## 2. Non-functional requirements with measurable targets

The brief gives no numeric targets other than the station limits. Each target below is a proposed,
justified assumption, as the brief allows (p.8). "Measured" values come from real runs, and the
evidence file is named for each.

| ID | Attribute | Target | Justification | Measured (evidence) | Met |
|---|---|---|---|---|---|
| NFR-01 | Safety | Peak active bays ≤ 4 and peak reserved power ≤ 60 kW in 100% of runs, with 0 assertion failures (`-ea`). | Hard physical limits; one violation is a failure. | 24/24 benchmark runs: peak 4 bays, 58 kW. Both normal runs 58 kW. T18 58 kW. 0 assertion failures (`evidence/benchmark-runs.csv`, `normal-run-*.txt`). | yes |
| NFR-02 | Liveness | 100% of runs terminate (`shutdown` returns true) within the timeout; no deadlock on the normal path. | A hung controller stops every vehicle. | 24/24 benchmark runs, all normal runs and all 24 tests terminated. | yes |
| NFR-03 | Fairness (bound) | At the default load (A-09) with ≥ 4 workers, maximum wait ≤ 1,440 sim-min (24 simulated hours). | A vehicle left for a working day and a night should be charged within a day. | Median max wait. Dataset A: pool-4 1,184, pool-16 1,206, thread-per-request 1,250 sim-min (`evidence/benchmark-summary.md`). Dataset B: 1,102, 1,095, 1,114 (`evidence/benchmark-summary-clean-checkout.md`). | yes |
| NFR-04 | Fairness (order) | Under load, average wait by priority satisfies P1 ≤ P2 ≤ P3 for every configuration. | Priority must have a measurable effect, not just exist in code. | Dataset A: pool-4 600 / 677 / 713; pool-16 600 / 683 / 724; thread-per-request 620 / 698 / 723. Dataset B: 543 / 609 / 641; 534 / 608 / 635; 542 / 603 / 626 sim-min. | yes |
| NFR-05 | Performance (scalability) | With 16 workers or thread-per-request, total completion is within 5% of pool-4. | Threads beyond the 4 bays must not degrade throughput beyond noise. | Dataset A: pool-4 137.5 sim-h; pool-16 137.6 (+0.1%); thread-per-request 137.7 (+0.1%). Dataset B: 134.9; 134.8 (−0.1%); 134.6 (−0.2%). | yes |
| NFR-06 | Responsiveness | Cancelling a charging session takes effect within 500 ms real time; immediate shutdown completes within 2 s. | Operator actions must feel immediate at human timescales. | T09: 0.1 ms. T10: 0.7 ms (`evidence/test-results.md`). | yes |
| NFR-07 | Billing accuracy | 100% of completed sessions billed exactly once, 0 cancelled sessions billed, totals exact, in every run. | Financial correctness. | Every run reconciled. T12: 8,001/8,001 entries, totals exact. T08, T09, T10: 0 entries for cancelled sessions. | yes |
| NFR-08 | Reproducibility | The same seed gives an identical workload, proven by an identical SHA-256 fingerprint, in 100% of runs. | Fair comparison across configurations. | Fingerprint `b6cf65b335fdff82` in 24/24 runs. | yes |
| NFR-09 | Portability | Java 17 standard library only; builds and runs with `javac`/`java` from a clean checkout. | Marker reproducibility; brief forbids third-party concurrency (p.4). | Only `java.*` imports. Clean-checkout transcript in `evidence/clean-checkout-verification.txt`. | yes |
| NFR-10 | Observability | 100% of log events start with a timestamp and `[thread-name]`. | Concurrent behaviour must be traceable (p.10). | 1,048/1,048 events in each normal run. The only other lines are the 5-line continuation of the final summary. | yes |
| NFR-11 | Verifiability | Contracts enforced by exceptions and assertions. Tests refuse to run without `-ea`. Every contract has a test showing it holds and a test showing its violation is rejected. | Contracts must be checkable, not decorative (p.10). | Test report header "Assertions enabled: true". Violation tests T21–T23. | yes |
| NFR-12 | Legacy isolation | `LegacyBillingService` behaviour is never modified; all access goes through the adapter's lock. | Brownfield constraint (p.7). | Since its creation in commit `2442271`, the only changes are the Name / Student ID header comment required on every file (commits `76dc652` and `7300ac5`). | yes |
| NFR-13 | Simplicity | No database, network, GUI, cloud service or build tool. | Scope (p.6). | Repository contents. | yes |

## 3. Assumptions (decisions where the brief is silent)

| ID | Assumption | Rationale |
|---|---|---|
| A-01 | A session reserves its rate's full power (7 or 22 kW) from start to end; there is no tapering. | The brief gives fixed rates; this is the simplest model that matches "full charging rate is reserved" (p.7). |
| A-02 | Time scale: 1 simulated minute = 1 real millisecond (1 simulated hour = 60 ms). Same scale in every run. | Keeps a 200-request run at about 8 s (pool-4) while sleeps (≥ 7 ms) stay well above timer resolution. The brief allows any stated scale (p.6). |
| A-03 | Priority 1 covers both fleet and emergency vehicles; they are not further distinguished. | The brief lists them as one level (p.6). |
| A-04 | Aging: one level per 30 simulated minutes waited, capped at level 1; ties on effective priority are broken by arrival order. | Prevents indefinite waiting of visitors under sustained higher-priority load (p.7). |
| A-05 | Backfill: if the front request cannot fit, a later request that fits may start. | Avoids idling a bay and power that a smaller request could use. |
| A-06 | The starvation-guard clock is kept per request and starts at that request's first overtake by a backfill. It is not measured from arrival or from the first failed check, and it is not reset if aging briefly moves another request to the front. Accepted cost: in the worst reachable state with a free bay, up to 16 kW and 2 bays stay idle (two fast sessions = 44 kW); observed in T24. | Measuring from arrival would trigger the guard for almost every request under load, becoming strict head-of-line blocking. Measuring from the first failed check made the policy depend on how many idle threads poll (observed during development as about 100 activations per run for pool-16 versus 0 for pool-4; reproducible at commit `0913b2e`, see `docs/design.md` §4). A single scheduler-wide clock could be reset by aging (fixed; T25). |
| A-07 | Billing failure: the session becomes BILLING_FAILED, the failure is logged and counted, and resources are released. There is no automatic retry. | Retry policy is a business decision outside the brief; the failure is visible in reconciliation. |
| A-08 | Maximum single request: 100 kWh. | Larger than common EV batteries; rejects obviously invalid input. |
| A-09 | Workload: exponential inter-arrival times (mean 35 sim-min); priority mix 20% P1 / 30% P2 / 50% P3; 35% fast; energy uniform 5–40 kWh in 0.5 kWh steps; 40 accounts; seed 42. | Load close to station capacity at 4 bays, so queues form and drain. |
| A-10 | A session cancelled while charging is not billed for the energy already delivered. | The brief: "a cancelled session (before or during charging) must not create a billing entry" (p.7). |
| A-11 | The thread-per-request baseline obeys the same 4-bay / 60 kW limits; only thread creation differs. | Makes the comparison about threading, not about resource rules. |
| A-12 | Waiting time = session start − request submission. Total completion time = first scheduled arrival → last session end. | The same definitions for every configuration (p.10). |

## 4. Note on the scheduling-policy wording

Item 5 of the originally proposed policy said: "once the front request has *waited* at least 30
simulated minutes, stop starting new sessions". The implementation deliberately differs. It measures
30 minutes from the front request's *first overtake by a backfill* (A-06, FR-08), for the reasons given
in A-06. Any description of the policy, including the report, should use the FR-08 / A-06 definition.

## 5. Traceability matrix

| Req | Implemented in | Verified by |
|---|---|---|
| FR-01 | `model/ChargingRequest` | T01–T05 |
| FR-02 | `core/RequestValidator` | T05 |
| FR-03 | `ChargingStation.submit` (`seenRequestIds`) | T05 |
| FR-04 | `PriorityScheduler.enqueue` (per-priority FIFO lanes) | T04, T06, T07 |
| FR-05 | `PriorityScheduler.MergedOrder` | T04 |
| FR-06 | `PriorityScheduler.effectivePriority` | T16, T25 |
| FR-07 | `PriorityScheduler.selectNext` (loop past the front) | T17, T24 |
| FR-08 | `PriorityScheduler.selectNext`, per-request `ChargingSession.overtakenSinceNanos` | T17, T24, T25 |
| FR-09 | `StationResources.tryAllocate` | T06, T07, T20, T21 |
| FR-10 | `StationResources` (semaphore + power lock + assertions) | T03, T06, T07, T18, benchmark (24 runs) |
| FR-11 | `ChargingStation.workerLoop/runSession`, `TimeScale` | T01, T02, normal run log |
| FR-12 | `core/ChargingSession`, `model/SessionState` | T08–T11, T22 |
| FR-13 | `BillingAdapter.bill` | T01–T03, T12, normal run reconciliation |
| FR-14 | `BillingAdapter.bill` (`billedRequestIds`), `ChargingSession` state guard | T08, T09, T10, T15, T22 |
| FR-15 | `ChargingStation.cancel`, `PriorityScheduler.cancelQueued`, `ChargingSession.requestCancel` | T08, T09 |
| FR-16 | `BillingAdapter.bill` catch path | T14 |
| FR-17 | `ChargingStation.runSession` `finally` | T09, T10, T14, T21 |
| FR-18 | `core/Log`, `Main` status monitor | `evidence/normal-run-*.txt` (NFR-10) |
| FR-19 | `ChargingStation.shutdown` | T10, T11, T23 |
| FR-20 | `BillingAdapter.reconcile` | T03, T08, T14, T18, benchmark |
| FR-21 | `workload/WorkloadGenerator` | benchmark fingerprint (NFR-08) |
| FR-22 | `app/BenchmarkRunner` | `evidence/benchmark-*` |
| FR-23 | `demo/DeadlockDemo` | T19, T20, `evidence/deadlock-demo.txt` |
| NFR-01 | assertions in `StationResources` | all station tests (`-ea`), benchmark |
| NFR-02 | `StationResources` (non-blocking `tryAcquire` inside the power lock), lock order (`docs/design.md` §3) | T18, T20, benchmark |
| NFR-03, NFR-04 | aging + guard | benchmark, T16, T17, T24, T25 |
| NFR-05 | worker-pull design (`ChargingStation.workerLoop`) | benchmark |
| NFR-06 | `CountDownLatch` cancel signal, `ChargingStation.shutdown` | T09, T10 |
| NFR-07 | `BillingAdapter` | T08–T15, every reconciled run |
| NFR-08 | `WorkloadGenerator.fingerprint` | benchmark output |
| NFR-09 | README compile and run commands | `evidence/clean-checkout-verification.txt` |
| NFR-10 | `core/Log`, `TestRunner` log capture | `evidence/normal-run-*.txt`, `evidence/test-logs/` |
| NFR-11 | assertions, exceptions, `TestRunner` `-ea` guard | T21–T23, test report header |
| NFR-12 | `billing/LegacyBillingService`, `BillingAdapter` | git history, T12, T13 |
