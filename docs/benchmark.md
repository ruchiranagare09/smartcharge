# SmartCharge — worker-pool experiment

## Method

| Item | Setting |
|---|---|
| Configurations | fixed pool of 1, 4, 16 workers; thread-per-request (one new thread per accepted request) |
| Workload | 200 requests, `WorkloadGenerator` seed 42 (A-09). Every run regenerates it and checks the SHA-256 fingerprint `b6cf65b335fdff82`, which matched in all 24 runs |
| Time scale | 1 simulated minute = 1 ms, the same for every run (A-02) |
| Repetitions | 3 runs per configuration, interleaved round-robin (run 1 of all four, then run 2, …), so slow drift on the host affects all configurations alike. Two independent invocations of 12 runs each are reported. |
| Warm-up | one pool-4 run of 40 requests, executed first and discarded (JIT and class loading) |
| Logging | disabled during measured runs, so console I/O does not distort timing |
| Statistic | median of the 3 runs; per-run min–max also reported |
| Policy | identical in every configuration: aging 30 sim-min, starvation guard 30 sim-min, backfill |
| Host | Java 17.0.16, 12 logical CPUs (macOS); assertions on |
| Command | `java -ea -cp out/main smartcharge.app.BenchmarkRunner --out results` |

**Metric definitions** (`app/RunMetrics.java`), used identically in every run:

- **Total completion time:** from the run origin (the first scheduled arrival) to the end of the last session.
- **Waiting time:** session start minus request submission, over all sessions that started. Reported as average and maximum.
- **Peak reserved power:** the high-water mark of reserved kW, updated under the power lock at every allocation.
- **Completed sessions:** sessions that reached BILLED.

Diagnostics also recorded for every run:
- peak live JVM threads;
- starvation-guard activations;
- average session overrun (actual minus nominal charging time).

## Results

### Dataset A — final code, development checkout

Source: `evidence/benchmark-summary-a.md`, `evidence/benchmark-runs-a.csv`.

| Configuration | Total completion (sim-h) | Total completion (real s) | Avg wait (sim-min) | Max wait (sim-min) | Peak reserved (kW) | Peak bays | Completed | Peak live threads | Avg overrun (sim-min) |
|---|---|---|---|---|---|---|---|---|---|
| pool-1 | 540.0 | 32.40 | 12,488.1 | 25,082.1 | 22 | 1 | 200 | 8 | 6.62 |
| pool-4 | 137.5 | 8.25 | 680.9 | 1,184.1 | 58 | 4 | 200 | 11 | 6.52 |
| pool-16 | 137.6 | 8.26 | 689.0 | 1,205.7 | 58 | 4 | 200 | 23 | 6.82 |
| thread-per-request | 137.7 | 8.26 | 695.5 | 1,250.3 | 58 | 4 | 200 | 42 | 6.96 |

- **Per-run spread:** completion within 0.4% for every configuration; average wait within 1.5%.
- **Guard activations:** 1 run of 12 (pool-16, run 2).
- **Constraint check:** **PASSED**.

### Dataset B — fresh clone from GitHub

Source: `evidence/benchmark-summary-b.md`, `evidence/benchmark-runs-b.csv`.
This invocation ran on a fresh clone of commit `2b87dcd`, which has the same Java code as the final
version (later commits changed only documentation, helper-script removal and the name / ID header
comment). The final README verification (`evidence/verification.txt`, commit `a7f0c56`)
ran the same command once more, and all 12 runs again passed the constraint check.

| Configuration | Total completion (sim-h) | Total completion (real s) | Avg wait (sim-min) | Max wait (sim-min) | Peak reserved (kW) | Peak bays | Completed | Peak live threads | Avg overrun (sim-min) |
|---|---|---|---|---|---|---|---|---|---|
| pool-1 | 530.4 | 31.83 | 12,192.9 | 24,510.1 | 22 | 1 | 200 | 8 | 3.73 |
| pool-4 | 134.9 | 8.10 | 613.8 | 1,102.4 | 58 | 4 | 200 | 11 | 3.73 |
| pool-16 | 134.8 | 8.09 | 607.3 | 1,095.0 | 58 | 4 | 200 | 23 | 3.52 |
| thread-per-request | 134.6 | 8.08 | 603.4 | 1,113.7 | 58 | 4 | 200 | 39 | 3.43 |

- **Per-run spread:** completion within 0.4%; average wait within 1.6%.
- **Guard activations:** 2 runs of 12 (thread-per-request run 1, pool-16 run 3).
- **Constraint check:** **PASSED**.

## Analysis

**Why 1 worker is about 3.9× slower.** A pool-1 station can only ever charge one vehicle: peak 1 bay
and 22 kW, against 4 bays and 58 kW for the other configurations. Its throughput is bounded by threads,
not by the station. Requests pile up and wait 18–20× longer on average.

**Why more than 4 workers does not help:**
- **Bays are the limit.** Once there are at least as many workers as bays, the 4 bays and the 60 kW
  budget set the ceiling. A worker runs a session only after `tryAllocate` has reserved both, so worker
  number 5 and beyond simply wait in `awaitNext()`.
- **Completion time is flat.** Across pool-4, pool-16 and thread-per-request it differs by at most
  0.2% in both datasets.
- **Extra threads only cost resources.** Peak live JVM threads rise from 11 to 23 to 39–42 with no
  throughput gain.
- **Implication.** Adding threads cannot raise throughput past a physical resource limit. Only adding
  bays or power would.

**The small differences between configurations follow timer overrun, not thread count:**

| | Dataset A (busier host) | Dataset B (quieter host) |
|---|---|---|
| Overrun per session | pool-4 had the *lowest* (6.52 vs 6.82 / 6.96) | pool-4 had the *highest* (3.73 vs 3.52 / 3.43) |
| Average wait | pool-4 was best (680.9 vs 689.0 / 695.5) | pool-4 was worst (613.8 vs 607.3 / 603.4) |

Every queued request waits behind several sessions, so a fraction of a millisecond of extra overrun
per session accumulates into its wait. Extra threads can add a little scheduling overhead when the host
is loaded, but the direction of the gap changes from one invocation to the next.

**Why peak power is 58 kW, never 60.** The budget is respected, and 60 itself is unreachable. With
rates of 7 and 22 kW on 4 bays, the largest sum not above 60 is 22+22+7+7 = 58. So "≤ 60" is verified
by observation, and the gap to 60 is structural, not slack in the scheduler.

**Bottleneck versus defect.** The flat curve from 4 workers upward is a capacity limit, not a
concurrency defect. In all 24 runs, with assertions on:
- **No over-commitment:** peaks never exceeded 4 bays or 58 kW.
- **No lost billing:** every run reconciled.
- **No deadlock:** every run terminated.
- **No starvation by thread count:** max waits stay within 5% across the three multi-worker
  configurations, and within the 1,440 sim-min target (NFR-03).

**Priority effect.** Median average waits for P1 / P2 / P3 are ordered in every configuration of both
datasets:
- Dataset A: pool-4 600 / 677 / 713; pool-16 600 / 683 / 724; thread-per-request 620 / 698 / 723.
- Dataset B: pool-4 543 / 609 / 641; pool-16 534 / 608 / 635; thread-per-request 542 / 603 / 626.

The differentiation is modest, because typical waits (about 10 h) are 20× the 30-minute aging step,
so most requests are aged to effective priority 1 and served near-FIFO. A longer aging step would
increase differentiation. That is a tuning decision, recorded as a finding rather than changed after
the fact.

## Measurement bias and threats to validity

| Threat | Mitigation / observation |
|---|---|
| JIT warm-up and class loading | discarded warm-up run; round-robin ordering |
| Host load and noise | 3 runs per configuration, median and spread reported, two independent invocations. Absolute times differ by about 2% between invocations (pool-1: 540.0 vs 530.4 sim-h), while ratios between configurations are stable. |
| Timer and wake-up latency | measured directly as average session overrun: 6.4–7.3 sim-min per run on the busier host, 3.4–3.8 on the quieter one (about 2.5–5% of a mean session). This is the main source of the small differences between multi-worker configurations, and it depends on the host. |
| Logging cost | logging is off in measured runs; logged normal runs (`evidence/normal-run-*.txt`) are shown for behaviour, not timing |
| Scaled time | real sleeps at 1 ms per sim-minute; a different scale changes absolute times, not the shape of the result |
| Policy differing by configuration | found and fixed during evaluation: the first starvation-guard implementation behaved differently with spare threads (`docs/design.md` §4). With the final code the guard activated in 3 of 24 runs, spread across configurations. |
| Single workload | one seed, one load level. Results describe a station running slightly above capacity, not every load. |
