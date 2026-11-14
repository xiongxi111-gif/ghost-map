---
inclusion: always
---
# Product: Ghost Map

## Purpose
Ghost Map gives AI coding agents an **evidence-graded map** of a Java/Spring codebase: a call and wiring graph
where every edge says which analysis layers saw it and how much to trust it, plus **transaction cards** that
trace one entry point end to end with file:line evidence.

## Users
1. **AI coding agents** (Kiro, other MCP clients) — primary consumer, via MCP tools.
2. **Backend engineers** onboarding to or changing a large legacy Spring codebase — via the web UI and cards.
3. **Reviewers/architects** who need to know "what does this endpoint touch?" and "how blind is our map?".

## Core promises (do not compromise these)
- **Never silent.** Anything the static layers cannot resolve is an explicit `UNRESOLVED` edge. Runtime calls no
  static layer predicted are `PHANTOM` edges. The blind-spot report is always available.
- **Evidence or it didn't happen.** Every edge and every card hop carries locatable evidence
  (file:line, class + bytecode offset, XML file:line, or runtime sample count).
- **Confidence is explainable.** A score is always derived from provenance by a named join-policy rule; the MCP
  answer includes the rule id.
- **Versioned.** Every snapshot and card records the git commit, a hash of analysed class files, and the Ghost Map
  version.

## Non-goals (for the hackathon)
- Not a security scanner, not a refactoring engine, not a general multi-language indexer.
- No LLM is required to build the graph. LLMs consume it; the graph itself is deterministic.

## Hackathon framing
- Kiroween 2026, **Frankenstein** category: tree-sitter + bytecode + Spring wiring + JVM probe stitched together.
- Judging is 20% each: Impact, Technical Implementation, Design, Innovation, Presentation. The headline evidence is
  the **"AST-only vs Ghost Map: missed edges"** benchmark.
- Theme vocabulary (UI/copy only, never in APIs): ghosts = unresolved edges, phantoms = runtime-only edges,
  séance = running the runtime probe, haunted house = synthetic fixture.

## Clean-room rule (hard constraint)
All code is written from scratch in this repository during the submission period. Never import, paraphrase or
reference proprietary code, internal framework names, or internal system structures. Examples and fixtures use
only public OSS projects or synthetic code written here. If unsure, stop and ask.
