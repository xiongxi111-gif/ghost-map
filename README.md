# 👻 Ghost Map

**An evidence-graded map of Java/Spring codebases for AI coding agents.**

> Kiroween 2026 · Category: **Frankenstein** — four analysis techniques that were never meant to share a body
> (a tree-sitter parser, a JVM bytecode reader, a Spring wiring resolver, and a live JVM probe), stitched
> together by one *join policy* and brought to life for agents through MCP.

Status: M2 wiring (XML/annotation analysis plus R05/R07 join rules complete; MCP remains Task 11). **Pre-existing code: none.** All code in this repository is written from scratch during the
Kiroween submission period. Demos use public open-source Java projects only.

---

## The problem

AI coding agents navigate codebases with maps that are built from **source text**: grep, embeddings, or a
tree-sitter syntax tree. In enterprise Java that map has holes in exactly the places that matter:

| What the source text says | What actually runs |
|---|---|
| `orderService.place(o)` on an interface | One of N implementations, chosen by Spring wiring |
| `@Data class Owner` (Lombok) | Getters/setters that exist only in bytecode |
| `<bean parent="baseDao">` in XML | Inherited properties and injected collaborators |
| `ctx.getBean(name)`, reflection, string-keyed handler registries | Targets known only at runtime |

An agent that trusts a source-only map **doesn't know what it doesn't know**. It will confidently
"trace" a call path that skips a hop, or edit the wrong implementation.

## The idea

Ghost Map builds the map from **four layers of evidence** and tells the agent how much to trust every edge.

| Layer | Technique | Catches |
|---|---|---|
| L1 Source | tree-sitter Java AST | declarations, call sites, file:line, comments, string literals |
| L2 Bytecode | ASM class-file analysis | Lombok/generated members, synthetic bridges, inheritance, real `invoke*` owner/descriptor |
| L3 Wiring | Spring XML + annotation resolver | `ref`/`property`, `parent` bean inheritance, `@Autowired`/constructor injection, `@Qualifier`/`@Primary` |
| L4 Runtime | Java agent probe | caller→callee pairs that really executed under a workload |

A **join policy** merges the layers into one graph. Every edge carries:

- **provenance** — which layers saw it (`AST`, `BYTECODE`, `WIRING`, `RUNTIME`)
- **confidence** — a score in [0,1] derived from the provenance by explicit, tested rules
- **evidence** — file:line, class-file offset, XML location, or runtime sample counts

Dynamic dispatch the static layers can't resolve (`getBean(var)`, reflection, string-keyed registries) becomes
an explicit **unresolved edge** ("a ghost"), never a silent gap. Runtime calls that no static layer predicted are
reported as **phantom edges**, and their share is the map's measured blind spot.

On top of the graph, **transaction cards** answer the question agents and humans actually ask:
*"what happens when this endpoint is called?"* Entry → controller → service → DAO/DB or external call, with
file:line evidence for each hop, aspects (external calls, config keys, branches, transactions) attached to
nodes, and the code version (git commit + build hash) the card was computed from.

Everything is exposed to agents through an **MCP server**, and a small haunted web UI ("the séance room")
lets humans explore the graph and cards.

## How Ghost Map compares (honest version)

We checked each project's public docs on 2026-11-14. These are complementary tools; several of them are
excellent at things Ghost Map does not try to do.

| Tool | What it does (per its docs) | Where Ghost Map differs |
|---|---|---|
| [Aider repo map](https://aider.chat/docs/repomap.html) | Builds a concise map of key classes/functions and signatures (tree-sitter), ranks it with a graph-ranking algorithm over file dependencies, and fits it to a token budget for the LLM. | Repo map is a *context-selection* tool over source symbols. It does not model call edges with evidence, Spring wiring, bytecode-only members, or runtime calls. Ghost Map is a call/wiring graph with per-edge confidence. |
| [Code-Graph-RAG](https://github.com/vitali87/code-graph-rag) | Tree-sitter-based multi-language knowledge graph in an embedded graph DB; opt-in compiler frontends (incl. `javac` for Java); `cgr trace` merges runtime-observed calls; natural-language Q&A and editing; MCP server. MIT. | Closest neighbour — it already combines tree-sitter, a compiler frontend and runtime traces. Ghost Map is deliberately narrower and deeper for **JVM/Spring**: a **bytecode** layer (post-Lombok, post-annotation-processing), a **Spring wiring** layer (XML `ref`/`parent`, annotations) that resolves interface calls to wired beans, **graded confidence + provenance on every edge**, explicit unresolved/phantom edges with a **blind-spot report**, and **transaction cards**. We did not find Spring wiring or per-edge confidence in its README; we will re-verify before submission. |
| [SCIP](https://github.com/sourcegraph/scip) / [scip-java](https://github.com/sourcegraph/scip-java) / [LSIF](https://microsoft.github.io/language-server-protocol/specifications/lsif/0.6.0/specification/) · [Sourcegraph precise navigation](https://sourcegraph.com/docs/code-search/code-navigation/precise_code_navigation) | Language-agnostic code-intelligence index formats (SCIP is protobuf-based; LSIF is its predecessor) powering go-to-definition, find-references and find-implementations. scip-java builds the index with the compiler. | Compiler-precise **symbol/reference** data, but no notion of which implementation Spring wires in, no runtime calibration, no confidence, no transaction-level view. Ghost Map could *import* SCIP as an extra evidence layer later (stretch goal). |
| [CodeQL](https://codeql.github.com/docs/) ([Java guide](https://codeql.github.com/docs/codeql-language-guides/codeql-for-java/)) | Semantic analysis engine: extracts a relational database from a build and lets you query code as data (QL), mainly for security/dataflow analysis. Licensing restricts use to OSS/research unless licensed ([CLI license](https://github.com/github/codeql-cli-binaries/blob/main/LICENSE.md)). | Very powerful static analysis, but a query language for humans/security teams, not a confidence-graded navigation map for agents; no runtime calibration layer. Ghost Map is permissively licensed and agent-first. |
| [OpenLineage](https://openlineage.io/docs/) | Open standard for **data** lineage: a model of jobs, runs and datasets with extensible "facets", emitted by schedulers and data frameworks. | Different domain (data pipelines, not code call graphs). We borrow two ideas: *facets* for attaching aspects to nodes, and *run-level provenance* for recording where each fact came from. |

**What we claim, and how we will prove it.** We do not claim numbers until we measure them. The benchmark in
[`docs/demo-script.md`](docs/demo-script.md) runs an *AST-only* baseline and Ghost Map on the same public
projects and reports, for runtime-observed call edges: how many each approach predicted, how many it missed,
and how many unresolved sites it flagged honestly. Results will be published with the scripts to reproduce them.

## Demo targets (public, Apache-2.0)

- [spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic) — modern Spring Boot, annotations
- [spring-petclinic/spring-framework-petclinic](https://github.com/spring-petclinic/spring-framework-petclinic) — plain Spring Framework with XML config (`src/main/resources/spring/*.xml`)
- `fixtures/haunted-house` — a small synthetic app we write ourselves with known ground-truth edges
  (Lombok, parent beans, `getBean` by name, a reflection call, a string-keyed handler registry)

## Pre-existing code and dependencies

**Pre-existing code: none.** Everything in this repository was written from scratch during the submission period.
Third-party libraries are used only as declared dependencies, never copied in. Their licences are listed below.

| Where | Dependency | Why |
|---|---|---|
| all Java modules (test) | JUnit 5 (`junit-jupiter`, EPL-2.0), jqwik (EPL-2.0) | example and property-based tests |
| `ghostmap-core` | tree-sitter-ng + tree-sitter-java (MIT), ASM (BSD-3-Clause) | L1 Java parsing with bundled natives; L2 class/JAR reading without class loading |
| `ghostmap-core` (test) | Lombok (MIT), Spring annotations (Apache-2.0), Jakarta Inject/Annotations (Apache-2.0) | compile self-contained generated-member and annotation-wiring bytecode samples |
| `ghostmap-cli` | picocli (Apache-2.0) | validated commands, usage help, and process exit codes |
| `fixtures/haunted-house` | Spring Framework (Apache-2.0), Lombok (MIT) | synthetic wiring, proxy, JDBC, and generated-member target |
| `fixtures/haunted-house` (test) | Spring Test (Apache-2.0), H2 (MPL-2.0/EPL-1.0), Jackson (Apache-2.0), JUnit 5 (EPL-2.0) | executable fixture and ground-truth validation |
| `web/` | React, React DOM (MIT) | UI |
| `web/` (build) | Vite, `@vitejs/plugin-react` (MIT), TypeScript (Apache-2.0), `@types/react*` (MIT) | static build |

Planned, added by the task that first needs them (see [`tasks.md`](.kiro/specs/ghost-map-core/tasks.md)):
Byte Buddy (Apache-2.0),
MCP Java SDK (MIT), Cytoscape.js (MIT), fast-check (MIT).

L1 uses the permitted `bonede/tree-sitter-ng` fallback behind `SourceLayer`. The official jtreesitter artifact
requires separately installed native tree-sitter and Java grammar libraries; it does not provide a self-contained
Maven/CI runtime. tree-sitter-ng packages those native libraries for the supported CI platforms, avoiding an
undeclared system installation.

The fixed-order static join runs with `new JoinPolicy().join(sourceFacts, bytecodeFacts, wiringFacts)`; wiring facts
are optional, so existing L1/L2-only calls remain valid. R05 narrows wired virtual candidates, while R07 emits a
resolved or explicit `UNRESOLVED` edge for each detected dynamic bytecode site. The result is a `JoinedGraph`
containing canonical model nodes, scored edges, unresolved provisional nodes, and diagnostics. The `index` command
adds target version metadata and serialises it as `ghostmap.snapshot/v1`.

Run XML and annotation wiring together with
`new WiringLayer().analyse(new LayerInput(Optional.empty(), classRoots, resourceRoots))`; use
`LayerInput.forResourceRoots(resourceRoot)` for XML-only analysis. The layer uses secure JDK StAX plus direct ASM
metadata reads. It never loads target classes, boots Spring, or fetches external XML resources. Wiring is not
included in the default `index` command yet, so the committed M1 snapshots remain unchanged.

## Build

Requires JDK 25 and Node 22+.

```
./mvnw -B verify                                     # analysis engine modules
./mvnw -B -f fixtures/haunted-house/pom.xml verify  # standalone synthetic fixture
cd web && npm ci && npm run build
```

CI (`.github/workflows/ci.yml`) runs all three.

## Index CLI

Build/install the reactor artifacts once, then run the configured picocli main class:

```bash
./mvnw -q -pl ghostmap-cli -am install -DskipTests
./mvnw -q -f ghostmap-cli/pom.xml exec:java \
  -Dexec.args="index --src fixtures/haunted-house/src/main/java --classes fixtures/haunted-house/target/classes --out snapshots/haunted-house-static.ghostmap.json"
```

Omit `--classes` for an AST-only snapshot. `--classes` accepts comma-separated class directories or JARs.
`scripts/regen-snapshot.sh` wraps the same command and explicitly skips the not-yet-implemented benchmark/diff steps.

## Repository layout

See [`.kiro/steering/structure.md`](.kiro/steering/structure.md). Specs live in
[`.kiro/specs/ghost-map-core/`](.kiro/specs/ghost-map-core/).

## Built with Kiro

See [`docs/kiro-usage.md`](docs/kiro-usage.md) for how specs, steering, hooks, MCP and property-based testing
were used.

## License

Apache-2.0. See [LICENSE](LICENSE).
