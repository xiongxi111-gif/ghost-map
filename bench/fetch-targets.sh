#!/usr/bin/env bash
# Fetch public demo targets at reviewed commits without vendoring their source trees.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
targets="$root/bench/targets"
mkdir -p "$targets"

fetch_target() {
  local name="$1"
  local url="$2"
  local commit="$3"
  local directory="$targets/$name"
  if [[ ! -d "$directory/.git" ]]; then
    git clone --quiet --filter=blob:none --no-checkout "$url" "$directory"
  fi
  git -C "$directory" fetch --quiet --depth=1 origin "$commit"
  git -C "$directory" checkout --quiet --detach "$commit"
  printf '%s %s\n' "$name" "$(git -C "$directory" rev-parse HEAD)"
}

fetch_target spring-petclinic \
  https://github.com/spring-projects/spring-petclinic.git \
  500158f732419217507c7656904b8e6aa1bcc0d6
fetch_target spring-framework-petclinic \
  https://github.com/spring-petclinic/spring-framework-petclinic.git \
  d980667952cdfd9c69e87cce2efadc89938af6f0
