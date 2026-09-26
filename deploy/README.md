# deploy

Season 2's production deployment: one `docker compose` stack on one host. This file is the
operator's runbook; the project overview is [`../README.md`](../README.md).

**`compose.yml` and `.env.example` are at the repository root, not in here.** `compose.yml` is
copied from there into `steward-deployer`'s image, which is the one service allowed to create a
container — see [`../steward-deployer/README.md`](../steward-deployer/README.md). **Every command in
this file therefore runs from the repository root**, not from `deploy/`.

**A management panel used to sit in front of all of this and was removed on 2026-09-13.** Its five
jobs are now steward-worker's (container state, health, stop, start, image drift — over the Docker
socket) and steward-deployer's (recreate and pull). Anything on a host still carrying `ARCANE_*`
variables in its `.env` can delete those lines; nothing reads them.

**`steward-worker` was called `updater` until 2026-09-12**, and Steward is the name of the whole
system it belongs to.

```
compose.yml            seven services, six profiles: db · bot · mc · backup · devpack · standby
                       (steward-worker has none, devpack is local only, and standby is in no
                       ordinary selection - see *The standby services* below)
.env.example           every setting there is, as a reference - nobody fills it in by hand
deploy/
  minecraft/
    Dockerfile         one image for all four Minecraft services
    entrypoint.sh      PID 1: resolve the jar, pull the plugins, run tmux, trap SIGTERM
    entrypoint-test.sh the seeding half of it, against fixture directories (runs on `check`)
    scripts/console    attach to the real server console (read + write)
    scripts/mc         send one command, no TTY needed
                       (named scripts/ and not bin/ - .gitignore has a repo-wide bin/ rule)
  nordtal.sh           the host's own script, and the whole install: it asks, writes the
                       environment file, generates the secrets, makes the installation's
                       directories, waits for the name and renews the deployer. It installs
                       itself into the installation directory and renews itself from GitHub
  nordtal-test.sh      its checks, without Docker or a resolver (runs on `check`)
  restore.sh           put one archive back - a volume, or a dump into a NEW database
  restore-test.sh      its guards, without Docker (runs on `check`)
  dev                  the local stack: init · up · deploy · pack · reset - see Locally below
  dev-test.sh          the guard on `dev reset`, without Docker (runs on `check`)
  dev.env.example      every setting the local stack needs; copy to dev.env
  servers/             NOT IN GIT. Local plugin directories for the dev stack
  pack/                NOT IN GIT. The locally built resource pack the devpack profile serves
```

## First deployment, in order

The host needs a shell and nothing else — no JDK, no Gradle, no checkout of anything but this
repository. All five of our images — `minecraft`, `steward-worker`, `discord-bot`, `steward-ui` and
`steward-deployer` — are pushed to `ghcr.io/nordtal` by
[`.github/workflows/release.yml`](../.github/workflows/release.yml) when a release is published, and
compose pulls every one of them. The `build:` blocks in `compose.yml` are for developing on your own
machine: **a deploy pulls and never builds**, so an image that only exists in one host's
Docker fails the deploy with `error from registry: denied`.

**Running the network means running Steward.** Caddy is the guard in front of the game port as well
as the interface's front door, so it is in the `mc` profile too, and its one configuration carries
both: the certificate for `STEWARD_HOST`, requested with `STEWARD_ACME_EMAIL`. Both are required on
every installation, including one whose `COMPOSE_PROFILES` leaves `steward` out, and
`deploy/nordtal.sh` asks for them unconditionally.

### Once, before the first deployment

1. **Publish the release.** Tag it, publish it on GitHub, and let `release.yml` finish. It attaches
   eight assets and pushes **five** images — `discord-bot`, `steward-worker`, `minecraft`,
   `steward-ui`, `steward-deployer` — each tagged with the version and with `latest`.
2. **Set all five packages to Public**, in their GitHub package settings. A package under an
   organisation is **private on its first push**, and a private package answers a pull with the same
   `denied` as one that does not exist. The alternative is a registry credential on the host.

   **Three of the five have never been pushed, and one of them is a surprise.** Measured against
   the registry on 2026-09-13, anonymously: `discord-bot`, `minecraft`, `updater` and
   `postgres-backup` answer `200`; `steward-worker`, `steward-ui` and `steward-deployer` answer
   `403`. The first two new ones are expected — they are new modules. `steward-worker` is not: it
   is the _renamed_ `updater`, and a rename of the image is a **new package** under a new name. The
   public one is the old name nothing pushes to any more, so the next release creates
   `steward-worker` from scratch, private, exactly like the other two.

   That matters beyond a failed pull. steward-worker asks the daemon's `/distribution` endpoint for
   a registry digest, with no credentials, so a private package answers nothing and the image lands
   in `unverifiable` — `UNKNOWN`, never "up to date". That is the honest answer rather than a wrong
   one, but three of five services reporting `UNKNOWN` is a drift report that says very little.
   `updater` and `postgres-backup` stay behind as public packages nothing pushes to any more; they
   can be deleted once the cutover holds.

### On the host

3. **Pick the directory and run one line in it.** There is nothing to check out: the script comes
   from GitHub through `curl`, and everything else it needs is in an image it pulls.

   ```bash
   mkdir -p /srv/nordtal && cd /srv/nordtal
   curl -fsSL https://raw.githubusercontent.com/nordtal/season-2/main/deploy/nordtal.sh | bash
   ```

   **That directory is the installation**. Every volume in `compose.yml`
   defaults to a folder in it — `mc-smp`, `mc-smp-plugins`, `postgres-data`, `steward-backups`,
   one per volume and named exactly like the volume it replaced — so reading a plugin's
   `config.yml` over SFTP is opening a file, and deleting the installation is deleting one folder.
   The first thing the script asks is whether this directory is really the right one.

   Until 2026-09-19 all of those were named Docker volumes, and the reason was Arcane: it browsed
   them through its own API, and it checked this repository out on the host, which is what made a
   path under the checkout unsafe (finding 151). Arcane was removed on 2026-09-15, SFTP is the way
   in again, and SFTP cannot see inside a Docker volume — so the rule turned over. A value without
   a `/` in it is still a volume name to Docker, which is the way back, per volume.

   There is no `.env` to write either: the environment file is created by the script, at the
   absolute path `STEWARD_ENV_FILE` names, mode 600, and **not** in the installation directory —
   it holds every secret the deployment has, and the installation directory is the one somebody
   reaches over SFTP. [`../.env.example`](../.env.example) stays in the repository as the
   **reference for what a setting is called**, not as a form — copying it and working down it puts
   values in two places, and from the first edit in the web interface one of the two is wrong.

   **Rotating a value in this file means editing it in place** (`sed -i`, or write to a temp file
   and `cat` the result back over the original with `>`), never replacing it (`mv` a new file over
   it, or any editor that writes-then-renames to save). `steward-deployer` mounts the _directory_
   holding this file, not the file itself — a directory bind re-resolves the path on
   every access, so an edit in place is visible immediately, but a replacement changes which inode
   `STEWARD_ENV_FILE_NAME` resolves to under that directory, which is exactly what a directory bind
   is for and is not a problem here. What _is_ still a trap, on a host running a `steward-deployer`
   image old enough to bind the file directly — check `docker inspect
nordtal-s2-steward-deployer-1` for whether its mount `Source` is this file or its parent
   directory — is that an older image still binds the _file_, and a replacement there orphans the
   old inode behind that mount for the life of the container, silently. Editing in place is the one
   operation that is safe either way.

4. **What that run does.** It builds nothing; every image is pulled. Afterwards the script is
   `./nordtal.sh` in the installation directory, and that is how everything below is run again.

   ```bash
   ./nordtal.sh                # the menu: what is set, change one, then deploy
   ./nordtal.sh --deploy       # no menu; ask only for what is missing, then deploy
   ./nordtal.sh --check        # every check, and stop before anything is changed
   ./nordtal.sh --from f       # take the answers from a file instead of asking
   ```

   **It renews itself on every run**, because a new `compose.yml` reaches this host only inside a
   new `steward-deployer` image and a directory that has stood for half a year would otherwise
   deploy with a script that knows nothing about it. Without a network it carries on with the copy
   that is there, and it always says which one it is running — the file's own fingerprint and where
   it came from, on the first line.

   **The menu is how a setting is changed.** It lists every value a person answered for, a secret
   as `•••` and never as itself, lets one be picked and typed again, and deploys at the end. The
   generated ones — the database password, the forwarding secret, the two Steward tokens, the Web
   Push keypair — are listed under it and are not editable there: regenerating `POSTGRES_PASSWORD`
   against a database that already exists is the one edit that breaks a working deployment in a way
   nothing reports.

   **It asks.** Nine things only a person can know: the name the interface answers on
   (`STEWARD_HOST`), the address Let's Encrypt writes to (`STEWARD_ACME_EMAIL`), the EULA, the bot
   token, the client id and client secret of the Discord application the login uses, the guild, the
   admin role — and the two bunq values if there is a bunq, which are optional and skipped with
   Enter. Every answer whose shape can be checked is checked at the prompt: a snowflake is digits,
   a host name is not a URL, an address has an `@` and a dot. Secrets are read with the terminal
   echo off. **Nothing is ever printed back**, and no value ever reaches a command line —
   `/proc/<pid>/cmdline` is world-readable, so a token passed as an argument is published to every
   user on the host for as long as that process runs.

   Everything else it either generates (the database password, the forwarding secret, the two
   Steward tokens) or leaves alone, because the service's own configuration has a default and the
   web interface can edit it. **Run it twice and it asks only for what is still missing**, so an
   interrupted setup is resumed rather than restarted.

   One thing it will _not_ generate: on a host where `postgres-data` already exists, a missing
   `POSTGRES_PASSWORD` is **asked for**. Postgres reads that variable only when it initialises an
   empty data directory, so inventing a new one there produces a stack that cannot log in to its
   own database, with no error that says why.

   It then generates the two Steward secrets, **resolves `STEWARD_HOST` and waits
   until it points at this host**, renews `steward-deployer`, and then runs that image once with
   `up` — which pulls every image before it stops anything and creates the rest of the stack,
   including the long-running deployer.

   **The wait is the point** (concept §10): Caddy asks Let's Encrypt for a certificate the moment it
   starts, and the challenge is answered by whatever the name points at. A setup that ran to the end
   therefore means a working deployment, not a deployment with a certificate still to come. The
   script says which name, what it resolved to and what this host is, and checks again every 15 s —
   so the waiting is not guesswork. `--address` supplies this host's public address on a host behind
   NAT; it does not skip the comparison.

   **Run it again after every release.** `compose.yml` reaches this host only inside a new
   `steward-deployer` image, so shipping a changed deployment _is_ this script. Everything else —
   a new bot, a new worker, a new server jar — the deployer and the worker do from inside.

   **Every `docker compose` typed by hand needs `--env-file`**, and so does every one further
   down this file — the environment file is not beside `compose.yml` and never was:

   ```bash
   docker compose --env-file /etc/nordtal/season-2.env ps
   ```

   Compose looks for `.env` in the project directory and nowhere else, and it does not fail when
   there isn't one: it interpolates empty strings, which turns `${X:?}` into an error naming a
   variable you did set, and everything without a `:?` into a silently different deployment.

   On its first start `steward-worker` applies the schema and **fills every empty
   volume** — the four `plugins/` folders, both jar volumes, each server's `.server/` cache and the
   proxy's `pack.yml` — and only then writes the readiness marker every other service waits for. It
   **cannot move a version**: only artefacts with nothing installed are fetched.
   `STEWARD_WORKER_BOOTSTRAP=false` turns it off, and the servers then refuse to start until
   `steward-worker bootstrap` has run, and say so by name. A first deployment downloads four server
   jars and every plugin before it goes healthy, which is why the worker's healthcheck allows
   fifteen minutes.

5. **Upload the hand-built worlds** — see [Getting a world into a
   volume](#getting-a-world-into-a-volume).
6. **Run the login-path rehearsal.** Nothing above proves a client can join, and it is the one step
   no log on this host can answer.

**A release cannot be pinned, and there is no rollback.** `IMAGE_TAG` and `UPDATER_SEASON_RELEASE`
were removed on 2026-09-09: every image is `latest` and the worker follows the newest published
release. A bad release is corrected by publishing a better one — the same trade this project already
took on the Paper build, for the same reason, which is that a pin is a version number kept somewhere
other than `gradle.properties` and every one of those went stale.

## First-start seeding

The container writes seven things, **only when they are not there already**; a file that exists is
never edited again. Everything else stays Paper's and Velocity's own default.

| what                                                                                                | where                                   | when                                                                                                                                        |
| --------------------------------------------------------------------------------------------------- | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `player-info-forwarding-mode`, `bind`, `online-mode`, `[servers]`, `try`, an empty `[forced-hosts]` | proxy, `velocity.toml`                  | no `velocity.toml` yet and `VELOCITY_SERVERS` is set                                                                                        |
| `proxies.velocity.enabled: true`                                                                    | each backend, `config/paper-global.yml` | no `paper-global.yml` yet and `PAPER_VELOCITY_SECRET` is set                                                                                |
| `online-mode=false`                                                                                 | each backend, `server.properties`       | **every start**, see below                                                                                                                  |
| `level-name=$LEVEL_NAME`                                                                            | each Paper server, `server.properties`  | seeded once; a volume still on Paper's default `world` is repaired, any **other** disagreement stops the container, see below               |
| `level-seed=$LEVEL_SEED`                                                                            | each Paper server, `server.properties`  | only while the `level-name` world does not exist yet — which is `level.dat`, not the folder; an existing world is compared and warned about |
| `forwarding.secret`                                                                                 | proxy                                   | every start, from `VELOCITY_FORWARDING_SECRET`                                                                                              |
| `accepts-transfers = true` under `[advanced]`                                                       | proxy, `velocity.toml`                  | **every start**, on a file this script did not write, see below                                                                             |
| `max-players=$MAX_PLAYERS`                                                                          | each Paper server, `server.properties`  | **every start** — the network's own limit, out of the same `NETWORK_MAX_PLAYERS` the proxy gets, see below                                  |

The MOTD and the player limit are deliberately not in that table: both are `proxy` config
(`plugins/proxy/network.yml`), reachable from `.env` through jcore environment overrides
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
Velocity falls back to its _default_ one, which routes three hostnames at servers the file does not
define — and then refuses to start with _"Your configuration is invalid"_.

**`accepts-transfers` is enforced on every start, on a file the entrypoint did not write** — the
only key in `velocity.toml` treated that way, because it is what a live proxy swap needs from
Velocity: without it the receiving proxy refuses every player the other one hands
it, and from the player's seat that is a network that is simply gone. The seeding writes it once,
so a proxy volume older than that line does not have it, and a swap against one fails on exactly
that. Missing table, table without the key, and an explicit
`false` are three different edits and it makes all three; a file that already says so is left
byte-identical.
A root-level key of that name is read by nothing, so it is called out in the log rather than
quietly deleted.

**Nothing else here fixes a volume that already exists**, beyond that and the exception above. The
manual equivalent is `online-mode=false` in `server.properties` and `proxies.velocity.enabled: true`
in `config/paper-global.yml`.

`deploy/minecraft/entrypoint-test.sh` drives thirty-one cases of this logic against fixture directories on
`./gradlew check`, and `:smp`'s and `:hunger-games`' `ComposeWorldTest` compare the `LEVEL_NAME` in
`compose.yml` against each plugin's own configured world name.

## Who limits the players

**One number decides.** `NETWORK_MAX_PLAYERS` in `.env` is what the server browser advertises, what
the proxy enforces at the login gate, _and_ what every Paper backend's
`server.properties#max-players` is set to. All four services read the same variable.

The proxy is the only thing that refuses, at the login gate, before the resource pack and before the
wait in `limbo`. The backends' `max-players` only guarantees they are never a _smaller_ limit than
the advertised one — Paper's own default is 20.

**Admins are exempt from the proxy's limit**, so a full network holds `NETWORK_MAX_PLAYERS` plus
whoever came to fix it. Each Paper plugin therefore answers Paper's own
`PlayerServerFullCheckEvent` itself and lets an admin through; the flag is `discord_user.admin` in
the database, not `ops.json`, which is why this cannot be a server setting. See
`common/…/access/FullServerAdmission.java`.

**Changing the number means restarting the backends, not just the proxy** — it is written into each
`server.properties` by that container's own entrypoint on every start. The whole change is
`docker compose restart proxy limbo hunger-games smp`, or a redeploy.

An existing `network.yml` may still carry `backend-limit`; the proxy deletes the line on its next
start, says which key in a `WARN`, and leaves the old file as `network.yml.bak`. Nothing to do by
hand. A **misspelled** key still stops the proxy with the key named: only you know what you meant.

## What the server browser shows

`NETWORK_MOTD_PRE_LAUNCH`, `_PRE_EVENT`, `_START_EVENT`, `_SMP` and `_MAINTENANCE` in `.env` — one
per season phase, MiniMessage, with placeholders in braces. The full placeholder list is in
`network.yml` itself, which the proxy writes on first start with real defaults; anything left unset
in `.env` keeps that default.

Read at **proxy start**. The MOTD follows the phase on its own, live, but an edit to `network.yml`
or to any `NETWORK_MOTD_*` needs `docker compose restart proxy`; there is no reload
command. That restart is _not_ enough for `NETWORK_MAX_PLAYERS` — see
[Who limits the players](#who-limits-the-players).

When `proxy` cannot start at all, the fail-closed handler answers the ping saying so.

## Picking the Discord ids instead of typing them

Every id in the bot's `access.yml` is an eighteen-digit number that exists in exactly one place a
person can read it: Discord's own right-click menu, behind a developer-mode switch most people have
never turned on. Typing one into a field is a transcription with no feedback — the wrong id is still
a valid snowflake, so nothing refuses it, and the first sign of the mistake is a message appearing
in a channel nobody meant.

So the configuration editor offers a **list of names**. `compose.yml` hands `steward-ui` the same
`NORDTAL_BOT_TOKEN` the bot gets, as `NORDTAL_STEWARD_UI_DISCORD_BOT_TOKEN`, and it asks Discord
what the guild's roles and channels are called. One answer is cached for a minute on the server and
five in the browser, because a page with eleven pickers on it must not be eleven requests.

**Steward only reads with that token.** It never sends a message, never changes a role, and never
puts the token in an answer, in a log or in front of a browser; what leaves the process is a list of
`{id, name}` that is public inside the guild anyway. **It is still a real widening and is worth
knowing:** whoever reaches that container has the bot. The alternative was a second Discord
application with its own token — a second thing to rotate and a second bot in the member list — for
a token that is already on this host, in the service next door.

**It never fails the page.** No token, an unreachable Discord and a rate limit all come back as
`available: false` with a sentence, and the picker is the text field it replaced, with the reason
underneath it and a reminder of where the id is in Discord. An id that is set but is not in the
list — a channel the bot cannot see, one deleted since — is kept and marked, never silently
replaced by "none".

**Which keys get a picker** is decided on the key name, not on the value: anything called `role`,
`*-role` or under `roles:`, and anything called `channel`, `*-channel` or under `channels:`. It has
to be the key, because the whole point is to help with an id that is still empty. `guild-id` gets
none — the list is read _from_ the guild, so picking it out of itself is circular.

## The forwarding secret

Modern forwarding needs the **same secret in all four containers**, and a mismatch does not say so:
it shows up as every login failing with _"Unable to connect you to the backend server"_.

**`nordtal.sh` generates it** as `VELOCITY_FORWARDING_SECRET` and you never see it — it is exactly
the kind of secret a machine can invent, so nobody types it. `compose.yml` hands the same value to
the proxy under that name and to each backend as `PAPER_VELOCITY_SECRET`; the proxy writes it to
`/data/forwarding.secret`, and Paper reads its own copy from the environment.

To **rotate** it, which is the one time it is written by hand:

```bash
openssl rand -hex 24
```

**It lands on disk**: Paper writes the value into `config/paper-global.yml` on first load, so a
rotation is the environment file _plus_ that one line in each of the three backend volumes.

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

**Stop the server first**, whichever way the files get there. Copying into a world a running server
has open produces corruption that surfaces days later.

## The console

A shell opened into the container is a `docker exec`, which cannot reach PID 1's stdin — so the
server runs inside a tmux session, and the image ships two commands:

```bash
console            # attach to the real console, read and write. Detach with Ctrl-b then d.
mc <command>       # send one command, no TTY needed. Output goes to the container log.
```

`mc` is the one to reach for in a runbook or a script; `console` is for watching something happen.
**Ctrl-C inside `console` goes to the server, not to your shell** — use `mc stop` or
`docker compose stop` to shut a server down.

Reading is unaffected: the entrypoint tails the server's own `logs/latest.log` onto the container's
stdout, so `docker logs` and steward-ui's log view show everything.

## Updating

**The normal way is `/update` in the admin channel on Discord** — the same command in game and on
the proxy console. It reports what is newer than what is running and changes nothing; an **Update
now** button under it runs the whole thing, after a 30-second countdown every player online is
warned through.

Both reach steward-worker through a row in `update_request` and a notification, answered by the
container that has the volumes; the result is an `UpdateReport` written into
`update_request.result` as JSON, one line per service and one entry per artefact moving.

**On the host, when neither door can be reached**, `./nordtal.sh` in the installation directory
writes the same row itself:

```bash
./nordtal.sh update                # the whole network, now, and wait for the report
./nordtal.sh update --restart      # restart everything, install nothing
./nordtal.sh update --backup       # one backup run
./nordtal.sh update --down smp     # stop one service and hold it down
./nordtal.sh update --start        # release every hold (or name one service)
./nordtal.sh update --in 10        # let the countdown run for ten minutes first
./nordtal.sh update --no-wait      # print the request id and return
```

That is the emergency exit and the reason it exists is the shape of everything above it: Discord
needs the bot, `/update` in game needs a server, and Steward is a container in the stack being
updated. `nordtal.sh` is the one piece that lives outside the deployment. It reads two variables
out of the environment file — the database user and the database name — reaches the database with
`docker exec` on the postgres container, and prints the run's own report when it is over. It runs
no self-update and no deploy: `update` is checked before any of that, because a command somebody
reaches for when something is wrong must not need GitHub first.

The rest, on the host:

```bash
docker compose run --rm steward-worker report      # what would change, changes nothing
docker compose run --rm steward-worker bootstrap   # migrate, then fill EMPTY slots. Upgrades nothing
```

`bootstrap` is the manual form of the automatic first start and what to reach for when
`STEWARD_WORKER_BOOTSTRAP` is off or a volume has been emptied. **It fills gaps and never upgrades.** To
move a version that is already installed, use `/update now` in Discord or in game: it stops the
affected servers, swaps, starts them again and waits until each reports healthy.

A `bootstrap` run by hand, an update and a restart cannot collide: **all three take the same
PostgreSQL advisory lock, and the second one to ask is refused rather than queued**, naming which
one is already running.

An update applies the schema **with the affected servers stopped**, before a jar moves, so a plugin
can never come up against a schema older than itself. A migration that fails stops the run there:
nothing is fetched, nothing is written, and every service that was stopped is started again.

The worker asks GitHub, Modrinth and the PaperMC Fill API what the newest version of everything is,
compares that against the jars in the volumes, and moves the ones that differ. **What a server runs
is the jar in its volume**; no version is written into a file. What it _follows_ is not configurable
at all — GitHub's `/releases/latest`, which skips drafts and pre-releases, so an update that never
arrived is usually a release nobody published.

Four properties worth knowing before reading a report:

- **Two phases.** Everything is downloaded into a `.nordtal-staging` directory inside the folder it
  will end up in and verified there; only when all of it is present does anything move.
- **A server moves together or not at all**, so an unresolvable plugin skips that whole server. The
  server jar is the exception: a build the Fill API could not answer for is its own "skipped" row
  and the plugins move anyway, being compiled against the _version_, never a build.
- **The report restarts nothing, and a run restarts exactly what it stopped.** A server that does
  not come back makes the whole run fail by name. `bootstrap` stops and starts nothing at all.
- **"Skipped" is not "up to date".** A run where nothing could be checked — an unmounted volume, a
  source that did not answer — says so rather than closing with "Nothing needed doing".

Checksums are verified where one exists (Modrinth sha512 per file, Fill sha256 per build) and a
mismatch deletes the download instead of installing it. **A GitHub release asset carries no digest
of any kind**, so our own jars and the DisplayTags jar arrive unverified over TLS.

The **server jar** is the worker's too: it installs the newest `STABLE` build of the version pinned
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
`/update restart` in game. It is not a project-wide redeploy, which would take steward-worker
down with everything else and leave nothing to report whether the network came back.

**The confirmation comes first, and the countdown only after it.** `/update now` and
`/update restart` have to be typed again inside thirty seconds in chat, or confirmed with a button
in Discord. Until that happens **nothing is scheduled at all** — no row, no countdown, no warning to
anybody. Once confirmed, **every player on the network is counted down** at 30, 10 and 5 seconds;
the proxy announces, because it is the only process that sees everybody. Inside those thirty seconds
the **Stop the countdown** button or `/update cancel` still stops it.

**Players on a server that is about to stop are moved into `limbo`, eight seconds before it goes.**
The waiting room shows _"Update in progress / You will be moved back automatically"_ rather than the
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

**The check asks the registry, and that is the fix rather than a detail.** The worker resolves each
running container's image reference against its registry (`GET /distribution/{ref}/json` over the
Docker socket, ~250 ms) and compares the answer with the digest the container actually carries.
Three outcomes, and the third is the point: newer in the registry, the same, or **could not be
asked** — which is a named note in the report and never counted as current. Until 2026-09-12 this
went through a panel whose image check never asked a registry at all; it answered from what it had
already persisted, and four releases ran behind while every report said the network was current.

An image that **cannot** be checked is one built on this host and pushed nowhere, or a registry that
did not answer. Credentials are not among the reasons: all three `ghcr.io/nordtal` packages are
public.

Two things a run will not renew, and both are named in the report rather than done quietly: **its own
image**, because the recreate would end the run from inside it, and **anything it does not own** —
`postgres` and the backup sidecar, which it never stops. Both need a redeploy of the project from
the host, which is now the only thing in this deployment that does.

**It is the Docker socket, and that is a stated cost rather than a hidden one.** `compose.yml` mounts
`/var/run/docker.sock` into `steward-worker`, which is how it reads every container's state, health,
log and image digest and how it stops and starts them. A container that can talk to that socket can
do anything the daemon can — `:ro` restricts the socket _file_, not the API behind it — and this is
also the container whose job is downloading files from the internet and putting them where servers
execute them. The restraint is in the code: `DockerOps` reads, stops and starts, and **refuses to
create a container**. Creating one needs the compose file, which `steward-deployer` owns. The
boundary that matters is that `steward-ui`, the part an attacker reaches first, gets no socket at
all.

**Without the socket, updating stops working** — and says so rather than half-running. An update
stops each affected server before its jars move, so a run that cannot read the container runtime
refuses before resolving a version or touching a file. What still works is
`docker compose run --rm steward-worker bootstrap` on the host: enough to bring a fresh deployment
up, not enough to move a version.

### Replacing one service, from this checkout

For iterating on `steward-ui` or `steward-worker` without waiting for a release. **This is no longer
how the dev host runs them** (2026-09-15): both pull `ghcr.io/nordtal/<service>:latest` like every
other service, because the release workflow has pushed all five images since 2026-09-02. The host's
environment file carried `STEWARD_UI_IMAGE` and `STEWARD_WORKER_IMAGE` pointing at locally built
`:alpha` images until then, and the cost was that no update run could ask a registry about either -
so every delivery needed the three commands below by hand, and the run said `FAILED` while having
done everything it was allowed to.

What follows is therefore a **development** loop, not a delivery one. Building locally replaces the
registry image under that tag until the next `pull`. From the root of this checkout, with `$ENV_FILE`
pointing at the host's environment file (`/etc/nordtal/season-2.env` on the dev host) and `$PROJECT`
at the compose project (`nordtal-s2` there):

```bash
sh gradlew :steward-ui:build                            # the jar, with the frontend in it
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f ./compose.yml build steward-ui
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f ./compose.yml up -d --no-deps steward-ui
docker inspect -f '{{.State.Health.Status}}' "$PROJECT-steward-ui-1"   # wait for `healthy`
```

`--no-deps` is the word that keeps it to one service: without it Compose recreates everything
`steward-ui` depends on, which is `postgres` and `steward-worker`, which is the network.

**A new image is not a new process for two of these three, and that has now cost two rollouts.**
`steward-ui` runs the jar the image carries, at `/app/app.jar`. `steward-worker` and `discord-bot`
do **not**: they run the jar out of a volume — `nordtal-s2_steward-worker-jar` at
`/volumes/steward-worker/steward-worker-0.9.1.jar`, and `nordtal-s2_bot-jar` at
`/app/lib/discord-bot-0.9.1.jar`. Rebuilding and recreating those two containers deploys nothing:
the new jar sits at `/app/app.jar`, unused, and the volume's older copy keeps running. On
2026-09-17 that copy was a day and a half old, and the matching file **size** at the image path is
what made it look deployed.

> **Ask the process, not the filesystem:**
>
> ```bash
> docker exec nordtal-s2-discord-bot-1 cat /proc/1/cmdline | tr '\0' ' '
> ```
>
> That prints the jar actually running. Replace _that_ path, then restart:
>
> ```bash
> docker run --rm -v nordtal-s2_bot-jar:/vol -v "$PWD/discord-bot/build/libs:/src:ro" alpine \
>   sh -c 'cp /src/discord-bot-0.9.1.jar /vol/discord-bot-0.9.1.jar'
> docker restart nordtal-s2-discord-bot-1
> ```
>
> Compare the md5 on both sides before restarting; "Built" and "Recreated" are not evidence that
> anything moved. This is the same trap in a second shape as the one under [The
> images](#the-images): a Dockerfile that only copies a jar reports success whether or not
> `shadowJar` ran.

**`-f` has to be said, and the reason is worth a paragraph.** Counted on the dev host on
2026-09-14, `com.docker.compose.project.config_files` across the ten running containers said two
different things, and neither is a path a person can open:

- eight said `/app/compose.yml`, which is inside `steward-deployer` — that is where the deployer
  runs Compose from, and the file is baked into its image. It survives a reboot; it is simply not
  on the host.
- `steward-ui` and `steward-worker` said **`/tmp/live-compose.yml`**, left over from being rebuilt
  by hand earlier that day, and `/tmp` is emptied by a reboot. That is the one Till found, and it
  is the one that would really have gone missing.

Naming the checkout's copy on each `up` moves the label onto a path that is on the host and
survives a restart, one service at a time.

**The checkout's copy is the right one to name.** Compared the same day, `/tmp/live-compose.yml`
(which is byte-identical to `/app/compose.yml` inside `steward-deployer`, i.e. where it came from)
differs from `season-2/compose.yml` in exactly one line — a comment in which one German word for
the traffic light was replaced by the English one. (Not quoted here: the guard that found it in the
first place, `NothingIsGermanTest`, reads this file too, and it was right to.) Nothing declarative
differs, so the checkout is not a divergence to reconcile; it is the same file with a German word
taken out of a comment.

**`--env-file` has to be said too.** The stack's `.env` is not beside `compose.yml` in this
checkout — it is `/etc/nordtal/season-2.env`, mode 600, and it holds the live secrets. Without it
every `${X:?}` in the compose file fails and the command refuses before it does anything, which is
the good failure. Do not copy it anywhere.

**After a reboot, the same file brings the whole stack back**, and that is the answer to the
worry that opened this section — drop `--no-deps` and the service name:

```bash
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f ./compose.yml up -d
```

Nothing is lost with `/tmp`. The volumes are named and the daemon owns them; the file that was in
`/tmp` is in this checkout and in `steward-deployer`'s image, twice over.

**Run once, 2026-09-14, on `steward-ui` and `steward-worker` together.** `:steward-ui:build` and
`:steward-worker:build` with their tests: 1m52s. `build` for both images: 17s warm. `up -d
--no-deps`: 14s to both containers recreated and the worker `healthy`, with `steward-ui` following
20s later - the healthcheck's own interval, not a hang. Afterwards both containers carry
`com.docker.compose.project.config_files` pointing at this checkout, which is the point.

## Locally

The same `compose.yml`, the same `Dockerfile`s, the same `steward-worker`. What differs is a second
env file and jars out of `build/libs` instead of a GitHub release.

```bash
deploy/dev init          # writes deploy/dev.env, generates the two secrets, makes the directories
deploy/dev up            # builds the five jars and both images, then brings the stack up
deploy/dev deploy smp    # rebuild :smp, replace the jar, restart that one container
```

The first `up` takes a while: `steward-worker` fetches Paper, Velocity, DisplayTags, PacketEvents
and the SMP's two world-generation datapacks. It does **not** fetch our five jars, because
`deploy/dev up` has already put them in `plugins/` and the bootstrap installs only what is missing.
Then join `localhost` with a real client.

**On a Mac, install a current bash first: `brew install bash`.** macOS ships 3.2.57 as `/bin/bash`
and never will ship anything newer, and both scripts need the associative arrays bash 4 added. They
say so themselves since 2026-09-20; before that the failure was `STEWARD_HOST: unbound variable`
from `deploy/dev init`, which reads like a complaint about the env file and is not one.

`deploy/dev` also carries `ui`, `logs`, `console`, `mc`, `psql`, `ps`, `stop`, `down`, `pack` and
`reset`;
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
- **Images are built, never pulled** (`MC_IMAGE`, `STEWARD_WORKER_IMAGE` on a `:dev` tag). A locally
  built plugin needs the locally built steward-worker: the migrations are compiled into `:common`
  and shaded into that jar, so a released worker would migrate to the released schema.
- **Four `<SERVICE>_PLUGINS` variables pointing at `./deploy/servers/<service>/plugins`**, which is
  what turns each server's plugins folder into a bind mount you can edit with a text editor. An
  existing `deploy/dev.env` needs those four lines added by hand — the file is gitignored, so
  nothing migrated it. `deploy/dev` refuses a value with no `/` in it rather than writing jars into
  a directory no container mounts.
- **Small heaps and `NETWORK_MAX_PLAYERS=20`.**

### The interface

Two halves, and the reason there is a command for it at all is that they are easy to start in the
wrong order.

```bash
deploy/dev ui
```

brings up everything `COMPOSE_PROFILES` selects, adds the three steward services, asks Gradle for
the private Node and the npm packages, and then runs Vite in the foreground. The interface is on
**http://localhost:5173**; the Java process behind it answers on `127.0.0.1:8080`, and Vite proxies
`/api` and `/auth` to it. **Ctrl-C stops Vite and leaves the containers running** — the
counter-command is `deploy/dev stop`.

- **The whole stack, not only what the interface talks to directly.** What it draws is the servers,
  their plugins and their players; a page whose every card says _not running_ is not a page worth
  working on. The first `deploy/dev up` still has to have happened — `ui` starts containers, it
  does not build jars.
- **The three steward services are named, never added to `COMPOSE_PROFILES`.** Naming a service
  activates its profile, so nothing here needs `--profile`. Caddy comes up with `mc` anyway, as
  the guard in front of the game port; for `steward.localhost` it issues its own local certificate
  and asks Let's Encrypt nothing.
- **`PACK_PORT` is 8081 because `STEWARD_UI_PORT` is 8080.** Both defaulted to 8080, and with
  `devpack` on and the interface up they are on at the same time now. `deploy/dev ui` refuses to
  start when the two are equal rather than letting Docker explain it three services later.
- **There is no Node on this host and there is not going to be one.** `:steward-ui:npmInstall`
  downloads its own under `steward-ui/build/nodejs/`, and `deploy/dev ui` _searches_ for it rather
  than spelling the path out — the directory name carries the platform, so a written path works on
  one machine only. (`npx vitest` with no Node on `PATH` exits 0 having tested nothing, which is
  how this matters.)
- **Working on the Java half instead** means running `:steward-ui:run` and pointing Vite's proxy at
  it — the same `:8080`, so the container and the Gradle process cannot both have it. Stop the
  container first (`docker compose --env-file deploy/dev.env stop steward-ui`) or set
  `STEWARD_UI_PORT` to something else in `deploy/dev.env`. The `run` task reads `deploy/dev.env`
  into its environment, so it needs no configuration of its own and carries no secret in a run
  configuration; `.run/steward-ui.run.xml` is that configuration for IntelliJ.
- **`STEWARD_UI_PUBLIC_URL` is the address the browser uses**, which locally is
  `http://localhost:5173` and not the container's port. Discord compares the redirect URI as a
  string and WebAuthn compares the relying party to the page's own host, so a value that names the
  wrong half fails at sign-in rather than at startup. `STEWARD_WEBAUTHN_RP_ID` is that address's
  host, and `deploy/dev init` derives it rather than asking twice.

### The resource pack

The pack and the plugins are one change — a glyph code point is declared in `:common`'s `Glyphs`, in
a font file and in a PNG — so testing the drawn half against the previous release's pack answers
nothing.

```bash
deploy/dev pack
```

builds the zip, puts it under `PACK_ROOT`, and writes `url` and `sha1` into the proxy's `pack.yml` —
the same two lines the worker's `PackWriter` owns and no others. The `devpack` profile serves that
directory on `http://localhost:8081` (`PACK_PORT` — it was 8080 until the interface wanted that
port too), which is the client's `localhost` too. A `FAILED_DOWNLOAD` on
the client is almost always the hash and not the network — rerun after any change under
`resource-pack/src/`.

### Rehearsing the restart path locally

`deploy/dev deploy` restarts one container and is what you want ninety-nine times out of a hundred.
The hundredth is the restart path itself — the button, the countdown, the stop and start of each
container — and **that needs nothing extra installed any more.** It used to need a management panel
running beside the stack and four variables pointing at it, which is why it went unrehearsed for so
long. The worker now uses the Docker socket `compose.yml` already mounts, so `/update restart`
against a local stack exercises the real sequence.

What a local rehearsal still cannot show you is the Discord half — the countdown message, the
confirmation button and the report in the admin channel — which needs a real guild.

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
else and leaves the backup sidecar running — the network then cannot be removed (_"Resource is still
in use"_), and a backup job is left pointed at a database that no longer exists. Production is
`db,bot,mc,backup`, which is what `.env.example` ships. Whatever selection is used, **`up` and
`down` have to use the same one.**

### The standby services

`proxy-standby` and `limbo-standby` are a second proxy and a second waiting room, each on its own
volumes, each in the profile `standby` and in no other. They are not part of normal operation and
must not be added to `COMPOSE_PROFILES`: they exist to hold the network for the seconds their model
is being replaced, and running them all season would cost this host about 1.6 GB for nothing.

They are started by name for a swap and stopped the same way:

```
docker compose --profile standby up -d proxy-standby
docker compose --profile standby down proxy-standby
```

**`--profile standby` belongs on the stop as well as on the start** — that is the same trap the
paragraph above describes, and here it bites harder, because a standby left running is a second
Velocity on this host holding a port and 680 MB.

`steward-worker` fills their `plugins/` at the end of every update run, by copying the live
service's folder rather than resolving anything a second time: a standby comes up on the jar that
was just installed, never on whatever is newest at the moment it starts.

**What voice chat does on the standby proxy: nothing.** Simple Voice Chat's proxy plugin ships
`port: -1`, which means "the port Velocity bound", and Velocity binds 25565 inside every proxy
container. The plugin therefore hands the client 25565 - which is the guard's port,
and the guard sends UDP to whichever proxy is answering. So audio follows the
same failover the game connection does: while the live proxy is down it lands on the standby, and
the moment it is back it lands there. `PROXY_STANDBY_PORT` (25566 by default) is published by the
guard for UDP as well as TCP so that the day the plugin's `voice_host` is set the port is already
open. Neither proxy publishes a port of its own any more; both are reached through `caddy`, and
both therefore run with `haproxy-protocol = true` in velocity.toml, which `VELOCITY_HAPROXY` and the
entrypoint keep true on every start.

## Backups

Access periods, payment records, aura, milestone progress and graves are all in one PostgreSQL, and
it is the only thing in this stack that cannot be rebuilt from the repository and a world folder.

**steward-worker takes both halves since 2026-09-13** (`konzept-eigenstaendiger-stack.md` §9a). The
`postgres-backup` sidecar is gone — one clock, one retention, one directory, one report.

### The database is dumped, the volumes are tarred

`pg_dump --format=custom` runs **inside the postgres container**, which is what makes it impossible
for the client to be older than the server it dumps — an older `pg_dump` refuses a newer server
outright, and this makes the versions match by construction instead of by somebody keeping two
images in step. It takes an MVCC snapshot, so it is consistent as of the moment it starts and
**nothing is stopped for it**: it runs before the servers go down.

The volumes are `tar` piped through `zstd -1`, read from the read-only mounts under
`/backup-sources`, written to `/backups`. Measured on this host against the real SMP world: 657 MiB
in, 512.9 MiB out, **4.0 s**, about 1.6 s of which is reading the archive back to verify it. `-3`
took 5.3 s for 0.6 % less — region files are already deflated.

Both write a `.partial` file and rename only after it has been read back (`tar --zstd -tf`, and
`pg_restore --list` for the dump). A truncated archive still decompresses perfectly; only walking
its members finds the truncation. A half-written file that looks like every other one is worse than
none — it is the one the retention sweep keeps and the one a restore picks.

### A backup is a run, not a schedule

`/backup now` on any surface writes a row, and so does the worker's own clock at
`steward.yml#backup.at` (04:45). **That clock moved out of `smp`**, where it lived because `serve`
was not allowed to schedule anything — with the consequence that a season with `smp` down had no
backup and nothing said so. The protection that mattered is kept: the clock writes a request row
and nothing else, and everything after that row is the path `/backup now` already took.

What replaced the fifteen-minute coupling between that clock and the farm reset is a query: `smp`
will not reset the farm world unless a `BACKUP` run finished, succeeded **and saved something**
inside `config.yml#farm-reset-backup-window-hours` (12). A `DONE` row is not enough — run 23
once reported success having saved zero volumes, so the check reads the report.

The run is a thirty-second countdown every player sees, then `smp`, `proxy` and the bot
are stopped, then every volume is tarred in order, then retention runs, then everything comes back
and is checked. Update and backup take the same lock and never overlap.

**The report names the size and the duration of every line.** A volume that produced nothing is
`FAILED` even when every call succeeded.

### What is saved, and what is deliberately not

`nordtal-s2_mc-smp` and the four `*-plugins` volumes — the hand-built world and every hand-edited
`config.yml`, `milestones.yml`, `sounds.yml` and `pack.yml` — plus `mc-proxy` (velocity.toml
and the forwarding secret) and `bot-config`. `postgres-data` is **never** in that list: a snapshot of
a live PGDATA is torn and fails at restore. `mc-limbo` and `mc-hunger-games` are rebuilt rather than
restored; `bot-jar` and `steward-worker-jar` are refilled by `steward-worker bootstrap`.

**There is no offsite copy yet.** §9a's Hetzner Storage Box does not exist, so every archive sits on
the same disk as the thing it is a copy of, and what `backup.retention` keeps of them (fourteen
days, then one a week for eight weeks, then one a month for six months) protects against a mistake
and against nothing else. There is deliberately no untested S3 path in the code.

### Restoring

```bash
sudo bash deploy/restore.sh --list                                  # what is on the disk
sudo bash deploy/restore.sh nordtal-s2_mc-smp-<stamp>.tar.zst       # a volume
sudo bash deploy/restore.sh nordtal-<stamp>.dump                    # the database
```

**A volume archive replaces a volume.** Not merges — replaces: the script empties it and unpacks the
archive into it, so anything younger than the archive is gone. On `nordtal-s2_mc-smp` that is
Nordtal. So it stops whatever mounts the volume, prints what it is about to do, and **makes you type
the volume's name back** — not "yes", the name, the same guard `deploy/dev reset` has and for the
same reason. Afterwards it starts again exactly what it stopped.

**A database dump replaces nothing.** It is restored into a _new_ database called
`restore_<stamp>` beside the live one, with `--no-owner --no-privileges`, so you can look inside it
before anything points at it. Promoting it is a separate, deliberate act and the script does not do
it — nor does it need the stack to be broken, it needs postgres to be _running_, because a dump is
restored by the server.

It reads everything out of the `steward-backups` volume and unpacks with a container of the
`steward-worker` image — the same `tar` and the same `zstd` that wrote the archive. A `.partial`
file is refused by name: steward-worker renames an archive only after reading it back, so one still
carrying that suffix is a backup that was interrupted, and it is the only file in that directory
that _looks_ restorable.

**A `.unverified` file beside an archive is a sentence to read before restoring from it.**
Docker's stop call succeeds whether a container shut down or was killed at the end of the grace
period, so `steward-worker` inspects afterwards — and that inspect can itself fail. A backup taken
over a stop whose ending nobody could read is still taken (refusing would take the network down
over an unreadable `inspect`), and it is not reported as an ordinary one: the run's report says
`UNVERIFIED STOP`, and a `<archive>.unverified` file lands next to the archive saying which service
it was. `--list` prints those files' contents, naming one directly is refused with a pointer at the
archive it belongs to, and a restore warns with the text _before_ asking for the typed
confirmation. The retention sweep deletes the mark with the archive it belongs to. The archive
itself is byte for byte an ordinary archive — what is unverified is the moment it was taken, not
its readability, which is checked when it is written and again before a restore touches anything.

`deploy/restore-test.sh` pins which names are recognised and that the confirmation cannot be
satisfied by "yes", by a bare Return or by a neighbouring volume's name; it runs on `check`.

**Both halves can be rehearsed on this host** against a `024500Z` backup: a `pg_dump` into a
`restore_<stamp>` database beside the live one, and a volume archive over `steward-ui-config`. Two
things a rehearsal finds, neither of which is a defect and both of which will mislead whoever
checks the result:

- **`restore.sh` starts the services it stopped again, and they write into the volume on start.**
  A restored volume is therefore not byte for byte the archive once the service is up: for
  `steward-ui-config` the two `*.schema.json` are rewritten from the running jar's `@ConfigSpec`
  and the two `*.env-overrides.txt` are written afresh. Four of the six files match the archive's
  checksums exactly; the schema does not, because the jar has moved on since the archive.
- **Checking "did it really unpack" by looking for files that should have disappeared does not
  work**, for that same reason — the service recreates its own within the second. What separates an
  unpacked file from one that was merely left alone is **`ctime`**: `tar` restores the mtime and
  cannot forge the ctime. After the rehearsal the four config files carried yesterday's mtime and a
  ctime from the minute of the restore, and the service's four carried a ctime one second later.
  That one second is the whole sequence: tar first, service second.

**Restoring `steward-ui-config` brings back its VAPID keypair too, and that is not a neutral
byte.** A browser's push subscription is
bound to the `applicationServerKey` it subscribed under; overwrite the keypair — by restoring an
older archive, or by `docker volume rm steward-ui-config` for any other reason — and every row in
`steward_push_subscription` survives while none of them verify against a push service any more.
There is no migration for this today: the fix is asking whoever cares to resubscribe. If the
answer to "is that acceptable" is ever no, the file to write is a follow-up ticket, not a rewrite of
this paragraph.

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
for s in proxy limbo hunger-games smp; do
  docker run --rm \
    -v nordtal-s2_mc-$s-plugins:/dst \
    -v "$PWD/deploy/servers/$s/plugins:/src:ro" \
    alpine cp -a /src/. /dst/
done
docker compose up -d
```

Check `docker compose logs` for the four servers before deleting anything. **Keep a copy of
`deploy/servers/` outside the checkout until you have seen a server come up with its own config** —
that directory is inside the checkout, and a deployment that checks this repository out over itself
deletes ignored files from it (finding 151).

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

| symptom                                                                    | cause                                                                                                                                                                                                                                                                                                                   |
| -------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Container will not start, log names a config key                           | jcore refused the config. The message names the file and the setting; it is not a container fault.                                                                                                                                                                                                                      |
| `FATAL: set EULA=true`                                                     | Deliberate. The image does not accept Minecraft's EULA on your behalf.                                                                                                                                                                                                                                                  |
| `FATAL: could not fetch <jar> … Refusing to start`                         | The release tag or the asset name in `.env` is wrong, or GitHub is down and this jar was never cached. It will not fall back to an older jar.                                                                                                                                                                           |
| Every login fails with _"Unable to connect you to the backend server"_     | The forwarding secret does not match — one container did not get `VELOCITY_FORWARDING_SECRET`, or the volume predates the automation and still carries an old one.                                                                                                                                                      |
| Velocity exits at once with _"Your configuration is invalid"_              | `velocity.toml` names a server in `[forced-hosts]` or `try` that its `[servers]` does not define.                                                                                                                                                                                                                       |
| A backend logs _"SERVER IS RUNNING IN OFFLINE/INSECURE MODE"_              | Expected, and required. The proxy authenticates; a backend that also does refuses every forwarded login.                                                                                                                                                                                                                |
| Proxy starts but refuses every login with a "network misconfigured" screen | `proxy` failing closed on a bad `gate.yml`/`database.yml`/`pack.yml`/`network.yml`. Intended; the server browser says the same thing. Read the log.                                                                                                                                                                     |
| Log names `backend-limit` as a setting that no longer exists               | `network.yml` in the volume predates the retirement of that key. Nothing to do: the line is deleted for you and the old file is in `network.yml.bak`. A deployment older than 2026-09-05 refuses to start instead — delete the line by hand there, see [Who limits the players](#who-limits-the-players).               |
| A backend answers _"Server full"_                                          | Only an admin should ever see this, and only if the exemption is not firing. Everybody else is refused by the proxy at the login gate. Check the backend's `max-players` really is `NETWORK_MAX_PLAYERS` (the container was restarted after the last change) and see [Who limits the players](#who-limits-the-players). |
| The browser shows the old MOTD after editing `.env`                        | `network.yml` is read at proxy start. Restart the `proxy` service; there is no reload command.                                                                                                                                                                                                                          |
| Everybody is refused with a countdown, and nobody asked for that           | The phase is `PRE_LAUNCH`, which is the seeded initial state. `/phase set PRE_EVENT` opens the network.                                                                                                                                                                                                                 |
| `docker rm -f` fails with _"did not receive an exit event"_                | You are running a container that mirrors its console with `tmux pipe-pane > /proc/1/fd/1`. Do not do that — see [below](#never-mirror-the-console-with-tmux-pipe-pane). Only a Docker daemon restart clears it.                                                                                                         |

## Third-party plugins

- **Required, `smp` only: DisplayTags, and PacketEvents underneath it.** Nametags come from
  [`papermc-display-tags`](https://github.com/nordtal/papermc-display-tags) — our own fork — through
  its API. `smp`'s `paper-plugin.yml` declares it with `load: BEFORE` and `required: true`, so a
  server missing either fails loudly at start instead of quietly rendering plain nametags.
- **Optional: CoreProtect**, purely as insurance. Nothing in the design depends on it, and it gets
  its own SQLite file rather than a schema in our PostgreSQL so that exactly one process migrates.
  It had no 26.2 release as of 2026-08-31, only a `master` that builds against it; if it has not
  shipped when the phase is ready, the phase opens without block logging and Prism 4.4 is the
  written fallback.

The worker resolves both — DisplayTags from its own repository's releases, PacketEvents from
Modrinth filtered to this Minecraft version and `paper` — so a version bump is a run of
`/update now` and not an edit to `.env`.

**Each service names what it needs in `EXPECTED_PLUGINS`** (filename prefixes), and a folder missing
any of them stops the container naming them — counting jars is not enough, because a source that
answers 403 for one jar while another answers fine leaves a folder that is not empty and a server
with no season on it, reporting healthy. It is a **minimum, never an exact set**: an extra jar is
reported and left alone. `TopologyTest` asserts that every plugin the topology gives a service is one
that service's guard asks for.

**Two datapacks belong in the same conversation: Terralith and Dungeons and Taverns.** They are the
terrain of every world in this season, pinned by sha512 in `.env` (`SMP_DATAPACK_URLS`), and the
entrypoint fetches them into the `level-name` world's `datapacks/` folder _before_ the server
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
survives the SIGKILL at the end of the grace period, and `docker rm -f` then fails with _"did not
receive an exit event"_ — only a Docker daemon restart clears it. The writer holds a second handle on
the container's stdout pipe from a process whose lifetime the shim does not track.

**The rule is about `/proc/1/fd/1`, not about `pipe-pane`.** The entrypoint does use `pipe-pane`,
into a _file_ — an ordinary file in the volume is a different descriptor and holds nothing open. It
captures the pane to `logs/console.log`, empties it at every start and switches it off again the
moment `latest.log` exists, so it is a boot log with nothing to rotate; if the JVM dies before Paper
starts logging, the entrypoint prints that file to stdout on the way out. Ordinary log reading is
`tail -F` on `logs/latest.log`, a plain child of PID 1 inheriting its stdout.

Two ordering rules go with it, both one line, both asserted by `:common`'s `EntrypointRulesTest`.
`remain-on-exit` has to be set **globally, before** `new-session` — which needs `exit-empty off`,
since a tmux server with no sessions exits immediately — or a JVM that dies at once takes the session
with it before the option applies and a real exit status of 3 is reported as 1. And `pipe-pane` has
to be attached in the _same_ `tmux` invocation as `new-session`; a separate call against a pane that
already exited fails with _"target pane has exited"_, taking the crash output with it.
