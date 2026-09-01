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

The order matters, and **it changed on 2026-09-01**: the updater runs between the database and
everything else, and nothing else starts before it has. It is the bootstrap now — without it there
is no schema and there are no plugin jars.

1. **`cp .env.example .env` and replace every `REPLACE_ME`.** That file is the whole of the
   configuration; nothing else has to be edited anywhere. Nothing has a plausible default for a
   value nobody can guess, and `REPLACE_ME` is not one either — it fails validation by name
   (*"roles.access must be a Discord snowflake (digits only)"*) rather than starting something
   surprising. One of them is the forwarding secret: `openssl rand -hex 24`.

   **There is no hash to look up any more.** `PACK_SHA1` was in this file for a few hours on
   2026-09-01 and is gone again: the updater reads the release's `.zip.sha1` asset and writes it
   into the proxy's `pack.yml` itself. Every remaining `REPLACE_ME` is something only you know — a
   Discord id, a bunq key, a password.
2. **Build the updater jar.** Compose builds its image from `../updater`, and that image copies in
   a jar Gradle has to have produced first. The Minecraft image needs no such step; it builds from
   `./minecraft` alone.
   ```bash
   ./gradlew :updater:shadowJar
   ```
3. **Bring up PostgreSQL alone.**
   ```
   docker compose --profile db up -d
   ```
4. **Run the updater.** ← *ordering matters, and this step is the bootstrap.*
   ```
   docker compose --profile updater run --rm updater apply
   ```
   It does three things in this order: applies the database schema, fills every `plugins/` folder,
   and writes the proxy's `pack.yml`. **Nothing else in this stack can start before it has run** —
   the bot refuses a database it did not migrate, and a Minecraft container refuses an empty
   `plugins/` folder. Both say so by name.

   This changed on 2026-09-01. The bot used to be the only process that migrated, and the plugin
   jars used to be fetched by each Minecraft container; both are the updater's now, because a
   release that adds a table is a release that adds a migration and the two belong to one owner
   ([../docs/updater.md](../docs/updater.md)).

   Run it without an argument first if you want to see the plan and change nothing, or
   `updater migrate` for the schema alone — useful on a host where no release is published yet.
5. **Bring up the bot, then the Minecraft services.**
   ```
   docker compose --profile bot up -d
   docker compose --profile mc up -d
   ```
   Leave `NORDTAL_ACCESS_PAYMENT_WATERMARK` empty on a fresh database: the first start stamps its
   own instant, and without it the first poll books up to 50 historical bunq payments — roles, DMs
   and public thank-yous included.

   On a fresh volume each Minecraft container seeds the configuration it cannot work without and
   then never touches it again — see [First-start seeding](#first-start-seeding) for exactly what is
   written and what that leaves to you. Database credentials, the forwarding secret and the server
   list all arrive from `.env`; there is nothing to paste into a volume.
6. **Upload the hand-built worlds** — see below.
7. **Run the login-path rehearsal** — [`../../todo.md`](../../todo.md), section 1. Nothing above
   proves a client can join.

## First-start seeding

The container writes four things, **only when they are not there already**, and a file that exists
is never edited again. It is deliberately the minimum that makes a login work, not a set of
opinions about how to run a server: everything else stays Paper's and Velocity's own default.

| what | where | when |
|---|---|---|
| `player-info-forwarding-mode`, `bind`, `online-mode`, `[servers]`, `try`, an empty `[forced-hosts]` | proxy, `velocity.toml` | no `velocity.toml` yet and `VELOCITY_SERVERS` is set |
| `proxies.velocity.enabled: true` | each backend, `config/paper-global.yml` | no `paper-global.yml` yet and `PAPER_VELOCITY_SECRET` is set |
| `online-mode=false` | each backend, `server.properties` | **every start**, see below |
| `forwarding.secret` | proxy | every start, from `VELOCITY_FORWARDING_SECRET` |

`online-mode` is the one thing enforced rather than seeded. It is not a preference: a backend that
authenticates players itself refuses every forwarded login, so `online-mode=true` there is a server
that cannot work, not a choice somebody might have made. The rest is seeded once and yours
afterwards.

**`[forced-hosts]` is written empty on purpose, and it is not tidiness.** Measured 2026-09-01 on
Velocity 4.1.1 build 24: leave the table out of `velocity.toml` and Velocity falls back to its
*default* one, which routes `lobby.example.com` and two others at servers the file does not define
— and it then refuses to start at all with *"Your configuration is invalid"*. "Velocity defaults
everything you leave out" is true per key, not per table.

**What this does not do** is fix a volume that already exists. The seeding only ever fires on a
first start, so a server that has run before keeps whatever is in its volume; the manual equivalent
is `online-mode=false` in `server.properties` and `proxies.velocity.enabled: true` in
`config/paper-global.yml`.

## The forwarding secret

Modern forwarding needs the **same secret in all four containers**, and a mismatch does not say so:
it shows up as every login failing with *"Unable to connect you to the backend server"*.

```bash
openssl rand -hex 24
```

Put it in `.env` as `VELOCITY_FORWARDING_SECRET` and that is the whole of it. `compose.yml` hands
the same value to the proxy under that name and to each backend as `PAPER_VELOCITY_SECRET`; the
proxy writes it to `/data/forwarding.secret`, and Paper reads its own copy straight from the
environment ([PaperMC/Paper#10127](https://github.com/PaperMC/Paper/discussions/10524)). Since a
mismatch can now only mean "one container did not get the variable", it is not really a class of
failure any more.

**It does still land on disk.** Verified 2026-09-01 on Paper 26.2 build 121: Paper writes the value
it took from the environment into `config/paper-global.yml` on first load. The environment variable
removes the manual paste, not the copy in the volume — so **rotating** the secret is `.env` *plus*
that one line in each of the three backend volumes.

**Auto-generating it was considered and rejected.** All four containers would have to arrive at the
same value, which means either a shared volume holding it or deriving it from something that is not
random — new shared state, or a weaker secret, to save one `openssl` call that happens once per
season. Its value here is admittedly limited: no backend publishes a port, so the secret protects
against something already inside the compose network. But Paper will not run modern forwarding
without one, and modern forwarding is what gives the backends real UUIDs.

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

A plugin update is one command, and a second one to make it take effect.

```bash
docker compose --profile updater run --rm updater          # what would change, changes nothing
docker compose --profile updater run --rm updater apply    # migrate, then fetch and put in place
docker compose --profile mc restart                        # the servers pick up what is on disk
```

`apply` applies the schema before it moves a jar, so a plugin never comes up against a schema older
than itself. A migration that fails stops the run there: nothing is fetched, nothing is written, and
a half-migrated database with new jars on top of it is the state nobody can reason about.

The updater asks GitHub, Modrinth and the PaperMC Fill API what the newest version of everything is,
compares that against the jars lying in the volumes, and moves the ones that differ. Nothing is
copied by hand and no version is written into a file — **what a server runs is the jar in its
volume**, which is why `SEASON_RELEASE` no longer exists here. What the updater *follows* is
`UPDATER_SEASON_RELEASE`, and setting it to an exact tag instead of `latest` is how a rollback is
expressed.

Three properties worth knowing, because each is a decision:

- **Two phases.** Everything a server needs is downloaded into `.nordtal-staging` inside that
  server's own volume and verified there; only when all of it is present does anything move into
  `plugins/`. A download that fails half way leaves the server exactly as it was. Four servers on
  two versions of the season is a worse state than four servers that did not update.
- **A server moves together or not at all.** If one of a server's artefacts cannot be resolved, that
  whole server is skipped. "The new SMP jar with last week's PacketEvents" is a combination nobody
  chose, and DisplayTags is a *required* plugin of `smp` whose own required plugin PacketEvents is.
- **It restarts nothing.** `apply` prints what it did and stops. Read that before restarting — a
  server that was part-updated is exactly the thing worth catching before the network goes down on
  it. Steps 4 to 6 of [../docs/updater.md](../docs/updater.md) put that report in Discord with a
  restart button under it.

Checksums are verified where a checksum exists: Modrinth publishes a sha512 per file and the Fill
API a sha256 per build, and a mismatch deletes the download instead of installing it. **A GitHub
release asset carries no digest of any kind**, so our own jars and the DisplayTags jar arrive
unverified over TLS — the same way `entrypoint.sh` has always fetched them, and a real gap rather
than an implied one.

The **server jar** is still the entrypoint's, not the updater's: Paper and Velocity builds are
resolved by the container at start from the version pinned in `.env`. The updater reports when a
newer `STABLE` build exists and installs it into the same cache the entrypoint reads.

## Stopping

```bash
docker compose stop smp          # graceful: SIGTERM, the JVM saves, up to 180s
docker compose down              # the whole stack; volumes survive
```

`stop_grace_period` is 180 s on every Minecraft service. Measured 2026-09-01, a Paper server with a
generated world stops in **3 s** and logs `All dimensions are saved`; the headroom is for a
border-4000 Nordtal, not for the normal case. Do not lower it, and never use `docker kill`.

## Backups

Access periods, payment records, aura, milestone progress and graves are all in one PostgreSQL, and
it is the only thing in this stack that cannot be rebuilt from the repository and a world folder.

**The `backup` profile dumps it.** `postgres-backup` runs `pg_dump --format=custom` into the
`postgres-dumps` volume once a day at `BACKUP_AT` (04:00 by default) and again at start-up, keeps
`BACKUP_KEEP` of them (14), and writes the outcome of the last run to `postgres-dumps/LAST_RESULT`
as well as to the container log. A dump is written under a `.partial` name, checked by reading its
own table of contents back with `pg_restore --list`, and only then renamed — a half-written file
that looks like every other dump in the directory is the one the retention sweep keeps and the
restore picks. It runs as `postgres`, not root, and stops on SIGTERM instead of waiting out the
grace period.

### Point Arcane at `postgres-dumps`, never at `postgres-data`

Arcane can snapshot a named volume to S3 with `rustic` (S3 backups shipped in v2.9.0, scheduled
volume backups in v2.10.0). It stops the containers using that volume **only when the backup
policy's `Stop Containers` flag is set** — read from `backend/internal/volume/backup.go` on
2026-09-01, where the whole stop/restart block sits behind `if plan.policy != nil &&
plan.policy.StopContainers`. For a live PostgreSQL data directory both settings are wrong:

- **Off**, it tars a running PGDATA. That is a torn copy. It raises no error at backup time and is
  a broken cluster at restore time, which is the worst possible order to find out in.
- **On**, PostgreSQL goes down for the length of the tar, every night. Every process in this stack
  fails fast on an unreachable database, so that is a nightly outage of logins and of payment
  booking.

`postgres-dumps` has neither problem. Nothing holds it open between runs, so a policy with `Stop
Containers` **off** is correct there, and what travels to S3 is a few megabytes rather than a whole
data directory — which matters, because Arcane runs at home and the host is at the far end of a
tunnel.

### Restoring

```bash
docker compose exec postgres psql -U "$POSTGRES_USER" -d postgres -c 'CREATE DATABASE restored;'
docker compose exec postgres-backup sh -c 'pg_restore --dbname=restored --no-owner /dumps/<file>'
```

Restore into a *new* database and look at it before you point anything at it. `--no-owner` is what
lets a dump taken as one role restore under another.

### What was measured

The whole cycle was run on 2026-09-01, on this compose file, from empty volumes — not inferred:

| | |
|---|---|
| First run on a fresh named volume | **failed**, `Permission denied` on its own output file. Docker creates a named volume owned by root when the mount path is not in the image; the Dockerfile now creates `/dumps` owned by `postgres`, which is what Docker seeds an empty volume from |
| A dump of a seeded database | 2,657 bytes, written, TOC-verified and renamed |
| `pg_restore` into an empty database | both tables and both rows came back, timestamps intact |
| Retention at `BACKUP_KEEP=2` | four dumps in, two kept, the two oldest pruned by name |
| SIGTERM during the wait | trapped and exited, rather than sitting out an 18-hour `sleep` |

**What it does not prove** is a restore of the real season database from a dump pulled back out of
S3. That needs the host, and it is in [`../../todo.md`](../../todo.md).

### The world volumes are a different problem

Nothing here backs up `mc-smp`, `mc-hunger-games`, `mc-limbo` or `mc-network-control`, and that is
deliberate: a world is recreatable from a seed and a build, a payment record is not. Pointing an
Arcane policy at a world volume also means streaming it over the tunnel from the rented host to a
home connection, and after border 4000 that is potentially tens of gigabytes.

The chosen path is season 1's — an installed plugin that zips the world and a list of extra folders
and uploads them by SFTP — and it is an **operator task, not a build**: it is in
[`../../todo.md`](../../todo.md) with what was checked on 2026-09-01. Two things from that check are
worth having here, because they are the kind that get rediscovered expensively:

- **DriveBackupV2 has no 26.2 build.** It stops at 26.1.2 (Modrinth API, 2026-09-01).
- **[Backuper](https://modrinth.com/plugin/backuper) does, and its `setWorldsReadOnly` defaults to
  `false`** while its own config comment says "True recommended". Left at the default it zips a
  world folder the server is writing into — the same torn copy this whole section exists to avoid,
  in the place nobody looks for it.

## Troubleshooting

| symptom | cause |
|---|---|
| Container will not start, log names a config key | jcore refused the config. The message names the file and the setting; it is not a container fault. |
| `FATAL: set EULA=true` | Deliberate. The image does not accept Minecraft's EULA on your behalf. |
| `FATAL: could not fetch <jar> … Refusing to start` | The release tag or the asset name in `.env` is wrong, or GitHub is down and this jar was never cached. It will not fall back to an older jar. |
| Every login fails with *"Unable to connect you to the backend server"* | The forwarding secret does not match — which now means one container did not get `VELOCITY_FORWARDING_SECRET`, or the volume predates the automation and still carries an old one. |
| Velocity exits at once with *"Your configuration is invalid"* | `velocity.toml` names a server in `[forced-hosts]` or `try` that its `[servers]` does not define. |
| A backend logs *"SERVER IS RUNNING IN OFFLINE/INSECURE MODE"* | Expected, and required. The proxy authenticates; a backend that also does refuses every forwarded login. |
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
- **Required, on the `smp` service only: Chunky.** Added 2026-09-01, and it is required for a
  mechanic rather than a rendering: the farm world is pre-generated every night — roughly 15 000
  chunks beside a live server — and the daily reset waits for Chunky's completion event before it
  swaps anything in. Without it the reset would not fail, it would postpone itself every night,
  silently, which is why `required: true` turns that into a start-up failure instead.
  `Chunky-Bukkit-1.5.3.jar`, which is the version Modrinth tags for `paper` on 26.2.

**Where all three come from changed on 2026-09-01.** They used to be three full URLs in
`SMP_EXTRA_PLUGIN_URLS`, with three versions written into `.env` by hand. The updater resolves them
now — DisplayTags from its own repository's releases, PacketEvents and Chunky from Modrinth filtered
to this Minecraft version and `paper` — so a version bump is a run of `updater apply` and not an
edit. `required: true` is unchanged, and the container refuses to start on an empty `plugins/`
folder, which is what catches "the updater never ran here".

**And two datapacks, which are not plugins but belong in the same conversation: Terralith and
Dungeons and Taverns.** They are what the terrain of every world in this season is, they are pinned
by sha512 in `.env` (`SMP_DATAPACK_URLS`), and the entrypoint fetches them into the `level-name`
world's `datapacks/` folder *before* the server starts.

Three facts about them, all measured on Paper 26.2 build 121 on 2026-09-01 rather than assumed:

- **Datapacks are server-global.** They are read only from `<level-name>/datapacks/`. A probe pack
  placed in a secondary world's own `datapacks/` folder was never listed — not at start, not after
  that world was created, not after `refreshPacks()`. There is no per-world datapack API.
- **They are read once, at start.** A pack dropped in afterwards changes no terrain, and terrain is
  never re-rolled once it is on disk. That is why the entrypoint fetches them before Java runs.
- **A world created through the Bukkit API lands at `<level-name>/dimensions/minecraft/<name>`**,
  inside the primary world rather than beside it. Worth knowing before you go looking for the farm
  world's folder, or size a volume for the two that exist during a swap.

`smp` verifies both are enabled and refuses to start otherwise. That refusal is deliberate: a farm
world generated without Terralith is one flat day, but Nordtal generated without it is the whole
season, on a world with a spawn built on it that therefore cannot be thrown away.
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
- **Plugin jars are pulled from a GitHub release**, not from a dashboard. This is the job the
  SimpleCloud dashboard could not do at all: its plugin management only understands Modrinth-hosted
  jars, and every jar we deploy is either ours or a fork of ours. *(They were pulled by each
  container at start until 2026-09-01; the `updater` pulls them now, and the argument against the
  dashboard is unchanged.)*
- **The updater is the only process that migrates**, so it runs before everything else. It was the
  bot until 2026-09-01. Compose `depends_on` can express "PostgreSQL is healthy" and cannot express
  "the schema is current", so this stays an operator rule — but it is no longer only a rule: the bot
  validates the schema and refuses a database it was not built against, naming the command.
- **The proxy needs database access**, which is a compose network rather than a firewall rule now.
  The credentials exist in more than one config file because the database is the source of truth;
  accepted. They are written **once** in `compose.yml`, as three YAML anchors, and handed to each
  process under the prefix it reads — `NORDTAL_DATABASE_*` for the bot, `NORDTAL_SMP_DATABASE_*`
  for the SMP plugin, and so on. `.env` therefore has one set of database settings and no
  per-plugin anything; pointing the whole stack at an external PostgreSQL is `NORDTAL_DATABASE_*`
  plus dropping the `db` profile.
- **The container seeds its own first-start configuration**, rather than the runbook asking for
  five hand edits across four volumes. Those edits were the largest remaining source of a stack
  that comes up and cannot be joined, and three of them (the forwarding secret in three files) were
  the same value typed three times. The rule that keeps this from becoming a config manager: a file
  that exists is never touched, so the seeding is only ever the empty-volume case. The one
  exception is `server.properties#online-mode`, which is not a preference — see
  [First-start seeding](#first-start-seeding).
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

Extended on 2026-09-01, when the first-start seeding was written — again run, not inferred:

| | |
|---|---|
| A four-line `config/paper-global.yml` | comes back as Paper's full config with every other key defaulted, one warning that it had no version set, and `proxies.velocity.enabled: true` intact |
| `PAPER_VELOCITY_SECRET` | is honoured — **and written into `paper-global.yml`**, so the variable saves the paste and not the on-disk copy |
| `online-mode=false` seeded before first start | survives Paper writing its own `server.properties`; the server logs OFFLINE/INSECURE MODE, which is the wanted state behind a proxy |
| A `velocity.toml` without `[forced-hosts]` | **fails**: Velocity applies its default table, whose three servers do not exist, and refuses to start. The table is now written empty |
| The same file with it | boots in 0.8 s with modern forwarding on and no "forwarding is disabled" warning |

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
