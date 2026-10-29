#!/usr/bin/env sh
# Builds, then runs the worker-pool benchmark (pool-1, pool-4, pool-16, thread-per-request; 3 runs each).
# Output: results/benchmark-runs-<timestamp>.csv and results/benchmark-summary-<timestamp>.md
set -e
cd "$(dirname "$0")/.."
./scripts/build.sh
java -ea -cp out/main smartcharge.app.BenchmarkRunner --out results "$@"
