# Conventions

These rules hold for every contributor, human or agent. The same shared part stands in
`season-2`, `jcore` and `papermc-display-tags`; the last section lists what is specific to this
repository. Every rule marked _(checked)_ fails `./gradlew check`, and warnings count as failures.

## Comments

A comment says what the code cannot. It describes the code as it is now.

- **A doc comment opens with one line**: one sentence stating what the element does or is, for
  types as for members. _(checked)_ A short paragraph may follow for the contract the signature
  cannot state: side effects, threading, ordering, units, failure cases. Never history.
- **No sentence the signature already says.** If the name together with `@param`, `@return` and
  `@throws` explains the element, the doc comment has tags only, or does not exist.
- **Present tense only.** No history, no dates, no "used to", "until", "since" or "no longer".
  _(dates checked)_ Why a change was made belongs in its commit message.
- **No recorded reasoning** unless a later change to that code would go wrong without it. Then it
  is that one line.
- **Inline `//` only for a non-obvious why**: a workaround, an ordering constraint, a vendor quirk.
  One line, never what the next line does. _(one line checked)_
- **No banner comments** (`// ---- section`). _(checked)_
- **No TODO, FIXME or XXX.** Open work is an issue in the tracker, not a comment. _(checked)_
- **No issue-tracker IDs anywhere in the repository**: not in code, comments, docs or commit
  messages. Write the reason instead. _(checked in files)_
- Doc comments use `/** */`, not `///`. No block HTML (`<p>`, `<h2>`, lists) inside them.
  _(checked)_
- These rules apply to every file type: Java, Kotlin and Groovy build scripts, TypeScript, YAML,
  shell and SQL.

A README gives a rough overview for finding one's way around the code. It holds no details and no
decision records.

## Formatting

- Java is formatted by **palantir-java-format** through Spotless: 120 columns, 4-space indent.
  `./gradlew spotlessApply` fixes everything. _(checked)_
- Kotlin build scripts by ktlint, Groovy build scripts by greclipse. _(checked)_
- No wildcard imports; unused imports are removed. _(checked)_
- Bulk-format commits are listed in `.git-blame-ignore-revs`. Run
  `git config blame.ignoreRevsFile .git-blame-ignore-revs` once per clone.

## Java

- **`final`** on every local variable and parameter that is never reassigned. _(checked)_
- **Nullness:** everything is non-null unless annotated `org.jspecify.annotations.Nullable`.
  _(checked by NullAway)_
- **Error Prone** runs on every compile. A check is disabled only in the build file, with a
  one-line reason. _(checked)_
- **Size:** a file has at most 800 lines, a method at most 60. _(checked)_

## Structure

- **Package by feature**: `eu.nordtal.<repo>.<module>.<feature>`, never by layer. _(partly checked)_
- **No `util`, `helper` or `misc` packages** inside a module. Code shared by several
  features lives at the module root. _(checked)_
- **No package cycles.** _(checked by ArchUnit)_

## Tests

- JUnit Jupiter. A test method's name is the sentence it proves, in camelCase:
  `aRefusalIsNotAnError`. No `@DisplayName`.

## Commits

[Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/):
`type(scope): description`, type in lowercase, no issue ID.
Types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`, `style`. A
breaking change carries `!` after the type. _(checked by a `commit-msg` hook and in CI)_
The release notes are generated from these subjects by git-cliff (`cliff.toml`). Run
`git config core.hooksPath .githooks` once per clone to get the hook.

## This repository: season-2

- **Commit scope** is the module directory (`feat(steward-ui): …`, `fix(smp): …`), `deploy` for
  `deploy/` and `compose.yml`, `build-logic` for `build-logic/` and the version catalog.
- **Architecture** _(checked by ArchUnit)_:
  - `:common` depends only on the JDK, JDBI, HikariCP, slf4j-api, the PostgreSQL driver, JSpecify
    and Adventure, which both platforms provide.
  - No Paper plugin calls a blocking `join`; database work leaves the main thread.
  - Only `steward-worker` runs Flyway `migrate()`. `discord-bot` only validates, plugins do neither.
- **steward-ui frontend** is formatted by oxfmt and linted by oxlint with type-aware rules
  (oxlint-tsgolint), warnings as errors. `src/components/ui` is ours once generated and follows every
  rule. TSDoc follows the comment rules above. _(checked)_
- **YAML, Markdown and JSON** are formatted by oxfmt. _(checked)_
- **SQL migrations** are never reformatted: an applied migration whose checksum changes is refused
  by Flyway `validate()`.
- The one file under `steward-worker/src/main/java/com/bunq/sdk` keeps the vendor's package.
