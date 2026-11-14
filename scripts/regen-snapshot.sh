#!/usr/bin/env bash
# Re-index a target, verify against ground truth (fixtures only), print diff vs committed snapshot.
set -euo pipefail
target="${1:-fixtures/haunted-house}"
name="$(basename "$target")"
./gradlew -q :ghostmap-cli:installDist
cli=ghostmap-cli/build/install/ghostmap-cli/bin/ghostmap-cli
"$cli" index --project "$target" --out "build/snapshots/$name.ghostmap.json"
if [[ -f "$target/ground-truth.json" ]]; then
  "$cli" bench --snapshot "build/snapshots/$name.ghostmap.json" --truth "$target/ground-truth.json"
fi
if [[ -f "snapshots/$name.ghostmap.json" ]]; then
  "$cli" diff "snapshots/$name.ghostmap.json" "build/snapshots/$name.ghostmap.json" --summary
fi
