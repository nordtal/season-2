# deploy

The whole of season 2's production deployment: one `docker compose` stack on one host, driven
through [Arcane](https://github.com/ofkm/arcane).

This file is both the runbook and the record of why the stack has this shape; the design
reasoning is at the bottom, under [Why it looks like this](#why-it-looks-like-this). SimpleCloud was
dropped on 2026-09-01 — see [../docs/README.md](../docs/README.md#decisions-and-when-they-were-taken).

**`compose.yml` and `.env.example` are at the repository root, not in here.** They moved out of
`deploy/` on 2026-09-01: Arcane's GitOps sync pulls *"the entire directory the compose file lives
in, not just the file itself"* (its own documentation, read 2026-09-01), so a compose file under
`deploy/` would put `deploy/` on the host and nothing else. **Every command in this file therefore
runs from the repository root**, not from `deploy/`.

The original reason was that the `./updater` and `./discord-bot` build contexts would not exist in
such a tree, and *that half is now weaker than it was*: since 2026-09-02 nothing on the host builds,
so a deploy that only pulls never looks at a build context. What still argues for the root is the
sync pulling one directory, and `docker compose build` working from a checkout — which is what the
`build:` blocks are for. Worth knowing, because it means the file's location is a convention now
rather than a hard requirement, and a future change should not be argued down with a reason that has
expired.

```
compose.yml            six services, four profiles: db · bot · mc · backup (the updater has none)
.env.example           every setting; copy to .env and fill in
deploy/
  minecraft/
    Dockerfile         one image for all four Minecraft services
    entrypoint.sh      PID 1: resolve the jar, pull the plugins, run tmux, trap SIGTERM
    scripts/console    attach to the real server console (read + write)
    scripts/mc         send one command, no TTY needed
                       (named scripts/ and not bin/ - .gitignore has a repo-wide bin/ rule)
```

## First deployment, in order

**Since 2026-09-02 the host needs no shell, no JDK and no Gradle.** All four of our images —
`minecraft`, `updater`, `discord-bot`, `postgres-backup` — are pushed to `ghcr.io/nordtal` by
[`.github/workflows/release.yml`](../.github/workflows/release.yml) when a release is published, and
compose pulls every one of them. The `build:` blocks that remain in `compose.yml` are for developing
on your own machine.

That was not a preference. **Arcane deploys by pulling and never builds** — building is a separate
action in its interface ([its documentation](https://getarcane.app/docs/features/projects), read
2026-09-02) — so an image that only ever existed in one host's Docker failed the deploy with
`error from registry: denied`, which is also what a *private* package answers and therefore explains
nothing. Two of the four could not have been built from a checkout in any case: they `COPY` a jar
Gradle produces into a directory `.gitignore` excludes.

### Once, before the first deployment

1. **Publish the release.** Tag it, publish it on GitHub, and let `release.yml` finish. It attaches
   eight assets and pushes four images, each tagged with the version and with `latest`.
2. **Set all four packages to Public**, in their GitHub package settings. A package under an
   organisation is **private on its first push**, and a private package answers a pull with the same
   `denied` as one that does not exist. The alternative is a registry credential in Arcane; the
   trade is in [`../../todo.md`](../../todo.md).

### From Arcane, with no shell on the host

3. **Point the project at the repository root**, not at `deploy/`. Arcane's GitOps sync pulls the
   whole directory the compose file lives in, which is why `compose.yml` is up there.
4. **Type the environment into Arcane.** For a git-synced project Arcane keeps `.env.git` from the
   repository, `project.env` for what you type, and writes the effective `.env` from both; a sync
   never overwrites your values (its documentation, read 2026-09-02). Since `.env` is gitignored
   here, `.env.git` is empty and **everything comes from what you type**.
   [`../.env.example`](../.env.example) is the reference for what to type — every `REPLACE_ME` in it
   is something only you know, and one is the forwarding secret (`openssl rand -hex 24`). Nothing
   has a plausible default for a value nobody can guess, and `REPLACE_ME` is not one either: it
   fails validation by name rather than starting something surprising.
5. **Set Auto Sync on and Redeploy After Sync off**, with the pull policy on *always pull latest*.
   A redeploy takes the four Minecraft servers down for minutes, and a commit must not do that to
   people who are playing — the updater's restart button exists for that and gives them a countdown
   first.
6. **Deploy.** Nothing else is needed, and the reason is the `updater` service: on its first start
   it applies the schema and **fills every empty volume** — the four `plugins/` folders, both jar
   volumes, each server's `.server/` cache and the proxy's `pack.yml` — and only then writes the
   readiness marker that every other service waits for. That used to be
   `docker compose run --rm updater apply`, typed by a person, which Arcane has no way to do.

   It **cannot move a version**: only artefacts with *nothing* installed are fetched, so a restart
   of a running network finds nothing missing and does nothing at all. Upgrades stay a request
   somebody makes, from Discord or in game. `UPDATER_BOOTSTRAP=false` turns it off, and then the
   servers refuse to start until an apply has run and say so by name.

   A first deployment downloads four server jars and every plugin before it goes healthy, which is
   why the updater's healthcheck allows fifteen minutes.
7. **Upload the hand-built worlds** — see below. **This is the one step that still needs a shell**,
   because a world folder is not something a repository or a release carries.
8. **Run the login-path rehearsal** — [`../../todo.md`](../../todo.md), section 1. Nothing above
   proves a client can join.

### From a shell instead

Steps 3 to 6 collapse into one command from the repository root, and it still builds nothing:

```bash
docker compose up -d
```

`docker compose run --rm updater report` prints what is installed and changes nothing;
`updater apply` is the manual form of step 6 and is what to reach for when the bootstrap is off or
you want an upgrade now rather than through Discord.

**Pinning a release** is one line of environment, and it is how a rollback is expressed:

```bash
IMAGE_TAG=0.2.1
```

## First-start seeding

The container writes six things, **only when they are not there already**, and a file that exists
is never edited again. It is deliberately the minimum that makes a login work, not a set of
opinions about how to run a server: everything else stays Paper's and Velocity's own default.

| what | where | when |
|---|---|---|
| `player-info-forwarding-mode`, `bind`, `online-mode`, `[servers]`, `try`, an empty `[forced-hosts]` | proxy, `velocity.toml` | no `velocity.toml` yet and `VELOCITY_SERVERS` is set |
| `proxies.velocity.enabled: true` | each backend, `config/paper-global.yml` | no `paper-global.yml` yet and `PAPER_VELOCITY_SECRET` is set |
| `online-mode=false` | each backend, `server.properties` | **every start**, see below |
| `level-name=$LEVEL_NAME` | each Paper server, `server.properties` | seeded once; a volume still on Paper's default `world` is repaired, any **other** disagreement stops the container, see below |
| `level-seed=$LEVEL_SEED` | each Paper server, `server.properties` | only while the `level-name` world does not exist yet — which is `level.dat`, not the folder; an existing world is compared and warned about |
| `forwarding.secret` | proxy | every start, from `VELOCITY_FORWARDING_SECRET` |

**`level-name` was missing from this table and from the script until 2026-09-02, and that was the
whole of the first deployment's worst finding.** `entrypoint.sh` fetched Terralith and Dungeons and
Taverns into `/data/$LEVEL_NAME/datapacks` — the script's own comment even said *"LEVEL_NAME has to
agree with `server.properties#level-name`"* — while nothing wrote the key, so Paper kept its default
and generated `world`. The packs were never loaded, `smp` refused to start on a `nordtal` that did
not exist, and `hunger-games` had no `LEVEL_NAME` at all while its plugin looked for
`hunger_games`. Terrain is not re-rolled: had it gone unnoticed, the season would have been played
on vanilla terrain permanently.

**A disagreeing `level-name` is fatal rather than enforced, and the asymmetry with `online-mode` is
deliberate.** `online-mode=true` behind the proxy is a server that *cannot* work; a `level-name`
that disagrees is a server that works perfectly, on the wrong world. Pointing an existing volume at
a new name does not move anything — Paper generates a second, empty world beside the first and runs
the season on that, while the world with everything in it sits untouched in the same volume. So the
container refuses to start and names both values and the two ways out. `:smp`'s and
`:hunger-games`' `ComposeWorldTest` compare the `LEVEL_NAME` in `compose.yml` against each plugin's
own configured world name on every build.

**With one exception, added 2026-09-03, because the guard's own arrival was the first thing it
stopped.** v0.2.3 shipped the fix above onto volumes that had already run without it — so every one
of them said `level-name=world`, and `smp` and `hunger-games` went into a restart loop on the
`FATAL` above from the moment the release landed. A check written to prevent a misconfiguration had
become one, and it is worth naming as a shape: a guard that fires on its own migration is a guard
people switch off.

So `world`, **and only the literal string `world`**, is now repaired instead of refused. Two
conditions, both narrow because this deletes a world folder in an automatic start:

- **the old name is `world`** — Paper's own default, what it writes when nothing tells it
  otherwise, and therefore not a name anybody chose. Any other name was typed into `.env` by a
  person, and the container still refuses. `limbo` is the proof this is not arbitrary: it
  deliberately has no `LEVEL_NAME`, so *its* `level-name` is `world`, it matches, and it never
  reaches this path.
- **nobody has ever logged out in it** — Paper writes `<world>/playerdata/<uuid>.dat` on quit, so
  one file there is enough to refuse. The message then says so and gives the volume-wipe command.

`world_nether` and `world_the_end` go with it: they are that same world's two dimensions, and
keeping them would leave gigabytes belonging to a world nothing can reach. The repair happens at
most once per volume — afterwards `level-name` is `nordtal` or `hunger_games`, and a later mismatch
can only mean somebody edited `.env`, which is exactly what the refusal is for.

**`deploy/minecraft/entrypoint-test.sh` is where that logic is verified**, on `./gradlew check` via
the root build's `checkEntrypoint`. It sources `entrypoint.sh` — which carries a guard at the line
where its definitions end and the container's run begins — and drives ten cases against fixture
directories: no Docker, no network, no server jar. It is the only shell in this deployment with a
test, and the reason is specific: everything else here is verified by running it and looking, while
this one deletes a folder that on the SMP is the season.

**The seed's "does this world exist yet" test is `level.dat` and not the directory**, corrected in
the same pass. `fetch_datapacks` creates `/data/$LEVEL_NAME/datapacks` before Paper has generated
anything, so on every volume that had ever fetched a datapack the directory test was already true
and `level-seed` was never written — Nordtal would have generated from a random seed while `.env`
named `1837371427`, permanently and silently.

`online-mode` is the one thing enforced rather than seeded. It is not a preference: a backend that
authenticates players itself refuses every forwarded login, so `online-mode=true` there is a server
that cannot work, not a choice somebody might have made. The rest is seeded once and yours
afterwards.

**`[forced-hosts]` is written empty on purpose, and it is not tidiness.** Measured 2026-09-01 on
Velocity 4.1.1 build 24: leave the table out of `velocity.toml` and Velocity falls back to its
*default* one, which routes `lobby.example.com` and two others at servers the file does not define
— and it then refuses to start at all with *"Your configuration is invalid"*. "Velocity defaults
everything you leave out" is true per key, not per table.

**What this does not do** is fix a volume that already exists — with the two exceptions above it
now has. The seeding otherwise only ever fires on a first start, so a server that has run before
keeps whatever is in its volume; the manual equivalent is `online-mode=false` in
`server.properties` and `proxies.velocity.enabled: true` in `config/paper-global.yml`.

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
docker cp ./world-hunger-games/. nordtal-s2-hunger-games-1:/data/hunger_games/
docker compose start hunger-games
```

**The destination is `/data/hunger_games/`, not `/data/world/`.** It said `world` until 2026-09-02,
and following it produced a world under the one name the plugin will not look for: `hunger-games`
does not create its world, it disables itself when `config.yml#world-name` — default `hunger_games`
— is not loaded. The service's `LEVEL_NAME` is what brings that folder up as the primary world, so
the two have to be the same string; `:hunger-games`' `ComposeWorldTest` asserts they are.

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

**The normal way is `/update` in the admin channel on Discord.** It reports what is newer than what
is running, an **Install** button installs it, and a **Restart the network** button under that
starts a one-minute countdown that every player online is warned through. `/smp update` in game does
the same four things for an admin who is not at a keyboard with Discord on it.

Both reach the updater the only way anything here can — a row in `update_request` and a
notification, answered by the container that has the volumes. Neither renders the report itself:
what you read is the updater's own text, written once by the process that did the work.

On the host it is one command, and a second one to make it take effect:

```bash
docker compose run --rm updater report   # what would change, changes nothing
docker compose run --rm updater apply    # migrate, then fetch and put in place
docker compose --profile mc restart      # the servers pick up what is on disk
```

An `apply` run by hand and one asked for from Discord cannot collide: an apply takes a PostgreSQL
advisory lock and **the second one to ask is refused rather than queued** — a plan resolved now
would be stale by the time it got its turn. The refusal names both possibilities so you know which
one you are waiting for.

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
- **A server moves together or not at all.** If one of a server's plugins cannot be resolved, that
  whole server is skipped. "The new SMP jar with last week's PacketEvents" is a combination nobody
  chose, and DisplayTags is a *required* plugin of `smp` whose own required plugin PacketEvents is.
  The server jar is the one exception (2026-09-02): a Paper or Velocity build the Fill API could not
  answer for is its own "skipped" row and the plugins move anyway — they are compiled against the
  *version*, never a build, and the build already in `.server/` runs.
- **It restarts nothing.** `apply` prints what it did and stops. Read that before restarting — a
  server that was part-updated is exactly the thing worth catching before the network goes down on
  it. The restart is a separate button for that reason, and it is a *button* and not a step of the
  run.
- **"Skipped" is not "up to date".** A run where nothing could be checked — an unmounted volume, a
  source that did not answer — did no work and had no failure, and the report says so in as many
  words rather than closing with "Nothing needed doing". That sentence on a run like this one is how
  somebody comes away believing the network is current.

Checksums are verified where a checksum exists: Modrinth publishes a sha512 per file and the Fill
API a sha256 per build, and a mismatch deletes the download instead of installing it. **A GitHub
release asset carries no digest of any kind**, so our own jars and the DisplayTags jar arrive
unverified over TLS — the same way `entrypoint.sh` has always fetched them, and a real gap rather
than an implied one.

The **server jar** is the updater's too, since 2026-09-02. It installs the newest `STABLE` build of
the version pinned in `.env` into each server's `.server/` cache, and `entrypoint.sh` runs whatever
build of that version it finds there. `PAPER_BUILD` and `VELOCITY_BUILD` are only read into an
**empty** cache — a fresh volume, or a version bump before the updater has run against it — and are
fetched exactly once. Until that day the entrypoint fetched the pinned build unconditionally and
deleted every other jar, which undid each updater run on the next restart and turned every restart
after an update into a Fill API call, i.e. the outage the cache exists to survive.

Rolling back to an older build is `UPDATER_PAPER_BUILD=121` (or `UPDATER_VELOCITY_BUILD`) in
`.env`, then `apply`, then the restart — the same shape as `UPDATER_SEASON_RELEASE=v0.1.0`. The
report shows `paper-26.2-125.jar -> paper-26.2-121.jar` like any other move. It is *not*
`PAPER_BUILD`: that one seeds an empty cache and never moves a running server.

### Restarting the network

The restart is **one Arcane redeploy of the whole project**, asked for by the button in Discord, by
`/smp update restart` in game, or not at all.

Whichever asks, the request is written with an instant sixty seconds out and **every player on the
network is counted down towards it** — in limbo, in Hunger Games and on the SMP, at 60, 30, 10 and 5
seconds and then "restarting now". The proxy does the announcing, because it is the only process
that sees everybody. Inside that minute the countdown can be stopped: the **Stop the countdown**
button, or `/smp update restart cancel`. After it, "too late" is the honest answer and that is what
you get.

**It is not the Docker socket, deliberately.** A container holding `/var/run/docker.sock` can do
anything on the host, and the updater is the container whose whole job is downloading files from the
internet and putting them where servers will execute them. The socket is not mounted anywhere in
`compose.yml` and must not be.

Four variables turn it on, all optional together:

```
ARCANE_URL=https://arcane.example.com       # origin, no trailing slash
ARCANE_API_KEY=...                          # Settings -> API Keys, permission projects:deploy
ARCANE_ENVIRONMENT=0                        # the environment's ID; 0 is Arcane's own host
ARCANE_PROJECT=51b523fe-21aa-…              # the project's ID. A UUID, NOT 'nordtal-s2'
```

**Leave `ARCANE_URL` empty and nothing breaks.** Everything else works; both surfaces answer "Arcane
is not configured" and tell you to click Redeploy yourself.

**Both of those are IDs, and that is the trap.** The compose project is called `nordtal-s2` in every
other file here, and putting that name in `ARCANE_PROJECT` answers 404. The project ID is a UUID
Arcane generated: read it out of the browser URL with the project open, or ask for it —

```bash
curl -H "X-Api-Key: $ARCANE_API_KEY" "$ARCANE_URL/api/environments/0/projects"
```

It has no default and the updater refuses to start without it once `ARCANE_URL` is set, because an
ID is not something anybody can guess. `ARCANE_ENVIRONMENT` defaults to `0` and only changes if
Arcane reaches this host through an agent, in which case it is a UUID too.

`ARCANE_REDEPLOY_PATH` is a fifth variable that no longer needs a person. It was read from Arcane's
own source on 2026-09-01 — `backend/internal/project/handler.go` at release v2.10.0 registers
`POST /environments/{id}/projects/{projectId}/redeploy` under the `/api` group — and that is the
default. It stays a setting because Arcane's public documentation still does not publish it, so a
version that moves the path is a line in `.env` and not a release.

**Watch the first press.** [getarcaneapp/arcane#1943](https://github.com/getarcaneapp/arcane/issues/1943)
reports a redeploy of an *already running* project doing nothing while still answering success —
which is exactly this case, since the stack is up when the button is pressed. It was reported on one
agent at v1.15.3 and closed as *not planned*. Nothing in the updater can detect it, because the
stream that would say so is one the redeploy kills this container part way through reading. Watch
the containers actually cycle.

## Stopping

```bash
docker compose stop smp          # graceful: SIGTERM, the JVM saves, up to 180s
docker compose down              # the whole stack; volumes survive
```

`stop_grace_period` is 180 s on every Minecraft service. Measured 2026-09-01, a Paper server with a
generated world stops in **3 s** and logs `All dimensions are saved`; the headroom is for a
border-4000 Nordtal, not for the normal case. Do not lower it, and never use `docker kill`.

**`down` only acts on the profiles the current selection names, and that bites.** With
`COMPOSE_PROFILES` set to anything that leaves `backup` out, `docker compose down` stops everything
else and leaves the backup sidecar running — the network then cannot be removed (*"Resource is
still in use"*), and what is still running is a backup job pointed at a database that no longer
exists. Production is `db,bot,mc,backup`, which is what `.env.example` ships; `compose.yml`'s own
comment said `db,bot,mc` until 2026-09-02 and was the wrong half of the disagreement. Whatever
selection is used, **`up` and `down` have to use the same one.**

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
edit. `required: true` is unchanged, and the container refuses to start unless **every plugin the
service is supposed to have** is in `plugins/`.

**That guard used to count jars, and counting was not enough — it is the finding that would have
lost the launch.** On the first deployment the GitHub releases API answered 403 while Modrinth
answered fine, so PacketEvents and Chunky landed in the SMP's `plugins/` and the season jar did not.
The folder was not empty, the count passed, and the SMP came up with no season on it and reported
healthy. `limbo`, `hunger-games` and `network-control` were caught only because their folders
happened to be *entirely* empty. Each service now names what it needs in `EXPECTED_PLUGINS`
(filename prefixes, split the way the updater splits them), and a folder missing any of them stops
the container with the missing prefixes in the message.

**It is a minimum, never an exact set.** An extra jar is expected and fine — a hand-installed block
logger is planned in [smp.md](../docs/smp.md#block-logging--checked-2026-08-31), and the updater's
own rule for a jar it does not account for is that it is reported and left alone. `TopologyTest`
asserts that every plugin the topology gives a service is one that service's guard asks for.

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

- **One image for all four Minecraft services**, built from `minecraft/Dockerfile`: a JRE 25 base
  and a wrapper as PID 1 — no jar of any kind; the server jar and the plugins are in the volume and
  the updater owns them. `itzg/docker-minecraft-server` is the obvious candidate and was rejected —
  it does not cover Velocity (that is a *second* image with its own vocabulary), neither image
  solves the console problem for the proxy, and we want the build to move through one mechanism
  rather than through an image's own version resolution at start. One Dockerfile covers all four
  identically.
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

**The rule is about `/proc/1/fd/1`, not about `pipe-pane` — and since 2026-09-02 the entrypoint uses
`pipe-pane` into a *file*.** What makes the forbidden form lethal is the second handle on the
container's stdout *pipe*; an ordinary file in the volume is a different descriptor and holds
nothing open. Two comments in this repository (the image's `Dockerfile` and `compose.yml`) claimed
until that day that the console *was* mirrored to `/proc/1/fd/1`, which is the one sentence most
likely to make somebody implement it.

**Why a capture was needed at all.** `tail -F logs/latest.log` can show nothing that happens before
Paper creates that file. A Paperclip that could not load `mojang_26.2.jar` printed a forty-line
stack trace into the tmux pane, the pane died with the container, and what an operator saw — on a
loop, because `restart: unless-stopped` — was:

```
[nordtal] starting paper 26.2 build 121
[nordtal] server exited with status 1
```

So the pane is captured to `logs/console.log`, emptied at every start and **switched off again the
moment `latest.log` exists**: it is a boot log, bounded by construction, and it stops being written
before a player could join — no rotation policy, nothing to grow. If the JVM dies before Paper
starts logging, the entrypoint prints that file to stdout on the way out, which is the only place
it would ever be read.

**Two ordering bugs came out of the same drill, and both are one line.** `remain-on-exit` was set
*after* `new-session`, which works for every server that runs for a while and fails for the only
one where it matters: a JVM that dies at once takes the session with it before the option applies,
every `display-message` falls back to `|| echo 1`, and a real exit status of 3 is reported as 1.
Setting it globally first needs `exit-empty off`, because a tmux server with no sessions exits
immediately. And `pipe-pane` has to be attached in the *same* `tmux` invocation as `new-session` —
a separate call against a pane that already exited fails with *"target pane has exited"*, taking
the crash output with it. Both measured in a container on 2026-09-02, both directions;
`:common`'s `EntrypointRulesTest` is what notices if either is undone.
