# season-2

Everything nordtal.eu season 2 deploys: four Minecraft plugins, a Discord bot, the three Steward
services and the resource pack. One Gradle multi-module build, one version in `gradle.properties`,
one `docker compose` stack.

**Steward is the name of the system that runs this deployment**, and it is three services, not one:
`steward-worker` does versions, the schema, jars and backups; `steward-ui` is the web interface;
`steward-deployer` is the only service allowed to create a container. Until 2026-09-13 the last two
were a third-party management panel, and the deployment was a checkout on the host that its GitOps
sync could overwrite. `steward-worker` was called `updater` until 2026-09-12.

## Installing it

On a host with a Docker daemon and nothing else, in the directory the installation should live in:

```bash
curl -fsSL https://raw.githubusercontent.com/nordtal/season-2/main/deploy/nordtal.sh | bash
```

It asks whether that directory is really the right one, then for the handful of things only a
person knows, and deploys. **The installation is that directory**: every world, the database, every
plugin's configuration and the nightly backups are folders in it, which is what makes SFTP a way in
to them. The secrets are the one thing that is not there - they go to `/etc/nordtal/season-2.env`,
mode 600.

The script stays behind as `./nordtal.sh`. Running it again lists what is set, lets one be changed
and deploys; it fetches the current version of itself on every run and says which one it is running.
[`deploy/README.md`](deploy/README.md) is the runbook.

## The network

A Velocity proxy in front of three Paper backends. Every login lands on `limbo`, is offered the
resource pack, and is released onto whichever backend the current season phase names.

```mermaid
%%{init: {'themeVariables': {'fontSize': '16px'}}}%%
flowchart TB
    players(["Players"]):::ext --> NC

    subgraph stack["docker compose stack"]
        direction TB
        subgraph steward["Steward · its own network"]
            direction TB
            CADDY["<b>caddy</b><br/>HTTPS"]:::side
            UI["<b>steward-ui</b><br/><i>:steward-ui</i><br/>the interface · no socket"]:::app
            UPD["<b>steward-worker</b><br/><i>:steward-worker</i><br/>versions · schema · jars · backups"]:::app
            DEP["<b>steward-deployer</b><br/><i>:steward-deployer</i><br/>the only one that may create containers"]:::app
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
    players ~~~ UPD
    CADDY --> UI
    UI -->|"read: state · logs · drift"| UPD
    UI -->|"create a container"| DEP
    UPD ==>|"schema, then jars"| servers
    UPD ==> BOT
    NC -->|"pack"| LIMBO
    NC -->|"phase"| HG
    NC -->|"phase"| SMP
    servers --> PG
    BOT --> PG
    UPD --> PG
    UI --> PG

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

Italic names are Gradle modules; every other box is a container in `compose.yml`. The four library
modules — `:common`, `:commands`, `:paper-common` and `:resource-pack` — have no container of their
own: they are compiled into the jars above.

**A service is named after its role, and a replacement instance of one is that name plus `-standby`**
— `proxy-standby`, `limbo-standby`. That is the whole rule, and it is a rule rather than a colour
pair because a standby always takes the ordinary role of the service it stands in for: it runs the
same jar under the same configuration, and nothing outside the run that created it has to learn a
second identity. (The module is `proxy` and not `velocity` for the same reason — the name has to
survive the day the proxy is a different piece of software.)

**The Steward box is a second Docker network, and the Minecraft services are not on it.** Otherwise
"the internal API is only reachable from inside" would also read "any plugin may deploy". `postgres`
is on both, because it is the one thing both halves genuinely share. `steward-ui` is the part facing
the internet and therefore the part that can do the least: it owns no Docker socket, and everything
it knows about a container it asks one of the other two for, with a different shared secret for each.

**The database is the source of truth** for access, language, season phase and event state. Discord
roles are a projection of it, never the other way round.

## Modules

| module | platform | what it owns |
|---|---|---|
| `proxy` | Velocity | The login gate, the season phase, and which backend a player belongs on. |
| `limbo` | Paper | The waiting room: applying and enforcing the resource pack before a player goes anywhere. |
| `hunger-games` | Paper | The start event — registration, teams, border, loot, HUD, winning. |
| `smp` | Paper | The SMP: Nordtal, the farm world, the Nether and the End, milestones, aura, prestige, duels, graves. |
| `discord-bot` | JVM app | Sells access periods, books bunq payments, mirrors admins. |
| `steward-worker` | JVM app | Resolves platform and plugin versions, migrates the schema, swaps jars, stops and starts the servers around a run, and takes the nightly backup. |
| `steward-ui` | JVM app + React | The web interface: state, logs, configuration, seasons, access, payments, the journal. No Docker socket, ever. |
| `steward-deployer` | JVM app | The only service allowed to create a container. Carries `compose.yml` inside its own image. |
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

**A declaration is what a command on several surfaces costs, and a command on one does not pay it.**
`/msg`, `/whisper`, `/r`, `/discord` and `/rules` are plain Velocity Brigadier in `:proxy`: one
surface, one process, no confirmation, no admin flag, and no argument that travels through a
database row. Going through `:commands` bought them an effects interface, an adapter translation and
a catalogue entry that no other surface could reach. The test for whether a new command belongs in
`:commands` is therefore not "is it a command" but "does anything other than the process that runs
it need to know about it".

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

**An update run does not have to empty the network, and the `-standby` services are how.** What
drops players today is not a backend stopping — `Evacuation` already moves them off one that is
about to, and `WaitingBook` lets them back in. It is the *proxy* restarting under them, and the
limbo stopping alongside it. So a run that touches `proxy`, `limbo` or `smp` first brings up the
`-standby` of each service it touches, already carrying the new jar, and spends it on the gap:
sixty seconds of warning in chat and title, a seamless hop to `limbo-standby`, one `transferToHost`
onto `proxy-standby`, the real services restarting together, and a transfer back that puts everyone
on the server they were standing on. Where each player stood is a database row for the length of
the run. `steward-worker` conducts it; the two plugins only obey.

**What makes that cheap is that a standby is a backend, not a second network.** One Paper server can
be served by two proxies — they share nothing but the forwarding secret — so `limbo-standby` is an
ordinary limbo that two proxies happen to know about, and `proxy-standby` shrinks to its one real
job: holding the ten seconds in which the proxy itself is gone. Two Paper processes over one world
would not work at all (`session.lock`), which is why the standby of a backend is a *different*
world and never a second copy of the same one.

**Those ten seconds used to be the price, and since season-2-ops/162 the door stays open through
them.** Caddy - the same container that fronts the interface, rebuilt with `caddy-l4` - owns 25565
and hands the stream to `proxy`, and to `proxy-standby` for as long as the live proxy does not take
it. Measured on the dev host on 2026-09-20: run 73 left the port dead for 26 seconds; run 87, a
restart of the same proxy with the guard in front, answered 349 of 349 pings. The guard is in no
profile an update run touches and `Topology.SERVICES` does not know it, which is the whole of why it
is allowed to be the one permanent process in front of the network: a guard that restarted with the
proxy would have moved the dead port rather than closed it. The cost it does carry is that Velocity
now sees the guard's address, so both proxies run with `haproxy-protocol` and the guard writes a
PROXY header - one without the other answers nobody. The two alternatives weighed beside it are
still not built: a doorman that keeps both proxies up forever and swaps roles buys a persistent
"which one is live" that somebody has to be right about, and a cookie on the client would make the
client the authority on which server it may enter.

**Parking is a moment; the door is a state.** The park happens once, when the countdown reaches
zero, to whoever is connected then — and the process does not stop for another several seconds
while the worker waits for the backends to empty (sixteen of them in run 59 on 2026-09-20).
Somebody who connected inside that window was never parked, because parking was over, and met
Velocity's own *Proxy shutting down*. So for as long as a run is moving this proxy, an arrival is
**refused** with a sentence rather than let onto a process that is about to go. Refused and not
transferred, deliberately: a screen that says come back in a moment is better than a loading bar
that ends in a dropped connection (season-2-ops/151).

**And being configured is not being there.** A standby address that resolves is not a standby that
is running — those containers sit in a compose profile of their own and are stopped for all but a
minute of the season. The proxy therefore probes before it parks, and a silent standby means the
run behaves exactly as it did before any of this existed: a plain restart, and a line in the log
saying why (season-2-ops/139). Failing back to the old behaviour is a feature declining to run;
transferring a network onto a dead port is not.

**The deployment deploys itself, and `compose.yml` travels inside an image.** `steward-deployer`
carries the file it runs, so "which compose file is live" has a version number for an answer rather
than a directory on the host that somebody edited during an incident. The cost is stated plainly: a
change to the deployment needs a new image of that service, and the one thing that cannot renew it
is that service — so [`deploy/nordtal.sh`](deploy/nordtal.sh) does, from outside the stack. That script
is also what resolves the interface's host name and **waits** until it points at this host, rather
than deploying an interface whose certificate can never be issued.

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
`./gradlew check releaseArtifacts imageContexts`, verifies the pack zip against its own SHA-1,
attaches the plugin jars, the bot jar and the pack, and pushes **all five images** —
`discord-bot`, `steward-worker`, `minecraft`, `steward-ui` and `steward-deployer` — each tagged with
the version and with `latest`. A failed build is re-run against the same release with
`gh workflow run release.yml -f tag=v0.1.0`.

**A deploy pulls and never builds**, so an image that exists only in one host's daemon fails with
`denied` from the registry — which is also what a package that is still private answers. A new
package under an organisation is private on its first push, and **three of the five have never
been pushed**: `steward-ui`, `steward-deployer` and — less obviously — `steward-worker`, because
renaming `updater` renamed the package with it. `TopologyTest` holds every image `compose.yml`
defaults to against the workflow that pushes it, but only a registry can answer the other half;
[`deploy/README.md`](deploy/README.md) has what was measured and when.

## Configuration

Every config file is commented YAML described by a `@ConfigSpec` interface and loaded through
`eu.nordtal.jcore.config`. Reading one, next to its interface, is the fastest way to learn what a
module does.

This repository is public and contains no secrets. Every credential — the Discord bot token, the bunq
API key, database access — arrives through environment variables at runtime; committed configuration
files are examples only.

### The one thing that leaves the deployment

Steward's identity display draws a player's Minecraft head from an image service, by default
[mineatar](https://mineatar.io) (`steward-ui.yml`, `avatars.minecraft-head-base-url`). That means
**every render sends a third party the `mc_uuid` being looked at** — on the strength of an admin
merely opening a page, with no consent step in front of it. Nothing else about the player goes with
it, and no image is ever stored: a face is a pure function of the uuid and that base URL, which is
why it is a config value and never a column.

It is written down here rather than only in the spec's comment because it is the only outbound
dependency the season has on a service nobody here runs. It has been Crafatar, then mc-heads, and
since 2026-09-19 mineatar — none of the three makes an uptime promise, and Crafatar was in fact
measured not answering at all (steward/111), so a non-answer is drawn as a placeholder and never
blocks the page. Pointing the setting somewhere else, or at an empty string, is a config edit and
needs no build (steward/44, steward/45).
