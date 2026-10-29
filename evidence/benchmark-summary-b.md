# Benchmark summary (median of 3 runs per configuration)

Workload: 200 requests, seed 42, fingerprint `b6cf65b335fdff82`; time scale 1.000 ms per simulated minute; Java 17.0.16; 12 logical CPUs; generated 2026-10-29T22:42:26.157763.

| Configuration | Total completion (sim-h) | Total completion (real s) | Avg wait (sim-min) | Max wait (sim-min) | Peak reserved (kW) | Peak bays | Completed | Avg wait P1 / P2 / P3 (sim-min) | Peak live threads | Guard activations | Avg session overrun (sim-min) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| pool-1 | 530.4 | 31.83 | 12192.9 | 24510.1 | 22 (max 22) | 1 | 200 | 10255 / 12246 / 12846 | 8 | 0 | 3.728 |
| pool-4 | 134.9 | 8.10 | 613.8 | 1102.4 | 58 (max 58) | 4 | 200 | 543 / 609 / 641 | 11 | 0 | 3.731 |
| pool-16 | 134.8 | 8.09 | 607.3 | 1095.0 | 58 (max 58) | 4 | 200 | 534 / 608 / 635 | 23 | 0 | 3.523 |
| thread-per-request | 134.6 | 8.08 | 603.4 | 1113.7 | 58 (max 58) | 4 | 200 | 542 / 603 / 626 | 39 | 0 | 3.433 |

Per-run spread (min - max):

- pool-1: completion 530.2 - 530.5 sim-h, avg wait 12184.5 - 12199.7 sim-min, max wait 24494.7 - 24512.0 sim-min
- pool-4: completion 134.9 - 135.0 sim-h, avg wait 612.8 - 614.6 sim-min, max wait 1097.5 - 1106.9 sim-min
- pool-16: completion 134.8 - 135.1 sim-h, avg wait 601.1 - 610.7 sim-min, max wait 1092.8 - 1146.1 sim-min
- thread-per-request: completion 134.5 - 134.8 sim-h, avg wait 603.2 - 609.0 sim-min, max wait 1097.2 - 1163.9 sim-min

Constraint check across all 12 runs (peak bays <= 4, peak power <= 60 kW, all requests billed, billing reconciled, identical workload fingerprint): **PASSED**
