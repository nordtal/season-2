# season-2 — agent guide

Everything nordtal.eu season 2 deploys. A Velocity proxy (`network-control`) in front of three
Paper backends (`limbo` → `hunger-games` → `smp`), plus the `discord-bot` Discord bot and the
`resource-pack` assets. Production runs on [SimpleCloud](https://simplecloud.app) on a remote host.

The workspace-level [../CLAUDE.md](../CLAUDE.md) carries the standing instructions and the map of
the sibling repos. Read it too.

**The project knowledge base is [docs/README.md](docs/README.md)** - the system map, the phase
model, the language model, the hunger games and SMP concepts, the decision log and the list of what
is built versus designed. Read it before planning anything; this file stays the place for build
conventions, platform versions and repository rules.

## Repository state (READ THIS FIRST)

Set up 2026-08-29 from a bare IntelliJ scaffold. **`hunger-games`, `smp` and
`limbo` are still scaffolds with no behaviour** — a main class that logs on
enable, a descriptor, and nothing else. Each is meant to be implemented in its own session.
`discord-bot` and `network-control` are the exceptions: `discord-bot` was rebuilt for season 2 in
stage B (2026-08-30) and `network-control` got the stage C access login gate on top of its own
scaffold the same day — see the "discord-bot" section below for both; `network-control` has no
section of its own yet.

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
  described by a `@ConfigSpec` interface. It is the default for every new config in this repo;
  none of the four plugins has one yet, so there is nothing to migrate — see "Configuration"
  below before writing the first one.
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
duels, POIs and graves, and the farm world is one part of it. **One rename is still planned and is
cheap only until something runs in production:** the `SeasonPhase` values. A Paper plugin's
`name:` is its runtime identity: the `plugins/<name>/` data folder and the permission prefix, so a
rename after deployment means moving data folders on the production host.

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

**SimpleCloud runs 26.2 — confirmed by the owner on 2026-08-31** against SimpleCloud v3's
dashboard. That was the biggest open platform risk and it is closed.

**What is still open is the API artefact, which is a different question.** `app.simplecloud.api:api`
is published *only* as `0.1.0-platform.NN-dev.*` snapshots — `repo.simplecloud.app` has no releases
channel at all (HTTP 404, checked 2026-08-31), and the catalog pins `platform.54-dev.1.1-770dcc6`
from 2026-08-20. No source file in this repository imports it yet, so it costs nothing today; it
becomes real when routing is written. If the coordinate breaks, routing does not need it — Velocity
knows its own registered servers, and `ProxyServer.getServer(name)` is the whole of what the routing
rules use. See [docs/operations.md](docs/operations.md#open-verification).

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
- **`:common` carries the access system** (`eu.nordtal.s2.common.access`) and the message system
  (`eu.nordtal.s2.common.message`) since stage A, 2026-08-30. That means it now depends on JDBI 3,
  HikariCP, slf4j-api and the PostgreSQL driver — and on **nothing else**; jcore is deliberately
  not used here even though it wraps the same stack, because its dependency block is what makes
  the bot's jar ~31 MB. Nothing from JDBI or HikariCP appears on `AccessDirectory`'s signature —
  the factories take a `javax.sql.DataSource` or a JDBC URL — so a consumer never compiles against
  them.
- **What a jar actually weighs, rebuilt and measured 2026-08-31.** An earlier version of this file
  and of `docs/state-of-play.md` claimed "every Paper plugin jar grew from ~20 KB to ~3.0 MB".
  **That was wrong, and backwards.**

  | jar | bytes |
  |---|---|
  | `smp-2.0.0.jar` | 34,745 |
  | `hunger-games-2.0.0.jar` | 34,784 |
  | `limbo-2.0.0.jar` | 34,886 |
  | `network-control-2.0.0.jar` | 5,196,184 |
  | `discord-bot-2.0.0.jar` | 30,893,431 |

  The three Paper plugins carry `:common`'s own classes plus ~14 KB of SQL and **nothing else**,
  precisely because the JDBI/Hikari/slf4j declarations are `compileOnly` and none of the three
  scaffolds has opted into `libs.bundles.access-persistence` yet. `network-control` weighs 5 MB
  because it *did* opt in, through `jcore`. The 3.12 MB figure in `common/build.gradle.kts` is the
  **counterfactual** — what a plugin jar would weigh if those declarations were `implementation` —
  and it was read as a measurement of the jars as they are. A plugin that starts using the access
  API will land near 3 MB; none does today.
- **Exactly one process migrates: `discord-bot`.** That is unchanged. **Where the SQL lives changed
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
  `docs/smp.md` and `docs/hunger-games.md` no longer carry code points of their own. `Glyphs` is
  knowingly behind the table today — it still declares the four retired season-1 role tags and names
  nothing at all from the boss bar font.

## Configuration

Every config file in this repo is a commented YAML file described by an interface, loaded through
`eu.nordtal.jcore.config.ConfigLoader` (jcore 3.0.0). `discord-bot` is the only module with configs
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
  them from `shadowJar` for that reason. They are *not* excluded for `discord-bot`, which has no
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
| `nordtal.jvm-app` | `discord-bot` | `application`, Main-Class in the shaded manifest |

Every external version lives in `gradle/libs.versions.toml`. Nothing pins a version in a module
build file.

**`app.simplecloud.api:api` must stay `compileOnly` and must never be shaded.** The `simplecloud-api`
platform plugin provides it at runtime; a bundled copy causes class-loading conflicts.

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
error on `main` stayed invisible until somebody tagged.

**No workflow has ever run.** There are no tags and no releases on the remote as of 2026-08-31;
everything above is unexercised.

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

**Two things this stage left open were decided on 2026-08-31. Both are decided and neither is
implemented yet** - they belong to the next session that touches these files:

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

## resource-pack

Brought in with `git subtree` from `nordtal/smp-resource-pack` with its history intact, so
`git log -- resource-pack/` still explains the assets. The standalone repo is superseded.

`packZip` builds reproducibly (fixed file order, no timestamps) so the same version always hashes
the same, and writes the SHA-1 next to the zip. The client is sent the URL *and* the hash and
refuses the pack if they disagree — never hardcode a hash.

## Verification

Three modules have tests now. `:common` has 47 (2026-08-30, up from 35 in stage B):
`AccessDirectoryIntegrationTest` (22) drives the access API against a real PostgreSQL container
running the real migration — the append rule, the expiry boundary, revocation, the four
`accessState` cases and the unique constraints that stop a double booking. `LinkCodeIntegrationTest`
(12, stage C) drives the same container through the link-code lifecycle: issuing, "a repeat attempt
returns the same code", an expired code being replaced rather than returned, redemption, redeeming
the same code twice, redeeming a code nobody typed for the account it was meant for, and the 1:1
that redemption enforces. `MessagesTest` and `LocalesTest` (13, in memory) round it out. The
`make_interval(hours => days * 24)` bug from stage A (see below) is still the reason a day is never
expressed in SQL as `interval 'N days'`.

`discord-bot` has 92 (2026-08-31): `ConfigsTest` (28, in memory), `LanguagesTest` (16, in memory),
`PhaseCommandTest` (14, in memory), `TiersTest` (12, in memory), `AdminFlagIntegrationTest` (5) and
`PaymentRequestIntegrationTest` (17) against a real PostgreSQL container. `LanguagesTest` owns every
rule the `languages` list decides - which of several held roles wins, which channel a locale posts
in, what a `managed_message.kind` is called - because none of the three classes that use them
(`GuildState`, `ManagedMessages`, `PaymentProcessor`) can be exercised without a real guild.
`LinkFlow`, the redemption side, is still untested by anything but `common`'s DAO-level tests and a
manual guild check; see "what none of it proves".

`network-control` has 16 (2026-08-30, stage C, new module): `FallbackCacheTest` (10, in memory,
driven by a settable `Clock` rather than `Thread.sleep`) covers the four fallback rules - a
recently-seen player with active access is let in, an unknown player is refused, everyone is
refused once the window has passed, and a state that could never let anyone in is never stored at
all rather than lingering as a stale positive. `ConfigsTest` (6, in memory) covers `database.yml`
and `gate.yml` the same way `discord-bot`'s does its own three files. Nothing here touches
PostgreSQL - the login gate's database calls are exercised by `common`'s tests, and
`network-control` itself has no schema of its own to test against a container.

**What none of it proves.** Nothing here touches bunq, Discord, or a running Velocity proxy. Tab
creation, cancellation and result inquiries need the **bunq sandbox**
(`bunq.environment: SANDBOX`); buttons, ephemeral messages, DMs, role assignment and the managed
messages need the **real guild** in an admin-only channel; a 3 € real purchase is the last step,
never the development loop. **Stage C added a third gap of the same shape**: the login gate
(`LoginGate`), the kick messages it produces, and code redemption through the actual Discord modal
all need a **running Velocity proxy with a real client** plus a **real Discord guild** to be
verified at all - nothing in this repository's test suite exercises any of them, and none of it was
exercised as part of building stage C either. The integration tests also skip themselves when no
Docker daemon is reachable, so a green build on a machine without Docker proves less than it looks.
`./gradlew build` compiling is not verification. Anything touching players, packets or world state
has to be exercised on `runServer` (or, for the proxy, a real client against a running proxy) with
real clients before it is called done.
