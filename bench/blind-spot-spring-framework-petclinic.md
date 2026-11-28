# First framework-petclinic blind-spot checkpoint

This is the first Task 14 blind-spot report for the pinned public
`spring-petclinic/spring-framework-petclinic` checkout. It joins real L1/L2 analysis with the real Task 13 probe
trace. It does not run R10, build transaction cards, extrapolate recall, or claim security findings.

## Inputs and environment

- Target commit: `d980667952cdfd9c69e87cce2efadc89938af6f0` (clean detached checkout).
- Target repository: `https://github.com/spring-petclinic/spring-framework-petclinic.git`.
- JDK: Temurin OpenJDK `25.0.5.1+2-LTS`; `JAVA_HOME=/home/box/.local/jvm/temurin-25`.
- Source input: `bench/targets/spring-framework-petclinic/src/main/java`.
- Bytecode inputs: `bench/targets/spring-framework-petclinic/target/classes` and `target/test-classes`.
  Test bytecode is included because the probe workload executes methods from the test suite in the same package
  filter. L1 remains the application `src/main/java` tree.
- Runtime input: `bench/out/workloads/20261122T052652Z/spring-framework-petclinic/runtime-edges.jsonl`.
  Task 13 recorded 417 distinct pairs and 2,113 hits from 75 passing tests.
- Generated checkpoint snapshot (gitignored, not committed):
  `bench/out/task14-framework-petclinic/spring-framework-petclinic-runtime.ghostmap.json`.
- Snapshot id: `sha256:2b2d87f6c68b3d2b29ea5ab44b83aa891c3f1acdb527cd83719651b6d98a7873`.
- Analysed class hash: `sha256:eaba9e3b863f45896f93ae66948acd9c29517aaf598f8f0925aa03d7eed3127a`.

## Exact commands

Working directory for both commands: `/workspace/ghost-map`.

```text
JAVA_HOME=/home/box/.local/jvm/temurin-25 ./mvnw -B -pl ghostmap-cli -am install -DskipTests
```

```text
JAVA_HOME=/home/box/.local/jvm/temurin-25 ./mvnw -B -f ghostmap-cli/pom.xml exec:java -Dexec.args="index --src /workspace/ghost-map/bench/targets/spring-framework-petclinic/src/main/java --classes /workspace/ghost-map/bench/targets/spring-framework-petclinic/target/classes,/workspace/ghost-map/bench/targets/spring-framework-petclinic/target/test-classes --runtime /workspace/ghost-map/bench/out/workloads/20261122T052652Z/spring-framework-petclinic/runtime-edges.jsonl --out /workspace/ghost-map/bench/out/task14-framework-petclinic/spring-framework-petclinic-runtime.ghostmap.json"
```

Both commands exited `0`. The index wrote 725 nodes and 1,548 edges with layers `AST`, `BYTECODE`, and `RUNTIME`.

## Report values

### Edges by provenance

| Provenance | Edges |
|---|---:|
| AST + BYTECODE | 253 |
| AST + BYTECODE + RUNTIME | 66 |
| BYTECODE | 878 |
| BYTECODE + RUNTIME | 83 |
| RUNTIME | 268 |
| **Total** | **1,548** |

### Unresolved sites and phantoms

| Unresolved category | Sites |
|---|---:|
| GETBEAN | 0 |
| REFLECTION | 0 |
| REGISTRY | 0 |
| PROXY | 0 |
| OTHER | 0 |

- Phantom runtime edges: **268**.
- Runtime pairs confirmed against a static edge: **149**.
- Conservation check: **149 confirmed + 268 phantom = 417 runtime pairs**.
- No phantom could be linked to an unresolved site because this join emitted no unresolved sites.

### Exercised-only recall

The denominator is the 417 distinct caller→callee pairs in the real runtime trace.

- AST-only recall on exercised pairs: **66 / 417 = 0.15827338129496402** (15.83%).
- Ghost Map static recall on exercised pairs: **149 / 417 = 0.35731414868105515** (35.73%).
- Exercised method share: **172 / 499 = 0.34468937875751503** (34.47%).

The snapshot caveat is: “Recall is measured only over code exercised by the recorded workloads and must not be
extrapolated to un-exercised code.” Nine source call facts lacked canonical endpoints and remain outside snapshot
edges.

## Limits

- The workload is the project's test suite only. It did not start the web application or execute HTTP/JMeter calls.
- Runtime `REFLECTION` includes JUnit invocation ancestors and is not an application-reflection count.
- The join used L1, L2, and L4. It did not include L3 wiring, so the 35.73% figure is not a claim about the eventual
  four-layer map.
- Static recall is measured only against the 417 exercised pairs. It says nothing about un-exercised code.
- A phantom means the joined static inputs did not predict that exercised pair. It does not by itself identify why.
- The committed M1 static snapshot was not modified; this checkpoint snapshot and raw logs remain under ignored
  `bench/out/`.
