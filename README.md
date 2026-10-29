# SmartCharge

SmartCharge is a small Java program that controls an EV charging station with 4 charging bays and a
60 kW power limit. Charging requests are scheduled by priority (FIFO for equal priority, with aging
and a starvation guard). A session only starts when it has both a free bay and its full power.
Sessions run on worker threads, and every completed session is billed once through an adapter
around the non-thread-safe legacy billing service. The project also has a deadlock demo, a test
suite and a benchmark.

## What you need

- Java 17 (JDK 17 or newer). Check with `java -version` and `javac -version`.
  It was tested with OpenJDK 17.0.16.
- Nothing else. No Maven, Gradle or external libraries.

## Project folders

```
src/main/java/smartcharge/
  model/     ChargingRequest, Priority, ChargingRate, SessionState
  core/      ChargingStation, PriorityScheduler, StationResources, ChargingSession,
             RequestValidator, TimeScale, Log
  billing/   LegacyBillingService (the legacy stand-in), BillingAdapter
  workload/  WorkloadGenerator, WorkloadDriver
  app/       Main (normal run), BenchmarkRunner, RunMetrics
  demo/      DeadlockDemo
src/test/java/smartcharge/tests/   TestRunner, SmartChargeTests
docs/      requirements, design, testing and benchmark notes, plus UML diagrams in docs/diagrams/
evidence/  output saved from real runs (logs, test results, deadlock output, benchmark results)
```

## 1. Compile

Run these from the project folder (the folder with this README).

macOS / Linux:

```sh
mkdir -p out/main out/test
javac --release 17 -d out/main $(find src/main/java -name "*.java")
javac --release 17 -cp out/main -d out/test $(find src/test/java -name "*.java")
```

Windows (Command Prompt):

```bat
mkdir out\main out\test
dir /s /b src\main\java\*.java > main-sources.txt
javac --release 17 -d out\main @main-sources.txt
dir /s /b src\test\java\*.java > test-sources.txt
javac --release 17 -cp out\main -d out\test @test-sources.txt
```

The compiled classes go into `out/`. That folder is not committed to Git.

## 2. Assertions

The design contracts are checked with Java `assert` statements, so always run with `-ea`
(enable assertions). Every command below includes it. Without `-ea` the tests refuse to run.

## 3. Run the station

```sh
java -ea -cp out/main smartcharge.app.Main
```

This runs 200 generated requests (seed 42) with a pool of 4 worker threads. It takes about 8 seconds.
Each log line shows the time and the thread name, for example `[worker-2]`. At the end it prints the
billing check and a summary.

Other options:

```sh
java -ea -cp out/main smartcharge.app.Main --workers 16
java -ea -cp out/main smartcharge.app.Main --thread-per-request
java -ea -cp out/main smartcharge.app.Main --requests 300 --seed 7 --scale 0.5
```

`--scale` is real milliseconds per simulated minute (default 1.0).

To save the log to a file, add `> run-log.txt` to the end of the command.

## 4. Run the tests

macOS / Linux:

```sh
java -ea -cp out/main:out/test smartcharge.tests.TestRunner results
```

Windows (use `;` instead of `:`):

```bat
java -ea -cp out\main;out\test smartcharge.tests.TestRunner results
```

This runs 24 tests plus 1 observation in about 3 seconds. The results table is written to
`results/test-results.md`, and the full log of each test to `results/test-logs/`.

To run only some tests, add their IDs at the end, for example `... TestRunner results T09 T10`.

## 5. Run the deadlock demo

```sh
java -ea -cp out/main smartcharge.demo.DeadlockDemo
```

First it runs the version that deadlocks on purpose. After 2 seconds without progress it prints a
thread dump and the wait-for cycle. Then it runs the corrected version, which finishes normally.

To run only one part:

```sh
java -ea -cp out/main smartcharge.demo.DeadlockDemo deadlock
java -ea -cp out/main smartcharge.demo.DeadlockDemo fixed
```

The demo is a separate class. The normal station never uses it.

## 6. Run the benchmark

```sh
java -ea -cp out/main smartcharge.app.BenchmarkRunner --out results
```

It compares a pool of 1, 4 and 16 workers and thread-per-request. All of them use the same 200
requests (seed 42), and each configuration runs 3 times. It takes about 2.5 minutes.

The results go to `results/benchmark-runs-<time>.csv` (every run) and
`results/benchmark-summary-<time>.md` (medians).

Other options: `--runs N`, `--requests N`, `--seed N`, `--scale MS`.

## Where the output goes

| Folder | What is in it | In Git? |
|---|---|---|
| `out/` | compiled classes | no |
| `results/` | test and benchmark output from your own runs | no |
| `evidence/` | the output used in the documentation, saved from real runs | yes |

## Main settings

| Setting | Value | Where |
|---|---|---|
| Bays | 4 | `StationResources.DEFAULT_BAYS` |
| Power limit | 60 kW | `StationResources.DEFAULT_POWER_KW` |
| Standard / fast rate | 7 kW / 22 kW | `ChargingRate` |
| Priorities | 1 fleet/emergency, 2 staff, 3 visitor | `Priority` |
| Session length | kWh / kW x 60 simulated minutes | `ChargingRequest.durationSimMinutes` |
| Time scale | 1 simulated minute = 1 ms | `TimeScale.DEFAULT` |
| Aging step / starvation guard | 30 / 30 simulated minutes | `ChargingStation.Config` |
| Workload | 200 requests, seed 42 | `WorkloadGenerator` |

## How the scheduler decides

1. **Order:** waiting requests are ordered by priority (1 first), then by arrival (FIFO).
2. **Aging:** a request moves up one priority level for every 30 simulated minutes it waits, up to
   level 1.
3. **Start rule:** a request starts only if a bay and its full 7 or 22 kW can be reserved together.
4. **Backfill:** if the first request cannot start, the next one that can start does.
5. **Starvation guard:** if the first request was first skipped by a backfill at least 30 simulated
   minutes ago, nothing else starts until it does. Running sessions finish normally and free the
   power it needs.

More detail is in `docs/design.md`. Tests T16, T17, T24 and T25 check this behaviour.

## Documentation

- `docs/requirements.md`: requirements, assumptions and traceability
- `docs/design.md`: design, threads, locks, hazards, deadlock analysis, contracts and UML models
- `docs/testing.md`: test results
- `docs/benchmark.md`: benchmark method and results

## Version

The submitted version is the latest Git tag (`git describe --tags --abbrev=0`).
