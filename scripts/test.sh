#!/usr/bin/env sh
# Builds, then runs the consolidated test suite with assertions enabled.
# Report: results/test-results.md, per-test logs: results/test-logs/
set -e
cd "$(dirname "$0")/.."
./scripts/build.sh
java -ea -cp out/main:out/test smartcharge.tests.TestRunner results "$@"
