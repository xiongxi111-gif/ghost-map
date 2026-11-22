# Pinned Petclinic probe workloads

This report records the final successful Task 13 run on 2026-11-22. It reports runtime observations only; no
R06/R08 join, phantom classification, recall calculation, or benchmark claim was performed.

## Environment and build

- JDK: Temurin OpenJDK `25.0.5.1+2-LTS`, `JAVA_HOME=/home/box/.local/jvm/temurin-25`.
- Agent: `/workspace/ghost-map/ghostmap-probe/target/ghostmap-probe-agent.jar`.
- Agent build working directory: `/workspace/ghost-map`.
- Agent build command:

  ```text
  /workspace/ghost-map/mvnw -B -pl ghostmap-probe -am package -DskipTests
  ```

- Final local output root (gitignored):
  `/workspace/ghost-map/bench/out/workloads/20261122T052652Z`.
- Both workloads used `include=org.springframework.samples`,
  `exclude=org.springframework.samples.petclinic.generated`, and `edgeLimit=250000`.
- `bench/run-workloads.sh` ran each Maven wrapper in a separate process group under a 1,200-second timeout. Both
  process groups exited normally and were reaped. A post-run JVM check found no Petclinic or Surefire process.

## Exact target commands

### spring-petclinic

- Commit: `500158f732419217507c7656904b8e6aa1bcc0d6`.
- Working directory: `/workspace/ghost-map/bench/targets/spring-petclinic`.
- Command:

  ```text
  timeout --signal=TERM --kill-after=15s 1200s ./mvnw -B -DargLine=-javaagent:/workspace/ghost-map/ghostmap-probe/target/ghostmap-probe-agent.jar=include=org.springframework.samples,exclude=org.springframework.samples.petclinic.generated,out=/workspace/ghost-map/bench/out/workloads/20261122T052652Z/spring-petclinic/runtime-edges.jsonl,edgeLimit=250000,trigger=/workspace/ghost-map/bench/out/workloads/20261122T052652Z/spring-petclinic/unused-trigger,log=/workspace/ghost-map/bench/out/workloads/20261122T052652Z/spring-petclinic/ghostmap-probe.log,runId=spring-petclinic-20261122T052652Z test
  ```

- Exit code: `0`; Maven reported 79 tests, 0 failures, 0 errors, and 2 skipped.
- Probe interval: `2026-11-22T05:27:00.437200749Z` to `2026-11-22T05:27:08.938459234Z`.
- Output: `spring-petclinic/runtime-edges.jsonl`; probe diagnostics:
  `spring-petclinic/ghostmap-probe.log` under the output root above.

### spring-framework-petclinic

- Commit: `d980667952cdfd9c69e87cce2efadc89938af6f0`.
- Working directory: `/workspace/ghost-map/bench/targets/spring-framework-petclinic`.
- Command:

  ```text
  timeout --signal=TERM --kill-after=15s 1200s ./mvnw -B -DargLine=-javaagent:/workspace/ghost-map/ghostmap-probe/target/ghostmap-probe-agent.jar=include=org.springframework.samples,exclude=org.springframework.samples.petclinic.generated,out=/workspace/ghost-map/bench/out/workloads/20261122T052652Z/spring-framework-petclinic/runtime-edges.jsonl,edgeLimit=250000,trigger=/workspace/ghost-map/bench/out/workloads/20261122T052652Z/spring-framework-petclinic/unused-trigger,log=/workspace/ghost-map/bench/out/workloads/20261122T052652Z/spring-framework-petclinic/ghostmap-probe.log,runId=spring-framework-petclinic-20261122T052652Z test
  ```

- Exit code: `0`; Maven reported 75 tests, 0 failures, 0 errors, and 0 skipped.
- Probe interval: `2026-11-22T05:27:11.124770957Z` to `2026-11-22T05:27:16.376449174Z`.
- Output: `spring-framework-petclinic/runtime-edges.jsonl`; probe diagnostics:
  `spring-framework-petclinic/ghostmap-probe.log` under the output root above.

## Recorded event coverage

Each target produced one shutdown record with schema `ghostmap.runtime/v1`, run id, start/end timestamps, filter,
edge limit, drop count, and method-identity edges containing positive hit counts and dispatch kinds.

| Target | Distinct pairs | Total hits | Dispatch pairs | Dispatch hits | Dropped events |
|---|---:|---:|---|---|---:|
| spring-petclinic | 746 | 2,771 | REFLECTION 704; UNKNOWN 36; LAMBDA 6 | REFLECTION 2,713; UNKNOWN 50; LAMBDA 8 | 0 |
| spring-framework-petclinic | 417 | 2,113 | REFLECTION 407; UNKNOWN 10 | REFLECTION 2,095; UNKNOWN 18 | 0 |

Packages represented by caller or callee identities:

- spring-petclinic: `org.springframework.samples.petclinic`,
  `org.springframework.samples.petclinic.model`, `org.springframework.samples.petclinic.owner`,
  `org.springframework.samples.petclinic.service`, `org.springframework.samples.petclinic.system`, and
  `org.springframework.samples.petclinic.vet`.
- spring-framework-petclinic: `org.springframework.samples.petclinic.model`,
  `org.springframework.samples.petclinic.repository.jdbc`, `org.springframework.samples.petclinic.repository.jpa`,
  `org.springframework.samples.petclinic.service`, `org.springframework.samples.petclinic.util`, and
  `org.springframework.samples.petclinic.web`.

The target stdout/stderr logs contained Maven, test, application, and JVM output, but no Ghost Map probe diagnostics.
`STARTED`, `INSTRUMENTATION_INSTALLED`, and `DUMP` messages appeared only in each probe-owned log.

## Honest limits and uncovered behavior

- These runs used each project's test suite. They did not start an HTTP server or execute the JMeter plans.
- Two spring-petclinic tests were skipped by that project's own test configuration.
- No `PROXY`, `DYNAMIC`, or `REGISTRY` dispatch was observed in either final run. No `LAMBDA` dispatch was observed
  in spring-framework-petclinic.
- The high `REFLECTION` count includes calls whose live stack retained JUnit's reflective test invocation. It must
  not be interpreted as a count of application-level reflective call sites.
- `org.springframework.samples.petclinic.generated` was excluded. The agent also ignores transient Mockito mock and
  Byte Buddy auxiliary classes rather than treating generated test doubles as application methods.
- The package list describes only methods reached by these workloads. Absence from the list does not mean a package
  has no executable calls.

During setup, preliminary runs exposed unsupported `package-info` and transient Mockito auxiliary transformations.
Those preliminary records were not used for the table. The agent's type filter now ignores package/module
descriptors and generated Mockito/auxiliary classes; the final two runs completed without probe disablement.
