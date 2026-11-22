# Haunted House fixture

This standalone Maven project is a synthetic Spring application used as **Ground truth A**. It is deliberately
small, but its integration tests execute the Java and Spring features that later Ghost Map layers must recover:
Lombok-generated members, qualified interface wiring, XML parent inheritance, constant and variable bean lookup,
reflection, a string-keyed registry, a transactional JDK proxy, and JDBC against an in-memory H2 database.

## Build and test

From the repository root, with JDK 25 at the configured `JAVA_HOME`:

```bash
./mvnw -B -f fixtures/haunted-house/pom.xml verify
```

The fixture compiles with `--release 17`. It remains outside the root Maven reactor so Spring, Lombok, H2, and
fixture-only test dependencies never enter the analysis engine's classpath.

## Ground-truth schema

`ground-truth.json` uses the local schema name `ghostmap.ground-truth/v1`:

- `workload` names the integration test and its canonical `ENTRY` id.
- `requiredCauses` is the complete cause-tag vocabulary for this fixture.
- `edges` is the hand-labelled golden list. Each edge has a stable label, edge kind, canonical `from` and `to`
  ids, an optional canonical bytecode `site`, exact source location, cause tag, dispatch kind, and observability
  hints.
- `to` is `null` only for the variable-name `getBean` site, which is intentionally labelled `UNRESOLVED`.
- `observability.astExpectedToMiss` describes the expected source-only baseline result.
  `runtimeProbeExpected` says whether a suitably filtered JVM probe can observe a method/runtime counterpart.
  These are fixture expectations, not claimed Ghost Map output.
- `location.contains` anchors each line to source text so line-number drift fails deterministically.

Canonical ids follow the formats in the core design: `t:` type, `m:` method, `f:` field, `b:` bean, `e:` entry,
`x:` external target, and `s:` bytecode site. Site offsets are taken from this project's Java 17 class files.
`GroundTruthValidationTest` parses every id, verifies every source file and anchored line, checks nullable targets,
and requires every cause tag. `HauntedHouseIntegrationTest` boots the Spring context and executes the labelled
behaviour against real H2 JDBC and Spring transaction infrastructure.

All fixture code and labels are synthetic and use only haunted-house terminology.
