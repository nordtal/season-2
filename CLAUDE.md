# season-2 — agent guide

Everything nordtal.eu season 2 deploys. A Velocity proxy (`network-control`) in front of three
Paper backends (`resource-pack-coercion` → `hunger-games` → `smp-farm-world`), plus the
`payments-bot` Discord bot and the `resource-pack` assets. Production runs on
[SimpleCloud](https://simplecloud.app) on a remote host.

The workspace-level [../CLAUDE.md](../CLAUDE.md) carries the standing instructions and the map of
the sibling repos. Read it too.

## Repository state (READ THIS FIRST)

Set up 2026-08-29 from a bare IntelliJ scaffold. **The four server-side plugins are scaffolds with
no behaviour** — a main class that logs on enable, a descriptor, and nothing else. Each is meant to
be implemented in its own session. `payments-bot` is the exception: its source was ported from
`nordtal-payments` and is real, working code.

Deliberately **not** set up, so nobody adds it by accident thinking it was forgotten:

- **Persistence and `jcore` are now in scope for the plugins.** The earlier rule here ("no
  database, no `jcore` dependency in `common` or in any of the four server-side modules") was
  lifted by the owner on 2026-08-30. Plugins may take a `jcore` dependency and may persist.
  Two things still hold: **decide per module whether that module actually needs persistence**
  rather than adding it by reflex, and **do not copy `payments-bot`'s dependency block** — its
  shaded jar is ~33 MB, which is fine for a container and not fine inside a Paper plugin. A
  plugin that only needs the config system does not need the JDBI/Flyway/PostgreSQL side of
  `jcore` on its runtime classpath; shade what you use.
- **The config system is chosen: `eu.nordtal.jcore.config`** (jcore 3.0.0). Commented YAML
  described by a `@ConfigSpec` interface. It is the default for every new config in this repo;
  none of the four plugins has one yet, so there is nothing to migrate — see "Configuration"
  below before writing the first one.
- **No command framework.** Season 1 used Incendo Cloud; nothing has been chosen for season 2.

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

**Unverified risk:** SimpleCloud's docs state no supported Minecraft versions, and its API is
published only as `0.1.0-platform.NN-dev.*` snapshots. Whether SimpleCloud actually supports 26.2
servers has **not** been confirmed. If it turns out it does not, the platform choice reopens — ask
before working around it.

## Layout and conventions

- Packages are `eu.nordtal.s2.<module>` — the `s2` segment keeps season 3 from colliding.
- Paper plugin `name:` values **match the module directory names** (`hunger-games`,
  `smp-farm-world`, `resource-pack-coercion`, `network-control`), lowercase and hyphenated. That is
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
- `Glyphs` in `:common` mirrors `resource-pack/src/assets/minecraft/font/default.json`. A change
  to either is a change to both, plus the pack's README table.

## Configuration

Every config file in this repo is a commented YAML file described by an interface, loaded through
`eu.nordtal.jcore.config.ConfigLoader` (jcore 3.0.0). `payments-bot` is the only module with configs
today; the four plugins have none yet and get this as the standing instruction for their first one.

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
  never trimmed. In a Paper plugin, catch `ConfigException` in `onLoad`/`onEnable` and call
  `getServer().getPluginManager().disablePlugin(this)` — **the plugin goes down, the server keeps
  running**. `papermc-display-tags` is the worked example.
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
  them from `shadowJar` for that reason. They are *not* excluded for `payments-bot`, which has no
  platform to provide them.

## Build wiring

Shared build configuration lives in the `build-logic` included build as precompiled convention
plugins, **not** in a root `subprojects {}` block. A module's build file should stay a `plugins {}`
block plus its own dependencies.

| convention | applies to | gives |
|---|---|---|
| `nordtal.java-base` | everything | Java 25 toolchain, UTF-8, JUnit 6, group |
| `nordtal.shaded` | every deployable | shadow; thin jar moved to the `thin` classifier so `shadowJar` takes the plain name |
| `nordtal.paper-plugin` | the three Paper modules | paper-api, `:common`, `runServer` on 26.2, `${version}` expansion |
| `nordtal.velocity-plugin` | `network-control` | velocity-api as compileOnly + annotationProcessor, `:common` |
| `nordtal.jvm-app` | `payments-bot` | `application`, Main-Class in the shaded manifest |

Every external version lives in `gradle/libs.versions.toml`. Nothing pins a version in a module
build file.

**`app.simplecloud.api:api` must stay `compileOnly` and must never be shaded.** The `simplecloud-api`
platform plugin provides it at runtime; a bundled copy causes class-loading conflicts.

## Releasing

One repo-wide version in `gradle.properties` drives everything. `.github/workflows/release.yml`
fires on a `v*` tag, refuses a tag that disagrees with `gradle.properties`, runs `releaseArtifacts`,
and attaches four plugin jars + the bot jar + the pack zip and its `.sha1` to one GitHub release,
then pushes `ghcr.io/nordtal/payments-bot:<version>`.

`season-2` itself produces no combined build, and does not republish jars built in other repos.

## payments-bot → access-bot

**The season 2 model was decided on 2026-08-30 and is written down in
[docs/access-system.md](docs/access-system.md), with the three implementation stages in
`docs/access-stage-{a,b,c}.md`. Read the concept before touching this module or the access path in
`common` and `network-control` — the code below still describes season 1's model.**

In short: contribution tiers, bank transfer and the receiver select are gone. Season 2 sells
**access periods** (30/60/90 days at 3/5/7 €, optional +5 € donation) bound to a Discord account,
paid by bunq.me card payment only. The database is the source of truth for access, donor status and
language; Discord roles are a projection of it and LuckPerms is not involved. The proxy gates the
login, the account link is built in-house, and the module is renamed to `access-bot`
(`eu.nordtal.s2.accessbot`) as part of stage B.

Ported from `nordtal-payments` on 2026-08-29; package renamed `eu.nordtal.paymentsbot` →
`eu.nordtal.s2.paymentsbot`. It will change substantially, but the season 1 code is the base.

- **It is the only module that depends on `jcore`** (`com.github.nordtal:jcore:2.0.0`, published
  via JitPack). That exports jdbi3-core, jdbi3-sqlobject, slf4j-api, commons-lang3, commons-io,
  gson, snakeyaml and `org.jetbrains:annotations` as `api` dependencies, and brings HikariCP,
  Flyway and the PostgreSQL driver along at runtime. The shaded jar is ~33 MB. Fine for a
  container; it would not be fine inside a Paper plugin.
- **jcore does not export a logging backend** (logback is `testRuntimeOnly` there). The bot
  declares `ch.qos.logback:logback-classic` itself. Remove that and SLF4J binds to a no-op: every
  log line disappears behind a single "no providers found" warning.
- **Persistence is JDBI 3 + HikariCP + Flyway on PostgreSQL** — no Hibernate, no JPA, no MariaDB.
  One `Database` per process, created and closed by `NordTalPayments`. The schema is owned by
  `src/main/resources/db/migration/`, applied by `database.migrate()` at startup. The
  `contribution` table starts empty by design; season 1's rows are deliberately not migrated.
  `euro_amount` is `numeric(10,2)` in PostgreSQL while the Java field is still a `float` — the
  column is the source of truth for the value.
- **It shadows a bunq SDK class.** `src/main/java/com/bunq/sdk/http/BunqRequestBuilder.java` is a
  patched copy of a class from `com.github.bunq:sdk_java`, sitting in the library's own package so
  it wins on the classpath. **Resolved 2026-08-30 by diffing it against the 1.28.0.6 sources:** JDA
  pulls **OkHttp 5**, where `Request.Builder.delete()` (no argument) is `final` and
  `okhttp3.internal.Util` no longer exists — and the SDK's original class overrides exactly that
  method. The patch is required as long as JDA and the bunq SDK share a classpath. Do not delete it,
  and re-check it on any bunq SDK or JDA bump.
- The Dockerfile is runtime-only: Gradle builds the jar, `docker build --build-arg JAR=...` wraps
  it. A self-contained build stage would have to copy this whole multi-module repo.
- **Configuration is `config/*.yml`, loaded through jcore 3.0.0's config system** — three files:
  `bot.yml` (Discord token, bunq credentials), `database.yml` and `payment-processing.yml`, each
  with its own environment namespace: `NORDTAL_BOT_*`, `NORDTAL_DATABASE_*`,
  `NORDTAL_PAYMENT_PROCESSING_*`. A shared `NORDTAL` prefix was deliberately not used — generic
  keys such as `password` would collide across files.
- **Every environment variable was renamed.** `BOT_TOKEN` → `NORDTAL_BOT_TOKEN`, `BUNQ_API_KEY` →
  `NORDTAL_BOT_BUNQ_API_KEY`, `BUNQ_ACCOUNT_ID` → `NORDTAL_BOT_BUNQ_ACCOUNT_ID`,
  `BUNQ_CONFIG_PATH` → `NORDTAL_BOT_BUNQ_CONTEXT_PATH`, `POSTGRES_URL` →
  `NORDTAL_DATABASE_JDBC_URL`, `POSTGRES_USER` → `NORDTAL_DATABASE_USERNAME`, `POSTGRES_PASSWORD`
  → `NORDTAL_DATABASE_PASSWORD`. **The old names are no longer read.** The compose file or
  orchestrator secrets have to be updated before the next deploy, or the bot starts on the file
  values — and refuses to start at all if the credentials are then empty. The `MARIADB_*`
  variables are long gone.
- **The credentials are config values with empty defaults, not bare `getenv` calls.** They are
  validated once at startup; the bot refuses to start while any of them is empty or the bunq
  account id is not numeric. Previously a missing account id surfaced as a `NumberFormatException`
  inside the poll loop, minutes into a run. An environment value is never written back to the
  file, so the config volume never sees a real token unless somebody types one in.
- **An existing jcore 1.x `config/*.json` is converted to YAML once, on first start**, and kept as
  `*.json.migrated`. `Configs` owns that conversion, including the two payment-processing keys
  that moved into a nested `balance` section and so need more than the snake-case-to-kebab rule.
  A manual step was rejected: the production config lives in a volume nobody edits between pulling
  an image and starting it.
- **The bot fails fast on configuration.** `NordTalPayments` loads and validates all three files
  before anything with a lifecycle starts, and `main` exits with status 1 on a `ConfigException`.
  `PaymentProcessingService` no longer loads its own config: it used to catch the failure, log it
  and carry on with `new PaymentProcessingConfig()`, so a broken file ran the bot against default
  Discord channel ids. In production the password still belongs in the environment; the files
  exist so a local checkout runs without setting anything. The bunq API context lives in a Docker
  volume, never on the host.

## resource-pack

Brought in with `git subtree` from `nordtal/smp-resource-pack` with its history intact, so
`git log -- resource-pack/` still explains the assets. The standalone repo is superseded.

`packZip` builds reproducibly (fixed file order, no timestamps) so the same version always hashes
the same, and writes the SHA-1 next to the zip. The client is sent the URL *and* the hash and
refuses the pack if they disagree — never hardcode a hash.

## Verification

The only tested module is `payments-bot`: `ContributionRepositoryTest` covers the
contribution-scheduling logic in memory, and `ContributionRepositoryIntegrationTest` runs the DAO
layer against a real PostgreSQL container (Testcontainers, driven by hand from `@BeforeAll` —
the `org.testcontainers:junit-jupiter` extension is built against JUnit 5 and this repo is on the
JUnit 6 BOM). It skips itself when no Docker daemon is reachable, so a green build on a machine
without Docker proves less than it looks. Nothing else has tests, and nothing has been run on a
real server. `./gradlew build` compiling is not verification. Anything touching players,
packets or world state has to be exercised on `runServer` with real clients before it is called done.
