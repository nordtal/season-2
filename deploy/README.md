# deploy

The whole of season 2's production deployment: one `docker compose` stack on one host, driven
through [Arcane](https://github.com/ofkm/arcane).

This file is both the runbook and the record of why the stack has this shape; the design
reasoning is at the bottom, under [Why it looks like this](#why-it-looks-like-this). SimpleCloud was
dropped on 2026-09-01 — see [../docs/README.md](../docs/README.md#decisions-and-when-they-were-taken).

```
deploy/
  compose.yml          six services, three profiles: db · bot · mc
  .env.example         every setting; copy to .env and fill in
  minecraft/
    Dockerfile         one image for all four Minecraft services
    entrypoint.sh      PID 1: resolve the jar, pull the plugins, run tmux, trap SIGTERM
    scripts/console    attach to the real server console (read + write)
    scripts/mc         send one command, no TTY needed
                       (named scripts/ and not bin/ - .gitignore has a repo-wide bin/ rule)
```

## First deployment, in order

The order matters in two places only, and both are marked.

1. **`cp .env.example .env` and fill it in.** Nothing has a plausible default for a value nobody
   can guess; a missing one stops `docker compose` rather than starting something surprising.
2. **Generate the forwarding secret** — see below. This is the step most likely to be skipped and
   the one whose failure looks like something else.
3. **Bring up PostgreSQL and the bot first.** ← *ordering matters.* The bot is the only process
   that applies the schema, and every other service expects it to be current.
   ```
   docker compose --profile db --profile bot up -d
   ```
   Leave `NORDTAL_ACCESS_PAYMENT_WATERMARK` empty on a fresh database: the first start stamps its
   own instant, and without it the first poll books up to 50 historical bunq payments — roles, DMs
   and public thank-yous included.
4. **Start the Minecraft services.** They will come up with default configs and a generated world.
   ```
   docker compose --profile mc up -d
   ```
5. **Configure the proxy.** `velocity.toml` lives in the `mc-network-control` volume. Its `[servers]`
   entries use the compose service names as hostnames, and those names are also what
   `network-control`'s `gate.yml#server-*` keys expect:
   ```toml
   [servers]
   limbo = "limbo:25565"
   hunger-games = "hunger-games:25565"
   smp = "smp:25565"
   ```
   Set `player-info-forwarding-mode = "modern"` and
   `forwarding-secret-file = "forwarding.secret"` — the entrypoint writes that file from
   `VELOCITY_FORWARDING_SECRET` on every start.
6. **Turn the backends into backends.** In each Paper volume, `server.properties` needs
   `online-mode=false` (the proxy authenticates) and the port left at 25565; each container has its
   own address on the compose network, so there is no port to deconflict. Only the proxy publishes
   one to the host.
7. **Upload the hand-built worlds** — see below.
8. **Run the login-path rehearsal** — [`../../todo.md`](../../todo.md), section 1. Nothing above
   proves a client can join.

## The forwarding secret

Modern forwarding needs the **same secret in four places**, and a mismatch does not say so: it
shows up as every login failing with *"Unable to connect you to the backend server"*.

```bash
openssl rand -hex 24
```

Put it in `.env` as `VELOCITY_FORWARDING_SECRET`. The proxy container writes it to
`/data/forwarding.secret` on every start, so the proxy side is handled by the environment like
every other secret in this project.

**The three Paper backends are not.** Each one needs it pasted into its own
`config/paper-global.yml`:

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: '<the same value>'
```

That is a manual step per backend, once. It is not automated because it is YAML surgery on a file
Paper owns and rewrites, and a half-successful edit there is worse than a step in a runbook.

## Getting a world into a volume

The hunger games map and the Nordtal spawn are hand-built and have to be uploaded. There are no
bind mounts by design, so this goes through the volume:

```bash
docker compose stop hunger-games
docker cp ./world-hunger-games/. nordtal-s2-hunger-games-1:/data/world/
docker compose start hunger-games
```

In Arcane the same thing is a file upload into the volume. Either way: **stop the server first.**
Copying into a world a running server has open produces corruption that surfaces days later.

## The console

Arcane's per-container shell is a `docker exec`, which cannot reach PID 1's stdin — so a server
started as a plain `java -jar` would have a console you can read and not write. The server runs
inside a tmux session instead, and the image ships two commands:

```bash
console            # attach to the real console, read and write. Detach with Ctrl-b then d.
mc <command>       # send one command, no TTY needed. Output goes to the container log.
```

`mc` is the one to reach for in a runbook or a script; `console` is for watching something happen.

**Ctrl-C inside `console` goes to the server, not to your shell.** Use `mc stop` or
`docker compose stop` to shut a server down.

Reading is unaffected by any of this: the entrypoint tails the server's own `logs/latest.log` onto
the container's stdout, so `docker logs` and Arcane's log view show everything, without terminal
escape sequences.

## Updating

A plugin update is a version bump. Nothing is copied by hand — that was the daily cost of the
SimpleCloud dashboard and it is what this replaces.

```bash
# .env: SEASON_VERSION=0.1.1, SEASON_RELEASE=v0.1.1
docker compose up -d
```

Each container pulls the jars named by that version from the GitHub release into its own volume and
deletes the superseded ones. A rollback is the same edit backwards.

**Cache-first, and it fails closed.** A version already in the volume is never re-fetched, so a
GitHub outage does not stop a restart. A jar that the pin requires and that cannot be fetched stops
the container — it never quietly runs last week's plugin.

A **server** update is the same shape: bump `PAPER_BUILD` or `VELOCITY_BUILD` after checking
`https://fill.papermc.io/v3/projects/paper/versions/26.2/builds` for the newest `STABLE`. Never
"latest"; the pin is the point.

## Stopping

```bash
docker compose stop smp          # graceful: SIGTERM, the JVM saves, up to 180s
docker compose down              # the whole stack; volumes survive
```

`stop_grace_period` is 180 s on every Minecraft service. Measured 2026-09-01, a Paper server with a
generated world stops in **3 s** and logs `All dimensions are saved`; the headroom is for a
border-4000 Nordtal, not for the normal case. Do not lower it, and never use `docker kill`.

## Backups

**There is no backup concept yet, and it is the only irreversible risk in this project.** Access
periods, payment records, aura, milestone progress and graves are all in one PostgreSQL. The stack
makes the fix local — a `pg_dump` sidecar against the `postgres-data` volume — but nobody has
written it, and no restore has ever been tried. It belongs before the SMP phase opens, and it is
the first item in [`../../todo.md`](../../todo.md).

## Troubleshooting

| symptom | cause |
|---|---|
| Container will not start, log names a config key | jcore refused the config. The message names the file and the setting; it is not a container fault. |
| `FATAL: set EULA=true` | Deliberate. The image does not accept Minecraft's EULA on your behalf. |
| `FATAL: could not fetch <jar> … Refusing to start` | The release tag or the asset name in `.env` is wrong, or GitHub is down and this jar was never cached. It will not fall back to an older jar. |
| Every login fails with *"Unable to connect you to the backend server"* | The forwarding secret does not match. Four places, see above. |
| Proxy starts but refuses every login with a "network misconfigured" screen | `network-control` failing closed on a bad `gate.yml`/`database.yml`/`pack.yml`. Intended; read the log. |
| `docker rm -f` fails with *"did not receive an exit event"* | You are running a container that mirrors its console with `tmux pipe-pane > /proc/1/fd/1`. Do not do that — see [below](#never-mirror-the-console-with-tmux-pipe-pane). Only a Docker daemon restart clears it. |

## Third-party plugins

Two kinds, and the distinction matters because one of them may be missing and the other may not.

- **Required, on the `smp` service only: DisplayTags and PacketEvents underneath it.** Nametags come
  from [`papermc-display-tags`](https://github.com/nordtal/papermc-display-tags) — our own fork —
  through its API, which is an interface over the running plugin. `smp`'s `paper-plugin.yml`
  declares it with `load: BEFORE` and `required: true`, so a server missing either fails loudly at
  start instead of quietly rendering plain nametags. Two more jars to keep current with 26.2, one of
  them ours.
- **Optional: CoreProtect**, purely as insurance. Nothing in the design depends on it, and it gets
  its own SQLite file rather than a schema in our PostgreSQL so that
  "[exactly one process migrates](../docs/architecture.md#schema-ownership)" stays literally true.
  It had no 26.2 release as of 2026-08-31, only a `master` that builds against it; if it has not
  shipped when the phase is ready, the phase opens without block logging and Prism 4.4 is the
  written fallback. The comparison is in [smp.md](../docs/smp.md#block-logging--checked-2026-08-31).

## Why it looks like this

The runbook above is what to do; this is why, so that nobody re-opens a settled question by
accident. Everything here was decided on 2026-09-01.

- **One image for all four Minecraft services**, built from `minecraft/Dockerfile`: a JRE 25 base,
  the pinned server jar, and a wrapper as PID 1. `itzg/docker-minecraft-server` is the obvious
  candidate and was rejected — it does not cover Velocity (that is a *second* image with its own
  vocabulary), neither image solves the console problem for the proxy, and we want a pinned server
  build rather than runtime version resolution. One Dockerfile covers all four identically.
- **The console is tmux, not RCON**, because RCON would not be uniform: Paper has it, **Velocity has
  no RCON at all** (checked 2026-09-01 — the only option is the third-party Velocircon plugin).
  That would mean one mechanism for three servers, another for the proxy, and a third-party plugin
  on the single process whose whole job is deciding who may join. tmux costs two scripts.
- **PID 1 traps SIGTERM**, sends `stop` into the session and waits for the JVM to exit. Not
  optional: without it `docker stop` kills a wrapper and leaves the JVM to be SIGKILLed. That is
  what `stop_grace_period: 180` is for — the compose default of 10 s does not save a border-4000
  world, and a save cut off halfway stays invisible for days.
- **Named volumes, no bind mounts.** Arcane reaches volumes directly, and a bind-mounted world
  folder is a uid/permission problem whose symptom is a corrupted save.
- **Plugin jars are pulled at container start** from the GitHub release named by one version in
  `.env`. This is the job the SimpleCloud dashboard could not do at all: its plugin management only
  understands Modrinth-hosted jars, and every jar we deploy is either ours or a fork of ours.
- **The bot is the only process that migrates**, so bring it up first after a schema change.
  Compose `depends_on` can express "PostgreSQL is healthy" and cannot express "the schema is
  current", so this stays an operator rule.
- **The proxy needs database access**, which is a compose network rather than a firewall rule now.
  The credentials exist in more than one config file because the database is the source of truth;
  accepted.
- **No secrets are in this repository.** The Discord token, the bunq API key and the database
  credentials arrive as environment variables; committed config files are examples.

### What was measured, and what it cost

A Velocity 4.1.1 build 24 and a Paper 26.2 build 121 container were run from this image on
2026-09-01. Not inferred — run.

| | |
|---|---|
| Fill API resolution, end to end | the pinned build is fetched, its sha256 checked against what the API reports, and a cached jar means no network call at all |
| Cold boot | Paper 39 s from an empty volume, Velocity 9 s |
| Console writable from a plain `docker exec` | `mc "list"` and `mc "glist"` both executed, output in the container log. That is the mechanism Arcane's shell uses |
| `docker stop` | Paper 3 s, exit 143, `All dimensions are saved` and `All RegionFile I/O tasks to complete`. Velocity 2 s, exit 143 |
| The EULA gate | fails closed, with the message that names it, before anything starts |

Still untested: whether the *interactive* `console` attach behaves inside Arcane's browser terminal
— see [`../../todo.md`](../../todo.md), section 3.

### Never mirror the console with `tmux pipe-pane`

`tmux pipe-pane … > /proc/1/fd/1` is the obvious way to get the tmux console into `docker logs`, and
**it wedges the container**. Measured on Docker 29.4.1: with that line, SIGTERM never reaches PID 1,
the shutdown trap never runs, the container survives the SIGKILL at the end of the grace period, and
`docker rm -f` then fails with *"tried to kill container, but did not receive an exit event"* — a
container only a Docker daemon restart can clear. Same image, that one line removed: `docker stop`
finishes in **one second**.

A pipe-pane writer holds a second handle on the container's stdout pipe from a process whose
lifetime the shim does not track. `tail -F` on the server's own `logs/latest.log` is a plain child
of PID 1 inheriting its stdout, does not do that, and gives a container log free of terminal escape
sequences as a bonus. That is what `entrypoint.sh` does. An A/B of the identical image, one
variable, both directions — written down because rediscovering it costs the same hour it cost the
first time.
