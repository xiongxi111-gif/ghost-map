---
inclusion: always
---
# Tech stack

## Core (analysis engine, MCP server, runtime probe)
- **Java 25 (LTS)** for `ghostmap-core`, `ghostmap-cli`, `ghostmap-mcp`. Needed by the official tree-sitter Java
  bindings (`io.github.tree-sitter:jtreesitter`, FFM API, JDK 23+). If native bindings cause trouble on a
  platform, fall back to `bonede/tree-sitter-ng` (JDK 8+) behind the same `SourceLayer` interface.
- **Build:** Gradle (Kotlin DSL), multi-module. Reproducible builds; `./gradlew check` must pass before commit.
- **L1 Source:** tree-sitter + tree-sitter-java grammar; queries in `.scm` files under `resources/queries/`.
- **L2 Bytecode:** **ASM 9.x** (`org.ow2.asm:asm`, `asm-tree`). Read class files/JARs only; never load classes.
- **L3 Wiring:** our own resolver. XML via StAX (no Spring on the classpath); annotations read from bytecode
  via ASM. We do **not** boot the target application's Spring context.
- **L4 Runtime:** `ghostmap-probe` Java agent, compiled with `--release 17` so it attaches to older targets.
  Byte Buddy (shaded) for instrumentation, include/exclude package filters, low overhead; writes
  `runtime-edges.jsonl`. JFR is an optional secondary source (sampled stacks), not the primary one.
- **MCP:** official MCP Java SDK (`io.modelcontextprotocol.sdk`), stdio transport first, streamable HTTP second.
- **Storage:** graph snapshot as versioned JSON (`snapshot.ghostmap.json`, schema in design.md) + an in-memory
  index. No external database.
- **JSON:** Jackson. **CLI:** picocli.

## Web UI ("séance room")
- **TypeScript + Vite + React**, graph rendering with **Cytoscape.js**.
- Ships as a **static site** that loads published snapshots and cards → deploy to GitHub Pages (working app URL).
- Haunted theme via CSS variables; must stay readable (WCAG AA contrast) and support `prefers-reduced-motion`.

## Testing
- **Property-based tests: jqwik** for the join policy, confidence algebra, ID normalisation and card
  construction. **fast-check** for UI data transforms.
- JUnit 5 for examples; golden-file tests on `fixtures/haunted-house` ground truth.
- Every join-policy rule has at least one property test.

## Conventions
- Java: records for immutable model types, `sealed` interfaces for edge/evidence kinds, no nulls in the model
  (use `Optional`), no static mutable state.
- IDs: JVM-descriptor based (see design.md §Node identity). Never key by simple name.
- Logging: SLF4J; the probe logs nothing to stdout of the target app.
- Dependencies need a one-line justification in the PR; prefer the JDK.

## Licence
Apache-2.0 for all code. Third-party deps must be Apache-2.0/MIT/BSD compatible.
