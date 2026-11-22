#!/usr/bin/env bash
# Run pinned public Petclinic test suites in forked JVMs with Ghost Map's real agent.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
targets_root="$root/bench/targets"
out_base="$root/bench/out/workloads"
agent="$root/ghostmap-probe/target/ghostmap-probe-agent.jar"
include="org.springframework.samples"
default_exclude="org.springframework.samples.petclinic.generated"
edge_limit="${GHOSTMAP_EDGE_LIMIT:-250000}"
timeout_seconds="${WORKLOAD_TIMEOUT_SECONDS:-1200}"
run_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out_root="$out_base/$run_stamp"
active_pid=""

declare -A pinned=(
  [spring-petclinic]="500158f732419217507c7656904b8e6aa1bcc0d6"
  [spring-framework-petclinic]="d980667952cdfd9c69e87cce2efadc89938af6f0"
)
declare -A target_exclude=(
  [spring-petclinic]="$default_exclude"
  [spring-framework-petclinic]="$default_exclude"
)

if [[ -n "${JAVA_HOME:-}" ]]; then
  java_bin="$JAVA_HOME/bin/java"
else
  java_bin="$(command -v java)"
  JAVA_HOME="$(cd "$(dirname "$java_bin")/.." && pwd)"
  export JAVA_HOME
fi

cleanup_active_process() {
  if [[ -n "$active_pid" ]]; then
    if kill -0 -- "-$active_pid" 2>/dev/null; then
      kill -TERM -- "-$active_pid" 2>/dev/null || true
      sleep 1
      kill -KILL -- "-$active_pid" 2>/dev/null || true
    fi
    wait "$active_pid" 2>/dev/null || true
    active_pid=""
  fi
}

on_signal() {
  cleanup_active_process
  exit 130
}

trap cleanup_active_process EXIT
trap on_signal HUP INT TERM

if ! command -v setsid >/dev/null 2>&1; then
  printf 'run-workloads: setsid is required for reliable process-group cleanup\n' >&2
  exit 2
fi
if [[ ! -x "$java_bin" ]]; then
  printf 'run-workloads: Java executable not found: %s\n' "$java_bin" >&2
  exit 2
fi

selected=("$@")
if [[ ${#selected[@]} -eq 0 ]]; then
  selected=(spring-petclinic spring-framework-petclinic)
fi
for name in "${selected[@]}"; do
  if [[ -z "${pinned[$name]:-}" ]]; then
    printf 'run-workloads: unknown target %s\n' "$name" >&2
    exit 2
  fi
done

missing=false
for name in "${selected[@]}"; do
  if [[ ! -d "$targets_root/$name/.git" ]]; then
    missing=true
  fi
done
if [[ "$missing" == true ]]; then
  "$root/bench/fetch-targets.sh"
fi

for name in "${selected[@]}"; do
  checkout="$targets_root/$name"
  actual="$(git -C "$checkout" rev-parse HEAD)"
  if [[ "$actual" != "${pinned[$name]}" ]]; then
    printf 'run-workloads: %s is at %s, expected %s\n' "$name" "$actual" "${pinned[$name]}" >&2
    exit 2
  fi
  if [[ -n "$(git -C "$checkout" status --porcelain --untracked-files=no)" ]]; then
    printf 'run-workloads: tracked files are modified in %s; refusing to overwrite them\n' "$checkout" >&2
    exit 2
  fi
done

mkdir -p "$out_root"
printf '%s\n' "$out_root" > "$out_base/latest-path.txt"
{
  printf 'workingDirectory=%s\n' "$root"
  printf 'command='
  printf '%q ' "$root/mvnw" -B -pl ghostmap-probe -am package -DskipTests
  printf '\n'
} > "$out_root/agent-build-command.txt"
"$root/mvnw" -B -pl ghostmap-probe -am package -DskipTests \
  > "$out_root/agent-build.stdout.log" 2> "$out_root/agent-build.stderr.log"
if [[ ! -f "$agent" ]]; then
  printf 'run-workloads: shaded agent was not produced at %s\n' "$agent" >&2
  exit 1
fi

"$java_bin" -version > "$out_root/java-version.stdout.txt" 2> "$out_root/java-version.stderr.txt"
printf 'JAVA_HOME=%s\njava=%s\nagent=%s\n' "$JAVA_HOME" "$java_bin" "$agent" \
  > "$out_root/environment.txt"

run_target() {
  local name="$1"
  local checkout="$targets_root/$name"
  local run_dir="$out_root/$name"
  local runtime_jsonl="$run_dir/runtime-edges.jsonl"
  local probe_log="$run_dir/ghostmap-probe.log"
  local trigger="$run_dir/unused-trigger"
  local summary="$run_dir/summary.json"
  local run_id="$name-$run_stamp"
  local exclude_filter="${target_exclude[$name]}"
  local agent_argument
  local exit_code
  local lingering=false

  mkdir -p "$run_dir"
  agent_argument="include=$include,exclude=$exclude_filter,out=$runtime_jsonl,edgeLimit=$edge_limit,trigger=$trigger,log=$probe_log,runId=$run_id"
  local -a workload=(./mvnw -B "-DargLine=-javaagent:$agent=$agent_argument" test)
  local -a command=(timeout --signal=TERM --kill-after=15s "${timeout_seconds}s" "${workload[@]}")
  {
    printf 'target=%s\n' "$name"
    printf 'commit=%s\n' "${pinned[$name]}"
    printf 'workingDirectory=%s\n' "$checkout"
    printf 'JAVA_HOME=%s\n' "$JAVA_HOME"
    printf 'agent=%s\n' "$agent"
    printf 'include=%s\nexclude=%s\n' "$include" "$exclude_filter"
    printf 'runtimeJsonl=%s\nprobeLog=%s\n' "$runtime_jsonl" "$probe_log"
    printf 'command='
    printf '%q ' "${command[@]}"
    printf '\n'
  } > "$run_dir/run.txt"

  printf 'Running %s at %s\n' "$name" "${pinned[$name]}"
  setsid bash -c 'cd "$1"; shift; exec "$@"' _ "$checkout" "${command[@]}" \
    > "$run_dir/target.stdout.log" 2> "$run_dir/target.stderr.log" &
  active_pid=$!
  set +e
  wait "$active_pid"
  exit_code=$?
  set -e
  if kill -0 -- "-$active_pid" 2>/dev/null; then
    lingering=true
    cleanup_active_process
  else
    active_pid=""
  fi
  {
    printf 'exitCode=%s\n' "$exit_code"
    printf 'processGroupReaped=%s\n' "$([[ "$lingering" == false ]] && printf true || printf false)"
  } >> "$run_dir/run.txt"

  if [[ $exit_code -ne 0 ]]; then
    printf 'run-workloads: %s exited with %s; see %s\n' "$name" "$exit_code" "$run_dir" >&2
    return "$exit_code"
  fi
  if [[ "$lingering" == true ]]; then
    printf 'run-workloads: %s left child processes; they were terminated\n' "$name" >&2
    return 1
  fi
  if [[ ! -s "$runtime_jsonl" ]]; then
    printf 'run-workloads: %s produced no runtime JSONL\n' "$name" >&2
    return 1
  fi
  if grep -E 'ghostmap-probe|INSTRUMENTATION_INSTALLED|DUMP reason=|DISABLED agent' \
      "$run_dir/target.stdout.log" "$run_dir/target.stderr.log" >/dev/null; then
    printf 'run-workloads: probe diagnostics leaked into %s target streams\n' "$name" >&2
    return 1
  fi

  "$root/bench/summarize-runtime.py" "$runtime_jsonl" --json-out "$summary" \
    > "$run_dir/summary.stdout.json"
  python3 - "$summary" <<'PY'
import json
import pathlib
import sys
summary = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary["distinctPairs"] < 1 or summary["hitTotal"] < 1:
    raise SystemExit("workload recorded no runtime edges")
PY
  printf 'Completed %s: %s\n' "$name" "$(tr '\n' ' ' < "$summary")"
}

for name in "${selected[@]}"; do
  run_target "$name"
done

printf 'All workloads completed; outputs: %s\n' "$out_root"
