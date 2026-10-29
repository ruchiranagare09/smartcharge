#!/usr/bin/env sh
# Compiles main sources into out/main and test sources into out/test (Java 17).
set -e
cd "$(dirname "$0")/.."
rm -rf out
mkdir -p out/main out/test
javac --release 17 -Xlint:all -d out/main $(find src/main/java -name '*.java')
if [ -d src/test/java ] && [ -n "$(find src/test/java -name '*.java')" ]; then
  javac --release 17 -Xlint:all -cp out/main -d out/test $(find src/test/java -name '*.java')
fi
echo "Build OK: out/main, out/test"
