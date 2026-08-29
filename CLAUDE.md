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

- **No persistence anywhere.** No database, no `jcore` dependency in any plugin. MariaDB via
  `jcore` is the leading candidate, but the owner has open doubts about whether season 2 needs
  much persistence at all and about whether `jcore`'s own DAO layer is the right shape. Decide
  per module when implementing, and raise it rather than assuming.
- **No command framework, no message/config system.** Season 1 used Incendo Cloud and
  `jcore`'s `JsonConfigLoader`; neither has been chosen for season 2.

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

## payments-bot

Ported from `nordtal-payments` on 2026-08-29; package renamed `eu.nordtal.paymentsbot` →
`eu.nordtal.s2.paymentsbot`. It will change substantially, but the season 1 code is the base.

- **It shadows a bunq SDK class.** `src/main/java/com/bunq/sdk/http/BunqRequestBuilder.java` is a
  patched copy of a class from `com.github.bunq:sdk_java`, sitting in the library's own package so
  it wins on the classpath. Nobody currently knows what the patch fixes. **Do not delete it and do
  not bump the bunq SDK without working out what it was for** — that investigation is owed when
  work on the bot is picked up.
- The Dockerfile is runtime-only: Gradle builds the jar, `docker build --build-arg JAR=...` wraps
  it. A self-contained build stage would have to copy this whole multi-module repo.
- Secrets are environment variables (`BOT_TOKEN`, `MARIADB_*`, `BUNQ_API_KEY`, `BUNQ_ACCOUNT_ID`).
  The bunq API context lives in a Docker volume, never on the host.

## resource-pack

Brought in with `git subtree` from `nordtal/smp-resource-pack` with its history intact, so
`git log -- resource-pack/` still explains the assets. The standalone repo is superseded.

`packZip` builds reproducibly (fixed file order, no timestamps) so the same version always hashes
the same, and writes the SHA-1 next to the zip. The client is sent the URL *and* the hash and
refuses the pack if they disagree — never hardcode a hash.

## Verification

There are **no tests** in this repo, and nothing has been run on a real server. `./gradlew build`
compiling is not verification. Anything touching players, packets or world state has to be
exercised on `runServer` with real clients before it is called done.
