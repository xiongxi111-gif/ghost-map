#!/usr/bin/env bash
# Re-index a target, verify against ground truth (fixtures only), print diff vs committed snapshot.
set -euo pipefail
target="${1:-fixtures/haunted-house}"
name="$(basename "$target")"
out="target/snapshots/$name.ghostmap.json"

./mvnw -q -pl ghostmap-cli -am install -DskipTests
run_cli() {
  ./mvnw -q -f ghostmap-cli/pom.xml exec:java \
    -Dexec.mainClass=dev.ghostmap.cli.GhostMapCli \
    -Dexec.args="$*"
}

run_cli index --project "$target" --out "$out"
if [[ -f "$target/ground-truth.json" ]]; then
  run_cli bench --snapshot "$out" --truth "$target/ground-truth.json"
fi
if [[ -f "snapshots/$name.ghostmap.json" ]]; then
  run_cli diff "snapshots/$name.ghostmap.json" "$out" --summary
fi
