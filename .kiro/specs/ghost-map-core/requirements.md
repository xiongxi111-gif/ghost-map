# Requirements Document — Ghost Map Core

## Introduction
Ghost Map builds an evidence-graded call/wiring graph of a Java/Spring codebase from four analysis layers
(source AST, bytecode, Spring wiring, runtime probe), merges them with a join policy that assigns provenance and
confidence to every edge, flags what it cannot resolve, derives transaction cards for entry points, and serves all
of this to AI agents over MCP and to humans through a web UI.

Glossary: *node* = a JVM type, method, field, bean, entry point, or external resource; *edge* = a directed
relationship between nodes; *layer* = one analysis technique; *snapshot* = the merged graph for one code version.

## Requirement 1 — Source layer (L1, tree-sitter)
**User story:** As an agent, I want declarations and call sites from source, so that I can point to exact file:line
locations.

#### Acceptance Criteria
1. WHEN the indexer is given a source root THE SYSTEM SHALL parse every `.java` file with tree-sitter and emit
   type, method and field nodes with file, start line and end line.
2. WHEN a method body contains a method invocation THE SYSTEM SHALL emit a `CALLS` edge candidate with the call
   site's file:line and the textual receiver and method name.
3. IF a file fails to parse THEN THE SYSTEM SHALL record a parse-error diagnostic with the file path and continue
   with the remaining files.
4. WHEN a call argument is a string literal passed to a known dynamic-dispatch API THE SYSTEM SHALL record the
   literal as evidence on the call site.

## Requirement 2 — Bytecode layer (L2, class files)
**User story:** As an agent, I want the members and calls that actually exist after compilation, so that Lombok
and generated code are not invisible.

#### Acceptance Criteria
1. WHEN the indexer is given class directories or JARs THE SYSTEM SHALL read each class file without loading it
   into a JVM and emit type, method and field nodes keyed by JVM descriptor.
2. WHEN a method contains an `invokevirtual`, `invokeinterface`, `invokestatic`, `invokespecial` or
   `invokedynamic` instruction THE SYSTEM SHALL emit a `CALLS` edge with owner, name, descriptor and the source line
   from the `LineNumberTable` when present.
3. WHEN a class extends or implements another type THE SYSTEM SHALL emit `EXTENDS` / `IMPLEMENTS` edges.
4. WHEN a member is synthetic, a bridge, or has no matching source declaration THE SYSTEM SHALL mark it
   `generated=true`.
5. WHEN a class, method or field carries annotations THE SYSTEM SHALL record them as node attributes.

## Requirement 3 — Wiring layer (L3, Spring)
**User story:** As an agent, I want to know which bean implementation is actually injected, so that interface calls
resolve to the right class.

#### Acceptance Criteria
1. WHEN Spring XML bean definitions are found THE SYSTEM SHALL emit bean nodes with id, aliases, class, scope and
   XML file:line.
2. WHEN a bean definition uses `ref`, `<property ref=…>`, `<constructor-arg ref=…>` or inner beans THE SYSTEM SHALL
   emit `INJECTS` edges from the owning bean to the referenced bean with the property/argument name.
3. WHEN a bean definition declares `parent` THE SYSTEM SHALL resolve inherited class, properties and references
   and record the inheritance chain as evidence.
4. WHEN a class is a component (stereotype or `@Bean` factory method) THE SYSTEM SHALL emit a bean node and resolve
   `@Autowired`, `@Inject`, `@Resource` and constructor injection by type, honouring `@Qualifier` and `@Primary`.
5. IF an injection point has zero or more than one candidate after qualifiers THEN THE SYSTEM SHALL emit an
   `UNRESOLVED` edge listing the candidates.
6. WHEN a bean is bound to a type THE SYSTEM SHALL emit a `BINDS` edge from the bean to the implementing type.

## Requirement 4 — Runtime layer (L4, JVM probe)
**User story:** As a maintainer, I want to record calls that really happened, so that static edges are calibrated
and blind spots are measured.

#### Acceptance Criteria
1. WHEN the probe is attached with `-javaagent` and an include-package filter THE SYSTEM SHALL record distinct
   caller→callee method pairs within the filter with a hit count.
2. WHILE the probe is recording THE SYSTEM SHALL NOT write to the target application's stdout/stderr and SHALL
   keep recorded state bounded by a configurable edge limit.
3. WHEN the target JVM shuts down or the dump command is issued THE SYSTEM SHALL flush recorded edges to a JSONL
   file with the run id, start/end time and filter used.
4. WHEN a recorded call entered through reflection, a dynamic proxy, or a lambda THE SYSTEM SHALL record the
   dispatch kind when it is detectable.

## Requirement 5 — Join policy, provenance and confidence
**User story:** As an agent, I want one graph where every edge says who saw it and how sure we are, so that I can
decide what to trust.

#### Acceptance Criteria
1. WHEN layer facts are joined THE SYSTEM SHALL merge nodes by canonical JVM identity so that the same method from
   different layers becomes one node.
2. WHEN an edge is seen by one or more layers THE SYSTEM SHALL record the set of layers in `provenance` and each
   layer's evidence.
3. WHEN an edge is scored THE SYSTEM SHALL compute confidence in [0,1] with the rules defined in design.md and
   record the ids of the rules applied.
4. WHEN a call targets an interface or overridable method THE SYSTEM SHALL expand it to candidate implementations
   and, WHERE wiring evidence selects a bean, narrow the candidates to the wired implementation.
5. WHEN a dynamic-dispatch site cannot be resolved statically THE SYSTEM SHALL emit an `UNRESOLVED` edge from the
   call site with the dispatch kind and any partial key (e.g. bean-name prefix).
6. WHEN a runtime edge has no static counterpart THE SYSTEM SHALL emit it as a `PHANTOM` edge and link it to the
   nearest unresolved site in the caller when one exists.
7. The join SHALL be deterministic: joining the same inputs in any order SHALL produce an identical snapshot.
8. Adding a layer's evidence to an edge SHALL never lower that edge's confidence.

## Requirement 6 — Blind-spot report
**User story:** As a reviewer, I want to know how much the map may be missing, so that I don't over-trust it.

#### Acceptance Criteria
1. WHEN a snapshot is produced THE SYSTEM SHALL report counts of edges by provenance combination, unresolved sites
   by dispatch kind, and phantom edges.
2. WHERE runtime evidence exists THE SYSTEM SHALL report static recall on exercised code as
   |runtime edges also predicted statically| / |runtime edges| and the share of methods exercised.
3. The report SHALL state that recall is measured only over exercised code and SHALL NOT extrapolate it to
   un-exercised code.

## Requirement 7 — Transaction cards
**User story:** As an agent or engineer, I want a card per entry point, so that I can see the whole path of a
request with evidence.

#### Acceptance Criteria
1. WHEN entry points are detected (HTTP handler mappings, XML-mapped controllers, `main`, scheduled methods,
   message listeners) THE SYSTEM SHALL list them with their route or trigger.
2. WHEN a card is requested for an entry point THE SYSTEM SHALL produce hops from the entry through
   controller/action, service and DAO layers to DB access or external calls, each hop with file:line evidence,
   provenance and confidence.
3. WHEN a hop's node touches configuration, external calls, transactions or significant branches THE SYSTEM SHALL
   attach these as aspects on that node.
4. WHEN the path reaches an `UNRESOLVED` edge THE SYSTEM SHALL show it on the card as an explicit gap instead of
   omitting it.
5. Every card SHALL record the code version (git commit, dirty flag, class-file hash) and the snapshot id.
6. WHEN a card is serialised THE SYSTEM SHALL validate it against the JSON schema in design.md.

## Requirement 8 — MCP server
**User story:** As an agent, I want MCP tools over the graph, so that I can query it during a task.

#### Acceptance Criteria
1. WHEN an MCP client connects over stdio THE SYSTEM SHALL list the tools defined in design.md with JSON schemas.
2. WHEN a tool returns edges THE SYSTEM SHALL include provenance, confidence, rule ids and evidence for each edge.
3. IF a requested symbol is ambiguous THEN THE SYSTEM SHALL return the candidate list instead of guessing.
4. WHEN a tool result would exceed a configured size THE SYSTEM SHALL truncate and say what was truncated.

## Requirement 9 — Web UI
**User story:** As a judge or engineer, I want to explore the map visually, so that the idea is obvious in a minute.

#### Acceptance Criteria
1. WHEN the UI loads a published snapshot THE SYSTEM SHALL render the graph with edge styling by provenance and
   opacity by confidence.
2. WHEN the user selects an entry point THE SYSTEM SHALL render its transaction card with clickable evidence links
   to the public source.
3. WHEN the user toggles "AST-only" THE SYSTEM SHALL show only L1 edges and highlight edges the AST missed.
4. The UI SHALL be deployable as a static site and SHALL respect `prefers-reduced-motion`.

## Requirement 10 — Benchmark
**User story:** As a judge, I want a fair measurement against a source-only baseline, so that claims are credible.

#### Acceptance Criteria
1. WHEN the benchmark runs on a target THE SYSTEM SHALL compute, for runtime-observed edges and for the
   haunted-house ground truth, the edges predicted and missed by (a) the AST-only baseline and (b) Ghost Map.
2. The benchmark SHALL be reproducible from a single command and SHALL publish its inputs, versions and outputs.

## Requirement 11 — Clean room and submission compliance
1. The repository SHALL contain only code written during the submission period plus declared OSS dependencies.
2. The repository SHALL include an OSI licence, the `/.kiro` folder (specs, hooks, steering), and SHALL NOT
   gitignore `/.kiro`.
