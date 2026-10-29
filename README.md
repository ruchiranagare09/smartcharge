# SmartCharge

A concurrent controller for an EV charging station, built as a small educational Java prototype.
It schedules charging requests by priority, with FIFO order among equal priorities, aging and a
starvation guard. A session starts only when it has secured both a bay (4 bays, counting semaphore)
and its full power reservation (60 kW budget, mutex). Sessions run on worker threads. Every completed
session is billed exactly once through a thread-safe adapter around a non-thread-safe legacy billing
service. The project also includes a separate deadlock demonstration, a seeded benchmark and a
consolidated test suite.

## Requirements

- Java 17 (JDK 17 or newer with `javac` and `java` on `PATH`). Verified with OpenJDK 17.0.16.
- A POSIX shell (macOS, Linux, Git Bash or WSL) for the `scripts/*.sh` helpers. Plain `javac`/`java`
  commands for Windows `cmd` are listed below.
- No other dependencies: no build tool, external libraries, database or network.

## Layout

```
src/main/java/smartcharge/
  model/     ChargingRequest, Priority, ChargingRate, SessionState
  core/      ChargingStation (controller), PriorityScheduler, StationResources, ChargingSession,
             RequestValidator, TimeScale, Log
  billing/   LegacyBillingService (stand-in, unmodified, not thread-safe), BillingAdapter
  workload/  WorkloadGenerator (seeded), WorkloadDriver
  app/       Main (normal run), BenchmarkRunner, RunMetrics
  demo/      DeadlockDemo (separate; never used by the station)
src/test/java/smartcharge/tests/   TestRunner, SmartChargeTests
scripts/   build.sh, run.sh, test.sh, deadlock-demo.sh, benchmark.sh
docs/      requirements.md, design.md, testing.md, benchmark.md
evidence/  committed outputs from real runs (logs, test report, deadlock dump, benchmark data, git log)
```

## Build

```sh
./scripts/build.sh
```

This compiles `src/main/java` into `out/main` and `src/test/java` into `out/test` with
`javac --release 17`. The equivalent manual commands are:

```sh
mkdir -p out/main out/test
javac --release 17 -d out/main $(find src/main/java -name '*.java')
javac --release 17 -cp out/main -d out/test $(find src/test/java -name '*.java')
```

On Windows `cmd`:

```bat
dir /s /b src\main\java\*.java > main-sources.txt
javac --release 17 -d out\main @main-sources.txt
dir /s /b src\test\java\*.java > test-sources.txt
javac --release 17 -cp out\main -d out\test @test-sources.txt
```

## Assertions

Design contracts are checked with Java assertions, so always run with `-ea`. All scripts below pass
it. The test runner refuses to start without it, and the normal run prints `assertions=true` in its
first log line.

## Normal execution

```sh
./scripts/run.sh                         # 200 seeded requests, pool of 4 workers
./scripts/run.sh --workers 16
./scripts/run.sh --thread-per-request
./scripts/run.sh --requests 300 --seed 7 --scale 0.5
```

Manual equivalent: `java -ea -cp out/main smartcharge.app.Main --workers 4`

- **Options:** `--workers N` | `--thread-per-request`, `--requests N` (default 200), `--seed N`
  (default 42), `--scale MS` (real milliseconds per simulated minute; default 1.0).
- **Output:** a thread-named log on stdout, also saved to `logs/run-<timestamp>.txt`. It has one line
  per QUEUED, START, COMPLETED, BILLED, RELEASED and CANCELLED event, a STATUS line every 240
  simulated minutes, and a final billing reconciliation and metrics summary.
- **Run time:** about 8 s for the default run.
- **Exit code:** non-zero if threads did not terminate, billing did not reconcile, or a resource limit
  was exceeded.

## Tests

```sh
./scripts/test.sh            # all tests
./scripts/test.sh T09 T10    # selected tests
```

Manual equivalent: `java -ea -cp out/main:out/test smartcharge.tests.TestRunner results`
(use `;` instead of `:` in the classpath on Windows).

- **Output:** a console summary, plus `results/test-results.md` (ID, input, expected, observed,
  result and log excerpts) and `results/test-logs/<ID>.txt` (the full thread-named log per test).
- **Run time:** about 3 s.
- **Exit code:** 0 only if every test passes.

## Deadlock demonstration

```sh
./scripts/deadlock-demo.sh           # both variants
./scripts/deadlock-demo.sh deadlock  # deadlock-prone variant only
./scripts/deadlock-demo.sh fixed     # corrected variant only
```

Manual equivalent: `java -ea -cp out/main smartcharge.demo.DeadlockDemo both`

The deadlock-prone variant forces the fatal interleaving with latches. A watchdog detects no progress
after 2 s and prints a thread dump and the resource-allocation / wait-for graph. The corrected
variant runs the same threads through the production allocator and completes. Demo threads are
daemons, so the JVM exits normally. Output is printed to stdout and saved to
`logs/deadlock-demo-<timestamp>.txt`.

## Performance experiment

```sh
./scripts/benchmark.sh                       # 4 configurations x 3 runs, 200 requests, seed 42
./scripts/benchmark.sh --runs 5 --requests 300
```

Manual equivalent: `java -ea -cp out/main smartcharge.app.BenchmarkRunner --out results`

- **What it runs:** pool-1, pool-4, pool-16 and thread-per-request on the identical seeded workload
  (the fingerprint is printed and checked), interleaved round-robin after one discarded warm-up run.
- **Output:** `results/benchmark-runs-<timestamp>.csv` (one row per run) and
  `results/benchmark-summary-<timestamp>.md` (medians and spread).
- **Run time:** about 2.5 minutes.
- **Exit code:** non-zero if any run exceeds 4 bays or 60 kW, fails to bill every request, or fails
  reconciliation.

## Where output goes

| Path | Content | In git? |
|---|---|---|
| `out/` | compiled classes | no |
| `logs/` | logs from `run.sh` and `deadlock-demo.sh` | no |
| `results/` | test report and benchmark output from your own runs | no |
| `evidence/` | the outputs used in the documentation, produced by real runs of this code | yes |

## Documentation

- [docs/requirements.md](docs/requirements.md): numbered functional and non-functional requirements,
  assumptions, and the traceability matrix.
- [docs/design.md](docs/design.md): architecture, threads, lock order, scheduling policy, concurrency
  hazards (including a worked race interleaving), deadlock analysis, design contracts and models.
- [docs/testing.md](docs/testing.md): test strategy and observed results.
- [docs/benchmark.md](docs/benchmark.md): experiment method, results and analysis.

## Key parameters

| Parameter | Value | Where |
|---|---|---|
| Bays | 4 | `StationResources.DEFAULT_BAYS` |
| Power budget | 60 kW | `StationResources.DEFAULT_POWER_KW` |
| Standard / fast rate | 7 kW / 22 kW | `ChargingRate` |
| Priorities | 1 fleet/emergency, 2 staff, 3 visitor | `Priority` |
| Session duration | kWh / kW × 60 simulated minutes | `ChargingRequest.durationSimMinutes` |
| Time scale | 1 simulated minute = 1 ms | `TimeScale.DEFAULT` |
| Aging step / starvation guard | 30 / 30 simulated minutes | `ChargingStation.Config` |
| Workload | 200 requests, seed 42 | `WorkloadGenerator` |
