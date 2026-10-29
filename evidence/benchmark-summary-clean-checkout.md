# Benchmark summary (median of 3 runs per configuration)

Workload: 200 requests, seed 42, fingerprint `b6cf65b335fdff82`; time scale 1.000 ms per simulated minute; Java 17.0.16; 12 logical CPUs; generated 2026-10-29T21:59:55.420869.

| Configuration | Total completion (sim-h) | Total completion (real s) | Avg wait (sim-min) | Max wait (sim-min) | Peak reserved (kW) | Peak bays | Completed | Avg wait P1 / P2 / P3 (sim-min) | Peak live threads | Guard activations | Avg session overrun (sim-min) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| pool-1 | 539.8 | 32.39 | 12489.8 | 25063.5 | 22 (max 22) | 1 | 200 | 10507 / 12544 / 13158 | 8 | 0 | 6.577 |
| pool-4 | 137.3 | 8.24 | 679.3 | 1179.7 | 58 (max 58) | 4 | 200 | 598 / 674 / 711 | 11 | 0 | 6.489 |
| pool-16 | 137.7 | 8.26 | 695.0 | 1212.7 | 58 (max 58) | 4 | 200 | 609 / 693 / 723 | 23 | 0 | 6.910 |
| thread-per-request | 137.9 | 8.27 | 693.1 | 1260.1 | 58 (max 58) | 4 | 200 | 619 / 684 / 715 | 42 | 0 | 6.898 |

Per-run spread (min - max):

- pool-1: completion 538.0 - 540.5 sim-h, avg wait 12421.9 - 12503.5 sim-min, max wait 24953.0 - 25105.3 sim-min
- pool-4: completion 137.0 - 137.5 sim-h, avg wait 673.3 - 684.9 sim-min, max wait 1168.0 - 1201.2 sim-min
- pool-16: completion 137.6 - 138.0 sim-h, avg wait 685.8 - 695.8 sim-min, max wait 1201.1 - 1232.6 sim-min
- thread-per-request: completion 137.7 - 138.0 sim-h, avg wait 682.9 - 693.4 sim-min, max wait 1246.7 - 1260.3 sim-min

Constraint check across all 12 runs (peak bays <= 4, peak power <= 60 kW, all requests billed, billing reconciled, identical workload fingerprint): **PASSED**
