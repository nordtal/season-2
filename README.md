# season-2

Everything nordtal.eu season 2 deploys: four Minecraft plugins, a Discord bot, the three Steward
services and the resource pack. One Gradle build, one version in `gradle.properties`, one
`docker compose` stack. [`CONVENTIONS.md`](CONVENTIONS.md) holds the rules every change follows.

## What it is

A Velocity proxy fronts three Paper backends. Every login lands in `limbo`, gets the resource pack and
moves on to the backend the season phase names (the rows below). Access, language, phase and event state live in one
PostgreSQL database, and Discord roles follow it. Steward is the admins' web interface and runs every
update, restart and backup.

| phase         | who lands where                                                   |
| ------------- | ----------------------------------------------------------------- |
| `PRE_LAUNCH`  | only admins get in; everybody else sees a countdown               |
| `PRE_EVENT`   | every linked, non-banned member lands in the `hunger-games` lobby |
| `START_EVENT` | the start event, admitted as in `PRE_EVENT`, on `hunger-games`    |
| `SMP`         | the season on `smp`, for members with an active access period     |
| `MAINTENANCE` | members wait in `limbo` while admins reach the servers            |

## How it is built

```mermaid
%%{init: {'themeVariables': {'fontSize': '16px'}}}%%
flowchart TB
    players(["Players"]):::ext --> NC

    subgraph stack["docker compose stack"]
        direction TB
        subgraph steward["Steward · its own network"]
            direction TB
            CADDY["<b>caddy</b><br/>HTTPS"]:::side
            UPD["<b>steward</b><br/><i>:steward</i><br/>the interface · payments · alerts"]:::app
            DEP["<b>steward-agent</b><br/><i>:steward-agent</i><br/>schema · every run · backups"]:::app
            MIG["<b>migrate</b><br/><i>:steward-agent</i><br/>roles and schema, then exits"]:::app
            BANK["<b>steward-bunq</b><br/><i>:steward-bunq</i><br/>the only one that holds the bank key"]:::app
        end
        subgraph servers["Minecraft servers · one image"]
            direction TB
            NC["<b>proxy</b><br/><i>:proxy</i><br/>Velocity proxy"]:::proxy
            LIMBO["<b>limbo</b><br/><i>:limbo</i><br/>resource pack"]:::paper
            HG["<b>hunger-games</b><br/><i>:hunger-games</i><br/>start event"]:::paper
            SMP["<b>smp</b><br/><i>:smp</i><br/>the season"]:::paper
        end
        BOT["<b>discord-bot</b><br/><i>:discord-bot</i><br/>access · payments"]:::app
        PG[("<b>postgres</b><br/>source of truth")]:::db
        PACKHOST["pack-host<br/>dev only"]:::side
    end

    admins(["Admins"]):::ext --> CADDY
    BANK --> bunq(["bunq"]):::ext
    players ~~~ UPD
    CADDY --> UPD
    UPD -->|"containers, plan, archives"| DEP
    UPD -->|"tabs, payments"| BANK
    DEP ==>|"jars, runs"| servers
    DEP ==> BOT
    DEP --> PG
    MIG --> PG
    DEP -.->|"runs first"| MIG
    NC -->|"pack"| LIMBO
    NC -->|"phase"| HG
    NC -->|"phase"| SMP
    servers --> PG
    BOT --> PG
    UPD --> PG

    classDef ext fill:#8b949e26,stroke:#8b949e,stroke-width:2px
    classDef proxy fill:#4a90e233,stroke:#4a90e2,stroke-width:3px
    classDef paper fill:#46a75833,stroke:#46a758,stroke-width:2px
    classDef app fill:#e08c3433,stroke:#e08c34,stroke-width:2px
    classDef db fill:#d2565b33,stroke:#d2565b,stroke-width:3px
    classDef side fill:#8b949e1a,stroke:#8b949e,stroke-width:1px
    style stack fill:#8b949e14,stroke:#8b949e,stroke-width:1px
    style servers fill:#4a90e21a,stroke:#4a90e2,stroke-width:1px
    style steward fill:#e08c341a,stroke:#e08c34,stroke-width:1px
```

Italic names are Gradle modules; every other box is a container in `compose.yml`, which ships inside
the `steward-agent` image. The libraries below are compiled into the jars above.

- **Networks.** The Minecraft servers share none with Steward, so no plugin reaches the agent.
  `steward-agent` and `steward-bunq` each share an internal network with `steward` and nobody else.
- **One door to Docker.** `steward` faces the internet and holds no Docker socket. `steward-agent`
  does, and carries out every run a row in `steward_inbox` asks for.
- **One holder of the bank key.** `steward-bunq` alone, and it decides nothing.
- **Standbys.** `proxy-standby` and `limbo-standby` hold the network while an update replaces the real
  service; Caddy hands port 25565 to whichever proxy is live.
- **Commands** are Brigadier per plugin. An admin command is typed on that server's console, and what
  Steward asks for is a typed request in that server's inbox, answered by the same action.
- **Text** a player reads is German or English, looked up once per join off the main thread.
- **Glyphs** are allocated in [`resource-pack/glyphs.json`](resource-pack/glyphs.json); the build writes
  the fonts, `Glyphs` and the advance tables from it.

### Modules

| module              | platform        | what it owns                                                                                                     |
| ------------------- | --------------- | ---------------------------------------------------------------------------------------------------------------- |
| `proxy`             | Velocity        | The login gate, the season phase, and which backend a player belongs on.                                         |
| `limbo`             | Paper           | The waiting room: applying and enforcing the resource pack before a player goes anywhere.                        |
| `hunger-games`      | Paper           | The start event: the game of the teams the bot registers, border, loot, HUD, winning.                            |
| `smp`               | Paper           | The SMP: Nordtal, the farm world, the Nether and the End, milestones, aura, prestige, duels, graves.             |
| `discord-bot`       | JVM app         | Sells access periods, books bunq payments, mirrors admins, registers teams for a game.                           |
| `steward`           | JVM app + React | The web interface and its API, payments, alerts and the clocks that ask for runs.                                |
| `steward-agent`     | JVM app         | The schema, every jar version and every run. The only service allowed to create a container.                     |
| `steward-bunq`      | JVM app         | The only service holding the bank key: creates and cancels tabs, lists payments. Decides nothing.                |
| `common`            | library         | The shared kernel: platform constants, the phase enum, languages, readiness. No database, no Adventure, no pack. |
| `database`          | library         | Access, phase, online, audit, the request inboxes, the signal hub and the migration SQL.                         |
| `messages`          | library         | The message system without Adventure: bundles, specs, contexts. The bot and Steward stop here.                   |
| `message-rendering` | library         | Messages as Adventure components, for Paper and Velocity code.                                                   |
| `pack-rendering`    | library         | Glyph constants, the `<glyph:name>` tag, boss bar and tab list rendering from the resource pack.                 |
| `limbo-protocol`    | library         | The wire protocol between the proxy and limbo.                                                                   |
| `internal-api`      | library         | The token-guarded HTTP wire between `steward` and the services only it may call, and the bank's wire records.    |
| `paper-common`      | library         | What the three Paper plugins share and Velocity cannot use.                                                      |
| `display-tags`      | library         | Every player's name tag as packet-only text displays, which smp hosts as its `nametags` settings group.          |
| `settings`          | library         | Where every process's settings come from, and the database and colour settings they share.                       |
| `spec`              | library         | The spec interfaces every settings group is written as, and the schema Steward draws its forms from.             |
| `resource-pack`     | assets          | The pack, the glyph allocation its fonts are written from, and the zip + SHA-1 a release ships.                  |
| `architecture`      | tests           | The ArchUnit rules over every module's compiled classes: the dependency lists and the wiring.                    |
| `shipped-jars`      | tests           | Every shipped jar loaded as its host loads it, and its database pool opened on a throwaway PostgreSQL.           |

[`architecture`](architecture/README.md) holds the dependency rules between them as tests.

## Operating it

On a host with Docker, in the directory the installation should live in:

```bash
curl -fsSL https://raw.githubusercontent.com/nordtal/season-2/main/deploy/nordtal.sh | bash
```

The installation is that directory: worlds, database, plugin configuration and backups are folders
in it. Secrets go to `/etc/nordtal/season-2.env`, mode 600. The script stays behind as `./nordtal.sh`:

```bash
./nordtal.sh                 # the menu: what is set, change one, then deploy
./nordtal.sh update          # update the whole network and wait for the report
./nordtal.sh update --backup # one backup run
```

[`deploy/README.md`](deploy/README.md) is the runbook: first deployment, updates, backups, restore,
firewall, troubleshooting.

## Working on it

Requires JDK 25. Build only the modules you touched.

```bash
./gradlew :smp:check                 # one module, with its tests and the convention checks
./gradlew releaseArtifacts           # exactly what a release ships
./gradlew :hunger-games:runServer    # a local Paper test server
./gradlew -q :dev:run --args="init"  # then --args="up": the whole network here
```

`dev deploy smp` rebuilds one module and restarts its container. `dev` is a Java program in `:dev`,
run from IntelliJ's _dev: stack_ folder or as `./gradlew -q :dev:run --args="<command>"`; it needs Java
and Docker on any operating system. [`deploy/README.md`](deploy/README.md#locally) lists the commands.

A release is a published GitHub release, not a pushed tag:

```bash
gh release create v0.1.0 --target main --title v0.1.0 --generate-notes
```

The `release` workflow checks the tag against `gradle.properties`, pushes every image tagged with the
version alone, then attaches the four plugin jars and the pack. Rerun a failed one with
`gh workflow run release.yml -f tag=v0.1.0`.

Every config file is commented YAML described by a `@ConfigSpec` interface, and credentials arrive as
environment variables; this public repository holds none. The one outbound call to a third party is
Steward drawing player heads from [mineatar](https://mineatar.io), sending it the `mc_uuid` (the `web`
group, `avatars.minecraft-head-base-url`; empty disables it).
