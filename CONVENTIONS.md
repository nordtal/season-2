# Conventions

These rules hold for every contributor, human or agent. The same shared part stands in
`season-2`, `jcore` and `papermc-display-tags`; the last section lists what is specific to this
repository. Every rule marked *(checked)* fails `./gradlew check`, and warnings count as failures.

## Comments

A comment says what the code cannot. It describes the code as it is now.

- **A doc comment opens with one line**: one sentence stating what the element does or is, for
  types as for members. *(checked)* A short paragraph may follow for the contract the signature
  cannot state: side effects, threading, ordering, units, failure cases. Never history.
- **No sentence the signature already says.** If the name together with `@param`, `@return` and
  `@throws` explains the element, the doc comment has tags only, or does not exist.
- **Present tense only.** No history, no dates, no "used to", "until", "since" or "no longer".
  *(dates checked)* Why a change was made belongs in its commit message.
- **No recorded reasoning** unless a later change to that code would go wrong without it. Then it
  is that one line.
- **Inline `//` only for a non-obvious why**: a workaround, an ordering constraint, a vendor quirk.
  One line, never what the next line does. *(one line checked)*
- **No banner comments** (`// ---- section`). *(checked)*
- **No TODO, FIXME or XXX.** Open work is an issue in the tracker, not a comment. *(checked)*
- **No issue-tracker IDs anywhere in the repository**: not in code, comments, docs or commit
  messages. Write the reason instead. *(checked in files)*
- Doc comments use `/** */`, not `///`. No block HTML (`<p>`, `<h2>`, lists) inside them.
  *(checked)*
- These rules apply to every file type: Java, Kotlin and Groovy build scripts, TypeScript, YAML,
  shell and SQL.

A README gives a rough overview for finding one's way around the code. It holds no details and no
decision records.

## Formatting

- Java is formatted by **palantir-java-format** through Spotless: 120 columns, 4-space indent.
  `./gradlew spotlessApply` fixes everything. *(checked)*
- Kotlin build scripts by ktlint, Groovy build scripts by greclipse. *(checked)*
- No wildcard imports; unused imports are removed. *(checked)*
- Bulk-format commits are listed in `.git-blame-ignore-revs`. Run
  `git config blame.ignoreRevsFile .git-blame-ignore-revs` once per clone.

## Java

- **`final`** on every local variable and parameter that is never reassigned. *(checked)*
- **Nullness:** everything is non-null unless annotated `org.jspecify.annotations.Nullable`.
  *(checked by NullAway)*
- **Error Prone** runs on every compile. A check is disabled only in the build file, with a
  one-line reason. *(checked)*
- **Size:** a file has at most 800 lines, a method at most 60. *(checked)*

## Structure

- **Package by feature**: `eu.nordtal.<repo>.<module>.<feature>`, never by layer. *(partly checked)*
- **No `util`, `helper` or `misc` packages** inside a module. Code shared by several
  features lives at the module root. *(checked)*
- **No cycles between feature packages**, each counted with its subpackages. The module root,
  which wires the features and holds what they share, is exempt. *(checked by ArchUnit)*

## Tests

- JUnit Jupiter. A test method's name is the sentence it proves, in camelCase:
  `aRefusalIsNotAnError`. No `@DisplayName`.

## Commits

[Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/):
`type(scope): description`, type in lowercase, no issue ID.
Types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`, `style`. A
breaking change carries `!` after the type. *(checked by a `commit-msg` hook and in CI)*
The release notes are generated from these subjects by git-cliff (`cliff.toml`). Run
`git config core.hooksPath .githooks` once per clone to get the hook.

## This repository: jcore

- **Public API documentation** *(checked)*: every public and protected type and member has a doc
  comment. `@param` and `@return` are required, except on records and single-expression accessors.
- **Commit scope** is the area: `config`, `schema`, `db`, `build`.
- The vendored copy of Spec (`eu.nordtal.jcore.config.spec`) follows every rule here; `NOTICE`
  keeps its attribution.
- A change to the public API or to its exported dependencies is a new tag, and a breaking one is a
  new major version.
