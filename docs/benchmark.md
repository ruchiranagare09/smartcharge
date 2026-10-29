# SmartCharge — worker-pool experiment

## Method

| Item | Setting |
|---|---|
| Configurations | fixed pool of 1, 4, 16 workers; thread-per-request (one new thread per accepted request) |
| Workload | 200 requests, `WorkloadGenerator` seed 42 (A-09). Every run regenerates it and checks the SHA-256 fingerprint `b6cf65b335fdff82`, which matched in all 12 runs |
| Time scale | 1 simulated minute = 1 ms, the same for every run (A-02) |
| Repetitions | 3 runs per configuration, interleaved round-robin (run 1 of all four, then run 2, …), so slow drift on the host affects all configurations alike |
| Warm-up | one pool-4 run of 40 requests, executed first and discarded (JIT and class loading) |
| Logging | disabled during measured runs, so console I/O does not distort timing |
| Statistic | median of the 3 runs; per-run min–max also reported |
| Policy | identical in every configuration: aging 30 sim-min, starvation guard 30 sim-min, backfill |
| Host | Java 17.0.16, 12 logical CPUs (macOS); assertions on |
| Command | `./scripts/benchmark.sh` (raw output `evidence/benchmark-console.txt`) |

**Metric definitions** (`app/RunMetrics.java`), used identically in every run:

- **Total completion time:** from the run origin (the first scheduled arrival) to the end of the last session.
- **Waiting time:** session start minus request submission, over all sessions that started. Reported as average and maximum.
- **Peak reserved power:** the high-water mark of reserved kW, updated under the power lock at every allocation.
- **Completed sessions:** sessions that reached BILLED.

Two extra diagnostics: **peak live JVM threads**, and **average session overrun** (actual minus nominal charging time).

## Results (median of 3 runs)

Source: `evidence/benchmark-summary.md` and `evidence/benchmark-runs.csv`.

| Configuration | Total completion (sim-h) | Total completion (real s) | Avg wait (sim-min) | Max wait (sim-min) | Peak reserved (kW) | Peak bays | Completed | Peak live threads |
|---|---|---|---|---|---|---|---|---|
| pool-1 | 530.0 | 31.80 | 12,189.3 | 24,480.9 | 22 | 1 | 200 | 8 |
| pool-4 | 134.9 | 8.10 | 610.2 | 1,097.3 | 58 | 4 | 200 | 11 |
| pool-16 | 134.7 | 8.08 | 611.6 | 1,086.7 | 58 | 4 | 200 | 23 |
| thread-per-request | 134.8 | 8.09 | 609.5 | 1,089.5 | 58 | 4 | 200 | 39 |

**Per-run spread:**
- **Total completion:** within 0.6% for every configuration (pool-1: 529.3–530.2 sim-h; thread-per-request: 134.7–135.5 sim-h).
- **Average wait:** within 1.8% (pool-4: 601.4–610.8 sim-min).

**Constraint check, all 12 runs:** peak bays ≤ 4, peak power ≤ 60 kW, 200/200 billed, billing reconciled, identical fingerprint → **PASSED**.

## Analysis

**Why 1 worker is about 3.9× slower.** A pool-1 station can only ever charge one vehicle: peak
1 bay and 22 kW, against 4 bays and 58 kW for the other configurations. Its throughput is bounded by
threads, not by the station. Requests pile up and wait 20× longer on average.

**Why 4, 16 and thread-per-request perform the same:**
- **Bays are the limit.** Once there are at least as many workers as bays, the 4 bays and the 60 kW
  budget set the ceiling. A worker can only run a session after `tryAllocate` succeeds, so worker
  number 5 and beyond simply wait in `awaitNext()`.
- **The measured results agree.** Completion times differ by under 0.2% and average waits by under
  0.4%, well within run-to-run noise.
- **Extra threads only cost resources.** Peak live JVM threads rise from 11 to 23 to 39 with no
  throughput gain.
- **Implication.** Adding threads cannot raise throughput past a physical resource limit. Only
  adding bays or power would.

**Why peak power is 58 kW, never 60.** The budget is respected, and 60 itself is unreachable.
With rates of 7 and 22 kW on 4 bays, the largest sum not above 60 is 22+22+7+7 = 58. So "≤ 60" is
verified by observation, and the gap to 60 is structural, not slack in the scheduler.

**Bottleneck versus defect.** The flat curve from 4 workers upward is a capacity limit, not a
concurrency defect:
- **No over-commitment:** assertions on, and peaks were never above 4 bays or 58 kW.
- **No lost billing:** all 12 runs reconciled.
- **No deadlock:** every run terminated.
- **No starvation by thread count:** max waits are almost identical across the three configurations.

**Priority effect.** Median waits for P1 / P2 / P3 under pool-4 are 539 / 606 / 637 sim-min. Priority
helps only modestly here, because typical waits (~10 h) are 20× the 30-minute aging step, so most
requests are aged to effective priority 1 and served near-FIFO. A longer aging step would increase
differentiation. That is a tuning decision, recorded as a finding rather than changed after the fact.

## Measurement bias and threats to validity

| Threat | Mitigation / observation |
|---|---|
| JIT warm-up and class loading | discarded warm-up run; round-robin ordering |
| Host load and noise | 3 runs, median reported, spread reported. An earlier invocation, made while other work was running, was about 2% slower across *all* configurations, including pool-1, so absolute times are host-dependent while ratios are stable. |
| Timer and wake-up latency | measured directly: average session overrun 3.4–3.6 sim-min (≈ 2.4% of a mean session), the same across configurations, so comparisons stay fair |
| Logging cost | logging off in measured runs; logged normal runs (`evidence/normal-run-*.txt`) wait about 2% longer |
| Scaled time | real sleeps at 1 ms per sim-minute; a different scale changes absolute times, not the shape of the result |
| Policy differing by configuration | found and fixed during evaluation: the first starvation-guard implementation behaved differently with spare threads (see `docs/design.md` §4). After the fix, the guard activated 0 times in 11 of 12 runs. |
| Single workload | one seed, one load level. Results describe a station running slightly above capacity, not every load. |
