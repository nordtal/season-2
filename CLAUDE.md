# season-2 — agent guide

Everything nordtal.eu season 2 deploys. A Velocity proxy (`network-control`) in front of three
Paper backends (`resource-pack-coercion` → `hunger-games` → `smp-farm-world`), plus the
`access-bot` Discord bot and the `resource-pack` assets. Production runs on
[SimpleCloud](https://simplecloud.app) on a remote host.

The workspace-level [../CLAUDE.md](../CLAUDE.md) carries the standing instructions and the map of
the sibling repos. Read it too.

## Repository state (READ THIS FIRST)

Set up 2026-08-29 from a bare IntelliJ scaffold. **The four server-side plugins are scaffolds with
no behaviour** — a main class that logs on enable, a descriptor, and nothing else. Each is meant to
be implemented in its own session. `access-bot` is the exception: it was rebuilt for season 2 in
stage B (2026-08-30) and is real code — see its section below.

Deliberately **not** set up, so nobody adds it by accident thinking it was forgotten:

- **Persistence and `jcore` are now in scope for the plugins.** The earlier rule here ("no
  database, no `jcore` dependency in `common` or in any of the four server-side modules") was
  lifted by the owner on 2026-08-30. Plugins may take a `jcore` dependency and may persist.
  Two things still hold: **decide per module whether that module actually needs persistence**
  rather than adding it by reflex, and **do not copy `access-bot`'s dependency block** — its
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
- **`:common` carries the access system** (`eu.nordtal.s2.common.access`) and the message system
  (`eu.nordtal.s2.common.message`) since stage A, 2026-08-30. That means it now depends on JDBI 3,
  HikariCP, slf4j-api and the PostgreSQL driver — and on **nothing else**; jcore is deliberately
  not used here even though it wraps the same stack, because its dependency block is what makes
  the bot's jar 32 MB. Every Paper plugin jar grew from ~20 KB to **~3.0 MB** as a result
  (measured 2026-08-30). Nothing from JDBI or HikariCP appears on `AccessDirectory`'s signature —
  the factories take a `javax.sql.DataSource` or a JDBC URL — so a consumer never compiles against
  them.
- **The access schema is owned by `access-bot`** (`src/main/resources/db/migration`), because
  the bot is the only process that migrates, but the API that reads it lives in `:common`. A
  column change is therefore an edit in two modules. `:common`'s tests apply that migration
  directory directly (its path is handed to the test JVM by `common/build.gradle.kts`) rather than
  keeping a second copy of the DDL.
- `Glyphs` in `:common` mirrors `resource-pack/src/assets/minecraft/font/default.json`. A change
  to either is a change to both, plus the pack's README table.

## Configuration

Every config file in this repo is a commented YAML file described by an interface, loaded through
`eu.nordtal.jcore.config.ConfigLoader` (jcore 3.0.0). `access-bot` is the only module with configs
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
  them from `shadowJar` for that reason. They are *not* excluded for `access-bot`, which has no
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
| `nordtal.jvm-app` | `access-bot` | `application`, Main-Class in the shaded manifest |

Every external version lives in `gradle/libs.versions.toml`. Nothing pins a version in a module
build file.

**`app.simplecloud.api:api` must stay `compileOnly` and must never be shaded.** The `simplecloud-api`
platform plugin provides it at runtime; a bundled copy causes class-loading conflicts.

## Releasing

One repo-wide version in `gradle.properties` drives everything. `.github/workflows/release.yml`
fires on a `v*` tag, refuses a tag that disagrees with `gradle.properties`, runs `releaseArtifacts`,
and attaches four plugin jars + the bot jar + the pack zip and its `.sha1` to one GitHub release,
then pushes `ghcr.io/nordtal/access-bot:<version>`.

`season-2` itself produces no combined build, and does not republish jars built in other repos.

## access-bot

Season 2's Discord bot. **Stage B is implemented (2026-08-30)**; the concept is
[docs/access-system.md](docs/access-system.md) and the stage plans are
`docs/access-stage-{a,b,c}.md`. Read the concept before changing anything here or in the access
path in `common` and `network-control`.

It sells **access periods** (30/60/90 days at 3/5/7 €, optional +5 € donation) bound to a Discord
account, paid by bunq.me card payment only. Season 1's contribution tiers, bank transfer, receiver
select and balance voice channel are gone. The database is the source of truth for access, donor
status and language; Discord roles are a projection of it and LuckPerms is not involved.
**Stage C is not built**: the proxy gate and link-code issuing do not exist yet, which is why the
two link messages carry an explanation and no button, and why `link_code` is only swept, never
written.

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
- **"Pay what you get" lives in `Tiers.resolve`**, and it maximises days: with 3/5/7 € tiers and a
  5 € donation, 10 € is 90 days and *no* donor role. That is the agreed rule read literally and it
  has its own test, because it is what a support ticket will be about.
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
- **The schema is owned here** (`src/main/resources/db/migration/`), applied by `database.migrate()`
  at startup, while the API that reads it lives in `:common` — so a column change is an edit in two
  modules. `:common`'s tests apply this directory directly rather than keeping a copy of the DDL.
  - `V1__access.sql` (stage A): `discord_user`, `account_link`, `link_code`, `payment_request`,
    `access_grant`, `audit_log`.
  - `V2__bot_state.sql` (stage B): `managed_message`, `payment_notice`, `expiry_notice` — three
    tables that exist so something the bot does exactly once survives a restart. Without them a
    restart posts a second managed message, re-pings admins about the same payment every poll, and
    re-sends yesterday's expiry DMs. `V2__legacy_contribution.sql` and the whole `contribution`
    code path were deleted in stage B.
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
  tier days and prices, the donation surcharge, the guild id, five role ids, five channel ids, the
  poll interval, the request TTL, the watermark, the link-code TTL and the reminder lead time. A
  price change is a config edit, never a release.
- **The ids default to empty and the bot refuses to start until they are filled in.** Season 1
  shipped real channel and role ids as defaults, so a config that failed to load wrote into a
  production channel. Prices do have real defaults; ids never will.
- `bot.yml` gained `bunq.environment` (`PRODUCTION` / `SANDBOX`). It was hardcoded, which made the
  sandbox test in the concept impossible to run. The bunq context file belongs to one environment:
  switching also means pointing `bunq.context-path` at a fresh file.
- **The tiers are three flat slots** (`short-`, `medium-`, `long-` × `days` / `price-cents`) rather
  than a list. A reused nested `TierSpec` has one set of defaults, so a fresh file would offer 30
  days three times. The cost is that the number of tiers is fixed at three.
- **The one-time jcore 1.x `config/*.json` conversion was removed.** It existed to carry season 1's
  deployed config volume forward, and nothing is ever migrated between seasons — new bot, new
  database, new Discord application, new volume.
- The bot fails fast: all three files are read and validated before anything with a lifecycle
  starts, and `main` exits 1 on a `ConfigException`.

## resource-pack

Brought in with `git subtree` from `nordtal/smp-resource-pack` with its history intact, so
`git log -- resource-pack/` still explains the assets. The standalone repo is superseded.

`packZip` builds reproducibly (fixed file order, no timestamps) so the same version always hashes
the same, and writes the SHA-1 next to the zip. The client is sent the URL *and* the hash and
refuses the pack if they disagree — never hardcode a hash.

## Verification

Two modules have tests. `:common` has 35 (2026-08-30): `AccessDirectoryIntegrationTest` drives the
access API against a real PostgreSQL container running the real migration — the append rule, the
expiry boundary, revocation, the four `accessState` cases and the unique constraints that stop a
double booking — plus `MessagesTest` and `LocalesTest` in memory. It found one real bug: a day has
to be `make_interval(hours => days * 24)`, because `interval 'N days'` on a `timestamptz` is
calendar arithmetic in the *client session's* time zone and a period spanning the end of summer
time came out an hour long. **Never express an access duration in days in SQL.**

`access-bot` has 34 (2026-08-30): `ConfigsTest` (13, in memory) covers every value that used to be
able to reach production — a mistyped key, an empty channel id, a role id that is not a snowflake,
unordered tiers, an unparseable watermark, an unknown bunq environment. `TiersTest` (7, in memory)
is the "pay what you get" rule, including the 10 € case that gives 90 days and no donor role.
`PaymentRequestIntegrationTest` (14) drives the request state machine against a real PostgreSQL
container running the real migrations: one open request per person, superseding, the expiry sweep,
edit-in-place before a tab and refusal after one, manual settlement, and both halves of the
double-booking guard.

**What none of it proves.** Nothing here touches bunq or Discord. Tab creation, cancellation and
result inquiries need the **bunq sandbox** (`bunq.environment: SANDBOX`); buttons, ephemeral
messages, DMs, role assignment and the managed messages need the **real guild** in an admin-only
channel; a 3 € real purchase is the last step, never the development loop. The integration tests
also skip themselves when no Docker daemon is reachable, so a green build on a machine without
Docker proves less than it looks. `./gradlew build` compiling is not verification. Anything
touching players, packets or world state has to be exercised on `runServer` with real clients
before it is called done.
