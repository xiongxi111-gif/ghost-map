#!/usr/bin/env bash
# PreToolUse hook: reads the hook event JSON on STDIN and blocks the write (exit 2)
# if it contains any term from a PRIVATE denylist that is never committed.
set -euo pipefail
DENY="${GHOSTMAP_DENYLIST:-$HOME/.ghostmap/denylist.txt}"
[[ -f "$DENY" ]] || exit 0            # no denylist configured → allow
payload="$(cat)"
while IFS= read -r term; do
  [[ -z "$term" || "$term" == \#* ]] && continue
  if grep -qiF -- "$term" <<<"$payload"; then
    echo "Clean-room guard: write blocked — content matches a private denylist term. Rewrite generically." >&2
    exit 2
  fi
done < "$DENY"
exit 0
