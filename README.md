# season-2

Everything nordtal.eu season 2 deploys: four Minecraft plugins, a Discord bot, a version updater and
the resource pack. One Gradle multi-module build, one version in `gradle.properties`, one
`docker compose` stack.

## The network

A Velocity proxy in front of three Paper backends. Every login lands on `limbo`, is offered the
resource pack, and is released onto whichever backend the current season phase names.

```mermaid
flowchart TB
    subgraph libs["Library modules — compiled into the jars below"]
        direction LR
        PACK[":resource-pack"]:::lib
        COMMON[":common"]:::lib --> COMMANDS[":commands"]:::lib --> PCOMMON[":paper-common"]:::lib
        PACK ~~~ COMMON
    end

    subgraph stack["docker compose — one stack"]
        direction TB
        UPD["<b>updater</b><br/><i>:updater</i><br/>versions · schema · jars"]:::app
        subgraph servers["Minecraft servers · one image"]
            direction TB
            NC["<b>network-control</b><br/><i>:network-control</i><br/>Velocity · the only open port"]:::proxy
            LIMBO["<b>limbo</b><br/><i>:limbo</i><br/>resource pack"]:::paper
            HG["<b>hunger-games</b><br/><i>:hunger-games</i><br/>start event"]:::paper
            SMP["<b>smp</b><br/><i>:smp</i><br/>the season"]:::paper
        end
        BOT["<b>bot</b><br/><i>:discord-bot</i><br/>access · payments"]:::app
        PG[("<b>postgres</b><br/>source of truth")]:::db
        BACKUP["postgres-backup"]:::side
        PACKHOST["pack-host · dev only"]:::side
    end

    upstream(["PaperMC Fill · GitHub releases"]):::ext --> UPD
    UPD -->|"restart"| arcane(["Arcane, on the host"]):::ext
    UPD ==>|"schema, then jars"| servers
    UPD ==> BOT
    players(["Players"]):::ext --> NC
    NC -->|"pack"| LIMBO
    NC -->|"phase"| HG
    NC -->|"phase"| SMP
    discord(["Discord · bunq"]):::ext <--> BOT
    servers --> PG
    BOT --> PG
    UPD --> PG
    PG -.-> BACKUP

    classDef ext fill:#eceff4,stroke:#8b96a8,color:#1c2333
    classDef proxy fill:#cfe3ff,stroke:#2f6db5,color:#10233d
    classDef paper fill:#d8f0dc,stroke:#3f8d55,color:#12301c
    classDef app fill:#ffe4c7,stroke:#c97a2a,color:#3a2408
    classDef db fill:#fdd9d9,stroke:#c0504d,color:#3d1010
    classDef side fill:#f0f0f0,stroke:#a8a8a8,color:#2b2b2b
    classDef lib fill:#e8ddf5,stroke:#7a5aa8,color:#241436
    style stack fill:#fcfcfe,stroke:#c3c8d4,color:#33384a
    style servers fill:#f6f9ff,stroke:#9fb6d6,color:#22354f
    style libs fill:#faf7ff,stroke:#bda9d9,color:#3a2a52
```

Italic names are Gradle modules; every other box is a container in `compose.yml`.

**The database is the source of truth** for access, language, season phase and event state. Discord
roles are a projection of it, never the other way round.

## Modules

| module | platform | what it owns |
|---|---|---|
| `network-control` | Velocity | The login gate, the season phase, and which backend a player belongs on. |
| `limbo` | Paper | The waiting room: applying and enforcing the resource pack before a player goes anywhere. |
| `hunger-games` | Paper | The start event — registration, teams, border, loot, HUD, winning. |
| `smp` | Paper | The SMP: Nordtal, the farm world, the Nether and the End, milestones, aura, prestige, duels, graves. |
| `discord-bot` | JVM app | Sells access periods, books bunq payments, mirrors admins. |
| `updater` | JVM app | Resolves platform and plugin versions, migrates the schema, swaps jars, restarts the stack. |
| `common` | library | Access API, message system, glyph constants, the phase enum, the `LISTEN`/`NOTIFY` loop, the migration SQL. |
| `paper-common` | library | What the three Paper plugins share and Velocity cannot use. |
| `commands` | library | Every command in the network, declared once. |
| `resource-pack` | assets | The pack, its fonts, and the zip + SHA-1 a release ships. |

`DisplayTags` also runs on this network but ships from its own repo,
[nordtal/papermc-display-tags](https://github.com/nordtal/papermc-display-tags).

## How the pieces fit

**A command is declared once, in `:commands`, and appears on every surface that can carry it.** An
admin command works in game and in Discord alike; where its effect belongs to another process it
travels as a row in `command_request`. A player command must additionally be on the allowlist in
`network.yml`, or it does not exist — everything unlisted is refused with the same line a typo gets.

**The season runs through four phases** — `PRE_EVENT`, `START_EVENT`, `SMP`, `MAINTENANCE` — which
decide who gets in and where they land. The phase is one database row, switched from either the proxy
or Discord and propagated by `NOTIFY`, with polling as the guarantee behind it.

**Access is paid, and only from the `SMP` phase onwards**; the start event is free for every linked
member. A purchase is a bunq payment matched back to an open reference; the resulting access period
is a row, and the plugins only ever read it.

**Everything a player reads is translated.** German and English ship; a further language is a config
entry and a bundle, not a release. The lookup happens once per join, off the main thread.

**The SMP's design is distance.** There is no `/home`, `/tpa`, `/back` or `/spawn` and there never
will be; the balloon is the only fast travel given. Milestones are network-wide objectives that pay
out *aura*, the season's currency — earned by contributing, lost on death.

**The resource pack and the plugins are one artefact in two halves.** Glyph code points are allocated
in [`resource-pack/README.md`](resource-pack/README.md), mirrored by `:common`'s `Glyphs` and the font
files, and held against each other by a test on every build. Change one, change all of them.

## Building

Requires JDK 25.

```bash
./gradlew build
```

Each module builds its own artefact; there is no combined one. To produce exactly what a release
ships:

```bash
./gradlew releaseArtifacts
```

A Paper module has a local test server:

```bash
./gradlew :hunger-games:runServer
```

The proxy has none. Anything involving the proxy, the login path, the resource pack or two servers at
once wants the whole network, which runs on your own machine off the same `compose.yml` the
production host uses:

```bash
deploy/dev init && deploy/dev up
```

`deploy/dev deploy smp` then rebuilds one module and restarts one container. The runbook is
[deploy/README.md](deploy/README.md).

## Releasing

The version in `gradle.properties` is the single source of truth, and **a release is a published
GitHub release, not a pushed tag** — pushing the tag alone builds nothing:

```bash
git tag v0.1.0 && git push origin v0.1.0
gh release create v0.1.0 --title v0.1.0 --generate-notes
```

The `release` workflow refuses a tag that disagrees with `gradle.properties`, runs
`./gradlew check releaseArtifacts`, verifies the pack zip against its own SHA-1, attaches the plugin
jars, the bot jar and the pack, and pushes `ghcr.io/nordtal/discord-bot:<version>`. A failed build is
re-run against the same release with `gh workflow run release.yml -f tag=v0.1.0`.

## Configuration

Every config file is commented YAML described by a `@ConfigSpec` interface and loaded through
`eu.nordtal.jcore.config`. Reading one, next to its interface, is the fastest way to learn what a
module does.

This repository is public and contains no secrets. Every credential — the Discord bot token, the bunq
API key, database access — arrives through environment variables at runtime; committed configuration
files are examples only.
