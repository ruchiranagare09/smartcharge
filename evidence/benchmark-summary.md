# Benchmark summary (median of 3 runs per configuration)

Workload: 200 requests, seed 42, fingerprint `b6cf65b335fdff82`; time scale 1.000 ms per simulated minute; Java 17.0.16; 12 logical CPUs; generated 2026-10-29T21:52:53.310786.

| Configuration | Total completion (sim-h) | Total completion (real s) | Avg wait (sim-min) | Max wait (sim-min) | Peak reserved (kW) | Peak bays | Completed | Avg wait P1 / P2 / P3 (sim-min) | Peak live threads | Guard activations | Avg session overrun (sim-min) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| pool-1 | 530.0 | 31.80 | 12189.3 | 24480.9 | 22 (max 22) | 1 | 200 | 10253 / 12242 / 12843 | 8 | 0 | 3.588 |
| pool-4 | 134.9 | 8.10 | 610.2 | 1097.3 | 58 (max 58) | 4 | 200 | 539 / 606 / 637 | 11 | 0 | 3.643 |
| pool-16 | 134.7 | 8.08 | 611.6 | 1086.7 | 58 (max 58) | 4 | 200 | 535 / 611 / 639 | 23 | 0 | 3.538 |
| thread-per-request | 134.8 | 8.09 | 609.5 | 1089.5 | 58 (max 58) | 4 | 200 | 541 / 600 / 638 | 39 | 0 | 3.418 |

Per-run spread (min - max):

- pool-1: completion 529.3 - 530.2 sim-h, avg wait 12167.6 - 12192.9 sim-min, max wait 24448.2 - 24495.5 sim-min
- pool-4: completion 134.7 - 134.9 sim-h, avg wait 601.4 - 610.8 sim-min, max wait 1084.6 - 1097.3 sim-min
- pool-16: completion 134.5 - 134.8 sim-h, avg wait 602.3 - 613.2 sim-min, max wait 1085.6 - 1091.9 sim-min
- thread-per-request: completion 134.7 - 135.5 sim-h, avg wait 606.0 - 611.0 sim-min, max wait 1086.6 - 1094.5 sim-min

Constraint check across all 12 runs (peak bays <= 4, peak power <= 60 kW, all requests billed, billing reconciled, identical workload fingerprint): **PASSED**
