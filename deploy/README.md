# deploy

Season 2's production deployment: one `docker compose` stack on one host, driven through
[Arcane](https://github.com/ofkm/arcane). This file is the operator's runbook; the project overview
is [`../README.md`](../README.md).

**`compose.yml` and `.env.example` are at the repository root, not in here**, because Arcane's
GitOps sync pulls the entire directory the compose file lives in. **Every command in this file
therefore runs from the repository root**, not from `deploy/`.

```
compose.yml            seven services, five profiles: db · bot · mc · backup · devpack
                       (the updater has none, and devpack is local only)
.env.example           every setting; copy to .env and fill in
deploy/
  minecraft/
    Dockerfile         one image for all four Minecraft services
    entrypoint.sh      PID 1: resolve the jar, pull the plugins, run tmux, trap SIGTERM
    entrypoint-test.sh the seeding half of it, against fixture directories (runs on `check`)
    scripts/console    attach to the real server console (read + write)
    scripts/mc         send one command, no TTY needed
                       (named scripts/ and not bin/ - .gitignore has a repo-wide bin/ rule)
  dev                  the local stack: init · up · deploy · pack · reset - see Locally below
  dev-test.sh          the guard on `dev reset`, without Docker (runs on `check`)
  dev.env.example      every setting the local stack needs; copy to dev.env
  servers/             NOT IN GIT. Local plugin directories for the dev stack
  pack/                NOT IN GIT. The locally built resource pack the devpack profile serves
```

## First deployment, in order

The host needs no shell, no JDK and no Gradle. All four of our images — `minecraft`, `updater`,
`discord-bot`, `postgres-backup` — are pushed to `ghcr.io/nordtal` by
[`.github/workflows/release.yml`](../.github/workflows/release.yml) when a release is published, and
compose pulls every one of them. The `build:` blocks in `compose.yml` are for developing on your own
machine: **Arcane deploys by pulling and never builds**, so an image that only exists in one host's
Docker fails the deploy with `error from registry: denied`.

### Once, before the first deployment

1. **Publish the release.** Tag it, publish it on GitHub, and let `release.yml` finish. It attaches
   eight assets and pushes four images, each tagged with the version and with `latest`.
2. **Set all four packages to Public**, in their GitHub package settings. A package under an
   organisation is **private on its first push**, and a private package answers a pull with the same
   `denied` as one that does not exist. The alternative is a registry credential in Arcane.

### From Arcane, with no shell on the host

3. **Point the project at the repository root**, not at `deploy/`.
4. **Type the environment into Arcane.** [`../.env.example`](../.env.example) is the reference —
   every `REPLACE_ME` in it is something only you know, and one is the forwarding secret
   (`openssl rand -hex 24`). `.env` is gitignored here, so Arcane's `.env.git` is empty and
   everything comes from what you type; a sync never overwrites those values.
5. **Set Auto Sync on and Redeploy After Sync off**, with the pull policy on *always pull latest*. A
   redeploy takes the four Minecraft servers down for minutes, and a commit must not do that to
   people who are playing — the updater's restart button gives them a countdown first.
6. **Deploy.** On its first start the `updater` applies the schema and **fills every empty volume** —
   the four `plugins/` folders, both jar volumes, each server's `.server/` cache and the proxy's
   `pack.yml` — and only then writes the readiness marker every other service waits for. It
   **cannot move a version**: only artefacts with nothing installed are fetched.
   `UPDATER_BOOTSTRAP=false` turns it off, and the servers then refuse to start until
   `updater bootstrap` has run, and say so by name. A first deployment downloads four server jars
   and every plugin before it goes healthy, which is why the updater's healthcheck allows fifteen
   minutes.
7. **Upload the hand-built worlds** — see [Getting a world into a
   volume](#getting-a-world-into-a-volume). **This is the one step that still needs a shell.**
8. **Run the login-path rehearsal.** Nothing above proves a client can join.

### From a shell instead

Steps 3 to 6 collapse into one command from the repository root, and it still builds nothing:

```bash
docker compose up -d
```

**A release cannot be pinned, and there is no rollback.** `IMAGE_TAG` and `UPDATER_SEASON_RELEASE`
were removed on 2026-09-09: every image is `latest` and the updater follows the newest published
release. A bad release is corrected by publishing a better one — the same trade this project already
took on the Paper build, for the same reason, which is that a pin is a version number kept somewhere
other than `gradle.properties` and every one of those went stale.

## First-start seeding

The container writes seven things, **only when they are not there already**; a file that exists is
never edited again. Everything else stays Paper's and Velocity's own default.

| what | where | when |
|---|---|---|
| `player-info-forwarding-mode`, `bind`, `online-mode`, `[servers]`, `try`, an empty `[forced-hosts]` | proxy, `velocity.toml` | no `velocity.toml` yet and `VELOCITY_SERVERS` is set |
| `proxies.velocity.enabled: true` | each backend, `config/paper-global.yml` | no `paper-global.yml` yet and `PAPER_VELOCITY_SECRET` is set |
| `online-mode=false` | each backend, `server.properties` | **every start**, see below |
| `level-name=$LEVEL_NAME` | each Paper server, `server.properties` | seeded once; a volume still on Paper's default `world` is repaired, any **other** disagreement stops the container, see below |
| `level-seed=$LEVEL_SEED` | each Paper server, `server.properties` | only while the `level-name` world does not exist yet — which is `level.dat`, not the folder; an existing world is compared and warned about |
| `forwarding.secret` | proxy | every start, from `VELOCITY_FORWARDING_SECRET` |
| `max-players=$MAX_PLAYERS` | each Paper server, `server.properties` | **every start** — the network's own limit, out of the same `NETWORK_MAX_PLAYERS` the proxy gets, see below |

The MOTD and the player limit are deliberately not in that table: both are `network-control` config
(`plugins/network-control/network.yml`), reachable from `.env` through jcore environment overrides
that win over the file on every start. See [Who limits the players](#who-limits-the-players).

**A disagreeing `level-name` stops the container rather than being corrected.** Pointing an existing
volume at a new name moves nothing — Paper generates a second, empty world beside the first and runs
the season on that, while the world with everything in it sits untouched in the same volume. The
refusal names both values and the two ways out. **The one exception is the literal string `world`**,
Paper's own default and therefore not a name anybody chose: it is deleted (with `world_nether` and
`world_the_end`) and regenerated under the right name, but only when nobody has ever logged out in
it — one `<world>/playerdata/<uuid>.dat` is enough to refuse, and the message then gives the
volume-wipe command. Any other name was typed into `.env` by a person, and the container refuses.

`online-mode` is enforced rather than seeded: a backend that authenticates players itself refuses
every forwarded login, so `online-mode=true` there is a server that cannot work.

**`[forced-hosts]` is written empty on purpose.** Leave the table out of `velocity.toml` and
Velocity falls back to its *default* one, which routes three hostnames at servers the file does not
define — and then refuses to start with *"Your configuration is invalid"*.

**None of this fixes a volume that already exists**, beyond the exception above. The manual
equivalent is `online-mode=false` in `server.properties` and `proxies.velocity.enabled: true` in
`config/paper-global.yml`.

`deploy/minecraft/entrypoint-test.sh` drives ten cases of this logic against fixture directories on
`./gradlew check`, and `:smp`'s and `:hunger-games`' `ComposeWorldTest` compare the `LEVEL_NAME` in
`compose.yml` against each plugin's own configured world name.

## Who limits the players

**One number decides.** `NETWORK_MAX_PLAYERS` in `.env` is what the server browser advertises, what
the proxy enforces at the login gate, *and* what every Paper backend's
`server.properties#max-players` is set to. All four services read the same variable.

The proxy is the only thing that refuses, at the login gate, before the resource pack and before the
wait in `limbo`. The backends' `max-players` only guarantees they are never a *smaller* limit than
the advertised one — Paper's own default is 20.

**Admins are exempt from the proxy's limit**, so a full network holds `NETWORK_MAX_PLAYERS` plus
whoever came to fix it. Each Paper plugin therefore answers Paper's own
`PlayerServerFullCheckEvent` itself and lets an admin through; the flag is `discord_user.admin` in
the database, not `ops.json`, which is why this cannot be a server setting. See
`common/…/access/FullServerAdmission.java`.

**Changing the number means restarting the backends, not just the proxy** — it is written into each
`server.properties` by that container's own entrypoint on every start. The whole change is
`docker compose restart network-control limbo hunger-games smp`, or a redeploy.

An existing `network.yml` may still carry `backend-limit`; the proxy deletes the line on its next
start, says which key in a `WARN`, and leaves the old file as `network.yml.bak`. Nothing to do by
hand. A **misspelled** key still stops the proxy with the key named: only you know what you meant.

## What the server browser shows

`NETWORK_MOTD_PRE_LAUNCH`, `_PRE_EVENT`, `_START_EVENT`, `_SMP` and `_MAINTENANCE` in `.env` — one
per season phase, MiniMessage, with placeholders in braces. The full placeholder list is in
`network.yml` itself, which the proxy writes on first start with real defaults; anything left unset
in `.env` keeps that default.

Read at **proxy start**. The MOTD follows the phase on its own, live, but an edit to `network.yml`
or to any `NETWORK_MOTD_*` needs `docker compose restart network-control`; there is no reload
command. That restart is *not* enough for `NETWORK_MAX_PLAYERS` — see
[Who limits the players](#who-limits-the-players).

When `network-control` cannot start at all, the fail-closed handler answers the ping saying so.

## The forwarding secret

Modern forwarding needs the **same secret in all four containers**, and a mismatch does not say so:
it shows up as every login failing with *"Unable to connect you to the backend server"*.

```bash
openssl rand -hex 24
```

Put it in `.env` as `VELOCITY_FORWARDING_SECRET`. `compose.yml` hands the same value to the proxy
under that name and to each backend as `PAPER_VELOCITY_SECRET`; the proxy writes it to
`/data/forwarding.secret`, and Paper reads its own copy from the environment.

**It does still land on disk**: Paper writes the value into `config/paper-global.yml` on first load,
so **rotating** the secret is `.env` *plus* that one line in each of the three backend volumes.

## Getting a world into a volume

The hunger games map and the Nordtal spawn are hand-built and have to be uploaded:

```bash
docker compose stop hunger-games
docker cp ./world-hunger-games/. nordtal-s2-hunger-games-1:/data/hunger_games/
docker compose start hunger-games
```

**The destination is `/data/hunger_games/`, not `/data/world/`.** `hunger-games` does not create its
world; it disables itself when `config.yml#world-name` — default `hunger_games` — is not loaded, and
the service's `LEVEL_NAME` is what brings that folder up as the primary world. The two have to be
the same string; `ComposeWorldTest` asserts they are.

In Arcane the same thing is a file upload into the volume. Either way: **stop the server first.**
Copying into a world a running server has open produces corruption that surfaces days later.

## The console

Arcane's per-container shell is a `docker exec`, which cannot reach PID 1's stdin — so the server
runs inside a tmux session, and the image ships two commands:

```bash
console            # attach to the real console, read and write. Detach with Ctrl-b then d.
mc <command>       # send one command, no TTY needed. Output goes to the container log.
```

`mc` is the one to reach for in a runbook or a script; `console` is for watching something happen.
**Ctrl-C inside `console` goes to the server, not to your shell** — use `mc stop` or
`docker compose stop` to shut a server down.

Reading is unaffected: the entrypoint tails the server's own `logs/latest.log` onto the container's
stdout, so `docker logs` and Arcane's log view show everything.

## Updating

**The normal way is `/update` in the admin channel on Discord** — the same command in game and on
the proxy console. It reports what is newer than what is running and changes nothing; an **Update
now** button under it runs the whole thing, after a 30-second countdown every player online is
warned through.

Both reach the updater through a row in `update_request` and a notification, answered by the
container that has the volumes; the result is an `UpdateReport` written into
`update_request.result` as JSON, one line per service and one entry per artefact moving.

On the host:

```bash
docker compose run --rm updater report      # what would change, changes nothing
docker compose run --rm updater bootstrap   # migrate, then fill EMPTY slots. Upgrades nothing
```

`bootstrap` is the manual form of the automatic first start and what to reach for when
`UPDATER_BOOTSTRAP` is off or a volume has been emptied. **It fills gaps and never upgrades.** To
move a version that is already installed, use `/update now` in Discord or in game: it stops the
affected servers, swaps, starts them again and waits until each reports healthy.

A `bootstrap` run by hand, an update and a restart cannot collide: **all three take the same
PostgreSQL advisory lock, and the second one to ask is refused rather than queued**, naming which
one is already running.

An update applies the schema **with the affected servers stopped**, before a jar moves, so a plugin
can never come up against a schema older than itself. A migration that fails stops the run there:
nothing is fetched, nothing is written, and every service that was stopped is started again.

The updater asks GitHub, Modrinth and the PaperMC Fill API what the newest version of everything is,
compares that against the jars in the volumes, and moves the ones that differ. **What a server runs
is the jar in its volume**; no version is written into a file. What it *follows* is not configurable
at all — GitHub's `/releases/latest`, which skips drafts and pre-releases, so an update that never
arrived is usually a release nobody published.

Four properties worth knowing before reading a report:

- **Two phases.** Everything is downloaded into a `.nordtal-staging` directory inside the folder it
  will end up in and verified there; only when all of it is present does anything move.
- **A server moves together or not at all**, so an unresolvable plugin skips that whole server. The
  server jar is the exception: a build the Fill API could not answer for is its own "skipped" row
  and the plugins move anyway, being compiled against the *version*, never a build.
- **The report restarts nothing, and a run restarts exactly what it stopped.** A server that does
  not come back makes the whole run fail by name. `bootstrap` stops and starts nothing at all.
- **"Skipped" is not "up to date".** A run where nothing could be checked — an unmounted volume, a
  source that did not answer — says so rather than closing with "Nothing needed doing".

Checksums are verified where one exists (Modrinth sha512 per file, Fill sha256 per build) and a
mismatch deletes the download instead of installing it. **A GitHub release asset carries no digest
of any kind**, so our own jars and the DisplayTags jar arrive unverified over TLS.

The **server jar** is the updater's too: it installs the newest `STABLE` build of the version pinned
in `.env` into each server's `.server/` cache, and `entrypoint.sh` runs whatever build of that
version it finds there. There is no build number anywhere in the deployment, and rolling back to an
older platform build is not provided for. `SERVER_VERSION` is a literal in `compose.yml` mirroring
`eu.nordtal.s2.common.Platform`: on the three Paper backends the exact Minecraft version, `26.2`,
and **on the proxy `4.0.0`, which is not a version** but Fill's name for the whole Velocity 4 line,
inside which the proxy follows the newest release. The cache match there is by kind alone
(`velocity-*.jar`), highest version then highest build; a jar of another version is deleted at the
next start.

### Restarting the network

The restart is **the same sequence an update runs, with nothing installed** — stop each service,
start it again, wait until it reports healthy — asked for by the button in Discord or by
`/update restart` in game. It is not a project-wide Arcane redeploy, which would take the updater
down with everything else and leave nothing to report whether the network came back.

**The confirmation comes first, and the countdown only after it.** `/update now` and
`/update restart` have to be typed again inside thirty seconds in chat, or confirmed with a button
in Discord. Until that happens **nothing is scheduled at all** — no row, no countdown, no warning to
anybody. Once confirmed, **every player on the network is counted down** at 30, 10 and 5 seconds;
the proxy announces, because it is the only process that sees everybody. Inside those thirty seconds
the **Stop the countdown** button or `/update cancel` still stops it.

**Players on a server that is about to stop are moved into `limbo`, eight seconds before it goes.**
The waiting room shows *"Update läuft / Gleich geht es automatisch weiter"* rather than the
"waiting for the server" screen it shows when a backend is merely down — the proxy reads that from
the update row, because from outside the two are the same fact. Bringing them back needs nothing of
its own: the pack station's five-second sweep already releases a held player the moment their
backend takes a connection again.

Two cases where it does not happen, and both are in the proxy's log rather than silent: an update
that moves **`limbo` itself** has nowhere to put anybody, and a run whose report the proxy cannot
read moves nobody. In both, everyone connected is disconnected when the servers stop and the
countdown is all the warning they get — which is what happened on every update before 2026-09-09.

**If a service refuses to stop, an update installs nothing.** It migrates nothing, moves no jar,
starts every service that did stop, and comes back `FAILED` naming the ones that refused.

### The images

A jar update hands each container back to Docker with `start`, which recreates nothing — so
`entrypoint.sh`, the JRE under it and every change to `compose.yml` would stay on whatever image was
pulled at the last deploy. Since 2026-09-09 a run closes that itself: it reads which services run an
image the registry has moved past, and **recreates** those instead of starting them, pulling the
image on the way. The volumes are not touched, so a world is never at risk from it.

**Arcane's image update check has to be on.** It answers the updater from results its own check has
persisted rather than asking a registry when asked, so with the check off every run prints *"Arcane
holds no image-update result for any service"* and nothing is ever recreated. That sentence in a
report is the symptom.

Two things a run will not renew, and both are named in the report rather than done quietly: **its own
image**, because the recreate would end the run from inside it, and **anything it does not own** —
`postgres` and the backup sidecar, which it never stops. Both need **Redeploy** in Arcane, by hand,
which is now the only thing in this deployment that does.

**It is not the Docker socket, deliberately** — a container holding `/var/run/docker.sock` can do
anything on the host, and the updater's whole job is downloading files from the internet and putting
them where servers will execute them. The socket is not mounted anywhere in `compose.yml` and must
not be. It drives Arcane's API instead; four variables turn that on, all optional together:

```
ARCANE_URL=https://arcane.example.com       # origin, no trailing slash
ARCANE_API_KEY=...                          # Settings -> API Keys, permission projects:deploy
ARCANE_ENVIRONMENT=0                        # the environment's ID; 0 is Arcane's own host
ARCANE_PROJECT=51b523fe-21aa-…              # the project's ID. A UUID, NOT 'nordtal-s2'
```

**Leave `ARCANE_URL` empty and updating stops working.** An update stops each affected server before
its jars move, so a run that cannot reach Arcane **refuses before resolving a version or touching a
file**, and says so by name. What still works is
`docker compose run --rm updater bootstrap` on the host — enough to bring a fresh deployment up, not
enough to move a version. Clicking **Redeploy** in Arcane by hand is an out-of-band fallback: it
recreates diverged containers, does not stop anything for a jar swap, and nothing then checks that
the servers came back — and
[getarcaneapp/arcane#1943](https://github.com/getarcaneapp/arcane/issues/1943) reports a redeploy of
an *already running* project doing nothing while answering success, so watch the containers actually
cycle.

**`ARCANE_PROJECT` is an ID, and that is the trap.** The compose project is called `nordtal-s2` in
every other file here, and putting that name in answers 404. Read the UUID out of the browser URL
with the project open, or ask for it:

```bash
curl -H "X-Api-Key: $ARCANE_API_KEY" "$ARCANE_URL/api/environments/0/projects"
```

It has no default and the updater refuses to start without it once `ARCANE_URL` is set.
`ARCANE_ENVIRONMENT` defaults to `0` and only changes if Arcane reaches this host through an agent,
in which case it is a UUID too.

`ARCANE_RUNTIME_PATH` and `ARCANE_CONTAINER_PATH` are the two API paths a run uses: one read of the
project's services and one `stop`/`start` per container. Container-level is not a detail — Arcane's
project-wide calls stop *and* start in a single request, and an update needs the **gap** between
them, which is also why the updater survives its own update. `ARCANE_REDEPLOY_PATH` is no longer on
any production path. All three stay settings because Arcane does not publish them, so a version that
moves a path is a line in `.env` and not a release of ours.

## Locally

The same `compose.yml`, the same `Dockerfile`s, the same updater. What differs is a second env file
and jars out of `build/libs` instead of a GitHub release.

```bash
deploy/dev init          # writes deploy/dev.env, generates the two secrets, makes the directories
deploy/dev up            # builds the five jars and both images, then brings the stack up
deploy/dev deploy smp    # rebuild :smp, replace the jar, restart that one container
```

The first `up` takes a while: the `updater` fetches Paper, Velocity, DisplayTags, PacketEvents,
Chunky and the SMP's two world-generation datapacks. It does **not** fetch our five jars, because
`deploy/dev up` has already put them in `plugins/` and the bootstrap installs only what is missing.
Then join `localhost` with a real client.

`deploy/dev` also carries `logs`, `console`, `mc`, `psql`, `ps`, `stop`, `down`, `pack` and `reset`;
`deploy/dev help` prints the list. Everything it does is `docker compose` with
`--env-file deploy/dev.env`, so any of it can be typed by hand. **After editing `deploy/dev.env`,
run `deploy/dev up` and not `deploy` —** `deploy` restarts the existing container, which reuses the
environment it was created with, and a setting that did not take effect looks exactly like a setting
that does not work.

### What is different, in full

- **`deploy/dev.env` instead of `.env`.** Its own header explains every line. Every `${X:?}` in
  `compose.yml` needs a value even for services no profile selects — compose interpolates before it
  filters — so the twelve the bot needs carry obvious placeholders. `TopologyTest` fails if a
  required variable is ever added without one.
- **`COMPOSE_PROFILES=db,mc,devpack`.** No bot: it needs a real guild and a real bunq key, and it
  cannot tell a test guild from the real one. Add `bot` once you have one.
- **Images are built, never pulled** (`MC_IMAGE`, `UPDATER_IMAGE` on a `:dev` tag). A locally built
  plugin needs the locally built updater: the migrations are compiled into `:common` and shaded into
  that jar, so a released updater would migrate to the released schema.
- **Four `<SERVICE>_PLUGINS` variables pointing at `./deploy/servers/<service>/plugins`**, which is
  what turns each server's plugins folder into a bind mount you can edit with a text editor. An
  existing `deploy/dev.env` needs those four lines added by hand — the file is gitignored, so
  nothing migrated it. `deploy/dev` refuses a value with no `/` in it rather than writing jars into
  a directory no container mounts.
- **`SMP_BACKUP_TIME=` (empty), which turns the nightly volume backup off.** The interpolation is
  `${SMP_BACKUP_TIME-04:45}` — a **single** dash, the only one in `compose.yml`, and what makes an
  empty value mean "off" rather than falling back to the default.
- **`SMP_PREGENERATION_ON_START=false`**, or every `up` spends its first minutes with Chunky on
  every core. The cost is one postponed reset: the first daily reset finds no finished world, says
  so, and builds it then. The production default is `true`.
- **Small heaps and `NETWORK_MAX_PLAYERS=20`.**

### The resource pack

The pack and the plugins are one change — a glyph code point is declared in `:common`'s `Glyphs`, in
a font file and in a PNG — so testing the drawn half against the previous release's pack answers
nothing.

```bash
deploy/dev pack
```

builds the zip, puts it under `PACK_ROOT`, and writes `url` and `sha1` into the proxy's `pack.yml` —
the same two lines the updater's `PackWriter` owns and no others. The `devpack` profile serves that
directory on `http://localhost:8080`, which is the client's `localhost` too. A `FAILED_DOWNLOAD` on
the client is almost always the hash and not the network — rerun after any change under
`resource-pack/src/`.

### Rehearsing the restart path locally

`deploy/dev deploy` restarts one container and is what you want ninety-nine times out of a hundred.
The hundredth is the restart path itself — the button, the countdown, the Arcane calls — and that
cannot be rehearsed anywhere but against a real Arcane. Run Arcane as its own compose project on
this machine, point it at this project, and fill in `ARCANE_URL`, `ARCANE_API_KEY` and
`ARCANE_PROJECT` in `deploy/dev.env`.

`ARCANE_URL` is `http://host.docker.internal:<port>` — **not** `http://localhost:...`, which inside
the updater container is the updater container. Both wrong values are named by the updater at
startup and again in `update_request.result`. `http://` is right here and wrong in production: the
API key travels as a header on every call, so **in production `ARCANE_URL` must be an `https://`
origin**. The updater only warns about a key on `http://`, because it is the only process that
migrates and refusing to start would trade a working schema for an optional button.

Leaving `ARCANE_URL` empty breaks nothing locally: every surface answers "Arcane is not configured".

### What the local stack still cannot tell you

A world. `smp` expects Nordtal and `hunger-games` expects its arena, and neither is in this
repository — locally you get whatever Paper generates, so spawn geometry, the duel platform, the
balloon and the POIs are untested here, exactly as on a fresh production volume. The same
`docker compose cp` from [Getting a world into a volume](#getting-a-world-into-a-volume) works
locally.

## Stopping

```bash
docker compose stop smp          # graceful: SIGTERM, the JVM saves, up to 180s
docker compose down              # the whole stack; volumes survive
```

`stop_grace_period` is 180 s on every Minecraft service — headroom for a border-4000 Nordtal, not
for the normal case. Do not lower it, and never use `docker kill`.

**`down` only acts on the profiles the current selection names, and that bites.** With
`COMPOSE_PROFILES` set to anything that leaves `backup` out, `docker compose down` stops everything
else and leaves the backup sidecar running — the network then cannot be removed (*"Resource is still
in use"*), and a backup job is left pointed at a database that no longer exists. Production is
`db,bot,mc,backup`, which is what `.env.example` ships. Whatever selection is used, **`up` and
`down` have to use the same one.**

## Backups

Access periods, payment records, aura, milestone progress and graves are all in one PostgreSQL, and
it is the only thing in this stack that cannot be rebuilt from the repository and a world folder.

**The `backup` profile dumps it.** `postgres-backup` runs `pg_dump --format=custom` into the
`postgres-dumps` volume once a day at `BACKUP_AT` (04:00 by default) and again at start-up, keeps
`BACKUP_KEEP` of them (14), and writes the outcome of the last run to `postgres-dumps/LAST_RESULT`
and to the container log. A dump is written under a `.partial` name, checked by reading its own
table of contents back with `pg_restore --list`, and only then renamed.

### Point Arcane at `postgres-dumps`, never at `postgres-data`

Arcane can snapshot a named volume to S3, and stops the containers using it **only when the backup
policy's `Stop Containers` flag is set**. For a live PostgreSQL data directory both settings are
wrong: **off**, it tars a running PGDATA, which raises no error at backup time and is a broken
cluster at restore time; **on**, PostgreSQL goes down for the length of the tar every night, and
every process in this stack fails fast on an unreachable database.

`postgres-dumps` has neither problem — nothing holds it open between runs, so a policy with `Stop
Containers` **off** is correct there, and what travels to S3 is megabytes rather than a whole data
directory.

### The volume backup is a run, not a schedule

**Arcane's own scheduler is not what takes the nightly snapshot, and its `Stop Containers` flag must
stay off** — a stop nobody announced lands on whoever is online at a quarter to five. The updater
drives it: `/backup now` on any surface, and a nightly row `smp` writes at `config.yml#backup-time`
(default `04:45`, fifteen minutes before the farm reset). The run is a thirty-second countdown every
player sees, then `smp`, `network-control` and the bot are stopped, then every volume is
snapshotted, then everything comes back and is checked. Update and backup take the same lock and
never overlap.

What Arcane's backup policy still decides is the **destination** — the updater posts with an empty
body on purpose, so `local` / `s3` / `local_s3` is configured once, in Arcane, per volume.

The eight volumes, with the compose project prefix Arcane addresses them by: `nordtal-s2_mc-smp`,
`nordtal-s2_mc-network-control`, `nordtal-s2_bot-config`, `nordtal-s2_postgres-dumps` and the four
`*-plugins` volumes, which is where every hand edit to `config.yml`, `milestones.yml`, `sounds.yml`
and `pack.yml` lives. `postgres-data` is **never** in that list and is refused by name when the
updater loads its config. A volume that has not finished after thirty minutes is given up on, the
servers come back, and the run ends `FAILED`, mentioning the admin role in the admin channel.

### Restoring

```bash
docker compose exec postgres psql -U "$POSTGRES_USER" -d postgres -c 'CREATE DATABASE restored;'
docker compose exec postgres-backup sh -c 'pg_restore --dbname=restored --no-owner /dumps/<file>'
```

Restore into a *new* database and look at it before you point anything at it. `--no-owner` is what
lets a dump taken as one role restore under another.

A restore of the real season database from a dump pulled back out of S3 has not been rehearsed; it
needs the host.

### The world volumes are a different problem

Nothing here backs the four `mc-*` world volumes up to S3: a world is recreatable from a seed and a
build, a payment record is not, and streaming a post-border-4000 world over the tunnel to a home
connection is tens of gigabytes. The chosen path is season 1's — an installed plugin that zips the
world and uploads it by SFTP — and it is an operator task. Two things worth having here:

- **DriveBackupV2 has no 26.2 build.** It stops at 26.1.2.
- **[Backuper](https://modrinth.com/plugin/backuper) does, and its `setWorldsReadOnly` defaults to
  `false`** while its own config comment says "True recommended". Left at the default it zips a
  world folder the server is writing into — the same torn copy this section exists to avoid.

### Moving an older deployment off `SERVERS_ROOT`

Each server's `plugins/` used to be a bind mount at
`${SERVERS_ROOT:-./deploy/servers}/<service>/plugins`; it is a **named volume** now, one per
service, and Docker copies nothing between the two. Bring the stack up on the new compose file
without moving the data first and every server finds an empty `plugins/`, the entrypoint guard stops
it, and the bootstrap writes fresh defaults over the deployment's own config. Do this once, with the
stack **stopped**:

```bash
docker compose stop
for s in network-control limbo hunger-games smp; do
  docker run --rm \
    -v nordtal-s2_mc-$s-plugins:/dst \
    -v "$PWD/deploy/servers/$s/plugins:/src:ro" \
    alpine cp -a /src/. /dst/
done
docker compose up -d
```

Check `docker compose logs` for the four servers before deleting anything. **Keep a copy of
`deploy/servers/` outside the checkout until you have seen a server come up with its own config** —
that directory is inside the tree Arcane's GitOps sync pulls, and the sync deletes ignored files.

To roll back, set the four `<SERVICE>_PLUGINS` variables to the old paths in `.env`; the volumes are
left untouched and can be removed later with `docker volume rm`.

## Voice chat: one UDP port, no file to edit

Simple Voice Chat runs on `smp` and `hunger-games`, and the Velocity plugin on the proxy makes it
**one** endpoint rather than one per backend. The firewall therefore needs **UDP 25565 in addition
to TCP 25565**, and nothing else. Audio never travels over the Minecraft connection or the proxy's
TCP port.

The plugin detects each backend's address and port itself, so no `voicechat-server.properties` needs
touching. The one voice file an operator might ever open is `voicechat-proxy.properties` on the
proxy, and only to set `voice_host` if the published port stops matching the one the plugin hears on
inside the container. `PROXY_PORT` moves the Minecraft port only.

It is optional at every level: a player without the client mod notices nothing, and the jar is in no
`EXPECTED_PLUGINS`, so a Modrinth outage during a bootstrap costs voice chat rather than a server.

## Troubleshooting

| symptom | cause |
|---|---|
| Container will not start, log names a config key | jcore refused the config. The message names the file and the setting; it is not a container fault. |
| `FATAL: set EULA=true` | Deliberate. The image does not accept Minecraft's EULA on your behalf. |
| `FATAL: could not fetch <jar> … Refusing to start` | The release tag or the asset name in `.env` is wrong, or GitHub is down and this jar was never cached. It will not fall back to an older jar. |
| Every login fails with *"Unable to connect you to the backend server"* | The forwarding secret does not match — one container did not get `VELOCITY_FORWARDING_SECRET`, or the volume predates the automation and still carries an old one. |
| Velocity exits at once with *"Your configuration is invalid"* | `velocity.toml` names a server in `[forced-hosts]` or `try` that its `[servers]` does not define. |
| A backend logs *"SERVER IS RUNNING IN OFFLINE/INSECURE MODE"* | Expected, and required. The proxy authenticates; a backend that also does refuses every forwarded login. |
| Proxy starts but refuses every login with a "network misconfigured" screen | `network-control` failing closed on a bad `gate.yml`/`database.yml`/`pack.yml`/`network.yml`. Intended; the server browser says the same thing. Read the log. |
| Log names `backend-limit` as a setting that no longer exists | `network.yml` in the volume predates the retirement of that key. Nothing to do: the line is deleted for you and the old file is in `network.yml.bak`. A deployment older than 2026-09-05 refuses to start instead — delete the line by hand there, see [Who limits the players](#who-limits-the-players). |
| A backend answers *"Server full"* | Only an admin should ever see this, and only if the exemption is not firing. Everybody else is refused by the proxy at the login gate. Check the backend's `max-players` really is `NETWORK_MAX_PLAYERS` (the container was restarted after the last change) and see [Who limits the players](#who-limits-the-players). |
| The browser shows the old MOTD after editing `.env` | `network.yml` is read at proxy start. Restart the `network-control` service; there is no reload command. |
| Everybody is refused with a countdown, and nobody asked for that | The phase is `PRE_LAUNCH`, which is the seeded initial state. `/phase set PRE_EVENT` opens the network. |
| `docker rm -f` fails with *"did not receive an exit event"* | You are running a container that mirrors its console with `tmux pipe-pane > /proc/1/fd/1`. Do not do that — see [below](#never-mirror-the-console-with-tmux-pipe-pane). Only a Docker daemon restart clears it. |

## Third-party plugins

- **Required, `smp` only: DisplayTags, and PacketEvents underneath it.** Nametags come from
  [`papermc-display-tags`](https://github.com/nordtal/papermc-display-tags) — our own fork — through
  its API. `smp`'s `paper-plugin.yml` declares it with `load: BEFORE` and `required: true`, so a
  server missing either fails loudly at start instead of quietly rendering plain nametags.
- **Required, `smp` only: Chunky** (`Chunky-Bukkit-1.5.3.jar`, the version Modrinth tags for `paper`
  on 26.2). The farm world is pre-generated every night and the daily reset waits for Chunky's
  completion event before it swaps anything in. Without it the reset would postpone itself every
  night, silently, which is why `required: true` turns that into a start-up failure instead.
- **Optional: CoreProtect**, purely as insurance. Nothing in the design depends on it, and it gets
  its own SQLite file rather than a schema in our PostgreSQL so that exactly one process migrates.
  It had no 26.2 release as of 2026-08-31, only a `master` that builds against it; if it has not
  shipped when the phase is ready, the phase opens without block logging and Prism 4.4 is the
  written fallback.

The updater resolves all three — DisplayTags from its own repository's releases, PacketEvents and
Chunky from Modrinth filtered to this Minecraft version and `paper` — so a version bump is a run of
`/update now` and not an edit to `.env`.

**Each service names what it needs in `EXPECTED_PLUGINS`** (filename prefixes), and a folder missing
any of them stops the container naming them — counting jars is not enough, because a source that
answers 403 for one jar while another answers fine leaves a folder that is not empty and a server
with no season on it, reporting healthy. It is a **minimum, never an exact set**: an extra jar is
reported and left alone. `TopologyTest` asserts that every plugin the topology gives a service is one
that service's guard asks for.

**Two datapacks belong in the same conversation: Terralith and Dungeons and Taverns.** They are the
terrain of every world in this season, pinned by sha512 in `.env` (`SMP_DATAPACK_URLS`), and the
entrypoint fetches them into the `level-name` world's `datapacks/` folder *before* the server
starts. Datapacks are server-global — read only from `<level-name>/datapacks/`, with no per-world
API — and read **once, at start**: a pack dropped in afterwards changes no terrain, and terrain is
never re-rolled once it is on disk. `smp` verifies both are enabled and refuses to start otherwise,
because Nordtal generated without Terralith is the whole season on a world with a spawn built on it.

A world created through the Bukkit API lands at `<level-name>/dimensions/minecraft/<name>`, inside
the primary world rather than beside it — worth knowing before you go looking for the farm world's
folder, or size a volume for the two that exist during a swap.

## Never mirror the console with `tmux pipe-pane`

`tmux pipe-pane … > /proc/1/fd/1` is the obvious way to get the tmux console into `docker logs`, and
**it wedges the container**: SIGTERM never reaches PID 1, the shutdown trap never runs, the container
survives the SIGKILL at the end of the grace period, and `docker rm -f` then fails with *"did not
receive an exit event"* — only a Docker daemon restart clears it. The writer holds a second handle on
the container's stdout pipe from a process whose lifetime the shim does not track.

**The rule is about `/proc/1/fd/1`, not about `pipe-pane`.** The entrypoint does use `pipe-pane`,
into a *file* — an ordinary file in the volume is a different descriptor and holds nothing open. It
captures the pane to `logs/console.log`, empties it at every start and switches it off again the
moment `latest.log` exists, so it is a boot log with nothing to rotate; if the JVM dies before Paper
starts logging, the entrypoint prints that file to stdout on the way out. Ordinary log reading is
`tail -F` on `logs/latest.log`, a plain child of PID 1 inheriting its stdout.

Two ordering rules go with it, both one line, both asserted by `:common`'s `EntrypointRulesTest`.
`remain-on-exit` has to be set **globally, before** `new-session` — which needs `exit-empty off`,
since a tmux server with no sessions exits immediately — or a JVM that dies at once takes the session
with it before the option applies and a real exit status of 3 is reported as 1. And `pipe-pane` has
to be attached in the *same* `tmux` invocation as `new-session`; a separate call against a pane that
already exited fails with *"target pane has exited"*, taking the crash output with it.
