#!/usr/bin/env bash
# Re-index a target with the implemented L1/L2 CLI. Benchmark and diff commands arrive in later tasks.
set -euo pipefail
target="${1:-fixtures/haunted-house}"
name="$(basename "$target")"
src="$target/src/main/java"
classes="$target/target/classes"
out="${2:-snapshots/$name-static.ghostmap.json}"

if [[ ! -d "$src" ]]; then
  echo "source root not found: $src" >&2
  exit 2
fi

./mvnw -q -pl ghostmap-cli -am install -DskipTests
args="index --src $src --out $out"
if [[ -d "$classes" ]]; then
  args="$args --classes $classes"
else
  echo "class root not found; producing an AST-only snapshot" >&2
fi
./mvnw -q -f ghostmap-cli/pom.xml exec:java -Dexec.args="$args"
echo "bench and diff are not implemented yet; snapshot written without fabricated benchmark output" >&2
