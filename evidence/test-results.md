# SmartCharge test results

Generated: 2026-10-29T22:33:00.367148  
Java: 17.0.16  
Assertions enabled: true  
Summary: 24 passed, 0 failed

| ID | Category | Scenario | Input / precondition | Expected | Observed | Result |
|---|---|---|---|---|---|---|
| T01 | NORMAL | Standard-rate session completes and is billed once | pool-1; R1 P2 STANDARD 7 kWh (60 sim-min) | BILLED; 1 legacy entry; account total 7.0 kWh; charged ~60 sim-min; resources returned | state=BILLED entries=1 total=7.0 charged=70.1 sim-min reserved=0 bays=0 | **PASS** |
| T02 | NORMAL | Fast-rate session completes and is billed once | pool-1; R1 P1 FAST 22 kWh (60 sim-min) | BILLED; 1 entry; peak reserved 22 kW; resources returned | state=BILLED entries=1 peakReserved=22 kW charged=64.7 sim-min released=true | **PASS** |
| T03 | NORMAL | Multiple simultaneous valid requests | pool-4; 8 threads released by one latch each submit STANDARD 7 kWh | all 8 BILLED; peak bays 4/4; peak power 28 <= 60 kW; 8 entries; billing reconciled | billed=8 peakBays=4 peakPower=28 kW entries=8 reconciled=true released=true | **PASS** |
| T04 | NORMAL | Priority ordering with FIFO tie-break among equal priorities | pool-1, aging off; blocker charging; then queue V1(P3) S1(P2) S2(P2) F1(P1) V2(P3) | start order BLK, F1, S1, S2, V1, V2 | start order [BLK, F1, S1, S2, V1, V2] | **PASS** |
| T05 | EDGE | Invalid and duplicate requests are rejected at intake | zero, negative, NaN, infinite, >100 kWh; priority 0 and 4; null rate; blank account; null id; duplicate id | every invalid request throws ValidationException and creates no session | 11/11 rejected; sessions created=1 (only OK1) | **PASS** |
| T06 | EDGE | No free bay: fifth request waits for a released bay | pool-16; 5 x STANDARD 7 kWh submitted together (35 kW fits, 5 bays do not) | peak bays 4; 5th session starts only after an earlier session ends | peakBays=4; 5th (R5) started at +62.4 ms, first release at +62.0 ms; all billed=true | **PASS** |
| T07 | EDGE | Power exhausted while bays are free | pool-16; 3 x FAST 22 kWh submitted together (66 kW > 60 kW) | peak reserved 44 kW with 2 bays idle; 3rd starts only after one ends | peakReserved=44 kW peakBays=2 (2 bays idle); 3rd started +70.5 ms, first release +70.2 ms | **PASS** |
| T08 | EDGE | Cancellation before charging | pool-1; blocker charging; R2 queued then cancelled | R2 CANCELLED, never started, no billing entry; blocker billed | cancel()=true R2=CANCELLED started=false R2 billed kWh=0.0 entries=1 blocker=BILLED | **PASS** |
| T09 | EDGE | Cancellation during charging | pool-1; R1 STANDARD 60 kWh (~514 sim-min) charging, cancelled after ~20 ms | CANCELLED promptly (< 500 ms), no billing entry, bay and 7 kW released | cancel()=true state=CANCELLED reaction=0.1 ms entries=0 reserved=0 bays=0 | **PASS** |
| T10 | EDGE | Immediate shutdown while sessions are active | pool-4; 6 x STANDARD 60 kWh; 4 charging + 2 queued; shutdown(IMMEDIATE) | all 6 CANCELLED, 0 entries, 0 kW / 0 bays reserved, worker pool terminated | charging at shutdown=4; terminated=true in 0.7 ms; cancelled=6/6; entries=0; reserved=0 kW; bays=0 | **PASS** |
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
| T21 | EDGE | Contract C1/C2 violations on StationResources are rejected | tryAllocate(0); release(7) with nothing held; release(22) while only 7 kW held | IllegalArgumentException / IllegalStateException; ledger unchanged after each | tryAllocate(0)->IllegalArgumentException; release(7) unheld->IllegalStateException; release(22) with 7 held->IllegalStateException; ledger unchanged=true | **PASS** |
| T22 | EDGE | Contract C3/C5 violations on session and billing are rejected | markBilled on QUEUED; finishCharging on QUEUED; markCharging twice; bill() a CHARGING session | IllegalStateException each time; state unchanged; no billing entry | markBilled(QUEUED)->IllegalStateException; finishCharging(QUEUED)->IllegalStateException; markCharging twice->IllegalStateException; bill(CHARGING)->IllegalStateException; states unchanged=true; entries=0 | **PASS** |
| T23 | EDGE | Contract C6 lifecycle violations are rejected | submit() after shutdown; shutdown() twice | IllegalStateException both times; no session created | submit after shutdown->IllegalStateException; shutdown twice->IllegalStateException; sessions=0 | **PASS** |
| T24 | EDGE | Starvation guard on a running station | pool-16, guard 30; F1, F2 fast 240 sim-min charging; F3 (P1 fast) blocked on power; S1..S8 (P2 standard, 36 sim-min) arrive every 10 sim-min | S1, S2 backfill; guard activates; S3..S8 start only after F3; running sessions finish normally; all billed; resources released | S1,S2 backfilled=true; guard activations=1; S3..S8 after F3=true; F3 started 0.2 ms after first fast release; all billed=true; released=true | **PASS** |
| T25 | EDGE | Guard clock survives a temporary change of front request | aging 30, guard 30; F overtaken at t=2; aged V becomes front at t=61 and starts at t=62; S3 queued | selections [S1, S2, V, none]: at t=63 F is front again and its t=2 clock blocks backfill | selections at t=2,61,62,63: [S1, S2, V, none] | **PASS** |

## Evidence

Full per-test logs: `test-logs/<ID>.txt`. Excerpts (thread name in brackets):

### T01 - Standard-rate session completes and is billed once (106 ms)

```
22:32:57.113 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:32:57.124 [test-main] QUEUED R1(P2,STANDARD,7.0kWh) account=ACC-R1 seq=1
22:32:57.128 [worker-1] START R1(P2,STANDARD,7.0kWh) bay+7 kW waited=0.3 sim-min [bays 1/4, 7/60 kW]
22:32:57.129 [test-main] SHUTDOWN requested (GRACEFUL)
22:32:57.194 [worker-1] COMPLETED R1(P2,STANDARD,7.0kWh) after 70.1 sim-min
22:32:57.198 [worker-1] BILLED R1 account=ACC-R1 7.0 kWh
22:32:57.198 [worker-1] RELEASED R1 bay-7 kW [bays 0/4, 0/60 kW]
22:32:57.199 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T02 - Fast-rate session completes and is billed once (65 ms)

```
22:32:57.204 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:32:57.204 [test-main] QUEUED R1(P1,FAST,22.0kWh) account=ACC-R1 seq=1
22:32:57.204 [test-main] SHUTDOWN requested (GRACEFUL)
22:32:57.204 [worker-1] START R1(P1,FAST,22.0kWh) bay+22 kW waited=0.0 sim-min [bays 1/4, 22/60 kW]
22:32:57.269 [worker-1] COMPLETED R1(P1,FAST,22.0kWh) after 64.7 sim-min
22:32:57.269 [worker-1] BILLED R1 account=ACC-R1 22.0 kWh
22:32:57.270 [worker-1] RELEASED R1 bay-22 kW [bays 0/4, 0/60 kW]
22:32:57.270 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T03 - Multiple simultaneous valid requests (144 ms)

```
22:32:57.271 [test-main] Station started: mode=pool-4 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:32:57.273 [submitter-1] QUEUED R1(P2,STANDARD,7.0kWh) account=ACC-R1 seq=1
22:32:57.273 [submitter-4] QUEUED R4(P2,STANDARD,7.0kWh) account=ACC-R4 seq=4
22:32:57.273 [submitter-3] QUEUED R3(P2,STANDARD,7.0kWh) account=ACC-R3 seq=3
22:32:57.273 [submitter-5] QUEUED R5(P2,STANDARD,7.0kWh) account=ACC-R5 seq=5
22:32:57.273 [submitter-6] QUEUED R6(P2,STANDARD,7.0kWh) account=ACC-R6 seq=6
22:32:57.273 [submitter-2] QUEUED R2(P2,STANDARD,7.0kWh) account=ACC-R2 seq=2
22:32:57.273 [worker-1] START R1(P2,STANDARD,7.0kWh) bay+7 kW waited=0.1 sim-min [bays 1/4, 7/60 kW]
22:32:57.273 [submitter-8] QUEUED R8(P2,STANDARD,7.0kWh) account=ACC-R8 seq=7
22:32:57.273 [submitter-7] QUEUED R7(P2,STANDARD,7.0kWh) account=ACC-R7 seq=8
22:32:57.273 [worker-3] START R3(P2,STANDARD,7.0kWh) bay+7 kW waited=0.3 sim-min [bays 3/4, 21/60 kW]
22:32:57.273 [worker-2] START R2(P2,STANDARD,7.0kWh) bay+7 kW waited=0.2 sim-min [bays 2/4, 14/60 kW]
22:32:57.273 [worker-4] START R4(P2,STANDARD,7.0kWh) bay+7 kW waited=0.3 sim-min [bays 4/4, 28/60 kW]
22:32:57.273 [test-main] SHUTDOWN requested (GRACEFUL)
22:32:57.336 [worker-3] COMPLETED R3(P2,STANDARD,7.0kWh) after 63.2 sim-min
22:32:57.336 [worker-3] BILLED R3 account=ACC-R3 7.0 kWh
22:32:57.336 [worker-3] RELEASED R3 bay-7 kW [bays 3/4, 21/60 kW]
22:32:57.336 [worker-3] START R5(P2,STANDARD,7.0kWh) bay+7 kW waited=63.9 sim-min [bays 4/4, 28/60 kW]
22:32:57.338 [worker-4] COMPLETED R4(P2,STANDARD,7.0kWh) after 64.8 sim-min
22:32:57.338 [worker-2] COMPLETED R2(P2,STANDARD,7.0kWh) after 64.9 sim-min
22:32:57.338 [worker-4] BILLED R4 account=ACC-R4 7.0 kWh
22:32:57.338 [worker-4] RELEASED R4 bay-7 kW [bays 3/4, 21/60 kW]
22:32:57.338 [worker-2] BILLED R2 account=ACC-R2 7.0 kWh
22:32:57.338 [worker-2] RELEASED R2 bay-7 kW [bays 3/4, 21/60 kW]
22:32:57.338 [worker-4] START R6(P2,STANDARD,7.0kWh) bay+7 kW waited=65.4 sim-min [bays 4/4, 28/60 kW]
22:32:57.338 [worker-2] START R8(P2,STANDARD,7.0kWh) bay+7 kW waited=65.5 sim-min [bays 4/4, 28/60 kW]
22:32:57.343 [worker-1] COMPLETED R1(P2,STANDARD,7.0kWh) after 70.2 sim-min
22:32:57.343 [worker-1] BILLED R1 account=ACC-R1 7.0 kWh
22:32:57.343 [worker-1] RELEASED R1 bay-7 kW [bays 3/4, 21/60 kW]
22:32:57.343 [worker-1] START R7(P2,STANDARD,7.0kWh) bay+7 kW waited=70.4 sim-min [bays 4/4, 28/60 kW]
... (13 more lines in test-logs/T03.txt)
BILLING billedSessions=8 legacyEntries=8 cancelled=0 cancelledBilled=false totalsMatch=true duplicatesIgnored=0 billingFailures=0 => RECONCILED
```

### T04 - Priority ordering with FIFO tie-break among equal priorities (268 ms)

```
22:32:57.416 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=1000000000 min guard=1000000000 min scale=1.000 ms/sim-min
22:32:57.417 [test-main] QUEUED BLK(P3,STANDARD,7.0kWh) account=ACC-BLK seq=1
22:32:57.417 [test-main] QUEUED V1(P3,STANDARD,3.5kWh) account=ACC-V1 seq=2
22:32:57.417 [worker-1] START BLK(P3,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 1/4, 7/60 kW]
22:32:57.417 [test-main] QUEUED S1(P2,STANDARD,3.5kWh) account=ACC-S1 seq=3
22:32:57.417 [test-main] QUEUED S2(P2,STANDARD,3.5kWh) account=ACC-S2 seq=4
22:32:57.417 [test-main] QUEUED F1(P1,STANDARD,3.5kWh) account=ACC-F1 seq=5
22:32:57.417 [test-main] QUEUED V2(P3,STANDARD,3.5kWh) account=ACC-V2 seq=6
22:32:57.417 [test-main] SHUTDOWN requested (GRACEFUL)
22:32:57.486 [worker-1] COMPLETED BLK(P3,STANDARD,7.0kWh) after 69.2 sim-min
22:32:57.486 [worker-1] BILLED BLK account=ACC-BLK 7.0 kWh
22:32:57.486 [worker-1] RELEASED BLK bay-7 kW [bays 0/4, 0/60 kW]
22:32:57.486 [worker-1] START F1(P1,STANDARD,3.5kWh) bay+7 kW waited=69.1 sim-min [bays 1/4, 7/60 kW]
22:32:57.526 [worker-1] COMPLETED F1(P1,STANDARD,3.5kWh) after 40.2 sim-min
22:32:57.527 [worker-1] BILLED F1 account=ACC-F1 3.5 kWh
22:32:57.527 [worker-1] RELEASED F1 bay-7 kW [bays 0/4, 0/60 kW]
22:32:57.527 [worker-1] START S1(P2,STANDARD,3.5kWh) bay+7 kW waited=109.9 sim-min [bays 1/4, 7/60 kW]
22:32:57.566 [worker-1] COMPLETED S1(P2,STANDARD,3.5kWh) after 39.5 sim-min
22:32:57.566 [worker-1] BILLED S1 account=ACC-S1 3.5 kWh
22:32:57.567 [worker-1] RELEASED S1 bay-7 kW [bays 0/4, 0/60 kW]
22:32:57.567 [worker-1] START S2(P2,STANDARD,3.5kWh) bay+7 kW waited=149.7 sim-min [bays 1/4, 7/60 kW]
22:32:57.607 [worker-1] COMPLETED S2(P2,STANDARD,3.5kWh) after 40.2 sim-min
22:32:57.607 [worker-1] BILLED S2 account=ACC-S2 3.5 kWh
22:32:57.607 [worker-1] RELEASED S2 bay-7 kW [bays 0/4, 0/60 kW]
22:32:57.607 [worker-1] START V1(P3,STANDARD,3.5kWh) bay+7 kW waited=190.4 sim-min [bays 1/4, 7/60 kW]
22:32:57.647 [worker-1] COMPLETED V1(P3,STANDARD,3.5kWh) after 40.2 sim-min
22:32:57.647 [worker-1] BILLED V1 account=ACC-V1 3.5 kWh
22:32:57.648 [worker-1] RELEASED V1 bay-7 kW [bays 0/4, 0/60 kW]
22:32:57.648 [worker-1] START V2(P3,STANDARD,3.5kWh) bay+7 kW waited=230.4 sim-min [bays 1/4, 7/60 kW]
22:32:57.681 [worker-1] COMPLETED V2(P3,STANDARD,3.5kWh) after 33.0 sim-min
... (3 more lines in test-logs/T04.txt)
```

### T05 - Invalid and duplicate requests are rejected at intake (61 ms)

```
22:32:57.686 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:32:57.686 [test-main] QUEUED OK1(P2,STANDARD,7.0kWh) account=ACC-OK1 seq=1
22:32:57.686 [worker-1] START OK1(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 1/4, 7/60 kW]
22:32:57.687 [test-main] REJECTED energyKwh must be a positive finite number but was 0.0 (Z1)
22:32:57.687 [test-main] REJECTED energyKwh must be a positive finite number but was -5.0 (N1)
22:32:57.687 [test-main] REJECTED energyKwh must be a positive finite number but was NaN (NAN)
22:32:57.687 [test-main] REJECTED energyKwh must be a positive finite number but was Infinity (INF)
22:32:57.687 [test-main] REJECTED energyKwh must not exceed 100.0 but was 150.0 (BIG)
22:32:57.688 [test-main] REJECTED priority must be 1, 2 or 3 but was 0 (P0)
22:32:57.688 [test-main] REJECTED priority must be 1, 2 or 3 but was 4 (P4)
22:32:57.689 [test-main] REJECTED charging rate is required (NR)
22:32:57.689 [test-main] REJECTED accountId must not be blank
22:32:57.689 [test-main] REJECTED requestId must not be blank
22:32:57.689 [test-main] REJECTED duplicate request id OK1
22:32:57.689 [test-main] SHUTDOWN requested (GRACEFUL)
22:32:57.747 [worker-1] COMPLETED OK1(P2,STANDARD,7.0kWh) after 60.8 sim-min
22:32:57.747 [worker-1] BILLED OK1 account=ACC-OK1 7.0 kWh
22:32:57.747 [worker-1] RELEASED OK1 bay-7 kW [bays 0/4, 0/60 kW]
22:32:57.747 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
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

### T06 - No free bay: fifth request waits for a released bay (131 ms)

```
22:32:57.749 [test-main] Station started: mode=pool-16 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:32:57.749 [test-main] QUEUED R1(P2,STANDARD,7.0kWh) account=ACC-R1 seq=1
22:32:57.749 [worker-1] START R1(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 1/4, 7/60 kW]
22:32:57.749 [test-main] QUEUED R2(P2,STANDARD,7.0kWh) account=ACC-R2 seq=2
22:32:57.750 [test-main] QUEUED R3(P2,STANDARD,7.0kWh) account=ACC-R3 seq=3
22:32:57.749 [worker-2] START R2(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 2/4, 14/60 kW]
22:32:57.750 [test-main] QUEUED R4(P2,STANDARD,7.0kWh) account=ACC-R4 seq=4
22:32:57.750 [worker-3] START R3(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 3/4, 21/60 kW]
22:32:57.750 [test-main] QUEUED R5(P2,STANDARD,7.0kWh) account=ACC-R5 seq=5
22:32:57.750 [worker-4] START R4(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 4/4, 28/60 kW]
22:32:57.750 [test-main] SHUTDOWN requested (GRACEFUL)
22:32:57.811 [worker-1] COMPLETED R1(P2,STANDARD,7.0kWh) after 62.0 sim-min
22:32:57.811 [worker-3] COMPLETED R3(P2,STANDARD,7.0kWh) after 61.8 sim-min
22:32:57.812 [worker-1] BILLED R1 account=ACC-R1 7.0 kWh
22:32:57.812 [worker-1] RELEASED R1 bay-7 kW [bays 3/4, 21/60 kW]
22:32:57.812 [worker-3] BILLED R3 account=ACC-R3 7.0 kWh
22:32:57.812 [worker-3] RELEASED R3 bay-7 kW [bays 3/4, 21/60 kW]
22:32:57.812 [worker-11] START R5(P2,STANDARD,7.0kWh) bay+7 kW waited=61.9 sim-min [bays 4/4, 28/60 kW]
22:32:57.814 [worker-2] COMPLETED R2(P2,STANDARD,7.0kWh) after 64.4 sim-min
22:32:57.814 [worker-4] COMPLETED R4(P2,STANDARD,7.0kWh) after 64.2 sim-min
22:32:57.814 [worker-2] BILLED R2 account=ACC-R2 7.0 kWh
22:32:57.814 [worker-2] RELEASED R2 bay-7 kW [bays 2/4, 14/60 kW]
22:32:57.814 [worker-4] BILLED R4 account=ACC-R4 7.0 kWh
22:32:57.814 [worker-4] RELEASED R4 bay-7 kW [bays 1/4, 7/60 kW]
22:32:57.876 [worker-11] COMPLETED R5(P2,STANDARD,7.0kWh) after 64.5 sim-min
22:32:57.876 [worker-11] BILLED R5 account=ACC-R5 7.0 kWh
22:32:57.876 [worker-11] RELEASED R5 bay-7 kW [bays 0/4, 0/60 kW]
22:32:57.876 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T07 - Power exhausted while bays are free (143 ms)

```
22:32:57.883 [test-main] Station started: mode=pool-16 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:32:57.883 [test-main] QUEUED F1(P1,FAST,22.0kWh) account=ACC-F1 seq=1
22:32:57.883 [worker-2] START F1(P1,FAST,22.0kWh) bay+22 kW waited=0.0 sim-min [bays 1/4, 22/60 kW]
22:32:57.883 [test-main] QUEUED F2(P1,FAST,22.0kWh) account=ACC-F2 seq=2
22:32:57.883 [test-main] QUEUED F3(P1,FAST,22.0kWh) account=ACC-F3 seq=3
22:32:57.883 [test-main] SHUTDOWN requested (GRACEFUL)
22:32:57.883 [worker-1] START F2(P1,FAST,22.0kWh) bay+22 kW waited=0.0 sim-min [bays 2/4, 44/60 kW]
22:32:57.953 [worker-2] COMPLETED F1(P1,FAST,22.0kWh) after 70.2 sim-min
22:32:57.954 [worker-2] BILLED F1 account=ACC-F1 22.0 kWh
22:32:57.954 [worker-1] COMPLETED F2(P1,FAST,22.0kWh) after 70.3 sim-min
22:32:57.954 [worker-2] RELEASED F1 bay-22 kW [bays 1/4, 22/60 kW]
22:32:57.954 [worker-1] BILLED F2 account=ACC-F2 22.0 kWh
22:32:57.954 [worker-1] RELEASED F2 bay-22 kW [bays 1/4, 22/60 kW]
22:32:57.954 [worker-7] START F3(P1,FAST,22.0kWh) bay+22 kW waited=70.4 sim-min [bays 2/4, 44/60 kW]
22:32:58.024 [worker-7] COMPLETED F3(P1,FAST,22.0kWh) after 69.8 sim-min
22:32:58.024 [worker-7] BILLED F3 account=ACC-F3 22.0 kWh
22:32:58.024 [worker-7] RELEASED F3 bay-22 kW [bays 0/4, 0/60 kW]
22:32:58.024 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T08 - Cancellation before charging (62 ms)

```
22:32:58.025 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:32:58.025 [test-main] QUEUED BLK(P2,STANDARD,7.0kWh) account=ACC-BLK seq=1
22:32:58.025 [test-main] QUEUED R2(P1,STANDARD,7.0kWh) account=ACC-R2 seq=2
22:32:58.025 [worker-1] START BLK(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 1/4, 7/60 kW]
22:32:58.025 [test-main] CANCELLED R2(P1,STANDARD,7.0kWh) while queued
22:32:58.026 [test-main] SHUTDOWN requested (GRACEFUL)
22:32:58.087 [worker-1] COMPLETED BLK(P2,STANDARD,7.0kWh) after 61.9 sim-min
22:32:58.087 [worker-1] BILLED BLK account=ACC-BLK 7.0 kWh
22:32:58.087 [worker-1] RELEASED BLK bay-7 kW [bays 0/4, 0/60 kW]
22:32:58.088 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
BILLING billedSessions=1 legacyEntries=1 cancelled=1 cancelledBilled=false totalsMatch=true duplicatesIgnored=0 billingFailures=0 => RECONCILED
```

### T09 - Cancellation during charging (30 ms)

```
22:32:58.088 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:32:58.089 [test-main] QUEUED R1(P2,STANDARD,60.0kWh) account=ACC-R1 seq=1
22:32:58.089 [worker-1] START R1(P2,STANDARD,60.0kWh) bay+7 kW waited=0.0 sim-min [bays 1/4, 7/60 kW]
22:32:58.118 [test-main] CANCEL requested for charging session R1(P2,STANDARD,60.0kWh)
22:32:58.118 [worker-1] CANCELLED R1(P2,STANDARD,60.0kWh) during charging (not billed)
22:32:58.118 [test-main] SHUTDOWN requested (GRACEFUL)
22:32:58.118 [worker-1] RELEASED R1 bay-7 kW [bays 0/4, 0/60 kW]
22:32:58.119 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T10 - Immediate shutdown while sessions are active (2 ms)

```
22:32:58.119 [test-main] Station started: mode=pool-4 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:32:58.120 [test-main] QUEUED R1(P2,STANDARD,60.0kWh) account=ACC-R1 seq=1
22:32:58.120 [test-main] QUEUED R2(P2,STANDARD,60.0kWh) account=ACC-R2 seq=2
22:32:58.120 [worker-1] START R1(P2,STANDARD,60.0kWh) bay+7 kW waited=0.0 sim-min [bays 1/4, 7/60 kW]
22:32:58.120 [worker-2] START R2(P2,STANDARD,60.0kWh) bay+7 kW waited=0.0 sim-min [bays 2/4, 14/60 kW]
22:32:58.120 [test-main] QUEUED R3(P2,STANDARD,60.0kWh) account=ACC-R3 seq=3
22:32:58.120 [test-main] QUEUED R4(P2,STANDARD,60.0kWh) account=ACC-R4 seq=4
22:32:58.120 [worker-4] START R4(P2,STANDARD,60.0kWh) bay+7 kW waited=0.0 sim-min [bays 4/4, 28/60 kW]
22:32:58.120 [test-main] QUEUED R5(P2,STANDARD,60.0kWh) account=ACC-R5 seq=5
22:32:58.120 [worker-3] START R3(P2,STANDARD,60.0kWh) bay+7 kW waited=0.0 sim-min [bays 3/4, 21/60 kW]
22:32:58.120 [test-main] QUEUED R6(P2,STANDARD,60.0kWh) account=ACC-R6 seq=6
22:32:58.120 [test-main] SHUTDOWN requested (IMMEDIATE)
22:32:58.120 [test-main] CANCELLED R5(P2,STANDARD,60.0kWh) (queued at shutdown)
22:32:58.120 [test-main] CANCELLED R6(P2,STANDARD,60.0kWh) (queued at shutdown)
22:32:58.121 [test-main] CANCEL requested for R2(P2,STANDARD,60.0kWh) (active at shutdown)
22:32:58.121 [worker-2] CANCELLED R2(P2,STANDARD,60.0kWh) during charging (not billed)
22:32:58.121 [test-main] CANCEL requested for R3(P2,STANDARD,60.0kWh) (active at shutdown)
22:32:58.121 [worker-3] CANCELLED R3(P2,STANDARD,60.0kWh) during charging (not billed)
22:32:58.121 [worker-2] RELEASED R2 bay-7 kW [bays 3/4, 21/60 kW]
22:32:58.121 [test-main] CANCEL requested for R4(P2,STANDARD,60.0kWh) (active at shutdown)
22:32:58.121 [worker-3] RELEASED R3 bay-7 kW [bays 2/4, 14/60 kW]
22:32:58.121 [worker-4] CANCELLED R4(P2,STANDARD,60.0kWh) during charging (not billed)
22:32:58.121 [test-main] CANCEL requested for R1(P2,STANDARD,60.0kWh) (active at shutdown)
22:32:58.121 [worker-1] CANCELLED R1(P2,STANDARD,60.0kWh) during charging (not billed)
22:32:58.121 [worker-4] RELEASED R4 bay-7 kW [bays 1/4, 7/60 kW]
22:32:58.121 [worker-1] RELEASED R1 bay-7 kW [bays 0/4, 0/60 kW]
22:32:58.121 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T11 - Graceful shutdown while sessions are active (131 ms)

```
22:32:58.122 [test-main] Station started: mode=pool-4 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:32:58.123 [test-main] QUEUED R1(P2,STANDARD,7.0kWh) account=ACC-R1 seq=1
22:32:58.123 [worker-1] START R1(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 1/4, 7/60 kW]
22:32:58.123 [test-main] QUEUED R2(P2,STANDARD,7.0kWh) account=ACC-R2 seq=2
22:32:58.123 [test-main] QUEUED R3(P2,STANDARD,7.0kWh) account=ACC-R3 seq=3
22:32:58.123 [worker-2] START R2(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 2/4, 14/60 kW]
22:32:58.123 [worker-3] START R3(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 3/4, 21/60 kW]
22:32:58.123 [test-main] QUEUED R4(P2,STANDARD,7.0kWh) account=ACC-R4 seq=4
22:32:58.123 [test-main] QUEUED R5(P2,STANDARD,7.0kWh) account=ACC-R5 seq=5
22:32:58.123 [worker-4] START R4(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 4/4, 28/60 kW]
22:32:58.123 [test-main] QUEUED R6(P2,STANDARD,7.0kWh) account=ACC-R6 seq=6
22:32:58.123 [test-main] SHUTDOWN requested (GRACEFUL)
22:32:58.187 [worker-1] COMPLETED R1(P2,STANDARD,7.0kWh) after 64.6 sim-min
22:32:58.187 [worker-3] COMPLETED R3(P2,STANDARD,7.0kWh) after 64.5 sim-min
22:32:58.187 [worker-1] BILLED R1 account=ACC-R1 7.0 kWh
22:32:58.187 [worker-1] RELEASED R1 bay-7 kW [bays 3/4, 21/60 kW]
22:32:58.187 [worker-3] BILLED R3 account=ACC-R3 7.0 kWh
22:32:58.187 [worker-1] START R5(P2,STANDARD,7.0kWh) bay+7 kW waited=64.5 sim-min [bays 4/4, 28/60 kW]
22:32:58.187 [worker-3] RELEASED R3 bay-7 kW [bays 3/4, 21/60 kW]
22:32:58.188 [worker-3] START R6(P2,STANDARD,7.0kWh) bay+7 kW waited=64.6 sim-min [bays 4/4, 28/60 kW]
22:32:58.189 [worker-2] COMPLETED R2(P2,STANDARD,7.0kWh) after 66.5 sim-min
22:32:58.189 [worker-2] BILLED R2 account=ACC-R2 7.0 kWh
22:32:58.189 [worker-2] RELEASED R2 bay-7 kW [bays 3/4, 21/60 kW]
22:32:58.192 [worker-4] COMPLETED R4(P2,STANDARD,7.0kWh) after 69.6 sim-min
22:32:58.193 [worker-4] BILLED R4 account=ACC-R4 7.0 kWh
22:32:58.193 [worker-4] RELEASED R4 bay-7 kW [bays 2/4, 14/60 kW]
22:32:58.250 [worker-3] COMPLETED R6(P2,STANDARD,7.0kWh) after 62.5 sim-min
22:32:58.250 [worker-3] BILLED R6 account=ACC-R6 7.0 kWh
22:32:58.250 [worker-3] RELEASED R6 bay-7 kW [bays 1/4, 7/60 kW]
22:32:58.253 [worker-1] COMPLETED R5(P2,STANDARD,7.0kWh) after 65.5 sim-min
... (3 more lines in test-logs/T11.txt)
```

### T12 - Concurrent billing through the adapter (23 ms)

```
```

### T13 - Same concurrent load straight into the legacy service (no adapter) (24 ms)

```
trial 1: entries=6074/8000 summedTotals=5111/8000 exceptions=0 corrupted=true
trial 2: entries=6339/8000 summedTotals=7068/8000 exceptions=0 corrupted=true
trial 3: entries=6331/8000 summedTotals=7019/8000 exceptions=0 corrupted=true
trial 4: entries=5897/8000 summedTotals=7005/8000 exceptions=0 corrupted=true
trial 5: entries=6304/8000 summedTotals=7150/8000 exceptions=0 corrupted=true
```

### T14 - Resources released after a billing exception (71 ms)

```
22:32:58.312 [test-main] Station started: mode=pool-2 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:32:58.312 [test-main] QUEUED RF(P2,STANDARD,7.0kWh) account=ACC-FAIL seq=1
22:32:58.312 [test-main] QUEUED RG(P2,STANDARD,7.0kWh) account=ACC-RG seq=2
22:32:58.312 [test-main] SHUTDOWN requested (GRACEFUL)
22:32:58.312 [worker-1] START RF(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 1/4, 7/60 kW]
22:32:58.312 [worker-2] START RG(P2,STANDARD,7.0kWh) bay+7 kW waited=0.0 sim-min [bays 2/4, 14/60 kW]
22:32:58.382 [worker-1] COMPLETED RF(P2,STANDARD,7.0kWh) after 70.2 sim-min
22:32:58.382 [worker-2] COMPLETED RG(P2,STANDARD,7.0kWh) after 70.1 sim-min
22:32:58.383 [worker-1] BILLING FAILED for RF: simulated legacy billing outage
22:32:58.383 [worker-1] RELEASED RF bay-7 kW [bays 1/4, 7/60 kW]
22:32:58.383 [worker-2] BILLED RG account=ACC-RG 7.0 kWh
22:32:58.383 [worker-2] RELEASED RG bay-7 kW [bays 0/4, 0/60 kW]
22:32:58.383 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
BILLING billedSessions=1 legacyEntries=1 cancelled=0 cancelledBilled=false totalsMatch=true duplicatesIgnored=0 billingFailures=1 => RECONCILED
```

### T15 - Duplicate billing attempt is ignored (0 ms)

```
22:32:58.384 [test-main] BILLED R1 account=ACC-R1 7.0 kWh
22:32:58.384 [test-main] BILLING duplicate ignored for R1
```

### T16 - Aging lifts a waiting visitor above a later fleet request (0 ms)

```
```

### T17 - Starvation guard bounds backfill past a blocked fast request (0 ms)

```
22:32:58.385 [test-main] BACKFILL S1(P2,STANDARD,7.0kWh) starts ahead of blocked F(P1,FAST,22.0kWh)
22:32:58.385 [test-main] BACKFILL S2(P2,STANDARD,7.0kWh) starts ahead of blocked F(P1,FAST,22.0kWh)
```

### T18 - Burst of the seeded workload with thread-per-request (545 ms)

```
BILLING billedSessions=200 legacyEntries=200 cancelled=0 cancelledBilled=false totalsMatch=true duplicatesIgnored=0 billingFailures=0 => RECONCILED
```

### T19 - Deadlock-prone allocator deadlocks (separate demo) (1033 ms)

```
=== DEADLOCK-PRONE VARIANT (naive allocator, inconsistent acquisition order) ===
[std-2] holds 1 bay, now waiting for the power ledger lock
[std-4] holds 1 bay, now waiting for the power ledger lock
[fast-1] holds the power ledger lock, now waiting for a bay
[std-3] holds 1 bay, now waiting for the power ledger lock
[std-1] holds 1 bay, now waiting for the power ledger lock
DETECTED: no progress within 1000 ms timeout; 5 of 5 threads still blocked
Thread dump of demo threads:
  "std-1" state=WAITING waitingOn=java.util.concurrent.locks.ReentrantLock$NonfairSync@73a8dfcc owner=fast-1
      at app//smartcharge.demo.DeadlockDemo.lambda$runDeadlockProne$0(DeadlockDemo.java:83)
  "std-2" state=WAITING waitingOn=java.util.concurrent.locks.ReentrantLock$NonfairSync@73a8dfcc owner=fast-1
      at app//smartcharge.demo.DeadlockDemo.lambda$runDeadlockProne$0(DeadlockDemo.java:83)
  "std-3" state=WAITING waitingOn=java.util.concurrent.locks.ReentrantLock$NonfairSync@73a8dfcc owner=fast-1
      at app//smartcharge.demo.DeadlockDemo.lambda$runDeadlockProne$0(DeadlockDemo.java:83)
  "std-4" state=WAITING waitingOn=java.util.concurrent.locks.ReentrantLock$NonfairSync@73a8dfcc owner=fast-1
      at app//smartcharge.demo.DeadlockDemo.lambda$runDeadlockProne$0(DeadlockDemo.java:83)
  "fast-1" state=WAITING waitingOn=java.util.concurrent.Semaphore$FairSync@ea30797 owner=null
      at app//smartcharge.demo.DeadlockDemo.lambda$runDeadlockProne$1(DeadlockDemo.java:103)
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

### T20 - Corrected allocator under the same interleaving (58 ms)

```
=== CORRECTED VARIANT (atomic, non-blocking allocation, fixed order: power lock -> bay tryAcquire) ===
[std-1] allocated bay + 7 kW atomically (holds both, waits for nothing)
[std-2] allocated bay + 7 kW atomically (holds both, waits for nothing)
[std-3] allocated bay + 7 kW atomically (holds both, waits for nothing)
[std-4] allocated bay + 7 kW atomically (holds both, waits for nothing)
[fast-1] allocation refused (no free bay) - holding NOTHING, will retry
[std-2] charging done, released bay + 7 kW
[std-3] charging done, released bay + 7 kW
[std-4] charging done, released bay + 7 kW
[std-1] charging done, released bay + 7 kW
[fast-1] allocated bay + 22 kW after 8 refused attempt(s)
Finished=true, final reserved=0 kW, active bays=0, peak reserved=43 kW, peak bays=4
Why no deadlock: tryAcquire() never blocks while the power lock is held, and a refused request
  keeps nothing, so hold-and-wait and circular wait cannot occur.
```

### T21 - Contract C1/C2 violations on StationResources are rejected (0 ms)

```
```

### T22 - Contract C3/C5 violations on session and billing are rejected (0 ms)

```
```

### T23 - Contract C6 lifecycle violations are rejected (0 ms)

```
22:33:00.027 [test-main] Station started: mode=pool-1 bays=4 power=60 kW aging=30 min guard=30 min scale=1.000 ms/sim-min
22:33:00.028 [test-main] SHUTDOWN requested (GRACEFUL)
22:33:00.028 [test-main] SHUTDOWN complete: threadsTerminated=true reservedKw=0 activeBays=0
```

### T24 - Starvation guard on a running station (336 ms)

```
22:33:00.029 [test-main] Station started: mode=pool-16 bays=4 power=60 kW aging=1000000000 min guard=30 min scale=1.000 ms/sim-min
22:33:00.029 [test-main] QUEUED F1(P1,FAST,88.0kWh) account=ACC-F1 seq=1
22:33:00.029 [test-main] QUEUED F2(P1,FAST,88.0kWh) account=ACC-F2 seq=2
22:33:00.029 [worker-1] START F1(P1,FAST,88.0kWh) bay+22 kW waited=0.0 sim-min [bays 1/4, 22/60 kW]
22:33:00.029 [test-main] QUEUED F3(P1,FAST,22.0kWh) account=ACC-F3 seq=3
22:33:00.029 [worker-2] START F2(P1,FAST,88.0kWh) bay+22 kW waited=0.0 sim-min [bays 2/4, 44/60 kW]
22:33:00.029 [test-main] QUEUED S1(P2,STANDARD,4.2kWh) account=ACC-S1 seq=4
22:33:00.029 [worker-4] BACKFILL S1(P2,STANDARD,4.2kWh) starts ahead of blocked F3(P1,FAST,22.0kWh)
22:33:00.030 [worker-4] START S1(P2,STANDARD,4.2kWh) bay+7 kW waited=0.0 sim-min [bays 3/4, 51/60 kW]
22:33:00.045 [test-main] QUEUED S2(P2,STANDARD,4.2kWh) account=ACC-S2 seq=5
22:33:00.045 [worker-8] BACKFILL S2(P2,STANDARD,4.2kWh) starts ahead of blocked F3(P1,FAST,22.0kWh)
22:33:00.045 [worker-8] START S2(P2,STANDARD,4.2kWh) bay+7 kW waited=0.0 sim-min [bays 4/4, 58/60 kW]
22:33:00.060 [test-main] QUEUED S3(P2,STANDARD,4.2kWh) account=ACC-S3 seq=6
22:33:00.071 [worker-4] COMPLETED S1(P2,STANDARD,4.2kWh) after 41.4 sim-min
22:33:00.071 [test-main] QUEUED S4(P2,STANDARD,4.2kWh) account=ACC-S4 seq=7
22:33:00.071 [worker-4] BILLED S1 account=ACC-S1 4.2 kWh
22:33:00.071 [worker-4] RELEASED S1 bay-7 kW [bays 3/4, 51/60 kW]
22:33:00.071 [worker-10] STARVATION GUARD F3(P1,FAST,22.0kWh) overtaken for too long; backfill paused until it starts
22:33:00.085 [test-main] QUEUED S5(P2,STANDARD,4.2kWh) account=ACC-S5 seq=8
22:33:00.089 [worker-8] COMPLETED S2(P2,STANDARD,4.2kWh) after 44.2 sim-min
22:33:00.089 [worker-8] BILLED S2 account=ACC-S2 4.2 kWh
22:33:00.089 [worker-8] RELEASED S2 bay-7 kW [bays 2/4, 44/60 kW]
22:33:00.100 [test-main] QUEUED S6(P2,STANDARD,4.2kWh) account=ACC-S6 seq=9
22:33:00.110 [test-main] QUEUED S7(P2,STANDARD,4.2kWh) account=ACC-S7 seq=10
22:33:00.125 [test-main] QUEUED S8(P2,STANDARD,4.2kWh) account=ACC-S8 seq=11
22:33:00.135 [test-main] SHUTDOWN requested (GRACEFUL)
22:33:00.273 [worker-2] COMPLETED F2(P1,FAST,88.0kWh) after 243.6 sim-min
22:33:00.273 [worker-2] BILLED F2 account=ACC-F2 88.0 kWh
22:33:00.273 [worker-2] RELEASED F2 bay-22 kW [bays 1/4, 22/60 kW]
22:33:00.273 [worker-2] START S4(P2,STANDARD,4.2kWh) bay+7 kW waited=202.3 sim-min [bays 4/4, 58/60 kW]
... (31 more lines in test-logs/T24.txt)
Timeline (relative to F1 start):
  F1  start +   0.0 ms  end + 250.1 ms  BILLED
  F2  start +   0.0 ms  end + 243.7 ms  BILLED
  S1  start +   0.2 ms  end +  41.6 ms  BILLED
  S2  start +  15.2 ms  end +  59.4 ms  BILLED
  F3  start + 243.9 ms  end + 307.5 ms  BILLED
  S3  start + 243.9 ms  end + 288.4 ms  BILLED
  S4  start + 243.9 ms  end + 288.4 ms  BILLED
  S5  start + 250.2 ms  end + 292.2 ms  BILLED
  S6  start + 288.5 ms  end + 332.5 ms  BILLED
  S7  start + 288.5 ms  end + 332.5 ms  BILLED
  S8  start + 292.2 ms  end + 334.1 ms  BILLED
```

### T25 - Guard clock survives a temporary change of front request (0 ms)

```
22:33:00.366 [test-main] BACKFILL S1(P2,STANDARD,7.0kWh) starts ahead of blocked F(P1,FAST,22.0kWh)
22:33:00.366 [test-main] BACKFILL S2(P2,STANDARD,7.0kWh) starts ahead of blocked V(P3,FAST,22.0kWh)
```
