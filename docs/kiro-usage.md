# How we used Kiro (skeleton — fill in as we build)

## Surfaces used
- Kiro IDE ⟨version⟩ on ⟨OS⟩. ⟨CLI / Web if used⟩.

## Spec-driven development vs vibe coding
- `.kiro/specs/ghost-map-core/` — requirements (EARS) → design → tasks. ⟨Which tasks were run from the task list;
  how requirements analysis changed the design (e.g. monotonic confidence came from Requirement 5.8).⟩
- Vibe coding was used for: ⟨UI theming experiments, demo glue⟩. Spec-driven for: ⟨join policy, model, probe⟩.
  Why: ⟨correctness-critical parts needed traceable requirements⟩.

## Steering
- `product.md` (promises, clean-room rule), `tech.md` (stack, testing), `structure.md` (layer isolation, rule naming).
  ⟨Examples where steering prevented a wrong suggestion.⟩

## Hooks
- `pbt-on-save.json` (PostFileSave) — runs jqwik properties when model/join/card code changes. ⟨Bugs it caught.⟩
- `snapshot-regen.json` (Stop, with confirm) — re-indexes the fixture and diffs against ground truth.
- `clean-room-guard.json` (PreToolUse on writes) — blocks content matching a private denylist.

## MCP
- Ghost Map *is* an MCP server; we registered it in `.kiro/settings/mcp.json` and used it inside Kiro to navigate
  the demo targets and ⟨our own codebase⟩. ⟨Example prompts and tool calls.⟩

## Property-based testing
- jqwik properties: order independence, monotonicity, bounds, conservation, no silent drops, idempotence,
  id round-trip. ⟨Kiro Correctness feature usage, counterexamples found.⟩

## Custom agents / Powers
- ⟨If used.⟩

## What worked, what didn't
- ⟨Honest notes.⟩
