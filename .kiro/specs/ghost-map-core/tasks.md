# Implementation Plan — Ghost Map Core

Deadline: **Fri Dec 4, 2026 2:00 PM PT** (Beijing Sat Dec 5 06:00). Internal target: submit by **Dec 4 12:00 PT**.
Kiro credit request deadline: Tue Dec 1, 12:00 PM PT — request in week 1, not at the end.
Dates below are Beijing dates. Each task lists the requirements it satisfies.

## M0 — Foundation (Nov 14–16)
- [x] 1. Create public repo, Apache-2.0 LICENSE, Maven multi-module skeleton, CI (`./mvnw -B verify`, web build)
  - Commit `/.kiro` (steering, specs, hooks, settings); verify it is not gitignored
  - Record "pre-existing code: none" in README; declare dependencies
  - _Requirements: 11.1, 11.2_
- [x] 2. Model module: node/edge/evidence records, canonical ids, snapshot JSON (de)serialisation
  - [x] 2.1 Id formatter/parser with jqwik round-trip property (inner/anonymous classes, arrays, primitives)
  - [x] 2.2 Confidence algebra (noisy-OR) with order-independence, bounds and monotonicity properties
  - _Requirements: 5.1, 5.3, 5.7, 5.8_
- [x] 3. Write `fixtures/haunted-house` (synthetic, from scratch) + `ground-truth.json`
  - Lombok `@Data`/`@RequiredArgsConstructor`, interface with 2 impls + `@Qualifier`, XML `parent` bean,
    `getBean("name")`, `getBean(var)`, `Method.invoke`, string-keyed handler map, `@Transactional` proxy, JDBC call
  - _Requirements: 10.1_

## M1 — Static layers L1 + L2 (Nov 17–20)
- [x] 4. L1 SourceLayer with tree-sitter (jtreesitter; fallback tree-sitter-ng)
  - declarations, invocations, annotations, string literals at dynamic sites, best-effort receiver typing
  - parse-error diagnostics
  - _Requirements: 1.1–1.4_
- [x] 5. L2 BytecodeLayer with ASM: members, `invoke*` edges with line numbers, hierarchy, annotations, generated flags,
  dynamic-site detection
  - _Requirements: 2.1–2.5_
- [x] 6. Join rules R01 Identity, R02 Call match, R03 Generated members, R04 Virtual expansion, R09 Conflicts
  - one jqwik `*Properties` class per rule
  - _Requirements: 5.1–5.4, 5.7, 5.8_
- [x] 7. CLI `ghostmap index --src --classes --out` producing a snapshot for haunted-house and both petclinics
  - **Checkpoint:** AST-only vs AST+bytecode diff visible on the fixture.

## M2 — Wiring layer L3 + dynamic sites (Nov 21–24)
- [x] 8. XML wiring: beans, aliases, `import`, `ref`/`property`/`constructor-arg`, inner beans, `parent` inheritance
  - _Requirements: 3.1–3.3, 3.6_
- [x] 9. Annotation wiring: stereotypes, `@Bean` methods, `@Autowired`/`@Inject`/`@Resource`/constructor injection,
  `@Qualifier`/`@Primary`; UNRESOLVED on 0 or >1 candidates
  - _Requirements: 3.4, 3.5_
- [x] 10. Join rules R05 Wiring narrowing and R07 Dynamic sites (constant-key resolution via bean/registry index)
  - property: every dynamic site yields a resolved or UNRESOLVED edge
  - _Requirements: 5.4, 5.5_
- [ ] 11. MCP tool `who_injects` early (useful to dogfood in Kiro while building the rest)
  - _Requirements: 8.1–8.3_

## M3 — Runtime layer L4 (Nov 25–27)
- [ ] 12. `ghostmap-probe` agent (`--release 17`, Byte Buddy shaded): include/exclude filters, shadow stack,
  bounded map, shutdown flush to JSONL, dispatch-kind detection; never writes to target stdout
  - _Requirements: 4.1–4.4_
- [ ] 13. Workload scripts: run petclinic test suites / scripted HTTP calls with the probe attached
- [ ] 14. Join rules R06 Runtime floor and R08 Phantoms; blind-spot report
  - property: conservation of runtime pairs
  - _Requirements: 5.6, 6.1–6.3_
  - **Checkpoint:** first honest blind-spot report on spring-framework-petclinic.

## M4 — Cards + MCP (Nov 28–30)
- [ ] 15. R10 Roles & aspects; entry-point detection (HTTP mappings, XML-mapped controllers, main, scheduled, listeners)
  - _Requirements: 7.1, 7.3_
- [ ] 16. Card builder + JSON-schema validation; gaps for UNRESOLVED; code version on every card
  - card properties: viaEdge exists, valid order, gaps inside hops
  - _Requirements: 7.2, 7.4–7.6_
- [ ] 17. MCP server (stdio): all tools in design.md, truncation, structured errors; register in `.kiro/settings/mcp.json`
  - _Requirements: 8.1–8.4_

## M5 — Web UI + deploy (Dec 1–2)
- [ ] 18. Vite/React/Cytoscape séance room: entry list, graph, card/evidence panel, AST-only toggle, GitHub line links
  - fast-check properties for snapshot → view-model transforms
  - _Requirements: 9.1–9.4_
- [ ] 19. Publish snapshots + cards for the demo targets; deploy to GitHub Pages (working app URL)

## M6 — Benchmark, demo, write-up (Dec 2–4)
- [ ] 20. `bench/run.sh`: pinned commits, AST-only vs Ghost Map on ground truth A and B; publish `bench/results.md`
  - _Requirements: 10.1, 10.2_
- [ ] 21. **Demo-script milestone:** freeze numbers, record ≤3:00 video per `docs/demo-script.md`, upload to YouTube (public)
- [ ] 22. Finish `docs/kiro-usage.md`, README results section, English project description; re-verify comparison claims
- [ ] 23. Submission checklist: repo public, licence visible in About, `/.kiro` present, app URL live, video ≤3:00
  and public, category Frankenstein, pre-existing code disclosure, optional blog post / #KiroweenHackathon26 post

## Stretch (only if ahead)
- [ ] S1. SCIP import as an extra evidence layer
- [ ] S2. Snapshot diff tool (`diff_snapshots`) for "what changed in this card between two commits"
- [ ] S3. JFR stack-sample importer as a second runtime source
