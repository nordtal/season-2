# Conventions

These rules hold for every contributor, human or agent, on top of the text and code rules of the
agent-kit; the last section lists what is specific to this repository. Every rule marked _(checked)_ fails `./gradlew check`, and warnings count as failures.

## Wording

The text rules of the agent-kit hold for every text written for this project, including every text a
player, a Discord member or an admin reads: message bundles, Discord messages and Steward's interface.
This repository adds the rules for those texts:

- **A player text names game terms only**, never a component, and says what happens and what the
  player does next, never why.
- **German addresses the player as _du_** and is written, not translated.
  - Fails: `Verbinde dich erneut, um es noch einmal zu versuchen.`
  - Passes: `Verbinde dich neu.`
  - Fails: `Du wirst jetzt hinübergeschickt.`
  - Passes: `Du kommst jetzt zurück.`
- **A limited place wins over the full sentence**: a server-list line, a boss bar, a button. A value
  spliced into another text (`{countdown}`) reads correctly in every text that uses it.

### Game terms

The `terms` bundle of `messages` (`TermMessages`) holds the one name of each game term in English
and German, and Steward's Translations page lists it with the building blocks. A text uses no other
word for a term. A new term is declared there before a text uses it. `check` refuses a text that
writes a word `TermMessages.SYNONYMS` lists for a term.

## Comments

The code rules of the agent-kit hold. `check` enforces these in every file type: Java, Kotlin and
Groovy build scripts, TypeScript, YAML, shell and SQL.

- **A doc comment opens with one line**, one sentence stating what the element does or is, for types
  as for members. _(checked)_
- **A doc comment in main code has at most four lines before its tags**, its `/**` line included.
  _(checked)_
- **Doc comments use `/** */`**, not `///`, and hold no block HTML (`<p>`, `<h2>`, lists). _(checked)_
- **An inline `//` comment is one line.** _(checked)_
- **No banner comments** (`// ---- section`) **and no TODO, FIXME or XXX.** _(checked)_
- **No issue-tracker ID** in any file. _(checked)_
- **No dash as punctuation**: no em or en dash in any file, and no hyphen with a space on each side in
  a comment or in Markdown prose. A hyphen inside a word or at the start of a list item is fine.
  _(checked)_
- **No date in a comment, and no history word in Markdown**: `used to`, `formerly`, `previously`,
  `originally` or `before V21` fails. _(checked)_

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

A commit message is one line, `type(scope): description`, with no body and no issue ID.
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
  A commit that spans several modules has no scope.
- **Architecture** _(checked by ArchUnit)_:
  - `:common` depends only on the JDK, JSpecify and Gson, which every platform ships and nothing
    shades.
  - `:database` adds `:messages`, JDBI, HikariCP, slf4j-api and the PostgreSQL driver, and nothing
    else.
  - Only `NetworkTime` reads the wall clock; every other class is handed the process's clock.
  - `:messages` has no Adventure; `:message-rendering` and `:pack-rendering` add Adventure, which
    both platforms provide. `discord-bot` depends on neither Adventure nor either renderer. Steward
    draws a menu's preview with `:message-rendering` and never depends on `:pack-rendering`.
  - No Paper plugin reads an identity outside pre-login or a hub refresh; database work leaves the main thread.
  - A Paper plugin and the proxy render through their base's one `MessageRenderer` (`NordtalPlugin`,
    `ProxyPlugin`); no other class there builds one.
  - Only steward-agent's `migrate` service runs Flyway `migrate()`. `discord-bot` only validates, plugins do
    neither.
- **The steward frontend** is formatted by oxfmt and linted by oxlint with type-aware rules
  (oxlint-tsgolint), warnings as errors. `src/components/ui` is ours once generated and follows every
  rule. TSDoc follows the comment rules above. _(checked)_
- **YAML, Markdown and JSON** are formatted by oxfmt. _(checked)_
- **Data is in third normal form.** Every column holds one atomic value: no stored note, composed text or
  message document (`{key, args}`). Steward, the bot and the terminal render text when they show a value.
  A jsonb or array column exists only on a list of `ColumnsAreAtomicIntegrationTest`: the published
  snapshots, the values read and written whole, and the shrinking list still to normalize. _(checked)_
  The published snapshots stay composite columns, each written and read whole and mirroring another
  system's structure: `command_tree.tree`, `game_catalogue.catalogue`, `game_catalogue.datapacks`,
  `game_assets.icon_index`, `guild_channels.channels`, `setting_group.schema`, `setting_group.defaults`,
  `setting_group.environment`, `setting_group.problem` and `steward_session.webauthn_request`. The last
  two are text: the config library's message and the WebAuthn library's JSON.
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
- **Dependencies stay current.** A Dependabot alert is fixed by an update even when the vulnerable code
  is never reached; a transitive one is first lifted in the lockfile, within its parent's range.
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
- **No container of ours runs as root but steward-agent**: each image makes an account with a fixed uid,
  `compose.yml` names it in `user:` and gives the service `cap_drop: [ALL]` and `no-new-privileges`.
  steward-agent keeps root, since the Docker socket is root on the host anyway. _(checked by
  `TopologyDeploymentTest`)_

### Design

- **`steward/frontend/DESIGN.md` is Steward's design**: its scale, surfaces, colours, type and components.
  A class for spacing, a size, a colour, a radius or type names one of its tokens, never a value. _(checked)_
- **A settings field carries an `@Explain`** only when a change can break something and its name does not
  say what the change does. That sentence says what the change does in practice. Steward shows it between
  the field's name and its input. Every other field carries `@NoExplanationNeeded`.
- **No text symbol as a separator**, such as the middle dot, a pipe or a spaced hyphen: one value, two
  values set apart as two lines or two pills, or the second as an icon. A bundle or a document holds no pipe,
  spaced hyphen, dash (escaped or not), double space or banner comment. _(the middle dot in Steward and
  all of it in the bundles and documents checked)_
- **One icon per domain term.** A term Steward names (aura, spins, a service, a backup, a phase, access, an alert
  level) is drawn with the icon `steward/frontend/src/components/steward/term-icons.tsx` gives it, wherever it
  appears: page, pill, form field, table, tooltip, navigation and palette. That icon means nothing else, and a field
  whose value is a term shows it beside its label. _(checked)_
- **The name is Nordtal** in prose, in every language, and `nordtal.eu` where it is the address; never a
  lower-case nordtal in a text. _(checked in the bundles and documents)_
- **One blue, the brand's**, defined once as `Tone.BRAND` and read by the game's `<brand>` tone, the bot's
  container accent and Steward (`brand.gen.css`, written from it). A text that cannot carry a hex colour, the
  pack's `pack.mcmeta` and lang files, uses `§9`, Minecraft's nearest legacy blue.
- **Every Discord container carries the brand blue accent**, a success included. The outcome shows in the
  content through one coordinated set of emojis, `Mark` in discord-bot, and no class but `Mark` types an
  emoji. An arrow may show a transition (`from → to`); no text symbol stands for an outcome or separates
  values. _(emojis outside `Mark` checked)_ The set:
  - Status: ✅ done, 🛑 failed or down, ⚠️ needs a look but has not failed, 🔄 under way, ⏳ waiting, ➖ nothing
    to do, 🔍 not read yet, 📋 planned, ⏹️ cancelled.
  - Action: 💶 paid, 🎟️ granted, 🚫 revoked, 🔗 linked, ✂️ unlinked, ⏱️ play time set, 🔒 refused and kept shut,
    📝 any other action.
  - A language's flag, the country `Locales.flagCountry` names for it, drawn by `Mark.flag`.
- **A design proposal is drawn on Nordtal's design system**, outside this repository, never as a page
  Steward serves.
- **A visual change is accepted on screenshots** at phone and desktop width, compared with what was
  intended, not on green tests alone.
