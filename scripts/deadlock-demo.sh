#!/usr/bin/env sh
# Builds, then runs the separate deadlock demonstration: deadlock | fixed | both (default).
set -e
cd "$(dirname "$0")/.."
./scripts/build.sh
mkdir -p logs
java -ea -cp out/main smartcharge.demo.DeadlockDemo "${1:-both}" | tee "logs/deadlock-demo-$(date +%Y%m%d-%H%M%S).txt"
