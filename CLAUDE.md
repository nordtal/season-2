# season-2

Public repository `nordtal/season-2`. Read [`README.md`](README.md) for the architecture, modules,
development commands and release process, and [`CONVENTIONS.md`](CONVENTIONS.md) for code, wording,
comment, commit scope and design rules. `CONVENTIONS.md` overrides `agent-kit` on conflict.

For the pinned project roadmap and shared documents, run `atelier doc list --project nordtal`.

## Standing rules

- **Question unclear or broken instructions** in chat instead of silently working around them.
- **Nothing is ever migrated between seasons.** Each season is a full restart from scratch. Do not
  plan migrations, compatibility shims or data imports from a prior season unless explicitly
  requested.
- **Never quote the owner** in the repository or in a tracker item. Record decisions in plain words
  with their date. Put nothing from an agent conversation into the repository.
- **Where no instruction exists, decide for the best user experience** and record the decision as a
  ruling on the item. Ask non-UX questions in chat.
- **A schema expands before it contracts.** Verify a current backup exists before any step that
  breaks a schema or volume. A dev host session may run contractions without asking once a current
  backup is verified, the step passed on a restored dump and nothing reads the old data.

## Build and checks

- **Build with the repository wrapper**: run `sh gradlew` from this repository root, never a global
  Gradle.
- **Run `scripts/pre-pr` before pushing** to verify commit messages, pull request title and body,
  Flyway migration immutability, changed-module Spotless and Checkstyle, generated Steward API
  types and frontend `oxfmt` and `oxlint`. Pass `--fix` to apply formatting and regenerate API
  types.
- **Reuse domain terms** from `resource-pack/glyphs.json` and `eu.nordtal.season.common.Platform`.
- **Keep `.gitignore` directory patterns anchored** except `.idea/`, `.vscode/` and `.fleet/`. An
  unanchored pattern can hide a Java package of that name, which `checkSourcesTracked` catches.

## Commits, pull requests and releases

Follow `agent-kit`'s `github-flow` and `ship` skills, with these Nordtal rules:

- **Every session opens a pull request.** Never push to `main` or merge past a red check.
- **Atelier tasks defer review and merge to Atelier.** An Atelier builder session works on
  Atelier's assigned branch and opens the pull request. Atelier dispatches reviews and merges the
  pull request. Every other session works on branch `nt/<outcome>` and merges through `ship`'s
  merge gate.
- **Author**: `Till Hoffmann <till@nordtal.eu>`.
- **Dependabot**: keep it as squash author (`gh pr merge --squash --body ""`).
- **Merge gates**: `build` and `title` gate the merge (bot reviews may stay pending).
- **Releases**: release whenever work needs one (see `README.md`) and always before an area goes to
  owner review.
