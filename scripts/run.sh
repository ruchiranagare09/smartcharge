#!/usr/bin/env sh
# Builds, then runs the station on the seeded workload. Log is also saved to logs/run-<timestamp>.txt
# Exits with the Java program's exit code (non-zero on unreconciled billing or a limit violation).
set -e
cd "$(dirname "$0")/.."
./scripts/build.sh
mkdir -p logs
LOG="logs/run-$(date +%Y%m%d-%H%M%S).txt"
set +e
exec 3>&1
status=$( { { java -ea -cp out/main smartcharge.app.Main "$@" 2>&1; echo $? >&4; } | tee "$LOG" >&3; } 4>&1 )
exit "$status"
