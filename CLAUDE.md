# season-2 — agent guide

Everything nordtal.eu season 2 deploys. A Velocity proxy (`network-control`) in front of three
Paper backends (`limbo` → `hunger-games` → `smp`), plus the `discord-bot` Discord bot, the
`updater` container and the `resource-pack` assets. Production runs as **one `docker compose` stack** on a remote host, driven
through [Arcane](https://github.com/ofkm/arcane). SimpleCloud was dropped on 2026-09-01 — the
runbook, the reasoning, the container design and the console mechanism are all in
[deploy/README.md](deploy/README.md).

The workspace-level [../CLAUDE.md](../CLAUDE.md) carries the standing instructions and the map of
the sibling repos. Read it too.

**The project knowledge base is [docs/README.md](docs/README.md)** - the system map, the phase
model, the language model, the hunger games and SMP concepts, the decision log and the list of what
is built versus designed. Read it before planning anything; this file stays the place for build
conventions, platform versions and repository rules.

## Repository state (READ THIS FIRST)

Set up 2026-08-29 from a bare IntelliJ scaffold. **Re-derived from the code 2026-09-01**, and
updated the same day when `limbo` and the pack station were built.

**No module is a scaffold any more, and no half of one is missing either.** `smp` was finished on
2026-09-01: the server-free core in the morning — the milestone track, the aura payout, the prestige
function and the milestone engine — and then the world half in three blocks, from the worlds and the
daily farm swap through the HUD, boards and nametags to the duels, graves, wheel, milestone payout
and the spawn NPC. 135 tests.

**What is left is not building but watching.** Not one of those 135 tests touches a world, a packet
or a player, because none of them can; the rehearsal that has to follow is a checklist for the owner
rather than a document, and it lives outside this repository.

Everything else has behaviour: `common` (access API, messages, `PlayerLocales`, the phase directory,
`Glyphs`, the `nordtal:limbo` protocol, migrations V1–V8), `network-control` (login gate, phase,
play time, routing **and the pack station**), `limbo` (the waiting room in full, built 2026-09-01),
`discord-bot` (access, `/phase set`, the admin mirror, the language list, hunger games registration),
`hunger-games` (the start event, both halves, built 2026-08-31) and `resource-pack` (four fonts,
every code point allocated and drawn).

**The login path is complete and unrehearsed.** Every login now lands on `limbo`, is offered the
forced resource pack from `pack.yml`, and is released onto the phase's backend when the client
reports it applied. Nothing in the test suite touches a proxy, a client or a packet. The three
assumptions this leaves unverified — and what each falls back to if the answer is no — are in
[docs/state-of-play.md](docs/state-of-play.md#the-unverified-assumptions); the thirteen-step probe
that answers them needs a running proxy and a real client, so it lives in the owner's checklist
outside this repository and has not been run.

`docs/state-of-play.md` is the module-by-module version of this and is the one to trust; this
paragraph is a summary that will go stale again.

Deliberately **not** set up, so nobody adds it by accident thinking it was forgotten:

- **Persistence and `jcore` are now in scope for the plugins.** The earlier rule here ("no
  database, no `jcore` dependency in `common` or in any of the four server-side modules") was
  lifted by the owner on 2026-08-30. Plugins may take a `jcore` dependency and may persist.
  Two things still hold: **decide per module whether that module actually needs persistence**
  rather than adding it by reflex, and **do not copy `discord-bot`'s dependency block** — its
  shaded jar is ~33 MB, which is fine for a container and not fine inside a Paper plugin. A
  plugin that only needs the config system does not need the JDBI/Flyway/PostgreSQL side of
  `jcore` on its runtime classpath; shade what you use.
- **The config system is chosen: `eu.nordtal.jcore.config`** (jcore 3.0.0). Commented YAML
  described by a `@ConfigSpec` interface. It is the default for every new config in this repo.
  `network-control` (`database.yml`, `gate.yml`, `pack.yml`), `hunger-games` (`config.yml`,
  `database.yml`), `limbo` (`config.yml`, `database.yml`), `smp` (`config.yml`, `database.yml`,
  `milestones.yml`, `sounds.yml`), `hunger-games` (`config.yml`, `database.yml`, `sounds.yml`) and
  `discord-bot` (`access.yml`, `bot.yml`, `database.yml`) all use it — read one of them, and
  "Configuration" below, before writing the next.
- **No command framework, decided 2026-08-31.** Season 1 used Incendo Cloud; season 2 uses
  **Brigadier directly, through each platform's own API** — `io.papermc.paper.command.brigadier.
  Commands` on the Lifecycle API for the three Paper plugins, `BrigadierCommand` through
  `CommandManager.metaBuilder` on Velocity. `BasicCommand` is not used, and there is no shared
  helper in `:common`: Paper resolves `com.mojang:brigadier:1.3.10`, Velocity resolves
  `com.velocitypowered:velocity-brigadier:1.0.0-SNAPSHOT`, and neither is on Maven Central.
  **Brigadier is never shaded** — both platforms provide `com.mojang.brigadier.*` at runtime, the
  same way they provide Gson and SnakeYAML. Incendo Cloud 2.0.0 and Lamp 4.0.0-rc.18 were both
  checked against `paper-api:26.2.build.121-stable` and `velocity-api:4.1.1` and both work; they
  were rejected on cost and on maintenance signal. The full reasoning, the measurements and the
  rules that follow are in [docs/architecture.md](docs/architecture.md#commands) — read that
  before writing the first command.

**`smp-farm-world` → `smp`, `resource-pack-coercion` → `limbo` and `access-bot` → `discord-bot`
were all carried out by 2026-08-31** (`eu.nordtal.s2.smp`/`eu.nordtal.s2.limbo`/
`eu.nordtal.s2.discordbot`) — `smp` owns the build world, the spawn, milestones, aura, prestige,
duels, POIs and graves, and the farm world is one part of it. **The fourth rename, the `SeasonPhase`
values, was carried out the same day**: the enum is `PRE_EVENT`, `START_EVENT`, `SMP`,
`MAINTENANCE`, with a database `CHECK` on `season_phase.phase` pinning the same four strings. No
rename is outstanding. A Paper plugin's `name:` is its runtime identity: the `plugins/<name>/` data
folder and the permission prefix, so a rename after deployment means moving data folders on the
production host.

`name-displays` was removed from the module list: nametags are owned by the
[papermc-display-tags](https://github.com/nordtal/papermc-display-tags) fork, which ships from its
own repo and is not built or released here.

## Target platform (verified 2026-08-29, do not trust memory — re-check before changing)

Minecraft **26.2** / Java **25** / Gradle **9.7.1**. Notes:

- Minecraft moved to `year.drop.hotfix`. There is no "1.26.2".
- **Paper dropped `-R0.1-SNAPSHOT`.** The coordinate is
  `io.papermc.paper:paper-api:26.2.build.NNN-stable`; the catalog pins `26.2.build.121-stable`.
- `velocity-api:4.1.1` is compiled for **Java 25** and still ships the `@Plugin` annotation
  processor that generates `velocity-plugin.json`, so it is both `compileOnly` and
  `annotationProcessor`.
- Paper 26.2 and Velocity 4.1.1 both ship **Adventure 5.2.0**. Do not pin Adventure yourself;
  take it from the platform.
- `api-version` in `paper-plugin.yml` accepts `1.13`–`26.2`.
- Resource pack `pack_format` for 26.2 is **88** (26.1 was 84, 26.3 snapshots are 89). Season 1's
  pack was on 64.

**SimpleCloud is gone, decided 2026-09-01.** It ran 26.2 — confirmed by the owner on 2026-08-31
against the v3 dashboard — and that answer stopped mattering: season 2 uses none of its dynamic
instances, templates or failover, its plugin management only handles Modrinth-hosted jars, and v3
exists only inside a hosted closed-beta programme. Production is a single `docker compose` stack;
the four Minecraft services share one image with a tmux console and a SIGTERM trap, because
Arcane's per-container shell is a `docker exec` and cannot reach PID 1's stdin. Full reasoning in
[deploy/README.md](deploy/README.md#why-it-looks-like-this), which also carries what was actually
measured on that image. **Do not reintroduce a
cloud/orchestrator dependency without a concrete need** — the same rule the API artefact below
already carries.

**The API artefact question was closed earlier, by deletion.** `app.simplecloud.api:api` is published
*only* as `0.1.0-platform.NN-dev.*` snapshots — `repo.simplecloud.app` has no releases channel at
all (HTTP 404, checked 2026-08-31). It sat in `network-control` as a `compileOnly` placeholder for
routing; routing was written on 2026-08-31 and imported none of it, resolving backends by the names
in `gate.yml` through Velocity's own `ProxyServer.getServer(name)`. **The dependency, its two
repositories and its version-catalog entry were removed on 2026-09-01.** Do not add it back without
a concrete need: four fixed servers lose nothing by being named instead of discovered. See
[docs/README.md](docs/README.md#decisions-and-when-they-were-taken).

## Layout and conventions

- Packages are `eu.nordtal.s2.<module>` — the `s2` segment keeps season 3 from colliding.
- Paper plugin `name:` values **match the module directory names** (`hunger-games`, `smp`,
  `limbo`, `network-control`), lowercase and hyphenated. That is
  the runtime identity: the `plugins/<name>/` data folder and the permission prefix. Renaming one
  later is expensive; do not "tidy" them into PascalCase.
- `paper-plugin.yml` carries `${version}`, expanded by `processResources` from
  `gradle.properties`. Do not hardcode a version in a descriptor.
- Velocity has no descriptor file — `velocity-plugin.json` is generated from the `@Plugin`
  annotation, so the version has to be in the source. `network-control`'s annotated class
  therefore lives in `src/main/templates/`, not `src/main/java/`, and is expanded into a
  generated source directory. That is Velocity's own recipe; do not "fix" it by moving the
  file back.
- Shared code goes in `:common`, which is shaded into the plugins that use it. Keep it free of
  platform types — it is compiled against neither Paper nor Velocity.
- **A Paper plugin never queries the database from the main thread, decided 2026-09-01.** The
  language lookup at join was doing exactly that in `hunger-games` and would have in `limbo`: one
  round trip, a millisecond on a healthy database and the pool's whole connection timeout on one
  that has stopped answering — with the server stopped behind it, per join. Both now call
  `PlayerLocales#joinAsync(uuid, executor)` with Bukkit's async scheduler and render against
  `of(uuid)`, which answers English until the value lands. Every plugin `database.yml` also carries
  `query-timeout-seconds` (default 3), which sets both HikariCP's `connectionTimeout` and the
  PostgreSQL driver's `socketTimeout` — off the main thread bounds *where* a wait happens, not how
  long it lasts. `smp` inherits this rule rather than rediscovering it.
- **`:common` carries the access system** (`eu.nordtal.s2.common.access`) and the message system
  (`eu.nordtal.s2.common.message`) since stage A, 2026-08-30. That means it now depends on JDBI 3,
  HikariCP, slf4j-api and the PostgreSQL driver — and on **nothing else**; jcore is deliberately
  not used here even though it wraps the same stack, because its dependency block is what makes
  the bot's jar ~31 MB. Nothing from JDBI or HikariCP appears on `AccessDirectory`'s signature —
  the factories take a `javax.sql.DataSource` or a JDBC URL — so a consumer never compiles against
  them.
- **What a jar actually weighs, rebuilt and measured 2026-08-31 at version 0.1.0** (the repository
  moved to `0.2.0` on 2026-09-01; the file names below are the ones the measurements were taken on
  and are left as measured). An earlier
  version of this file and of `docs/state-of-play.md` claimed "every Paper plugin jar grew from
  ~20 KB to ~3.0 MB". **That was wrong, and backwards.**

  | jar | bytes | measured |
  |---|---|---|
  | `smp-0.1.0.jar` | 4,797,861 | 2026-09-01, after the whole world half — it was 4,619,974 with only the server-free core, so ~9 700 lines of plugin code weigh under 180 KB |
  | `limbo-0.1.0.jar` | 4,576,946 | 2026-09-01, after the module was built |
  | `hunger-games-0.1.0.jar` | 4,640,946 | 2026-08-31 |
  | `network-control-0.1.0.jar` | 5,291,760 | 2026-09-01, after the pack station |
  | `discord-bot-0.1.0.jar` | 30,952,094 | 2026-08-31 |

  **That 51 KB figure is also how the deployment audit of 2026-09-01 caught a stale release:** the
  published `v0.1.0` carries `smp-0.1.0.jar` and `limbo-0.1.0.jar` at 51 279 bytes each, so the
  release predates both modules being built — see `docs/state-of-play.md` finding 20.

  The 51 KB figure was what a plugin weighs that carries `:common`'s own classes plus ~14 KB of SQL
  and **nothing else** — which is what `smp` and `limbo` both were until 2026-09-01, precisely
  because the JDBI/Hikari/slf4j declarations are `compileOnly` and neither had opted into `jcore`.
  Both now weigh ~4.6 MB, because both took the persistence stack: `smp` for the reason
  docs/architecture.md gives ("heavily"), and `limbo` for one query per join — the language lookup
  docs/i18n.md requires of every module. `hunger-games` and `network-control` weigh what they weigh because they *did* opt
  in — the persistence stack, not `:common`, is the weight. The 3.12 MB figure in
  `common/build.gradle.kts` is the **counterfactual** — what a plugin jar would weigh if those
  declarations were `implementation` — and it was read as a measurement of the jars as they are.
  A Paper plugin that takes the stack lands near 5 MB, as two of the four now do.
- **Exactly one process migrates: `updater`.** It was `discord-bot` until **2026-09-01**, when the
  call moved — the rule that exactly one process runs Flyway, and that it is never `:common`, is
  what the move preserves. The bot now runs Flyway's `validate()` at startup (`SchemaCheck`) and
  refuses a database it was not built against, naming `updater migrate`. **The plugins do not
  validate and must not:** that needs Flyway, and Flyway must never be shaded into a Paper plugin.
  The consequence is deliberate — **the updater is the bootstrap of every deployment**, and without
  a run of it there is no schema, no bot and no server. **Where the SQL lives changed
  on 2026-08-31, and the move has been carried out**: the migration files sit in
  `common/src/main/resources/db/migration/`, so DDL is next to the API that reads it instead of
  inside the Discord bot module. The bot still applies them, and its call did not change — jcore's
  `database.migrate()` scans `classpath:db/migration`, and `:common` is shaded into the bot, so the
  files arrive at the same classpath location they were at before. Flyway itself must still never
  reach `:common` — a plugin jar carrying a few KB of SQL text is fine, a plugin jar carrying
  Flyway is not. `:common`'s tests read the same `classpath:db/migration` off their own runtime
  classpath. See [docs/architecture.md](docs/architecture.md#schema-ownership).
- **The glyph allocation is owned by [`resource-pack/README.md`](resource-pack/README.md)**, section
  "Code point allocation", decided 2026-08-31. It covers **both** fonts —
  `minecraft/font/default.json` (ordinary text: tab list, chat, nametags, boards) and
  `nordtal/font/bossbar.json` (the HUDs, with their own height/ascent). `:common`'s `Glyphs` and the
  two font files are mirrors of that table; a change is a change in all of them, in one commit.
  `docs/smp.md` and `docs/hunger-games.md` no longer carry code points of their own. **There are
  four fonts, not two**: `nordtal/font/board.json` is the third and `nordtal/font/gui.json`, added
  2026-09-04 with the menu panels, the fourth; every component carrying a
  glyph from a `nordtal:` font has to name that font — see the next bullet.

  This paragraph used to end by calling `Glyphs` "knowingly behind the table — it still declares the
  four retired season-1 role tags and names nothing at all from the boss bar font". **Both halves
  were false and it is kept as a correction** (`docs/state-of-play.md` finding 57): `Glyphs` declares
  one tag and one badge, and it names the whole boss bar font — nine background segments, thirteen
  icons, sixteen arrows, fifteen space advances. `ResourcePackTest` in `:common` is what makes the
  sentence unnecessary now: it holds `Glyphs`, the four font files and every PNG they name against
  each other on every `check`, so "is the mirror still a mirror" is answered by the build rather
  than by a paragraph somebody has to remember to update.
- **A component carrying a `nordtal:` glyph must name its font**, and forgetting is not a subtle
  bug. The four fonts allocate independently, so a boss bar code point left in `minecraft:default`
  does not fail to draw - it draws whatever `default.json` put at that code point.
  `Glyphs.BOSSBAR_BG_4` and `Glyphs.TAG_ADMIN` are both `U+E004`, which is why a real client showed
  the admin nametag inside the SMP's boss bar background on 2026-09-04 and four days of screenshots
  read it as missing art (`docs/state-of-play.md` finding 41). `Glyphs.FONT_BOSSBAR` and
  `FONT_BOARD` are the keys; `BossBarFontTest` asserts both renderers name one.

## Configuration

Every config file in this repo is a commented YAML file described by an interface, loaded through
`eu.nordtal.jcore.config.ConfigLoader` (jcore 3.0.0). Six modules have one:
`network-control` (`database.yml`, `gate.yml`, **`pack.yml`**), `hunger-games` (`config.yml`,
`database.yml`, **`sounds.yml`**), `limbo` (`config.yml`, `database.yml`), `smp` (`config.yml`,
`database.yml`, **`milestones.yml`**, **`sounds.yml`**) and `discord-bot` (`access.yml`, `bot.yml`, `database.yml`).

**A file is separate when it is reloaded, and `smp` now has two such files.** `config.yml` is
deliberately *not* reloadable — the plugin binds worlds, borders, boxes and coordinates once at
enable and would not notice any of them changing, so re-reading the handle would only make it
disagree with the running server. `milestones.yml` is separate because a milestone is appended
mid-season; `sounds.yml` is separate since 2026-09-04 because the documented escape hatch for a
sound that turns out to be irritating is "blank the key", and an escape hatch that costs a restart
of the season is worth very little. Both are re-read by `/smp reload`, each reported separately
because each fails on its own. The sounds spent one afternoon as a `sounds:` block inside
`config.yml` before the reload path was written and the mistake became visible; `ConfigsTest`
asserts by name that `config.yml` refuses such a block, because the *reason* is invisible from
`SmpSpec`.

**A nested spec interface needs its own `@ConfigSpec`, and two levels of nesting work.** `smp`'s
`milestones.yml` is a list of milestones each carrying a list of objectives, which is one level
deeper than anything here had used. It works — and a nested interface *without* the annotation
fails as a Gson error about making `java.lang.reflect.Proxy#h` accessible, which names nothing
useful. `MilestonesTest` is the standing proof for `milestones.yml`.

**Every module that has configs has a `ConfigsTest` that loads every one of its handles into an
empty directory, and that rule is not decoration.** `smp` was the one module without one until
2026-09-02, and what it cost was the whole plugin: four nested interfaces in `SmpSpec` carried no
`@ConfigSpec`, so the first write of a fresh `config.yml` died on `Proxy#h`, `onEnable` threw on
every start of every real server, and Paper disabled the plugin while the server carried on. 135
green tests said nothing, because not one of them had ever called `Configs.load`. Loading is what
*writes* the file, and writing is what serialises every nested spec — so the round trip is the
check. `smp`'s `ConfigsTest` also asserts the rule outright, by reflection over the three spec
roots: the round trip only fails because Gradle's test worker has `java.lang.reflect` closed, which
is a property of the JVM the build happens to start and not of the code, and a toolchain that
opened it would make the round trip pass on a plugin that still dies on a server.

```java
@ConfigSpec(header = "hunger-games")
public interface HungerGamesSpec {

    @Order(1) @Key("countdown-seconds")
    @Comment("How long the lobby countdown runs.")
    default int countdownSeconds() { return 60; }

    @Reload void reload();
}

ConfigHandle<HungerGamesSpec> handle = ConfigLoader
        .builder(getDataFolder().toPath().resolve("config.yml"), HungerGamesSpec.class)
        .envPrefix("NORDTAL_HUNGER_GAMES")
        .validator(config -> { /* plain if-statements, see jcore's README */ })
        .load();
```

What this buys, and the rules that come with it:

- **A spec interface must be `public`** — it is served by a reflective proxy. jcore rejects a
  package-private one when the config is built rather than failing later.
- **A setting the interface does not declare stops the load**, names the key with its full path
  (including its index inside a list) and suggests the one that was probably meant. The file is
  never trimmed. In a Paper plugin, catch `ConfigException` in `onLoad`/`onEnable`, call
  `getServer().getPluginManager().disablePlugin(this)` — **and then, in this repository's three
  Paper plugins, `getServer().shutdown()`.**

  **The second half is new on 2026-09-02 and reverses what this file used to say.** It said "the
  plugin goes down, the server keeps running", with `papermc-display-tags` as the worked example —
  and that is still right *there*: a third-party plugin on somebody else's server has no business
  stopping it. It is wrong here. `smp`, `limbo` and `hunger-games` are dedicated backends that exist
  to run exactly one thing, and the first deployment showed what the old rule costs: four nested
  specs without `@ConfigSpec` made `config.yml` throw, `smp` disabled itself, Paper carried on, and
  the container stayed up with a green healthcheck and no season on it — every jar was in
  `plugins/`, so the entrypoint's guard passed, and the port was open, so the image's own TCP
  `HEALTHCHECK` passed.

  **The half of this that said "nothing outside the JVM can see that state" stopped being true on
  2026-09-04.** Every one of the five long-running processes now writes `/tmp/nordtal-ready` as the
  last line of a successful start and refreshes it every 30 seconds, and `compose.yml` checks the
  file's *age* — so a plugin that never finished starting, and one that started and later stopped
  ticking, both go red. See `common/…/health/Readiness.java` and the comment on the
  `x-minecraft` anchor.

  **The rule survives that unchanged, and it is worth being exact about why.** Docker restarts
  nothing on health alone; an unhealthy container is a container that reports being unhealthy and
  keeps running. The marker makes the state *visible*, which is a different thing from making it
  *safe*. Inside the plugin is still the only place that can act on it, which is why the answer is
  still there. `:common`'s `FatalPathsStopTheServerTest` asserts all three still do it.
- **Every value can be overridden by an environment variable.** Give each config its own prefix
  (`NORDTAL_<MODULE>`); a single shared `NORDTAL` prefix makes generic keys such as `password`
  collide across files. jcore rejects a collision within one spec at load time.
- **Validate by hand** in a `ConfigValidator` — plain if-statements throwing
  `IllegalArgumentException`. Jakarta Bean Validation was considered and rejected: ~1.4 MiB in
  every plugin jar for a handful of checks.
- **`@Reload` on the spec** re-reads the file through the same strict path; wire it to the
  plugin's reload command.
- **Gson and SnakeYAML must not be shaded into a Paper plugin.** Paper 26.2 ships gson 2.14.0 and
  snakeyaml 2.6 in `libraries/`, and the plugin classloader resolves both — verified on a running
  26.2 server on 2026-08-30 with `Class.forName` in `onEnable`. `nordtal.paper-plugin` excludes
  them from `shadowJar` for that reason. They are *not* excluded for `discord-bot`, which has no
  platform to provide them.

## Build wiring

Shared build configuration lives in the `build-logic` included build as precompiled convention
plugins, **not** in a root `subprojects {}` block. A module's build file should stay a `plugins {}`
block plus its own dependencies.

| convention | applies to | gives |
|---|---|---|
| `nordtal.java-base` | everything | Java 25 toolchain, UTF-8, JUnit 6, group, `repositoryRootTestInputs`, `checkSourcesTracked` |
| `nordtal.shaded` | every deployable | shadow; thin jar moved to the `thin` classifier so `shadowJar` takes the plain name |
| `nordtal.paper-plugin` | the three Paper modules | paper-api, `:common`, `runServer` on 26.2, `${version}` expansion |
| `nordtal.velocity-plugin` | `network-control` | velocity-api as compileOnly + annotationProcessor, `:common` |
| `nordtal.jvm-app` | `discord-bot`, `updater` | `application`, Main-Class in the shaded manifest |

Every external version lives in `gradle/libs.versions.toml`. Nothing pins a version in a module
build file.

The **root** build file applies `base` and nothing else. That is deliberate and it is not the start
of a `subprojects {}` block: `base` exists there to give the root project a `check` task, which
`checkEntrypoint` hangs off so the deployment's shell test runs wherever `check` runs. Shared Java
configuration still belongs in `build-logic`.

**`app.simplecloud.api:api` is not a dependency of this build any more** (removed 2026-09-01), and
**there is no reason left for it to come back**: SimpleCloud itself was dropped the same day, so
nothing in production would provide that API at runtime. Both decisions are under "Target platform"
above. The rule it used to carry is kept only as the general one it is an instance of: an API that a
platform provides at runtime is `compileOnly` and never shaded, because a bundled copy causes
class-loading conflicts. That is the same rule Gson, SnakeYAML, Brigadier and DisplayTags follow.

**`smp` will take `com.github.nordtal:papermc-display-tags` from JitPack**, `compileOnly` and never
shaded, for the nametag API (decided 2026-09-01, `docs/smp.md#what-a-player-looks-like`). It is an
interface over a plugin that has to be installed on the server, which makes DisplayTags — and
PacketEvents underneath it — **required** on the SMP server.

## Releasing

One repo-wide version in `gradle.properties` drives everything.

**A release is a published GitHub release, not a pushed tag** (changed 2026-08-31).
`.github/workflows/release.yml` runs on `release: [published]`, so `git push --tags` on its own
builds nothing - tag, then publish the release. The old `push: tags` trigger never fired for a
release published from a tag that already existed, which is the ordinary way to make one; that
release would have carried no assets at all. `workflow_dispatch` with a tag re-runs a build that
failed. The workflow refuses a tag that disagrees with `gradle.properties`, runs
`./gradlew check releaseArtifacts` (`check` first: a release must not ship untested jars),
verifies the pack zip against its own `.sha1` and that `pack.mcmeta` sits at the zip's root,
attaches four plugin jars + the bot jar + the pack zip and its `.sha1` to that release, and pushes
`ghcr.io/nordtal/discord-bot:<version>`. It deliberately does **not** touch the release notes.

`.github/workflows/build.yml` runs `./gradlew build` on every push to `main` and every pull
request. Until 2026-08-31 the release workflow was the only CI in the repository, so a compile
error on `main` stayed invisible until somebody tagged. **Since 2026-09-02 it also builds both
Docker images and pushes neither** - the bot's and the updater's. Before that, `release.yml` was
the only thing that ever built an image, so a `COPY` of a file the module's `.dockerignore`
excludes could not fail before a release; that is precisely what happened to the bot image on
2026-09-02, and the updater's image is worse off still, because nothing but `docker compose` on
the host ever builds it.

**`build.yml` has run and failed; `release.yml` is still unexercised.** The first run on `main`,
2026-09-02, failed on `:updater:compileJava` with `package eu.nordtal.s2.updater.run does not
exist` - the package was in the working tree and not in the repository, because `.gitignore`
carried a bare `run/` that matched the Java package as readily as `hunger-games/run`. An *ignored*
file is not an untracked one, so `git status` stayed clean and the local build was green
throughout. Fixed 2026-09-02 by anchoring every directory pattern in `.gitignore` and by
`checkSourcesTracked`, which asks Git the same question on every local `./gradlew build`; see
`build-logic/src/main/kotlin/CheckSourcesTracked.kt`.

**`release.yml` has now run, and this file said the opposite until 2026-09-02.** It claimed "there
are still no releases on the remote" while **v0.1.0, v0.2.0 and v0.2.1 were all published**; the
v0.2.1 run succeeded, attached eight assets, and pushed all four images to `ghcr.io/nordtal`, from
where they were pulled for the first full deployment. The sentence is kept as a correction rather
than deleted, because it is this repository's most-repeated failure mode: a document that describes
the state at the moment somebody wrote it and is read as the state now.

`season-2` itself produces no combined build, and does not republish jars built in other repos.

## discord-bot

Season 2's Discord bot. **Stage C is implemented (2026-08-30)**; the concept is
[docs/access-system.md](docs/access-system.md). Read it before changing anything here or in the
access path in `common` and `network-control`.

It sells **access periods** (30/60/90 days at 3/5/7 €, optional +5 € donation) bound to a Discord
account, paid by bunq.me card payment only. Season 1's contribution tiers, bank transfer, receiver
select and balance voice channel are gone. The database is the source of truth for access, donor
status and language; Discord roles are a projection of it and LuckPerms is not involved.

**Stage C added the proxy login gate (`network-control`) and account linking end to end.** The two
managed link messages now carry a working button that opens a modal for the code; `/unlink` is a
self-service, no-waiting-period command that always writes to the admin channel. `link_code` is
issued by `network-control` (`AccessDirectory#issueLinkCode`, package-private SQL in `common`'s
`AccessDao`) and redeemed by the bot (`AccessDirectory#redeemLinkCode`), so the sweep in
`ReconcileDao#deleteExpiredLinkCodes` now actually cleans up rows something else writes, rather
than a table nothing has ever put a row into.

**Two things this stage left open were decided on 2026-08-31, and BOTH ARE NOW IMPLEMENTED** -
this paragraph said "neither is implemented yet" until 2026-09-04, which is the same drift
`docs/state-of-play.md` opens by warning about. `AccessSpec` carries a comment where the retired
setting used to be and `ConfigsTest` asserts the key is refused by name; `MisconfiguredGate` is
registered by `NetworkControlPlugin` and refuses every login with a bilingual screen. Both entries
are kept because the reasoning is what a future change has to argue with:

- **`access.yml#link-code-ttl-minutes` is retired; `gate.yml#link-code-ttl-minutes` is the only
  one.** The bot never read its own copy - `ReconcileDao#deleteExpiredLinkCodes` only compares
  `expires` to `now()` - and the proxy is the process that calls `AccessDirectory#issueLinkCode`,
  so it is the only one that can act on a TTL at all. Delete the field from `AccessSpec` rather
  than wire it through. **Free only while nothing is deployed:** a key the interface does not
  declare stops the load, so an `access.yml` in the wild carrying the retired key would refuse to
  start.
- **A bad `network-control` config must fail closed.** Today the gate is never registered and the
  proxy keeps accepting logins **un-gated**; that is to become a `LoginEvent` handler that refuses
  *everybody* with a "network misconfigured" screen. Velocity has no per-plugin disable, which is
  what the old behaviour was justified with - but a deny-all login handler *is* that disable, built
  by hand. "The proxy is up but nobody can join" announces itself; "the proxy is up and the gate is
  off" never does. Exempting admins is impossible: the admin flag lives in the database a bad
  `database.yml` cannot reach.

### Shape

| package | what |
|---|---|
| `config` | `access.yml`, `bot.yml`, `database.yml` and every validation rule |
| `bunq` | `BunqGateway` (tabs, cancellation, result inquiries, recent payments), `Money` |
| `payment` | `payment_request` and its state machine, the price list, the poll loop |
| `discord` | managed messages, the purchase flow, roles, guild state, admin commands, the admin log |

- **The purchase flow's state is the `payment_request` row**, not a cache. Season 1 kept it in
  Guava, so a restart answered "setup expired" to everyone mid-purchase. A request is written when
  the day count is picked, before a bunq tab exists; `bunq_tab_id IS NULL` is exactly the
  difference between "chose 60 days" and "asked for a payment link".
- **Closing a request closes its bunq tab** (`BunqMeTabApiObject.update(id, account, "CANCELLED")`),
  never just a status flip. `Purchases` is the only place that does either.
- **Matching is two paths, one gate.** Primary: a tab knows the payments that settled it
  (`getResultInquiries()` → `getPayment()`), which needs no text parsing. Fallback: recent payments
  are scanned for `NT-[0-9A-F]{6}`. Both are gated by `payment.watermark` — without it the first
  run books up to 50 historical payments, roles and messages included.
- **The settlement rule lives in `Tiers.resolve` and is asymmetric.** The `payment_request` row
  records what was ordered, and when the money covers that total **exactly what was ordered is
  granted** — the tiers are not re-derived from the amount. Only a payment that falls *short* is
  downgraded to the highest tier it covers. Surplus above the ordered total is a donation once it
  reaches the surcharge, otherwise ignored. The amount-only rule survives as `resolve(int)` for the
  orderless case and is currently unreachable in production, because an unknown reference is raised
  to admins rather than booked. The earlier version derived both directions from the amount, so
  paying the asked-for 10 € on a 60-days-with-donation order bought 90 days and no donor role;
  corrected 2026-08-30 and covered by `TiersTest`.
- **A payment on a reference that is not `OPEN` is never booked automatically.** It goes to the
  admin channel with `/settle` as the manual path.
- **The access role is bot-owned; the donor role is never removed.** The reconcile reads JDA's
  member cache (chunked once when the session opens) against one indexed query — not
  `loadMembers()` on a timer, which is what season 1 did every ten seconds.
- **Blocking work is off the gateway threads.** bunq HTTP calls and the database work behind an
  interaction run on `access-bot-worker`; an interaction not acknowledged within three seconds is
  dead, and a gateway thread waiting on a bank stalls the whole guild.

### Persistence

- **It is the only module that depends on `jcore`** (`com.github.nordtal:jcore:3.0.0`, via
  JitPack), which exports jdbi3-core, jdbi3-sqlobject, slf4j-api, commons-lang3, commons-io, gson,
  snakeyaml and `org.jetbrains:annotations`, and brings HikariCP, Flyway, jdbi3-postgres and the
  PostgreSQL driver at runtime. The shaded jar is ~30 MB — fine for a container, not for a plugin.
  It also depends on `:common` for the access API and the message system; `:common` declares its
  JDBI/Hikari/slf4j `compileOnly`, so that adds no second copy of anything.
- **jcore does not export a logging backend** (logback is `testRuntimeOnly` there). The bot
  declares `ch.qos.logback:logback-classic` itself. Remove it and every log line disappears behind
  one "no providers found" warning.
- **The schema is applied here**, by `database.migrate()` at startup, but **the `.sql` files live in
  `:common`** (`common/src/main/resources/db/migration/`, moved 2026-08-31) next to the API that
  reads those tables. `database.migrate()` takes no argument and scans `classpath:db/migration`;
  `:common` is shaded into this module, so the files are exactly there. A column change is now one
  edit in one module. `:common`'s tests apply the same classpath location rather than keeping a
  copy of the DDL.
  - `V1__access.sql` (stage A): `discord_user`, `account_link`, `link_code`, `payment_request`,
    `access_grant`, `audit_log`.
  - `V2__bot_state.sql` (stage B): `managed_message`, `payment_notice`, `expiry_notice` — three
    tables that exist so something the bot does exactly once survives a restart. Without them a
    restart posts a second managed message, re-pings admins about the same payment every poll, and
    re-sends yesterday's expiry DMs. `V2__legacy_contribution.sql` and the whole `contribution`
    code path were deleted in stage B.
  - `V3__bot_setting.sql` (stage B): `bot_setting`, holding the payment watermark. **The watermark
    sets itself** — the first start that finds none stores that instant and nothing ever rewrites
    it (`ON CONFLICT DO NOTHING`, no update path anywhere). `payment.watermark` in `access.yml` is
    an optional override that does *not* replace the stored value, so removing it falls back to the
    original first start rather than to the current restart. It was a guessed date in the config
    until 2026-08-30, which is a value nobody can get right in advance: too early books up to 50
    historical bunq payments, too late silently ignores real purchases.
    A separate migration rather than an edit to `V2`, because `V2` has been applied to local
    databases and Flyway validates checksums — rewriting in place (as stage A did to `V1`) is only
    safe while nothing anywhere has run it.
  - `V4__phase_admin_playtime.sql` (2026-08-31): `season_phase` (one row, a boolean primary key
    pinned by a CHECK so a second row is impossible), `discord_user.admin`, and `player_playtime`
    — the last of which carries **no `smp_` prefix** because the proxy writes it, not the SMP.
  - `V5__hunger_games.sql` (2026-08-31): `hg_game`, `hg_team`, `hg_member`, `hg_event`, with a
    partial unique index enforcing at most one non-`DECIDED` game.
  - `V6__smp.sql` (2026-09-01): `smp_player`, `smp_aura_event`, `smp_milestone`, `smp_objective`,
    `smp_contribution`, `smp_poi`, `smp_grave`, `smp_duel`, `smp_spin`. Progress only — a milestone
    is *defined* in the plugin's reloadable YAML, never here. **`smp_duel` is gone as of V10**; the
    row is left in this list because the migration is still applied and its `CREATE TABLE` is still
    what a fresh database runs first.
  - `V7__update_request.sql` (2026-09-01): the one row the updater is driven by - `/update` in
    Discord and `/smp update` in game both write here, and nothing calls that container. It was
    missing from this list until 2026-09-04, which is how a reader came to believe the numbering
    skipped a version (`docs/state-of-play.md` finding 56).
  - `V8__pre_launch.sql` (2026-09-03): the fifth `SeasonPhase`, `PRE_LAUNCH`, and the
    `season_phase.launch` column the countdown in the MOTD and in the three pre-opening disconnect
    screens is measured against. A `DROP`/`ADD CONSTRAINT` rather than an edit to `V4`, for the
    reason `V3` already wrote down. The seeded row moves from `PRE_EVENT` to `PRE_LAUNCH`, guarded
    so a database somebody has already switched by hand is not dragged backwards.
  - `V9__smp_start.sql` (2026-09-03): `season_phase.smp_start`, the second season date - the day
    paid access starts running, which is what a period bought weeks earlier is anchored to. Moving
    it shifts every grant that has not started yet; `PhaseDirectoryIntegrationTest` owns that
    arithmetic, including the DST trap that makes the shift seconds rather than days.
  - `V10__drop_smp_duel.sql` (2026-09-04): drops `smp_duel`. The table was created in V6 with two
    carefully paired constraints - one of them justified in its own comment by "the aura books
    disagreeing with the duel history" - and **nothing ever wrote a row to it**. `Duels` books the
    two `smp_aura_event` rows and forgets the duel, so there was no history for the constraint to
    disagree with. Dropped rather than filled in, decided by the owner 2026-09-04
    (`docs/state-of-play.md` finding 49). The aura side of a duel is untouched.
- **Money is integer cents** in Java and in the database. `Money` is the only place that converts
  to and from bunq's decimal strings, and it goes through `BigDecimal`. Season 1 used
  `Float.parseFloat` and `<`.
- **It shadows a bunq SDK class.** `src/main/java/com/bunq/sdk/http/BunqRequestBuilder.java` is a
  patched copy from `com.github.bunq:sdk_java`, in the library's own package so it wins on the
  classpath. JDA pulls **OkHttp 5**, where `Request.Builder.delete()` is `final` and
  `okhttp3.internal.Util` is gone — and the SDK's original overrides exactly that method. Do not
  delete it; re-check it on any bunq SDK or JDA bump. Diffed against the 1.28.0.6 sources
  2026-08-30.
- The Dockerfile is runtime-only: Gradle builds the jar, `docker build --build-arg JAR=...` wraps
  it. A self-contained build stage would have to copy this whole multi-module repo.

### Configuration

Three files under `config/`, loaded through jcore 3.0.0, each with its own environment namespace —
`NORDTAL_BOT_*`, `NORDTAL_DATABASE_*`, `NORDTAL_ACCESS_*`. A shared `NORDTAL` prefix was
deliberately not used: generic keys such as `password` would collide across files.

- `access.yml` is new in stage B and carries **everything that used to be an enum or a constant**:
  tier days and prices, the donation surcharge, the guild id, four role ids, the admin channel id,
  the `languages` list (each entry carrying a role and two channel ids), the poll interval, the
  request TTL, the watermark and the reminder lead time. A price change is a config edit, never a
  release.
- **The `languages` list is the only source for the language roles and the per-language channels**,
  since 2026-08-31. `roles.german`, `roles.english`, `channels.contribution-en|de` and
  `channels.link-en|de` are **deleted** — they were a second source of truth for the same ids and
  made a third language a code change. `eu.nordtal.s2.discordbot.config.Languages` is the one place
  that reads the list: `GuildState` mirrors a member's role through it, `ManagedMessages` publishes
  two messages per entry (the `managed_message.kind` is derived from the tag, so `CONTRIBUTION_EN`
  and friends keep their primary keys), `PaymentProcessor` picks the thank-you channel through it,
  and `AccessBot` loads message bundles for exactly the configured tags. Adding a language is an
  entry plus a `<tag>.properties`; see [docs/i18n.md](docs/i18n.md).
- **The ids default to empty and the bot refuses to start until they are filled in.** Season 1
  shipped real channel and role ids as defaults, so a config that failed to load wrote into a
  production channel. Prices do have real defaults; ids never will.
- `bot.yml` gained `bunq.environment` (`PRODUCTION` / `SANDBOX`). It was hardcoded, which made the
  sandbox test in the concept impossible to run. The bunq context file belongs to one environment:
  switching also means pointing `bunq.context-path` at a fresh file.
- **The tiers are a list**, so a fourth tier is a config edit and not a release. The obstacle was
  that jcore initialises a `List<NestedSpec>` to empty, which would ship a fresh install with no
  prices — solved with `Specs.createUnsafe` in `DefaultTiers`, which builds real default entries
  that jcore's writer serialises and reads back (**verified 2026-08-30**, not assumed). A fresh
  `access.yml` therefore comes out carrying 30/60/90 at 3/5/7 €. Validated at load: not empty, day
  counts unique, price rising with days — and an empty list fails with the YAML to write.
  A tier is identified by its **day count** (that is what a purchase button carries), so editing
  `days` on an existing entry retires that tier.
- **The one-time jcore 1.x `config/*.json` conversion was removed.** It existed to carry season 1's
  deployed config volume forward, and nothing is ever migrated between seasons — new bot, new
  database, new Discord application, new volume.
- The bot fails fast: all three files are read and validated before anything with a lifecycle
  starts, and `main` exits 1 on a `ConfigException`.

## updater

**New 2026-09-01, all six steps built** ([docs/updater.md](docs/updater.md)). A standalone JVM
application like `discord-bot`, and it is **both a long-running service and a set of one-shot
commands** — the same program either way:

```
docker compose up -d updater             # `serve`: migrate, then wait for requests. What compose runs.
docker compose run --rm updater          # resolve and report, changes nothing
docker compose run --rm updater migrate  # apply the database schema, nothing else
docker compose run --rm updater apply    # migrate, then fetch and place the files
```

**It has no compose profile**, so it is in every selection: it is the only process that applies the
schema, and it is what answers `/update` in Discord and `/smp update` in game. Everything else in
the stack has `depends_on: updater: service_healthy`, and it becomes healthy the moment the schema
is current (it touches `/tmp/updater-ready`).

It resolves the newest version of every jar the network runs — the six season-2 jars and the pack
from the GitHub releases API, DisplayTags from the fork's releases, PacketEvents and Chunky from
Modrinth filtered to `26.2`/`paper`, Paper and Velocity from the PaperMC Fill API — compares that
against the jars in the mounted volumes, and prints the difference. `apply` then installs what
differs and writes the proxy's `pack.yml` — after applying the schema, so a plugin never comes up
against a schema older than itself.

**It owns the plugin jars now, and `deploy/minecraft/entrypoint.sh` does not.** That script fetched
them until 2026-09-01; `SEASON_PLUGINS`, `EXTRA_PLUGIN_URLS`, `SEASON_RELEASE`, `PACK_URL` and
`PACK_SHA1` are gone from the deployment. Two owners of the same file is one owner too many — the
entrypoint deletes by filename prefix, so it would have deleted the very jar the updater had just
fetched. What is left in its place is a coarser guard: an empty `plugins/` folder stops the
container.

**It owns the bot's jar and its own too.** Both containers run `<name>-*.jar` out of a volume the
updater fills, and fall back to the jar baked into their image only while that volume is empty —
which is a first deployment and nothing else. So `SEASON_VERSION` and `BOT_VERSION` are a floor
rather than a version, and the updater's own version moves the same way everything else's does: the
new jar is placed, the running process carries on with the old one, and the restart picks it up.

Seven rules that are easy to break and expensive to break:

- **Its report is stdout and its logs are stderr.** That is what `logback.xml` in this module is
  for, and why it uses `<encoder>` rather than `<layout>` — a deprecated `<layout>` makes logback
  print its whole startup status report *on stdout*, on top of the thing a person is meant to read.
- **`serve` is not a scheduler and must never become one.** It migrates once at startup and then
  does nothing at all until a row appears in `update_request` — no timer, no watch, no "check for
  updates on boot". A crash restart at three in the morning must not move a version. The container
  having a restart policy does not change that; adding a timer would.
- **Nothing is rendered twice.** The Discord embed, the chat lines and the table all carry the
  updater's own report verbatim. A second rendering somewhere is the thing that eventually
  disagrees with the first.
- **Exactly one `serve` may run, enforced by its own advisory lock (`nordtalS`) since 2026-09-02.**
  `settleOrphans` closes every row left `RUNNING` because "nothing is running those rows: the only
  process that claims one is an updater, and this one has just started" - a premise that is true of
  one serve and false of two. A second serve marks the first one's in-flight `APPLY` as `FAILED`,
  the real one's `finish(...)` then matches nothing, and the report of the run that was installing
  jars is replaced by "the updater stopped while this request was running". Producing two was easy
  until the same day: `docker compose run` inherits the service's `command`, so the documented
  read-only report started a daemon. Both halves are fixed - `report` has a name, and the premise is
  now a fact.
- **An apply takes the advisory lock, and the second asker is refused, not queued.** The daemon and
  a hand-run `apply` overlap on exactly the day somebody is bootstrapping. A plan resolved now is
  stale by the time a queued run would start.
- **Filenames are the identity of what is installed**, split by `JarName` on the last `-`, which is
  `${file%-*.jar}` out of `deploy/minecraft/entrypoint.sh`. Inventing a better rule here means two
  programs disagreeing about which jar supersedes which, and the way that surfaces is Paper loading
  two versions of one plugin without complaining.
- **`Topology` and `compose.yml` are two copies of one fact.** A fifth backend server is a
  change to both in the same commit; `TopologyTest` reads the real compose file and fails otherwise.
  It also fails if `SEASON_PLUGINS`, `EXTRA_PLUGIN_URLS` or the two `PACK_*` variables come back.
- **`compose.yml` and `.env.example` live at the repository root** (moved 2026-09-01). Arcane's
  GitOps sync pulls only the directory the compose file is in. The original second half of this
  rule — that two build contexts are above `deploy/` — expired on 2026-09-02 when the host stopped
  building anything; it is a convention now, not a hard requirement, and both files say so. Do not
  argue a future change down with the expired half.
- **ARCANE PULLS AND NEVER BUILDS, so every image of ours has to be published** (learned the hard
  way 2026-09-02, finding 31 in `docs/state-of-play.md`). Redeploy pulls; building is a separate
  action in its interface. An image tagged for a local build — `ghcr.io/nordtal/minecraft:local`
  was the real one — fails a deploy with `error from registry: denied`, which is *also* what a
  private package answers, so the message identifies nothing. All four images are pushed by
  `release.yml` and every `image:` in `compose.yml` defaults to a `ghcr.io/nordtal` reference tagged
  from one `IMAGE_TAG`. `TopologyTest` fails if that stops being true. A `build:` block beside an
  image proves nothing about production and is there for local development only.
- **A `:?` is forbidden in a `build:` arg.** Compose interpolates build args on a deploy that only
  pulls, so a required variable there stops Arcane before it fetches a single image. `SEASON_VERSION`
  and `BOT_VERSION` are defaulted for exactly this reason.
- **`serve` fills empty volumes at startup, and that is bounded on purpose.** It installs only
  artefacts with *nothing* installed (`UpdatePlan#onlyMissing()`), so "a crash restart at three in
  the morning does not move a version" stays literally true. Widening it to `isWork()` would break
  the module's first rule; if a future change needs upgrades at startup, that is a decision to take
  out loud and not a filter to relax. A failure there must not block the readiness marker — `serve`
  runs on every restart of a *running* network, not only on a fresh one.
- **Arcane's redeploy takes two IDs, not a name.** `POST /api/environments/{id}/projects/{projectId}/redeploy`,
  read from Arcane's own source at v2.10.0. `arcane.project` is a UUID and *not* the compose project
  name `nordtal-s2`; it has no default and the updater refuses to start without it once
  `arcane.base-url` is set. A 2xx still proves nothing on its own — see
  [arcane#1943](https://github.com/getarcaneapp/arcane/issues/1943), finding 30 in
  `docs/state-of-play.md`.
- **A swap is two phases, and a server moves together or not at all.** Everything is staged inside
  the target volume and only moved in once all of it is there. Four servers on two versions of the
  season is worse than four servers that did not update.
- **"Skipped" is a third answer, not a quiet kind of "fine".** A run where every volume was
  unmounted did no work and had no failure, and closing it with "Nothing needed doing" is how
  somebody reads it as "the network is current". Found on a real container run, 2026-09-01.

`updater.yml` is the one config in this repository whose defaults are the real values and where a
freshly written file is what you want: the repositories, the two Modrinth project ids and the
platform versions are facts about this project, not about a deployment.

## resource-pack

Brought in with `git subtree` from `nordtal/smp-resource-pack` with its history intact, so
`git log -- resource-pack/` still explains the assets. The standalone repo is superseded.

`packZip` builds reproducibly (fixed file order, no timestamps) so the same version always hashes
the same, and writes the SHA-1 next to the zip. The client is sent the URL *and* the hash and
refuses the pack if they disagree — never hardcode a hash.

## Verification

**The deployment's shell has a test too, and it is the only one.** `deploy/minecraft/entrypoint-test.sh`
sources `entrypoint.sh` and drives its seeding against fixture directories — ten cases, no Docker,
no network — and runs on `check` through the root build's `checkEntrypoint`. Everything else in
`deploy/` is verified by running it and looking; that one function is exempt because it **deletes a
world folder** on a container that starts by itself, and on the SMP that folder is the season. The
guard it tests is itself the repair of a guard that stopped `smp` and `hunger-games` on every start
from v0.2.3 — see `deploy/README.md#first-start-seeding`. `entrypoint.sh` therefore carries a source
guard at the line where its definitions end; do not move code across it without reading the comment
there.

**Seven modules have tests: 972 in total, none skipped, all green** (`./gradlew build` with a Docker
daemon present, 2026-09-04). The counts below are what the JUnit XML reports, not
`@Test` counts.

| module | tests |
|---|---|
| `smp` | 163 |
| `common` | 256 |
| `network-control` | 189 |
| `updater` | 136 |
| `discord-bot` | 155 |
| `hunger-games` | 62 |
| `limbo` | 11 |

This said "537 in six modules" until 2026-09-02 and was wrong twice over: the number was stale, and
**`:updater` was missing from the list entirely** — a module with 129 tests, including the only ones
in this repository that drive a PostgreSQL advisory lock. A count that omits a whole module is worse
than no count, because it reads as complete.

`:common` has **256**. Eleven are `BoardFrameTest`, added 2026-09-04 with the board frame, and it
is the one test in this repository that runs the client's own layout: it reads `board.json`, derives
every code point's advance the way the client does - a space provider's number, or a bitmap's
rightmost non-transparent column plus the two pixels Minecraft adds - and then *walks* the composed
string with a cursor. So it can contradict `BoardFrame` rather than restate it. What it pins is the
one invariant the whole class exists for: **a row hands back a cursor at the content column**, because
the width of a line of text is the single thing nothing here can compute, so nothing may be placed
after the content. Eleven more are the menu panel added the same day. `MenuTitleTest` (8)
holds the panel's offset arithmetic against the pack rather than against its own constants: it
reads `gui.json` and the six panel PNGs, derives the 177px advance from the texture's own width,
and asserts the composition's net displacement is **zero** - `-8 + 177 - 169`, so the readable
title lands exactly where an unframed one would. `ChestOnlyMenuTest` (3) is the one that exists
because a document said it did: `docs/presentation.md` claimed "a test asserts no menu opens a
non-chest inventory" from the day the style sheet was written, and no such test existed. It now
asserts that, and that every inventory title goes through `MenuTitle` - with a named allowlist of
the four menus still waiting for their panel, so the remaining work is in the build instead of in
a document.

Six more came with the chat format on 2026-09-04. Three are `MessageRendererTest`'s, for the
overload that substitutes a **component** rather than text: vanilla's death message and an
advancement's title are `TranslatableComponent`s, so every reader's own client renders them in that
reader's language, and a trip through `String` would settle the language on the server, once, for
everybody. They arrive as `<_name>` MiniMessage tags, and the underscore is not decoration - it is
what lets `MessageBundlesTest` tell a slot from a style tag and hold both languages to the same set
of them, in `smp` and in `hunger-games`. **An unresolved slot renders as nothing at all, in
silence**, which is a worse failure than a printed `{name}` and is why it is checked. The other
three are `WorldEffectVocabularyTest`, which makes the same rule about particles that
`SoundVocabularyTest` makes about sound - and, like it, was written while the answer was still one
file and four call sites. One of its three counts rather than greps: every place in the effect
adapter that spawns a firework has to stamp it in the same method, because an unstamped rocket is
one the damage handler will not recognise.

Twenty-two more are the sound vocabulary added on 2026-09-04 -
`SoundVocabularyTest` (3) and `FeedbackSoundsTest` (8), plus the readiness pair. The first of those
is the one that carries a rule rather than an assertion: it walks all four client-facing modules'
`src/main` for `playSound(`, `org.bukkit.Sound`, `net.kyori.adventure.sound.` and a bare `Sound.`
constant, with a named allowlist of one file and its reason, exactly the way `OneMessageFormatTest`
polices message construction. It was written while the answer was still *zero* call sites, which is
the cheap moment: after the first exception exists a rule like this is an argument rather than a
fact. Thirty-three more came from the review of 2026-09-04 - `ResourcePackTest`
(8, the pack's three mirrors held against each other), `MessageOverridesTest` (9, the operator's
key-by-key override), `TabListTest` (4, one tab list written by three servers), `OneMessageFormatTest`
(4, one message format and a named allowlist of what still composes by hand), `BossBarFontTest` (3)
and `MessageRendererTest` (5). The older ones: `AccessDirectoryIntegrationTest` (46) and `LinkCodeIntegrationTest` (12) drive
the access API and the link-code lifecycle against a real PostgreSQL container running the real
migrations off `classpath:db/migration` — which also proves the location the bot depends on
resolves, and now applies V1 through V9. `PhaseDirectoryIntegrationTest` (26) does the same for the
phase row, its audit entry and the two dates on it - twelve of those cases are the grant shift that
follows `smp_start` when it moves, including the two a table-wide delta gets wrong (two buyers on
different days both starting at the opening, and a stacked pair staying stacked) and the DST trap
that makes the shift seconds rather than days. `SeasonDatesTest` (8) pins the one date format both
`/phase` commands share, the summer/winter offset it derives, and its refusal of `2026-02-30`. Five of the access cases are the season-start
anchor: a period bought weeks before the SMP opens has to begin when it opens, two such purchases
have to stack into one run, and a lapse after the opening has to start today rather than back at
the anchor — periods are never summed. `MessagesTest` (10), `PlayerLocalesTest` (7), `LocalesTest` (3),
`SeasonPhaseTest` (3) and `LimboProtocolTest` (11) are in memory - the last of those round-trips
every `nordtal:limbo` message and pins the two header bytes, because a proxy and a backend of
different versions that stop understanding each other produce a player stuck in the waiting room
with nothing in any log. The `make_interval(hours => days * 24)` bug from stage A (see
below) is still the reason a day is never expressed in SQL as `interval 'N days'`.

`discord-bot` has **155**: `ConfigsTest`, `LanguagesTest`, `PhaseCommandTest`, `TiersTest`,
`RedemptionLimitTest`, `StatusNameTest` and `GuildStateTest` in memory, `AdminFlagIntegrationTest`
and `PaymentRequestIntegrationTest` against a container, plus the `hungergames` package's own.
The three added on 2026-09-03 are each a rule that cannot be exercised against a real guild without
waiting for one: `RedemptionLimitTest` moves a clock through the sliding window that makes a
four-character link code safe and races eight threads at one account's last attempt, `StatusNameTest` pins every channel name against the real message
bundles — the granularity there *is* Discord's two-renames-per-ten-minutes budget — and
`GuildStateTest` covers the completeness check that decides whether the startup reconcile may
delete account links at all. `LanguagesTest` owns every rule the `languages` list decides — which of
several held roles wins, which channel a locale posts in, what a `managed_message.kind` is called —
because none of the three classes that use them (`GuildState`, `ManagedMessages`,
`PaymentProcessor`) can be exercised without a real guild. `LinkFlow` itself is still untested by
anything but `common`'s DAO-level tests and a manual guild check; the cap it now enforces is tested
on its own, in `RedemptionLimitTest`, which is the half that has arithmetic in it. Three of
`ConfigsTest`'s cases (added 2026-09-01) pin `.env.example` rather than the bot: the JSON
language list and price list it ships have to come back out of jcore's environment overlay as a
list of specs, and its `REPLACE_ME` placeholders have to be *refused by name*. A `.env.example`
whose structures do not parse is worse than none. **Those cases read the real file since
2026-09-02** — they held a hand-copy of both blocks until then, which is two sources of truth with
nothing comparing them (finding 19 in `docs/state-of-play.md`, now closed). Two rules come with
that, and both are easy to undo by accident:

- **A test that reads a file at the repository root declares it**, through
  `repositoryRootTestInputs { reads("…") }` in the module's build file, wired into the test task by
  `nordtal.java-base`. Without it Gradle cannot see the file — it is in no source set — so editing
  it leaves the task UP-TO-DATE and the one check that would have caught the drift is the one that
  does not run. `:updater:test` declares `compose.yml` the same way, for `TopologyTest`.
- **The lookup anchors on the directory holding `settings.gradle.kts`, not on the nearest file by
  name.** `discord-bot/` shipped an `.env.example` of its own until 2026-09-02 — a leftover of the
  bot's standalone compose deployment, pinning `BOT_VERSION=0.1.0` — and a walk-up by name found
  that one, from the module directory, in preference to the real one. It was deleted with this
  change; the anchor is what stops the next one shadowing the root file silently.

`network-control` has **189**: `FallbackCacheTest` (in memory, driven by a settable `Clock` rather
than `Thread.sleep`) covers the four fallback rules; `ConfigsTest` covers `database.yml` and
`gate.yml`, `pack.yml` and `network.yml` - all four handles it has; the `phase` and `routing`
packages are tested as pure decisions, exhaustively over the
five phases; and `PlaytimeDao`'s `seconds = seconds + EXCLUDED.seconds` gets a container, because no
in-memory test can say anything about it. The pack station adds `LimboHoldTest` (the release rule,
exhaustively over its three inputs) and eight more `ConfigsTest` cases for `pack.yml` - of which the
one worth keeping is that a `sha1` which is not 40 hex characters stops the proxy, because every
other way of finding a bad hash costs a player a `FAILED_DOWNLOAD` that reads as a network problem.
**`WaitingBookTest` (15, new 2026-09-03) is the one that came out of a real client.** It drives all
six orders in which the arrival, the pack status and `limbo`'s `READY` can reach the proxy, because
Velocity orders none of them against each other and the old code only worked in three of the six. It
fails on the old semantics — checked by putting the bug back — and it is the standing proof of the
rule that replaced them: **no single plugin message may be able to strand a player.** See
`docs/state-of-play.md` finding 38.

`limbo` has **11**, seven of them `ConfigsTest`. The other four are the only ones the rest of the
module can have: everything else in it is a world, a title, a potion effect or a plugin message.
(This paragraph said "4" until 2026-09-01, counting `WaitingTextTest` alone - the totals above were
right, the sentence was not.) What the four cover is that every `WaitReason` has
a title and a subtitle in both languages and that no title runs past forty characters - a missing
key there is not one wrong line among many, it is the literal string `limbo.wait.backend.title` on
an otherwise black screen.

`smp` has **163**. The newest is one case in `MessageBundlesTest` holding the two languages to
the same `<_component>` slots, added with the chat format - see `:common` above for what a slot is
and why an unresolved one is silent. Eight are `WheelStripTest`, new 2026-09-04 with the animation: over every pool
size and every winner, the last frame has to centre the prize the database already gave away. That is
the property the wheel rests on - the spin is spent in SQL before a frame is drawn, so an animation
that could stop anywhere else would be a second, disagreeing answer about one spin. `SoundDefaultsTest` (6) is the newest and the only one that can say anything
about a sound without a server: every one of the ten defaults resolves against `org.bukkit.Sound` as
compiled for this Paper version (via `getField`, which initialises no registry), blanking a key in
the real `sounds.yml` really does silence that one category and nothing else, `refused` and
`countdown-tick` do not ship as the same note block, and a `/smp reload` reaches the single
`SmpSounds` instance every listener was handed at enable - which is the whole reason the sounds are
their own file. `MilestonesTest` (9) writes a
fresh `milestones.yml`, reads it back and asserts the whole track survives — which is also the proof
that two levels of jcore nesting work; `AuraPayoutTest` (12) covers the 30/70 split, the 2 %
threshold, the concept's own worked example, the case it never named (more qualifiers than there is
aura in the pot) and the invariant that the pot is never overspent; `TrackValidationTest` (12)
asserts both halves of the reload rule, including the one that must *permit* a lowered target;
`PrestigeTest` (8), `ObjectiveProgressTest` (7) and `DeathPenaltyTest` (8) complete the server-free
half. Four are new with the world half on 2026-09-01 and every one of them is still pure — the
Bukkit-facing classes were written so the decisions could be taken out and asserted: `BoxesTest` (7)
holds the inclusive-corner arithmetic and the balloon's radius 10–21.5 band, `DailyScheduleTest` (6)
computes the reset delay against fixed clocks rather than the wall, `BalloonMenuTest` (8) pins the
2 × 2 layout as a table, `SeasonStateTest` (6) covers where the database's progress meets the file's
definition, and `MessageBundlesTest` (4) keeps the two language files symmetrical in keys and
placeholders. What no test here covers is the world itself; that is what the drills and the
rehearsals are for.

`hunger-games` has **62**. One is the `<_component>` slot check its `MessageBundlesTest` gained
with `smp`'s on 2026-09-04: this module has no component slot yet, and the check is here so that the
first one is not the one that discovers the rule is missing. Fourteen more are new the same day: `SoundDefaultsTest` (6) and
`ConfigsTest` (4) - this was **the last module with configs and no `ConfigsTest`**, the exact gap
that once cost `smp` its whole plugin - plus `KillCountsIntegrationTest` (4), the only test in this
module that needs a container and the one that made the ceremony's main-thread query loop safe to
replace. The other 47 are in memory and all of them arithmetic the game would otherwise get
wrong in front of players: `BorderMathTest` (the step, the extension of a running shrink, the
divide-by-zero floor at one participant), `TeamColoursTest` (evenly spaced hues and the
nearest-named mapping), `DemotionTest` (a duo whose partner never showed becoming a full-hearted
solo), `SpawnTowersTest` and `TiebreakTest`. It dropped from 57 on 2026-09-01 without losing a
single assertion: `BearingTest` and `BossBarWidthTest` moved into `:common` with the two helpers
they cover, because the SMP's HUD draws the same bar with the same glyphs and two copies of a glyph
composition are two things that drift apart the first time a segment is redrawn. Three are new on
2026-09-01, after a sweep found nine message keys in this module that no line of code could reach:
`CountdownTest` pins when the (previously silent) lobby countdown speaks, `WinOutcomeTest` pins that
the four ways a game can end stay four distinguishable shapes, and `MessageBundlesTest` asserts the
two language files carry the same keys with the same placeholders — a key added to one file and not
the other reaches a player as the literal string `hg.start.countdown`, because `Messages` degrades
to the key rather than throwing.

`updater` has **133**, and this section did not mention the module at all until 2026-09-02.
`TopologyTest` reads the real `compose.yml` and is what keeps that file and `Topology` from becoming
two facts - it now also asserts that every plugin a service runs is one that service's entrypoint
guard asks for, and that the Paper backends cap players at the same number. `ApplierTest` covers the
two-phase swap, the all-or-nothing rule and both of its exceptions; `ResolverTest`, `GitHubReleasesTest`,
`ModrinthTest` and `PaperFillTest` drive every source against recorded responses, so a
`-sources.jar`, a pre-release and a renamed asset are all properties of a plan rather than things
found in production. `ServeLockIntegrationTest` is the only place in this repository that drives a
PostgreSQL advisory lock, and it needs a container: "a second connection is refused" has no in-JVM
stand-in. `DocumentedCommandsTest` reads six documents rather than any code, because the bug it
guards was in the documents.

**What none of it proves.** Nothing here touches bunq, Discord, or a running Velocity proxy. Tab
creation, cancellation and result inquiries need the **bunq sandbox**
(`bunq.environment: SANDBOX`); buttons, ephemeral messages, DMs, role assignment and the managed
messages need the **real guild** in an admin-only channel; a 3 € real purchase is the last step,
never the development loop. **The login path is a third gap of the same shape**: the login gate
(`LoginGate`), the kick messages it produces, the routing that moves players on a phase change, and
code redemption through the actual Discord modal all need a **running Velocity proxy with a real
client** plus a **real Discord guild** to be verified at all - nothing in this repository's test
suite exercises any of them. **`hunger-games` is a fourth**: 41 green tests cover its arithmetic and
not one packet, player, boss bar or teleport; `docs/hunger-games.md#verification` is what actually
has to happen before it is called done. **The login path is a fifth, and the newest**: the forced
pack offer, the `nordtal:limbo` channel, the black screen, the titles and every disconnect screen
the pack station can produce need a running proxy, a running Paper backend and a real Minecraft
client. `docs/state-of-play.md#the-unverified-assumptions` carries those three rows and their
fallbacks; the thirteen-step probe that answers them is in the owner's checklist outside this
repository, and it has not been run. The integration tests also skip themselves when no
Docker daemon is reachable, so a green build on a machine without Docker proves less than it looks.
`./gradlew build` compiling is not verification. Anything touching players, packets or world state
has to be exercised on `runServer` (or, for the proxy, a real client against a running proxy) with
real clients before it is called done.
