# Benchmark summary (median of 3 runs per configuration)

Workload: 200 requests, seed 42, fingerprint `b6cf65b335fdff82`; time scale 1.000 ms per simulated minute; Java 17.0.16; 12 logical CPUs; generated 2026-10-29T22:35:59.217894.

| Configuration | Total completion (sim-h) | Total completion (real s) | Avg wait (sim-min) | Max wait (sim-min) | Peak reserved (kW) | Peak bays | Completed | Avg wait P1 / P2 / P3 (sim-min) | Peak live threads | Guard activations | Avg session overrun (sim-min) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| pool-1 | 540.0 | 32.40 | 12488.1 | 25082.1 | 22 (max 22) | 1 | 200 | 10505 / 12541 / 13157 | 8 | 0 | 6.619 |
| pool-4 | 137.5 | 8.25 | 680.9 | 1184.1 | 58 (max 58) | 4 | 200 | 600 / 677 / 713 | 11 | 0 | 6.515 |
| pool-16 | 137.6 | 8.26 | 689.0 | 1205.7 | 58 (max 58) | 4 | 200 | 600 / 683 / 724 | 23 | 0 | 6.822 |
| thread-per-request | 137.7 | 8.26 | 695.5 | 1250.3 | 58 (max 58) | 4 | 200 | 620 / 698 / 723 | 42 | 0 | 6.961 |

Per-run spread (min - max):

- pool-1: completion 539.3 - 541.4 sim-h, avg wait 12435.7 - 12550.8 sim-min, max wait 25031.6 - 25162.7 sim-min
- pool-4: completion 137.3 - 137.5 sim-h, avg wait 680.7 - 681.2 sim-min, max wait 1180.2 - 1191.1 sim-min
- pool-16: completion 137.4 - 137.6 sim-h, avg wait 682.4 - 692.7 sim-min, max wait 1194.8 - 1205.9 sim-min
- thread-per-request: completion 137.6 - 137.8 sim-h, avg wait 690.8 - 697.0 sim-min, max wait 1246.1 - 1257.0 sim-min

Constraint check across all 12 runs (peak bays <= 4, peak power <= 60 kW, all requests billed, billing reconciled, identical workload fingerprint): **PASSED**
