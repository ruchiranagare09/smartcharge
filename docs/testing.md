# SmartCharge — test strategy and results

## Strategy

- **Runner:** a dependency-free runner (`src/test/java/smartcharge/tests/TestRunner.java`), so the
  project needs nothing beyond the JDK. It refuses to run without `-ea`, because design contracts are
  enforced partly with `assert`.
- **Evidence:** every station test runs a real `ChargingStation` with real worker threads. The runner
  redirects `System.out` during each test, so the thread-named station log is captured as evidence
  (`evidence/test-logs/<ID>.txt`).
- **Timing assertions:** these compare recorded timestamps, for example "5th start ≥ first release",
  instead of fixed sleeps. This keeps them deterministic regardless of machine speed.
- **Deterministic policy tests:** scheduler policy (T16, T17) is tested by calling
  `PriorityScheduler.selectNext(now, allocator)` with an explicit clock and a fake allocator.
- **Deadlock tests:** these reuse the separate `DeadlockDemo`. Its threads are daemons, so a
  reproduced deadlock cannot hang the test JVM.
- **Stability check:** before the final run, the suite was re-executed 5 times with 19/19 passing
  each time.

## Results

Source: `evidence/test-results.md` (generated 2026-10-29, Java 17.0.16, assertions on) and
`evidence/test-console.txt`. Observed values are copied from that run; nothing below is predicted.

| ID | Cat. | Scenario | Expected | Observed | Result |
|---|---|---|---|---|---|
| T01 | Normal | Standard 7 kWh session | BILLED once, 7.0 kWh, ~60 sim-min, resources returned | BILLED, 1 entry, 7.0 kWh, charged 68.9 sim-min, 0 kW / 0 bays | PASS |
| T02 | Normal | Fast 22 kWh session | BILLED once, peak 22 kW | BILLED, 1 entry, peak 22 kW, 65.7 sim-min | PASS |
| T03 | Normal | 8 simultaneous submissions (latch) | all billed, ≤ 4 bays, ≤ 60 kW, reconciled | 8 billed, peak 4 bays / 28 kW, reconciled | PASS |
| T04 | Normal | Priority + FIFO (aging off) | BLK, F1, S1, S2, V1, V2 | `[BLK, F1, S1, S2, V1, V2]` | PASS |
| T05 | Edge | 10 invalid + 1 duplicate request | all rejected, no session | 11/11 rejected, 1 session (valid one) | PASS |
| T06 | Edge | 5 standard, 4 bays | 5th waits for a release | 5th started +60.9 ms, first release +60.2 ms | PASS |
| T07 | Edge | 3 fast (66 kW) | 2 run, 3rd waits with bays idle | peak 44 kW, 2 bays, 3rd +61.9 ms after release +61.2 ms | PASS |
| T08 | Edge | Cancel while queued | CANCELLED, never started, not billed | CANCELLED, not started, 0.0 kWh billed, blocker BILLED | PASS |
| T09 | Edge | Cancel while charging | CANCELLED < 100 ms, not billed, released | CANCELLED in 1.6 ms, 0 entries, 0 kW / 0 bays | PASS |
| T10 | Edge | Immediate shutdown, 4 active + 2 queued | all cancelled, nothing billed, all released, pool terminated | 6/6 cancelled, 0 entries, 0 kW, terminated in 1.1 ms | PASS |
| T11 | Edge | Graceful shutdown with active sessions | all billed, released, terminated | 6/6 billed, 0 kW / 0 bays, terminated | PASS |
| T12 | Edge | 16 threads × 500 billings via adapter + shared duplicate | 8001 entries, 15 duplicates ignored, exact totals | 8001, 15, exact | PASS |
| T13 | Obs. | Same load directly on legacy service | shows corruption risk | 5/5 trials corrupted (7,035–7,989 / 8,000 entries) | OBSERVED |
| T14 | Edge | Legacy throws for one account | BILLING_FAILED + BILLED, resources released | RF BILLING_FAILED, RG BILLED, 1 entry, 0 kW / 0 bays | PASS |
| T15 | Edge | Same session billed twice | BILLED then DUPLICATE_IGNORED | BILLED, DUPLICATE_IGNORED, 1 entry | PASS |
| T16 | Edge | Aging | F at t=20, V at t=65 | F, V | PASS |
| T17 | Edge | Starvation guard | S1, S2, none, F, S3 | `[S1, S2, none, F, S3]` | PASS |
| T18 | Edge | 200 seeded requests at once, thread-per-request | 200 billed, ≤ 4 bays, ≤ 60 kW, reconciled | 200 billed, 4 bays, 58 kW, reconciled, threads ended | PASS |
| T19 | Edge | Deadlock-prone allocator | deadlock detected | watchdog detected, 5/5 threads blocked | PASS |
| T20 | Edge | Corrected allocator, same interleaving | all finish | all finished, 0 kW at end | PASS |

**Totals:** 19 passed, 0 failed, 1 observation. That covers 4 normal cases (minimum required: 3) and
15 edge cases (minimum required: 3).

## Coverage of the brief's scenario list (§9)

| Scenario | Test |
|---|---|
| Normal standard-rate session | T01 |
| Normal fast-rate session | T02 |
| Multiple simultaneous valid requests | T03, T18 |
| No free bay | T06 |
| Exhausted power budget | T07 |
| Equal priority (FIFO) | T04 |
| Different priorities | T04, T16 |
| Invalid or zero energy | T05 |
| Cancellation before charging | T08 |
| Cancellation during charging | T09 |
| Shutdown while sessions are active | T10, T11 |
| Concurrent billing | T12 (T13 for contrast) |
| Resource release after an exception | T14 |
| Deadlock-prone scenario and corrected equivalent | T19, T20 |

## Notes on interpreting results

- **Charge-time overrun:** charged durations exceed nominal by a few simulated minutes. T01
  measured 68.9 against 60. This is wake-up latency of timed waits (1 sim-min = 1 ms). The first test
  in a JVM also pays class-loading cost.
- **T13 is non-deterministic by nature:** the number of lost updates changes from run to run. It is
  reported as an observation, not a pass/fail case, because a race can be shown to exist but not
  shown to be absent.
