#!/usr/bin/env sh
# Fills the Name / Student ID header at the top of every Java source file (required by the brief).
# Usage: ./scripts/set-author.sh "Full Name" "StudentID"
set -e
cd "$(dirname "$0")/.."
if [ $# -ne 2 ] || [ -z "$1" ] || [ -z "$2" ]; then
  echo "usage: $0 \"Full Name\" \"StudentID\"" >&2
  exit 2
fi
NAME="$1" ID="$2" find src -name '*.java' -exec perl -pi -e 's/<STUDENT NAME>/$ENV{NAME}/g; s/<STUDENT ID>/$ENV{ID}/g' {} +
remaining=$(grep -rl -e '<STUDENT NAME>' -e '<STUDENT ID>' src || true)
if [ -n "$remaining" ]; then
  echo "placeholders still present in: $remaining" >&2
  exit 1
fi
echo "Header set in $(find src -name '*.java' | wc -l | tr -d ' ') files"
