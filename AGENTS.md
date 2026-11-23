# season-2 (Codex, Kimi Code and Antigravity)

Read [`CLAUDE.md`](CLAUDE.md), [`README.md`](README.md) and [`CONVENTIONS.md`](CONVENTIONS.md) in
this repository before starting work.

## Harness notes

- **Formatting and pre-PR checks**: harnesses without a PostToolUse format hook (such as Codex and
  Antigravity) run `scripts/pre-pr` (or `scripts/pre-pr --fix`) before committing and pushing.
- **Shared rules**: generic engineering rules and skills come from `tillhfm/agent-kit`. If your
  context lacks _Agent rules_, load `agent-kit`'s `rules.md` from your harness plugin folder first.
- **Pinned roadmap**: run `atelier doc list --project nordtal` for the current roadmap and project
  documents.
