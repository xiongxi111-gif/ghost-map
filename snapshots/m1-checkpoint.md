# M1 checkpoint: source-only versus source + bytecode

Measured on 2026-11-22 with the real `ghostmap index` command. These are graph counts, not benchmark recall or
precision claims. Wiring, runtime evidence, R05–R08, R10, and the benchmark command are not implemented yet.

## Haunted house

Commands, from the repository root:

```bash
./mvnw -B -f fixtures/haunted-house/pom.xml verify
./mvnw -q -pl ghostmap-cli -am install -DskipTests
./mvnw -q -f ghostmap-cli/pom.xml exec:java \
  -Dexec.args="index --src fixtures/haunted-house/src/main/java --ast-only --out snapshots/haunted-house-ast.ghostmap.json"
./mvnw -q -f ghostmap-cli/pom.xml exec:java \
  -Dexec.args="index --src fixtures/haunted-house/src/main/java --classes fixtures/haunted-house/target/classes --out snapshots/haunted-house-static.ghostmap.json"
```

| Snapshot | Layers | Nodes | Edges | Generated nodes | Unmaterialized source calls |
|---|---:|---:|---:|---:|---:|
| `haunted-house-ast.ghostmap.json` | AST | 50 | 7 | 0 | 25 |
| `haunted-house-static.ghostmap.json` | AST + BYTECODE | 109 | 67 | 19 | 5 |

The L1/L2 snapshot adds 59 nodes and 60 edges. Comparing `(kind, from, to)` rather than edge ids gives 63 static-only
semantic edges, 1 common edge, and 6 AST-only candidate edges that bytecode/R04 replaces with more specific targets.
The static snapshot contains 36 AST+BYTECODE edges and 31 BYTECODE-only edges; 48 are `CANDIDATE` and 19 are
`PREDICTED`.

Concrete differences:

- `Spirit#getName()Ljava/lang/String;`, `setName(Ljava/lang/String;)V`, `getId()J`, and `setId(J)V` exist only after
  Lombok processing. They are present with `generated=true` in the static snapshot.
- The source calls to `Spirit#getName`, `setName`, and `setId` join to those generated methods with rules
  `R02`, `R03`, and `R04`; representative confidence is `0.9325` from AST `0.55` plus bytecode `0.85` noisy-OR.
- Lombok-generated constructors such as `HauntingService#<init>(SpiritRepository,ApparitionAnnouncer)` and
  `JdbcSpiritRepository#<init>(JdbcTemplate)` are absent from the AST-only snapshot and present as generated nodes.
- `HauntingService#recordSighting` calling `ApparitionAnnouncer#announce` expands to the two in-scope implementations.
  Each candidate has bytecode weight `0.85 / 2 = 0.425` and combined confidence `0.74125`. Qualifier narrowing is R05
  and is deliberately not claimed at M1.

The checkpoint exposed and fixed three real static-layer issues instead of hiding them in the CLI:

1. AST-only internal calls now resolve a unique `(receiver owner, name, arity)` declaration, while same-arity overloads
   remain ambiguous rather than being guessed.
2. Multiline source calls use the method-name line for R02 matching; this removed false stale-build line drift seen in
   both public targets.
3. Methods declared in anonymous classes keep a provisional owner and reconcile to bytecode owners such as `Outer$1`.
   Generic abstract methods can reconcile uniquely when bytecode has no line table.

## Public Petclinic targets

`bench/fetch-targets.sh` fetches detached, pinned public commits into ignored `bench/targets/`:

| Target | Pinned commit | Maven build | Static snapshot | Nodes | Edges | Generated |
|---|---|---|---|---:|---:|---:|
| `spring-projects/spring-petclinic` | `500158f732419217507c7656904b8e6aa1bcc0d6` | success | `spring-petclinic-static.ghostmap.json` | 305 | 317 | 28 |
| `spring-petclinic/spring-framework-petclinic` | `d980667952cdfd9c69e87cce2efadc89938af6f0` | success | `spring-framework-petclinic-static.ghostmap.json` | 470 | 464 | 37 |

Reproduction:

```bash
bench/fetch-targets.sh
(cd bench/targets/spring-petclinic && ./mvnw -B -DskipTests package)
(cd bench/targets/spring-framework-petclinic && ./mvnw -B -DskipTests package)
./mvnw -q -f ghostmap-cli/pom.xml exec:java \
  -Dexec.args="index --src bench/targets/spring-petclinic/src/main/java --classes bench/targets/spring-petclinic/target/classes --out snapshots/spring-petclinic-static.ghostmap.json"
./mvnw -q -f ghostmap-cli/pom.xml exec:java \
  -Dexec.args="index --src bench/targets/spring-framework-petclinic/src/main/java --classes bench/targets/spring-framework-petclinic/target/classes --out snapshots/spring-framework-petclinic-static.ghostmap.json"
```

The fetched source trees and their build output remain untracked. Each snapshot records its target commit, a clean
source state, and a SHA-256 hash of the analysed class files.
