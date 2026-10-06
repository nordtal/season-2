# Conventions

These rules hold for every contributor, human or agent; the last section lists what is specific to
this repository. Every rule marked _(checked)_ fails `./gradlew check`, and warnings count as failures.

## Comments

A comment says what the code cannot. It describes the code as it is now.

- **A doc comment opens with one line**: one sentence stating what the element does or is, for
  types as for members. _(checked)_ A short paragraph may follow for the contract the signature
  cannot state: side effects, threading, ordering, units, failure cases. Never history.
- **A doc comment in main code has at most four lines before its tags**, its `/**` line
  included. _(checked)_
- **No dash as punctuation**: no em or en dash in any file, and no hyphen with a space on each
  side in a comment or in Markdown prose. Use a comma, a colon, parentheses or a new sentence. A
  hyphen inside a word or at the start of a list item is fine. _(checked)_
- **No sentence the signature already says.** If the name together with `@param`, `@return` and
  `@throws` explains the element, the doc comment has tags only, or does not exist.
- **Present tense only.** No history, no dates, no `used to`, `until`, `since` or `no longer`.
  _(dates checked, and the history words of Markdown)_ Why a change was made belongs in its commit message.
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

A README states the present state and gives an overview of what sits where. It holds no history, no
rejected alternatives and no dates; the history of a decision is its tracker item and its commit.
Diagrams and snippets are preferred over prose, and reasoning stays as the one line next to what it
constrains. The present-tense rule also holds for every tracked Markdown file: a date, `used to`,
`formerly`, `previously`, `originally` or `before V21` fails `check`. _(checked)_

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

- **Package by feature**: `eu.nordtal.<repo>.<module>.<feature>`, never by layer. A package whose last segment is
  `db`, `dao`, `listener`, `service`, `model`, `impl`, `api` or `web` fails `check`; `config` is allowed.
  _(partly checked)_
- **No `util`, `helper` or `misc` packages** inside a module. Code shared by several
  features lives at the module root. _(checked)_
- **No cycles between feature packages**, each counted with its subpackages. The module root,
  which wires the features and holds what they share, is exempt. _(checked by ArchUnit)_

## Tests

- JUnit Jupiter. A test method's name is the sentence it proves, in camelCase:
  `aRefusalIsNotAnError`. No `@DisplayName`.
- **A decision about who gets in, who pays or what is said is a function of plain values with its own
  test**, as `GateOutcome`, `PaymentWait` and `RegisterReplies` are. A framework callback (a login event, a
  Discord interaction, a Paper listener) only reads its event, calls the function and applies the result, so
  a test never needs the framework. No build check sees this; review applies it.
- **A test finds the repository root through `RepositoryRoot` and reads `compose.yml` through
  `ComposeFile`**, both in `:common`'s test fixtures, which any module's tests may use. A test helper
  that two modules need lives there too, never as a copy beside each test. A test source that names
  `settings.gradle.kts` to find the root fails `check`. Every file reached this way is also declared in
  the module's `repositoryRootTestInputs`, or the test stays up to date when the file changes.
  _(checked)_

## Commits

[Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/):
`type(scope): description`, type in lowercase, no issue ID.
Types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`, `style`. A
breaking change carries `!` after the type. _(checked by a `commit-msg` hook and in CI)_
No message, pull request title or body names a model or its vendor.
_(checked by the same hook, in CI and by the `pr-title` check)_
The release notes are generated from these subjects by git-cliff (`cliff.toml`). The system hook at
`/etc/nordtal/githooks` runs `.githooks/commit-msg` in every clone; only on a machine without it,
run `git config core.hooksPath .githooks` once per clone.

## This repository: season-2

- **Commit scope** is the module directory (`feat(steward): …`, `fix(smp): …`), `deploy` for
  `deploy/` and `compose.yml`, `build-logic` for `build-logic/` and the version catalog.
- **Architecture** _(checked by ArchUnit)_:
  - `:common` depends only on the JDK, JSpecify and Gson, which every platform ships and nothing
    shades.
  - `:database` adds `:messages`, JDBI, HikariCP, slf4j-api and the PostgreSQL driver, and nothing
    else.
  - Only `NetworkTime` reads the wall clock; every other class is handed the process's clock.
  - `:messages` has no Adventure; `:message-rendering` and `:pack-rendering` add Adventure, which
    both platforms provide. Neither `discord-bot` nor Steward depends on Adventure or on either
    renderer.
  - No Paper plugin reads an identity outside pre-login or a hub refresh; database work leaves the main thread.
  - A Paper plugin and the proxy render through their base's one `MessageRenderer` (`NordtalPlugin`,
    `ProxyPlugin`); no other class there builds one.
  - Only steward-agent's `migrate` service runs Flyway `migrate()`. `discord-bot` only validates, plugins do
    neither.
- **The steward frontend** is formatted by oxfmt and linted by oxlint with type-aware rules
  (oxlint-tsgolint), warnings as errors. `src/components/ui` is ours once generated and follows every
  rule. TSDoc follows the comment rules above. _(checked)_
- **YAML, Markdown and JSON** are formatted by oxfmt. _(checked)_
- **SQL migrations** are never reformatted: an applied migration whose checksum changes is refused
  by Flyway `validate()`.
- The one file under `steward-bunq/src/main/java/com/bunq/sdk` keeps the vendor's package.
- **Platform versions** have one source: the version catalog for Paper and Velocity, `Platform` for the
  constants. `PlatformTest` holds them against their mirrors, and a new mirror is declared through
  `repositoryRootTestInputs`, or the test cannot see it. _(checked)_ Minecraft versions read
  `year.drop.hotfix`; Paper is an exact version, Velocity a family.
- **No pin of a platform build**, in any form. steward-agent installs the newest `STABLE` build and the
  entrypoint resolves the same one, each filtering the build list itself, since `/builds/latest` answers
  any channel.
- **Adventure comes from the platform** and is never pinned.
- **Never shaded into a Paper plugin**: Gson, SnakeYAML and Brigadier, which the platforms ship, and
  Flyway, which reaches neither `:common` nor `:database` either.
- **Commands use Brigadier directly**, through each platform's own API. No command framework.
- **Signals**: no process keeps a poll of its own on database state; the hub's reconciliation is the
  guarantee and a notification only makes a change feel instant. A notification is never the state, so
  every wake-up and every reconnect re-reads in full. Every channel is a constant of `Channel`.
- **Names are runtime identity.** A Paper plugin's `name:` matches its module directory and is its
  `plugins/<name>/` data folder and permission prefix, so renaming one moves data folders on the host.
  `proxy`'s `@Plugin` reads its version from a generated class, a template in `src/main/templates/`
  that Gradle expands with the version in `gradle.properties`, which is Velocity's own recipe for a plugin
  without a descriptor file.
- **Production is one `docker compose` stack** of named services. No cloud or orchestrator dependency
  without a concrete need.
- **Compose**: every `${X:?}` in `compose.yml` has a value in `deploy/dev.env.example`, and a build arg
  never uses `:?`, since Compose interpolates build args on a deploy that only pulls. steward schedules
  nothing but request rows; carrying out a run is steward-agent's.

### Design

- **Steward is designed for the phone first.** The desktop layout follows from the narrow one, and
  where the two disagree the phone wins.
- **Steward shows data, not explanatory text.** A sentence stays when it says what the value beside it
  does not and an admin would not know anyway, or when it keeps a state honest ("Some of the readings
  could not be fetched"). Everything else goes into the doc comment.
- **No text symbol as a separator**, such as the middle dot, a pipe or a spaced hyphen: one value, two
  values set apart as two lines or two pills, or the second as an icon. A message bundle holds no pipe,
  spaced hyphen, dash (escaped or not), double space or banner comment. _(the middle dot in Steward and
  all of it in the bundles checked)_
- **Bordered elements are not nested.** Cards lie flat on the background, side by side; an inner card
  becomes a heading.
- **The name is Nordtal** in prose, in every language, and `nordtal.eu` where it is the address; never a
  lower-case nordtal in a text. _(checked in the bundles)_
- **One blue, the brand's**, defined once as `Tone.BRAND` and read by the game's `<brand>` tone, the bot's
  embed line and Steward (`brand.gen.css`, written from it). A text that cannot carry a hex colour, the
  pack's `pack.mcmeta` and lang files, uses `§9`, Minecraft's nearest legacy blue.
- **Every Discord embed carries the brand blue line**, a success included. The outcome shows in the
  content through one coordinated set of emojis, never mixed with text symbols.
- **A design proposal is a Steward page under `/designs/`**, so every admin can try it live. Each one is
  removed with its route once the design is built, and no page is served from that path after the
  development phase.
- **A visual change is accepted on screenshots** at phone and desktop width, compared with what was
  intended, not on green tests alone.
