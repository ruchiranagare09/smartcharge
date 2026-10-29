# SmartCharge test results

Generated: 2026-10-29T21:53:40.279904  
Java: 17.0.16  
Assertions enabled: true  
Summary: 19 passed, 0 failed

| ID | Category | Scenario | Input / precondition | Expected | Observed | Result |
|---|---|---|---|---|---|---|
| T01 | NORMAL | Standard-rate session completes and is billed once | pool-1; R1 P2 STANDARD 7 kWh (60 sim-min) | BILLED; 1 legacy entry; account total 7.0 kWh; charged ~60 sim-min; resources returned | state=BILLED entries=1 total=7.0 charged=68.9 sim-min reserved=0 bays=0 | **PASS** |
| T02 | NORMAL | Fast-rate session completes and is billed once | pool-1; R1 P1 FAST 22 kWh (60 sim-min) | BILLED; 1 entry; peak reserved 22 kW; resources returned | state=BILLED entries=1 peakReserved=22 kW charged=65.7 sim-min released=true | **PASS** |
| T03 | NORMAL | Multiple simultaneous valid requests | pool-4; 8 threads released by one latch each submit STANDARD 7 kWh | all 8 BILLED; peak bays 4/4; peak power 28 <= 60 kW; 8 entries; billing reconciled | billed=8 peakBays=4 peakPower=28 kW entries=8 reconciled=true released=true | **PASS** |
| T04 | NORMAL | Priority ordering with FIFO tie-break among equal priorities | pool-1, aging off; blocker charging; then queue V1(P3) S1(P2) S2(P2) F1(P1) V2(P3) | start order BLK, F1, S1, S2, V1, V2 | start order [BLK, F1, S1, S2, V1, V2] | **PASS** |
| T05 | EDGE | Invalid and duplicate requests are rejected at intake | zero, negative, NaN, infinite, >100 kWh; priority 0 and 4; null rate; blank account; null id; duplicate id | every invalid request throws ValidationException and creates no session | 11/11 rejected; sessions created=1 (only OK1) | **PASS** |
| T06 | EDGE | No free bay: fifth request waits for a released bay | pool-16; 5 x STANDARD 7 kWh submitted together (35 kW fits, 5 bays do not) | peak bays 4; 5th session starts only after an earlier session ends | peakBays=4; 5th (R5) started at +60.9 ms, first release at +60.2 ms; all billed=true | **PASS** |
| T07 | EDGE | Power exhausted while bays are free | pool-16; 3 x FAST 22 kWh submitted together (66 kW > 60 kW) | peak reserved 44 kW with 2 bays idle; 3rd starts only after one ends | peakReserved=44 kW peakBays=2 (2 bays idle); 3rd started +61.9 ms, first release +61.2 ms | **PASS** |
| T08 | EDGE | Cancellation before charging | pool-1; blocker charging; R2 queued then cancelled | R2 CANCELLED, never started, no billing entry; blocker billed | cancel()=true R2=CANCELLED started=false R2 billed kWh=0.0 entries=1 blocker=BILLED | **PASS** |
| T09 | EDGE | Cancellation during charging | pool-1; R1 STANDARD 60 kWh (~514 sim-min) charging, cancelled after ~20 ms | CANCELLED promptly (< 100 ms), no billing entry, bay and 7 kW released | cancel()=true state=CANCELLED reaction=1.6 ms entries=0 reserved=0 bays=0 | **PASS** |
| T10 | EDGE | Immediate shutdown while sessions are active | pool-4; 6 x STANDARD 60 kWh; 4 charging + 2 queued; shutdown(IMMEDIATE) | all 6 CANCELLED, 0 entries, 0 kW / 0 bays reserved, worker pool terminated | charging at shutdown=4; terminated=true in 1.1 ms; cancelled=6/6; entries=0; reserved=0 kW; bays=0 | **PASS** |
| T11 | EDGE | Graceful shutdown while sessions are active | pool-4; 6 x STANDARD 7 kWh; shutdown(GRACEFUL) right after submission | all 6 BILLED before shutdown returns; resources returned; pool terminated | terminated=true billed=6/6 entries=6 reserved=0 bays=0 | **PASS** |
| T12 | EDGE | Concurrent billing through the adapter | 16 threads x 500 completed sessions over 10 accounts + every thread bills one shared session | 8001 entries; 15 duplicates ignored; every account total exact | entries=8001 (expected 8001) duplicatesIgnored=15 (expected 15) totalsExact=true | **PASS** |
| T13 | OBSERVATION | Same concurrent load straight into the legacy service (no adapter) | 16 threads x 500 recordSession calls directly on LegacyBillingService, 5 trials | shows why the adapter is needed (lost entries / totals or exceptions are possible) | 5/5 trials showed lost entries, lost total updates or exceptions | **OBSERVED** |
| T14 | EDGE | Resources released after a billing exception | pool-2; legacy throws for ACC-FAIL; submit failing + normal session | failing session BILLING_FAILED, normal BILLED, 1 entry, 0 kW / 0 bays reserved | RF=BILLING_FAILED RG=BILLED entries=1 reserved=0 kW bays=0 reconciled=true | **PASS** |
| T15 | EDGE | Duplicate billing attempt is ignored | one COMPLETED session passed to BillingAdapter.bill twice | first BILLED, second DUPLICATE_IGNORED, 1 entry | first=BILLED second=DUPLICATE_IGNORED entries=1 total=7.0 | **PASS** |
| T16 | EDGE | Aging lifts a waiting visitor above a later fleet request | scheduler aging step 30; V(P3) arrives t=0, F(P1) arrives t=5 | at t=20 F is chosen; at t=65 V (effective P1, earlier arrival) is chosen | selected at t=20: F; selected at t=65: V | **PASS** |
| T17 | EDGE | Starvation guard bounds backfill past a blocked fast request | guard 30; F(P1 fast) cannot fit, S1..S3 (P2 standard) can; selections at t=10,39,40, then F fits | S1, S2 backfill; at t=40 nothing starts (guard); then F before S3 | selections at t=10,39,40,41,42: [S1, S2, none, F, S3] | **PASS** |
| T18 | EDGE | Burst of the seeded workload with thread-per-request | 200 seeded requests submitted at once; thread-per-request; scale 0.05 ms/sim-min | 200 BILLED; peak <= 4 bays and <= 60 kW; reconciled; all threads ended | billed=200 peakBays=4 peakPower=58 kW reconciled=true threadsEnded=true released=true | **PASS** |
| T19 | EDGE | Deadlock-prone allocator deadlocks (separate demo) | DeadlockDemo.runDeadlockProne: 4 std hold bays wait for ledger; fast holds ledger waits for bay | watchdog detects no progress within 1000 ms; dump shows the wait cycle | deadlock detected by watchdog; threads blocked | **PASS** |
| T20 | EDGE | Corrected allocator under the same interleaving | DeadlockDemo.runCorrected: same roles and latches, allocation via tryAllocate | all 5 threads finish; 0 kW reserved at end | all threads finished; no deadlock | **PASS** |

## Evidence

Full per-test logs: `test-logs/<ID>.txt`. Excerpts (thread name in brackets):

### T01 - Standard-rate session completes and is billed once (139 ms)

```
21:53:37.343 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
21:53:37.364 [test-main] QUEUED R1(P2,STANDARD,7.0kWh) account=ACC-R1 seq=1
21:53:37.371 [worker-1] START R1(P2,STANDARD,7.0kWh) bay+7 kW waited=1.3 sim-min [bays 1/4, 7/60 kW]
21:53:37.372 [test-main] SHUTDOWN requested (GRACEFUL)
21:53:37.432 [worker-1] COMPLETED R1(P2,STANDARD,7.0kWh) after 68.9 sim-min
21:53:37.439 [worker-1] BILLED R1 account=ACC-R1 7.0 kWh
21:53:37.440 [worker-1] RELEASED R1 bay-7 kW [bays 0/4, 0/60 kW]
21:53:37.441 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T02 - Fast-rate session completes and is billed once (68 ms)

```
21:53:37.449 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
21:53:37.449 [test-main] QUEUED R1(P1,FAST,22.0kWh) account=ACC-R1 seq=1
21:53:37.449 [test-main] SHUTDOWN requested (GRACEFUL)
21:53:37.449 [worker-1] START R1(P1,FAST,22.0kWh) bay+22 kW waited=0.2 sim-min [bays 1/4, 22/60 kW]
21:53:37.515 [worker-1] COMPLETED R1(P1,FAST,22.0kWh) after 65.7 sim-min
21:53:37.515 [worker-1] BILLED R1 account=ACC-R1 22.0 kWh
21:53:37.516 [worker-1] RELEASED R1 bay-22 kW [bays 0/4, 0/60 kW]
21:53:37.516 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T03 - Multiple simultaneous valid requests (136 ms)

```
21:53:37.518 [test-main] Station started: mode=pool-4 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
21:53:37.521 [submitter-3] QUEUED R3(P2,STANDARD,7.0kWh) account=ACC-R3 seq=2
21:53:37.521 [submitter-1] QUEUED R1(P2,STANDARD,7.0kWh) account=ACC-R1 seq=1
21:53:37.521 [submitter-4] QUEUED R4(P2,STANDARD,7.0kWh) account=ACC-R4 seq=4
21:53:37.521 [submitter-8] QUEUED R8(P2,STANDARD,7.0kWh) account=ACC-R8 seq=5
21:53:37.522 [submitter-5] QUEUED R5(P2,STANDARD,7.0kWh) account=ACC-R5 seq=6
21:53:37.521 [submitter-2] QUEUED R2(P2,STANDARD,7.0kWh) account=ACC-R2 seq=3
21:53:37.522 [submitter-6] QUEUED R6(P2,STANDARD,7.0kWh) account=ACC-R6 seq=7
21:53:37.522 [worker-2] START R3(P2,STANDARD,7.0kWh) bay+7 kW waited=0.8 sim-min [bays 2/4, 14/60 kW]
21:53:37.522 [worker-1] START R1(P2,STANDARD,7.0kWh) bay+7 kW waited=0.7 sim-min [bays 1/4, 7/60 kW]
21:53:37.522 [submitter-7] QUEUED R7(P2,STANDARD,7.0kWh) account=ACC-R7 seq=8
21:53:37.522 [worker-3] START R2(P2,STANDARD,7.0kWh) bay+7 kW waited=0.8 sim-min [bays 3/4, 21/60 kW]
21:53:37.522 [worker-4] START R4(P2,STANDARD,7.0kWh) bay+7 kW waited=1.0 sim-min [bays 4/4, 28/60 kW]
21:53:37.523 [test-main] SHUTDOWN requested (GRACEFUL)
21:53:37.584 [worker-1] COMPLETED R1(P2,STANDARD,7.0kWh) after 62.4 sim-min
21:53:37.585 [worker-1] BILLED R1 account=ACC-R1 7.0 kWh
21:53:37.585 [worker-1] RELEASED R1 bay-7 kW [bays 3/4, 21/60 kW]
21:53:37.585 [worker-1] START R8(P2,STANDARD,7.0kWh) bay+7 kW waited=63.6 sim-min [bays 4/4, 28/60 kW]
21:53:37.585 [worker-2] COMPLETED R3(P2,STANDARD,7.0kWh) after 63.3 sim-min
21:53:37.585 [worker-2] BILLED R3 account=ACC-R3 7.0 kWh
21:53:37.586 [worker-2] RELEASED R3 bay-7 kW [bays 3/4, 21/60 kW]
21:53:37.586 [worker-2] START R5(P2,STANDARD,7.0kWh) bay+7 kW waited=64.1 sim-min [bays 4/4, 28/60 kW]
21:53:37.585 [worker-3] COMPLETED R2(P2,STANDARD,7.0kWh) after 63.1 sim-min
21:53:37.586 [worker-3] BILLED R2 account=ACC-R2 7.0 kWh
21:53:37.586 [worker-3] RELEASED R2 bay-7 kW [bays 3/4, 21/60 kW]
21:53:37.587 [worker-3] START R6(P2,STANDARD,7.0kWh) bay+7 kW waited=64.8 sim-min [bays 4/4, 28/60 kW]
21:53:37.588 [worker-4] COMPLETED R4(P2,STANDARD,7.0kWh) after 65.3 sim-min
21:53:37.588 [worker-4] BILLED R4 account=ACC-R4 7.0 kWh
21:53:37.588 [worker-4] RELEASED R4 bay-7 kW [bays 3/4, 21/60 kW]
21:53:37.588 [worker-4] START R7(P2,STANDARD,7.0kWh) bay+7 kW waited=66.1 sim-min [bays 4/4, 28/60 kW]
... (13 more lines in test-logs/T03.txt)
BILLING billedSessions=8 legacyEntries=8 cancelled=0 cancelledBilled=false totalsMatch=true duplicatesIgnored=0 billingFailures=0 => RECONCILED
```

### T04 - Priority ordering with FIFO tie-break among equal priorities (232 ms)

```
21:53:37.657 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=1000000000 min guard=1000000000 min scale=1.000 ms/sim-min
21:53:37.657 [test-main] QUEUED BLK(P3,STANDARD,7.0kWh) account=ACC-BLK seq=1
21:53:37.657 [test-main] QUEUED V1(P3,STANDARD,3.5kWh) account=ACC-V1 seq=2
21:53:37.657 [worker-1] START BLK(P3,STANDARD,7.0kWh) bay+7 kW waited=0.1 sim-min [bays 1/4, 7/60 kW]
21:53:37.657 [test-main] QUEUED S1(P2,STANDARD,3.5kWh) account=ACC-S1 seq=3
21:53:37.657 [test-main] QUEUED S2(P2,STANDARD,3.5kWh) account=ACC-S2 seq=4
21:53:37.658 [test-main] QUEUED F1(P1,STANDARD,3.5kWh) account=ACC-F1 seq=5
21:53:37.658 [test-main] QUEUED V2(P3,STANDARD,3.5kWh) account=ACC-V2 seq=6
21:53:37.658 [test-main] SHUTDOWN requested (GRACEFUL)
21:53:37.718 [worker-1] COMPLETED BLK(P3,STANDARD,7.0kWh) after 60.5 sim-min
21:53:37.718 [worker-1] BILLED BLK account=ACC-BLK 7.0 kWh
21:53:37.718 [worker-1] RELEASED BLK bay-7 kW [bays 0/4, 0/60 kW]
21:53:37.718 [worker-1] START F1(P1,STANDARD,3.5kWh) bay+7 kW waited=60.7 sim-min [bays 1/4, 7/60 kW]
21:53:37.749 [worker-1] COMPLETED F1(P1,STANDARD,3.5kWh) after 30.4 sim-min
21:53:37.749 [worker-1] BILLED F1 account=ACC-F1 3.5 kWh
21:53:37.749 [worker-1] RELEASED F1 bay-7 kW [bays 0/4, 0/60 kW]
21:53:37.750 [worker-1] START S1(P2,STANDARD,3.5kWh) bay+7 kW waited=92.1 sim-min [bays 1/4, 7/60 kW]
21:53:37.783 [worker-1] COMPLETED S1(P2,STANDARD,3.5kWh) after 33.7 sim-min
21:53:37.783 [worker-1] BILLED S1 account=ACC-S1 3.5 kWh
21:53:37.784 [worker-1] RELEASED S1 bay-7 kW [bays 0/4, 0/60 kW]
21:53:37.784 [worker-1] START S2(P2,STANDARD,3.5kWh) bay+7 kW waited=126.2 sim-min [bays 1/4, 7/60 kW]
21:53:37.816 [worker-1] COMPLETED S2(P2,STANDARD,3.5kWh) after 32.0 sim-min
21:53:37.816 [worker-1] BILLED S2 account=ACC-S2 3.5 kWh
21:53:37.816 [worker-1] RELEASED S2 bay-7 kW [bays 0/4, 0/60 kW]
21:53:37.816 [worker-1] START V1(P3,STANDARD,3.5kWh) bay+7 kW waited=158.8 sim-min [bays 1/4, 7/60 kW]
21:53:37.851 [worker-1] COMPLETED V1(P3,STANDARD,3.5kWh) after 35.3 sim-min
21:53:37.852 [worker-1] BILLED V1 account=ACC-V1 3.5 kWh
21:53:37.852 [worker-1] RELEASED V1 bay-7 kW [bays 0/4, 0/60 kW]
21:53:37.852 [worker-1] START V2(P3,STANDARD,3.5kWh) bay+7 kW waited=194.3 sim-min [bays 1/4, 7/60 kW]
21:53:37.883 [worker-1] COMPLETED V2(P3,STANDARD,3.5kWh) after 31.1 sim-min
... (3 more lines in test-logs/T04.txt)
```

### T05 - Invalid and duplicate requests are rejected at intake (64 ms)

```
21:53:37.890 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
21:53:37.891 [test-main] QUEUED OK1(P2,STANDARD,7.0kWh) account=ACC-OK1 seq=1
21:53:37.891 [worker-1] START OK1(P2,STANDARD,7.0kWh) bay+7 kW waited=0.3 sim-min [bays 1/4, 7/60 kW]
21:53:37.893 [test-main] REJECTED energyKwh must be a positive finite number but was 0.0 (Z1)
21:53:37.893 [test-main] REJECTED energyKwh must be a positive finite number but was -5.0 (N1)
21:53:37.893 [test-main] REJECTED energyKwh must be a positive finite number but was NaN (NAN)
21:53:37.893 [test-main] REJECTED energyKwh must be a positive finite number but was Infinity (INF)
21:53:37.893 [test-main] REJECTED energyKwh must not exceed 100.0 but was 150.0 (BIG)
21:53:37.895 [test-main] REJECTED priority must be 1, 2 or 3 but was 0 (P0)
21:53:37.895 [test-main] REJECTED priority must be 1, 2 or 3 but was 4 (P4)
21:53:37.896 [test-main] REJECTED charging rate is required (NR)
21:53:37.896 [test-main] REJECTED accountId must not be blank
21:53:37.896 [test-main] REJECTED requestId must not be blank
21:53:37.896 [test-main] REJECTED duplicate request id OK1
21:53:37.897 [test-main] SHUTDOWN requested (GRACEFUL)
21:53:37.953 [worker-1] COMPLETED OK1(P2,STANDARD,7.0kWh) after 62.2 sim-min
21:53:37.954 [worker-1] BILLED OK1 account=ACC-OK1 7.0 kWh
21:53:37.954 [worker-1] RELEASED OK1 bay-7 kW [bays 0/4, 0/60 kW]
21:53:37.954 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
rejected: energyKwh must be a positive finite number but was 0.0 (Z1)
rejected: energyKwh must be a positive finite number but was -5.0 (N1)
rejected: energyKwh must be a positive finite number but was NaN (NAN)
rejected: energyKwh must be a positive finite number but was Infinity (INF)
rejected: energyKwh must not exceed 100.0 but was 150.0 (BIG)
rejected: priority must be 1, 2 or 3 but was 0 (P0)
rejected: priority must be 1, 2 or 3 but was 4 (P4)
rejected: charging rate is required (NR)
rejected: accountId must not be blank
rejected: requestId must not be blank
rejected: duplicate request id: OK1
```

### T06 - No free bay: fifth request waits for a released bay (132 ms)

```
21:53:37.960 [test-main] Station started: mode=pool-16 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
21:53:37.960 [test-main] QUEUED R1(P2,STANDARD,7.0kWh) account=ACC-R1 seq=1
21:53:37.960 [test-main] QUEUED R2(P2,STANDARD,7.0kWh) account=ACC-R2 seq=2
21:53:37.961 [test-main] QUEUED R3(P2,STANDARD,7.0kWh) account=ACC-R3 seq=3
21:53:37.961 [test-main] QUEUED R4(P2,STANDARD,7.0kWh) account=ACC-R4 seq=4
21:53:37.961 [test-main] QUEUED R5(P2,STANDARD,7.0kWh) account=ACC-R5 seq=5
21:53:37.961 [test-main] SHUTDOWN requested (GRACEFUL)
21:53:37.961 [worker-1] START R1(P2,STANDARD,7.0kWh) bay+7 kW waited=0.3 sim-min [bays 1/4, 7/60 kW]
21:53:37.961 [worker-2] START R2(P2,STANDARD,7.0kWh) bay+7 kW waited=0.4 sim-min [bays 2/4, 14/60 kW]
21:53:37.961 [worker-3] START R3(P2,STANDARD,7.0kWh) bay+7 kW waited=0.4 sim-min [bays 3/4, 21/60 kW]
21:53:37.962 [worker-4] START R4(P2,STANDARD,7.0kWh) bay+7 kW waited=1.0 sim-min [bays 4/4, 28/60 kW]
21:53:38.021 [worker-1] COMPLETED R1(P2,STANDARD,7.0kWh) after 60.2 sim-min
21:53:38.021 [worker-1] BILLED R1 account=ACC-R1 7.0 kWh
21:53:38.022 [worker-1] RELEASED R1 bay-7 kW [bays 3/4, 21/60 kW]
21:53:38.022 [worker-1] START R5(P2,STANDARD,7.0kWh) bay+7 kW waited=60.9 sim-min [bays 4/4, 28/60 kW]
21:53:38.022 [worker-3] COMPLETED R3(P2,STANDARD,7.0kWh) after 61.0 sim-min
21:53:38.022 [worker-3] BILLED R3 account=ACC-R3 7.0 kWh
21:53:38.022 [worker-3] RELEASED R3 bay-7 kW [bays 3/4, 21/60 kW]
21:53:38.023 [worker-2] COMPLETED R2(P2,STANDARD,7.0kWh) after 61.1 sim-min
21:53:38.023 [worker-2] BILLED R2 account=ACC-R2 7.0 kWh
21:53:38.023 [worker-2] RELEASED R2 bay-7 kW [bays 2/4, 14/60 kW]
21:53:38.027 [worker-4] COMPLETED R4(P2,STANDARD,7.0kWh) after 64.9 sim-min
21:53:38.027 [worker-4] BILLED R4 account=ACC-R4 7.0 kWh
21:53:38.027 [worker-4] RELEASED R4 bay-7 kW [bays 1/4, 7/60 kW]
21:53:38.083 [worker-1] COMPLETED R5(P2,STANDARD,7.0kWh) after 61.7 sim-min
21:53:38.084 [worker-1] BILLED R5 account=ACC-R5 7.0 kWh
21:53:38.084 [worker-1] RELEASED R5 bay-7 kW [bays 0/4, 0/60 kW]
21:53:38.084 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T07 - Power exhausted while bays are free (136 ms)

```
21:53:38.099 [test-main] Station started: mode=pool-16 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
21:53:38.100 [test-main] QUEUED F1(P1,FAST,22.0kWh) account=ACC-F1 seq=1
21:53:38.100 [test-main] QUEUED F2(P1,FAST,22.0kWh) account=ACC-F2 seq=2
21:53:38.100 [test-main] QUEUED F3(P1,FAST,22.0kWh) account=ACC-F3 seq=3
21:53:38.100 [test-main] SHUTDOWN requested (GRACEFUL)
21:53:38.100 [worker-1] START F1(P1,FAST,22.0kWh) bay+22 kW waited=0.4 sim-min [bays 1/4, 22/60 kW]
21:53:38.101 [worker-7] START F2(P1,FAST,22.0kWh) bay+22 kW waited=0.6 sim-min [bays 2/4, 44/60 kW]
21:53:38.162 [worker-1] COMPLETED F1(P1,FAST,22.0kWh) after 61.2 sim-min
21:53:38.162 [worker-1] BILLED F1 account=ACC-F1 22.0 kWh
21:53:38.162 [worker-1] RELEASED F1 bay-22 kW [bays 1/4, 22/60 kW]
21:53:38.162 [worker-1] START F3(P1,FAST,22.0kWh) bay+22 kW waited=62.1 sim-min [bays 2/4, 44/60 kW]
21:53:38.163 [worker-7] COMPLETED F2(P1,FAST,22.0kWh) after 62.5 sim-min
21:53:38.163 [worker-7] BILLED F2 account=ACC-F2 22.0 kWh
21:53:38.164 [worker-7] RELEASED F2 bay-22 kW [bays 1/4, 22/60 kW]
21:53:38.226 [worker-1] COMPLETED F3(P1,FAST,22.0kWh) after 63.3 sim-min
21:53:38.226 [worker-1] BILLED F3 account=ACC-F3 22.0 kWh
21:53:38.226 [worker-1] RELEASED F3 bay-22 kW [bays 0/4, 0/60 kW]
21:53:38.226 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T08 - Cancellation before charging (64 ms)

```
21:53:38.228 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
21:53:38.228 [test-main] QUEUED BLK(P2,STANDARD,7.0kWh) account=ACC-BLK seq=1
21:53:38.228 [worker-1] START BLK(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 1/4, 7/60 kW]
21:53:38.229 [test-main] QUEUED R2(P1,STANDARD,7.0kWh) account=ACC-R2 seq=2
21:53:38.229 [test-main] CANCELLED R2(P1,STANDARD,7.0kWh) while queued
21:53:38.229 [test-main] SHUTDOWN requested (GRACEFUL)
21:53:38.291 [worker-1] COMPLETED BLK(P2,STANDARD,7.0kWh) after 62.3 sim-min
21:53:38.291 [worker-1] BILLED BLK account=ACC-BLK 7.0 kWh
21:53:38.291 [worker-1] RELEASED BLK bay-7 kW [bays 0/4, 0/60 kW]
21:53:38.292 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
BILLING billedSessions=1 legacyEntries=1 cancelled=1 cancelledBilled=false totalsMatch=true duplicatesIgnored=0 billingFailures=0 => RECONCILED
```

### T09 - Cancellation during charging (28 ms)

```
21:53:38.297 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
21:53:38.297 [test-main] QUEUED R1(P2,STANDARD,60.0kWh) account=ACC-R1 seq=1
21:53:38.297 [worker-1] START R1(P2,STANDARD,60.0kWh) bay+7 kW waited=0.1 sim-min [bays 1/4, 7/60 kW]
21:53:38.321 [test-main] CANCEL requested for charging session R1(P2,STANDARD,60.0kWh)
21:53:38.321 [worker-1] CANCELLED R1(P2,STANDARD,60.0kWh) during charging (not billed)
21:53:38.321 [worker-1] RELEASED R1 bay-7 kW [bays 0/4, 0/60 kW]
21:53:38.323 [test-main] SHUTDOWN requested (GRACEFUL)
21:53:38.323 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T10 - Immediate shutdown while sessions are active (5 ms)

```
21:53:38.326 [test-main] Station started: mode=pool-4 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
21:53:38.327 [test-main] QUEUED R1(P2,STANDARD,60.0kWh) account=ACC-R1 seq=1
21:53:38.327 [test-main] QUEUED R2(P2,STANDARD,60.0kWh) account=ACC-R2 seq=2
21:53:38.327 [test-main] QUEUED R3(P2,STANDARD,60.0kWh) account=ACC-R3 seq=3
21:53:38.327 [test-main] QUEUED R4(P2,STANDARD,60.0kWh) account=ACC-R4 seq=4
21:53:38.327 [test-main] QUEUED R5(P2,STANDARD,60.0kWh) account=ACC-R5 seq=5
21:53:38.327 [test-main] QUEUED R6(P2,STANDARD,60.0kWh) account=ACC-R6 seq=6
21:53:38.327 [worker-1] START R1(P2,STANDARD,60.0kWh) bay+7 kW waited=0.2 sim-min [bays 1/4, 7/60 kW]
21:53:38.328 [worker-2] START R2(P2,STANDARD,60.0kWh) bay+7 kW waited=0.7 sim-min [bays 2/4, 14/60 kW]
21:53:38.328 [worker-3] START R3(P2,STANDARD,60.0kWh) bay+7 kW waited=0.9 sim-min [bays 3/4, 21/60 kW]
21:53:38.328 [worker-4] START R4(P2,STANDARD,60.0kWh) bay+7 kW waited=1.0 sim-min [bays 4/4, 28/60 kW]
21:53:38.329 [test-main] SHUTDOWN requested (IMMEDIATE)
21:53:38.329 [test-main] CANCELLED R5(P2,STANDARD,60.0kWh) (queued at shutdown)
21:53:38.329 [test-main] CANCELLED R6(P2,STANDARD,60.0kWh) (queued at shutdown)
21:53:38.329 [test-main] CANCEL requested for R2(P2,STANDARD,60.0kWh) (active at shutdown)
21:53:38.329 [test-main] CANCEL requested for R3(P2,STANDARD,60.0kWh) (active at shutdown)
21:53:38.329 [test-main] CANCEL requested for R4(P2,STANDARD,60.0kWh) (active at shutdown)
21:53:38.329 [test-main] CANCEL requested for R1(P2,STANDARD,60.0kWh) (active at shutdown)
21:53:38.329 [worker-2] CANCELLED R2(P2,STANDARD,60.0kWh) during charging (not billed)
21:53:38.329 [worker-2] RELEASED R2 bay-7 kW [bays 3/4, 21/60 kW]
21:53:38.329 [worker-3] CANCELLED R3(P2,STANDARD,60.0kWh) during charging (not billed)
21:53:38.329 [worker-3] RELEASED R3 bay-7 kW [bays 2/4, 14/60 kW]
21:53:38.330 [worker-4] CANCELLED R4(P2,STANDARD,60.0kWh) during charging (not billed)
21:53:38.330 [worker-1] CANCELLED R1(P2,STANDARD,60.0kWh) during charging (not billed)
21:53:38.330 [worker-4] RELEASED R4 bay-7 kW [bays 1/4, 7/60 kW]
21:53:38.330 [worker-1] RELEASED R1 bay-7 kW [bays 0/4, 0/60 kW]
21:53:38.330 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T11 - Graceful shutdown while sessions are active (129 ms)

```
21:53:38.332 [test-main] Station started: mode=pool-4 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
21:53:38.333 [test-main] QUEUED R1(P2,STANDARD,7.0kWh) account=ACC-R1 seq=1
21:53:38.333 [test-main] QUEUED R2(P2,STANDARD,7.0kWh) account=ACC-R2 seq=2
21:53:38.333 [test-main] QUEUED R3(P2,STANDARD,7.0kWh) account=ACC-R3 seq=3
21:53:38.333 [test-main] QUEUED R4(P2,STANDARD,7.0kWh) account=ACC-R4 seq=4
21:53:38.333 [test-main] QUEUED R5(P2,STANDARD,7.0kWh) account=ACC-R5 seq=5
21:53:38.333 [test-main] QUEUED R6(P2,STANDARD,7.0kWh) account=ACC-R6 seq=6
21:53:38.333 [test-main] SHUTDOWN requested (GRACEFUL)
21:53:38.333 [worker-1] START R1(P2,STANDARD,7.0kWh) bay+7 kW waited=0.4 sim-min [bays 1/4, 7/60 kW]
21:53:38.334 [worker-2] START R2(P2,STANDARD,7.0kWh) bay+7 kW waited=0.7 sim-min [bays 2/4, 14/60 kW]
21:53:38.334 [worker-3] START R3(P2,STANDARD,7.0kWh) bay+7 kW waited=1.0 sim-min [bays 3/4, 21/60 kW]
21:53:38.334 [worker-4] START R4(P2,STANDARD,7.0kWh) bay+7 kW waited=1.2 sim-min [bays 4/4, 28/60 kW]
21:53:38.394 [worker-1] COMPLETED R1(P2,STANDARD,7.0kWh) after 60.3 sim-min
21:53:38.394 [worker-1] BILLED R1 account=ACC-R1 7.0 kWh
21:53:38.394 [worker-1] RELEASED R1 bay-7 kW [bays 3/4, 21/60 kW]
21:53:38.394 [worker-1] START R5(P2,STANDARD,7.0kWh) bay+7 kW waited=61.1 sim-min [bays 4/4, 28/60 kW]
21:53:38.395 [worker-2] COMPLETED R2(P2,STANDARD,7.0kWh) after 61.1 sim-min
21:53:38.395 [worker-2] BILLED R2 account=ACC-R2 7.0 kWh
21:53:38.395 [worker-2] RELEASED R2 bay-7 kW [bays 3/4, 21/60 kW]
21:53:38.395 [worker-2] START R6(P2,STANDARD,7.0kWh) bay+7 kW waited=61.9 sim-min [bays 4/4, 28/60 kW]
21:53:38.399 [worker-3] COMPLETED R3(P2,STANDARD,7.0kWh) after 64.6 sim-min
21:53:38.399 [worker-3] BILLED R3 account=ACC-R3 7.0 kWh
21:53:38.399 [worker-3] RELEASED R3 bay-7 kW [bays 3/4, 21/60 kW]
21:53:38.399 [worker-4] COMPLETED R4(P2,STANDARD,7.0kWh) after 64.7 sim-min
21:53:38.399 [worker-4] BILLED R4 account=ACC-R4 7.0 kWh
21:53:38.399 [worker-4] RELEASED R4 bay-7 kW [bays 2/4, 14/60 kW]
21:53:38.457 [worker-1] COMPLETED R5(P2,STANDARD,7.0kWh) after 62.9 sim-min
21:53:38.458 [worker-1] BILLED R5 account=ACC-R5 7.0 kWh
21:53:38.458 [worker-1] RELEASED R5 bay-7 kW [bays 1/4, 7/60 kW]
21:53:38.460 [worker-2] COMPLETED R6(P2,STANDARD,7.0kWh) after 64.6 sim-min
... (3 more lines in test-logs/T11.txt)
```

### T12 - Concurrent billing through the adapter (45 ms)

```
```

### T13 - Same concurrent load straight into the legacy service (no adapter) (85 ms)

```
trial 1: entries=7035/8000 summedTotals=7678/8000 exceptions=0 corrupted=true
trial 2: entries=7825/8000 summedTotals=7953/8000 exceptions=0 corrupted=true
trial 3: entries=7954/8000 summedTotals=7971/8000 exceptions=0 corrupted=true
trial 4: entries=7989/8000 summedTotals=7994/8000 exceptions=0 corrupted=true
trial 5: entries=7866/8000 summedTotals=7922/8000 exceptions=0 corrupted=true
```

### T14 - Resources released after a billing exception (68 ms)

```
21:53:38.599 [test-main] Station started: mode=pool-2 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
21:53:38.600 [test-main] QUEUED RF(P2,STANDARD,7.0kWh) account=ACC-FAIL seq=1
21:53:38.600 [test-main] QUEUED RG(P2,STANDARD,7.0kWh) account=ACC-RG seq=2
21:53:38.600 [test-main] SHUTDOWN requested (GRACEFUL)
21:53:38.601 [worker-1] START RF(P2,STANDARD,7.0kWh) bay+7 kW waited=1.4 sim-min [bays 1/4, 7/60 kW]
21:53:38.602 [worker-2] START RG(P2,STANDARD,7.0kWh) bay+7 kW waited=1.9 sim-min [bays 2/4, 14/60 kW]
21:53:38.663 [worker-1] COMPLETED RF(P2,STANDARD,7.0kWh) after 61.2 sim-min
21:53:38.663 [worker-2] COMPLETED RG(P2,STANDARD,7.0kWh) after 61.1 sim-min
21:53:38.663 [worker-1] BILLING FAILED for RF: simulated legacy billing outage
21:53:38.663 [worker-1] RELEASED RF bay-7 kW [bays 1/4, 7/60 kW]
21:53:38.663 [worker-2] BILLED RG account=ACC-RG 7.0 kWh
21:53:38.663 [worker-2] RELEASED RG bay-7 kW [bays 0/4, 0/60 kW]
21:53:38.664 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
BILLING billedSessions=1 legacyEntries=1 cancelled=0 cancelledBilled=false totalsMatch=true duplicatesIgnored=0 billingFailures=1 => RECONCILED
```

### T15 - Duplicate billing attempt is ignored (0 ms)

```
21:53:38.666 [test-main] BILLED R1 account=ACC-R1 7.0 kWh
21:53:38.666 [test-main] BILLING duplicate ignored for R1
```

### T16 - Aging lifts a waiting visitor above a later fleet request (1 ms)

```
```

### T17 - Starvation guard bounds backfill past a blocked fast request (0 ms)

```
21:53:38.669 [test-main] BACKFILL S1(P2,STANDARD,7.0kWh) starts ahead of blocked F(P1,FAST,22.0kWh)
21:53:38.669 [test-main] BACKFILL S2(P2,STANDARD,7.0kWh) starts ahead of blocked F(P1,FAST,22.0kWh)
```

### T18 - Burst of the seeded workload with thread-per-request (505 ms)

```
BILLING billedSessions=200 legacyEntries=200 cancelled=0 cancelledBilled=false totalsMatch=true duplicatesIgnored=0 billingFailures=0 => RECONCILED
```

### T19 - Deadlock-prone allocator deadlocks (separate demo) (1034 ms)

```
=== DEADLOCK-PRONE VARIANT (naive allocator, inconsistent acquisition order) ===
[std-1] holds 1 bay, now waiting for the power ledger lock
[std-2] holds 1 bay, now waiting for the power ledger lock
[std-3] holds 1 bay, now waiting for the power ledger lock
[std-4] holds 1 bay, now waiting for the power ledger lock
[fast-1] holds the power ledger lock, now waiting for a bay
DETECTED: no progress within 1000 ms timeout; 5 of 5 threads still blocked
Thread dump of demo threads:
  "std-1" state=WAITING waitingOn=java.util.concurrent.locks.ReentrantLock$NonfairSync@4c98385c owner=fast-1
      at app//smartcharge.demo.DeadlockDemo.lambda$runDeadlockProne$0(DeadlockDemo.java:71)
  "std-2" state=WAITING waitingOn=java.util.concurrent.locks.ReentrantLock$NonfairSync@4c98385c owner=fast-1
      at app//smartcharge.demo.DeadlockDemo.lambda$runDeadlockProne$0(DeadlockDemo.java:71)
  "std-3" state=WAITING waitingOn=java.util.concurrent.locks.ReentrantLock$NonfairSync@4c98385c owner=fast-1
      at app//smartcharge.demo.DeadlockDemo.lambda$runDeadlockProne$0(DeadlockDemo.java:71)
  "std-4" state=WAITING waitingOn=java.util.concurrent.locks.ReentrantLock$NonfairSync@4c98385c owner=fast-1
      at app//smartcharge.demo.DeadlockDemo.lambda$runDeadlockProne$0(DeadlockDemo.java:71)
  "fast-1" state=WAITING waitingOn=java.util.concurrent.Semaphore$FairSync@5fcfe4b2 owner=null
      at app//smartcharge.demo.DeadlockDemo.lambda$runDeadlockProne$1(DeadlockDemo.java:91)
Resource-allocation graph (holds: resource -> thread, requests: thread -> resource):
  bay permits x4 -> std-1, std-2, std-3, std-4      (all permits held)
  power ledger   -> fast-1                          (lock held)
  std-1..std-4   -> power ledger                    (blocked in lock())
  fast-1         -> bay permit                      (blocked in acquire())
Wait-for graph: std-k -> fast-1 (ledger owner) and fast-1 -> {std-1..std-4} (permit holders): CYCLE
Coffman conditions: mutual exclusion (ledger lock, bay permits) | hold-and-wait (each thread holds one,
  waits for the other) | no pre-emption (neither can be taken away) | circular wait (cycle above) => deadlock
ThreadMXBean.findDeadlockedThreads() = null (a Semaphore has no owner thread, so the JVM detector cannot see this cycle; the timeout watchdog does)
```

### T20 - Corrected allocator under the same interleaving (63 ms)

```
=== CORRECTED VARIANT (atomic, non-blocking allocation, fixed order: power lock -> bay tryAcquire) ===
[std-1] allocated bay + 7 kW atomically (holds both, waits for nothing)
[std-2] allocated bay + 7 kW atomically (holds both, waits for nothing)
[std-3] allocated bay + 7 kW atomically (holds both, waits for nothing)
[std-4] allocated bay + 7 kW atomically (holds both, waits for nothing)
[fast-1] allocation refused (no free bay) - holding NOTHING, will retry
[std-2] charging done, released bay + 7 kW
[std-1] charging done, released bay + 7 kW
[std-4] charging done, released bay + 7 kW
[std-3] charging done, released bay + 7 kW
[fast-1] allocated bay + 22 kW after 9 refused attempt(s)
Finished=true, final reserved=0 kW, active bays=0, peak reserved=28 kW, peak bays=4
Why no deadlock: tryAcquire() never blocks while the power lock is held, and a refused request
  keeps nothing, so hold-and-wait and circular wait cannot occur.
```
