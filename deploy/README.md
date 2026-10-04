# deploy

Season 2's deployment: one `docker compose` stack on one host. The project overview is
[`../README.md`](../README.md).

`compose.yml` and `.env.example` are at the repository root, so every command here runs from there.
`compose.yml` is baked into `steward-agent`'s image, the one service that creates containers; see
[`../steward-agent/README.md`](../steward-agent/README.md). steward holds no Docker socket and
asks the agent for everything Docker knows.

```
compose.yml            services in six profiles: db, bot, mc, steward, devpack (local), standby
.env.example           every setting, as a reference; nobody fills it in by hand
deploy/
  minecraft/           one image for all four Minecraft services
    entrypoint.sh      PID 1: resolve the jar, seed the config, run tmux, trap SIGTERM
    scripts/console    attach to the server console
    scripts/mc         send one console command, no TTY needed
  nordtal.sh           the host's installer and menu; renews itself from the newest release
  restore.sh           put one archive back: a volume, or a dump into a new database
  dev.env.example      the local stack's settings; see Locally
  *-test.sh            the scripts' checks, run on `check` without Docker
  servers/, pack/      not in git: the local stack's plugin folders and resource pack
```

## First deployment, in order

A deploy pulls and never builds. The images (`minecraft`, `steward`, `steward-agent`, `steward-bunq`,
`discord-bot`, `caddy`) are pushed to `ghcr.io/nordtal` by
[`release.yml`](../.github/workflows/release.yml) when a release is published.

1. **Publish a release** and let `release.yml` finish.
2. **Set all four packages to Public.** A package is private on its first push, a private package
   answers a pull with `denied`, and steward cannot read its digest, so drift shows as
   `UNKNOWN`.
3. **Run the installer in the installation directory.** Every volume is a folder in it.

   ```bash
   mkdir -p /srv/nordtal && cd /srv/nordtal
   curl -fsSL https://raw.githubusercontent.com/nordtal/season-2/main/deploy/nordtal.sh | bash
   ```

   It asks for what only a person knows (`STEWARD_HOST`, `STEWARD_ACME_EMAIL`, the EULA, the bot
   token, the Discord login's client id and secret, the guild, the admin role, and optionally bunq),
   generates the secrets, waits until `STEWARD_HOST` resolves to this host so Caddy's certificate
   request succeeds, pulls `steward-agent` at the newest release and brings the stack up. A second run asks only for
   what is missing. On a host with an existing `postgres-data` it asks for `POSTGRES_PASSWORD`
   instead of generating one, since Postgres reads it only on an empty data directory. Each service
   logs in as a database role of its own, `POSTGRES_<SERVICE>_PASSWORD`; steward-agent creates the
   roles and sets those passwords at every start, so a new one needs only a restart.
   Once per installation it also asks whether steward-agent may download Minecraft's client from
   Mojang to draw the item icons in Steward's pickers (`NORDTAL_MOJANG_ASSETS`). The jar is deleted
   once drawn and only the icons are kept, in the database; on no the pickers show names alone.

4. **Upload the hand-built worlds**; see [Getting a world into a volume](#getting-a-world-into-a-volume).
5. **Join with a real client.** Nothing on the host proves the login path works.

Afterwards the script is `./nordtal.sh` in the installation directory:

```bash
./nordtal.sh                # the menu: what is set, change one, then deploy
./nordtal.sh --deploy       # ask only for what is missing, then deploy
./nordtal.sh --check        # every check, and change nothing
./nordtal.sh --from f       # take the answers from a file
```

Every run first asks GitHub for the newest release and runs that release's own copy of the script,
fetched from its tag and never from `main`, so the installer is never ahead of the images it
installs; `NORDTAL_RELEASE=<version>` names another release for an install. Where a healthy
steward-agent already runs, a deploy is an ordinary update run: the script writes the request and
waits for the report, exactly like `./nordtal.sh update`. Only an install, or a stack whose agent
is down or unhealthy, gets a throwaway agent's `up`, which is the emergency repair.

**The environment file** lives at the absolute path `STEWARD_ENV_FILE`, mode 600, outside the
installation directory, and holds every secret. Edit it in place (`sed -i`); never `mv` a new file
over it, because an older agent image binds the file itself and would keep the old inode.

**Every hand-typed `docker compose` needs `--env-file` and the release.** Without the file Compose
interpolates empty strings, which fails on `${X:?}` and silently changes everything else. The release
is never in the file: it is the tag of the steward-agent that runs, which passes it on itself.

```bash
export NORDTAL_RELEASE="$(docker ps --filter label=com.docker.compose.service=steward-agent --format '{{.Image}}' | sed 's/.*://')"
docker compose --env-file /etc/nordtal/season-2.env ps
```

The `migrate` service applies the schema and exits; every service with a database login waits for
it to succeed. On every start steward-agent then fills every empty volume and only then writes the
readiness marker the Minecraft services wait for. It never upgrades anything that is installed.
With `bootstrap: false` in its `runs` group the servers refuse to start until an update run
(`./nordtal.sh update`) has installed them.

Every image of ours is tagged with the release steward-agent runs; only the plugins and Paper and
Velocity are files in volumes. There is no rollback: a bad release is fixed by a better one.

## First-start seeding

The entrypoint writes these only when they are missing, unless noted:

- proxy `velocity.toml`: forwarding mode, `bind`, `online-mode`, `[servers]`, `try` and an empty
  `[forced-hosts]` (Velocity's default table names servers that do not exist and fails the start).
- proxy `forwarding.secret`: every start, from `VELOCITY_FORWARDING_SECRET`.
- proxy `accepts-transfers = true` under `[advanced]`: every start, since a proxy swap needs it.
- each backend's `config/paper-global.yml`: `proxies.velocity.enabled: true`.
- each backend's `server.properties`: `online-mode=false` every start; `level-name=$LEVEL_NAME` once;
  `level-seed=$LEVEL_SEED` while the world has no `level.dat`.

**A `level-name` that disagrees with the volume stops the container**, because Paper would start a
second, empty world beside the real one. Paper's default `world` is the exception: it is replaced,
unless somebody has played in it.

## Who limits the players

The network's `players` setting, edited in Steward, is what the server browser shows and what the
proxy enforces at login, and nothing else does: a backend lets in whoever the proxy sends, whatever
its own `max-players` says. Admins (`discord_user.admin`) pass a full network. A change applies
without a restart.

The MOTD is the proxy bundle's `motd` section, one message per phase, which an admin overrides on
Steward's messages page like any other text, with the values it lists. It applies without a restart too.

## The forwarding secret

All four servers need the same `VELOCITY_FORWARDING_SECRET`; a mismatch shows as every login failing
with _"Unable to connect you to the backend server"_. `nordtal.sh` generates it. To rotate it, set
a new value (`openssl rand -hex 24`) and also edit it in each backend's `config/paper-global.yml`,
where Paper stored its copy.

## Getting a world into a volume

```bash
docker compose stop hunger-games
docker cp ./world-hunger-games/. nordtal-s2-hunger-games-1:/data/hunger_games/
docker compose start hunger-games
```

The folder is the service's `LEVEL_NAME`, which must equal the plugin's `world-name`;
`ComposeWorldTest` holds that. Always stop the server first.

## The console

The server runs in tmux, because `docker exec` cannot reach PID 1's stdin:

```bash
console            # attach; detach with Ctrl-b then d. Ctrl-C goes to the server.
mc <command>       # send one command; the output goes to the container log
```

## Updating

`/update` in the admin channel or in game reports what is newer; **Update now** runs it after a
confirmation and a 30 second countdown. Players on a server about to stop are moved to `limbo`.
From the host, when neither is reachable:

```bash
./nordtal.sh update                # the whole network, and wait for the report
./nordtal.sh update --restart      # restart everything, install nothing
./nordtal.sh update --backup       # one backup run
./nordtal.sh update --down smp     # stop one service and hold it down
./nordtal.sh update --start        # release every hold, or one service
./nordtal.sh update --in 10        # count down ten minutes first
./nordtal.sh update --no-wait      # print the request id and return
```

`./nordtal.sh update` asks steward-agent through `docker exec`, so a request from the host passes
the same refusals as a button. Each request is a row in `steward_inbox`, the run inbox; the report
is written to its `outcome` column. One run is open at a time: the database refuses a second request
while one is pending or running.

A run downloads everything into a staging directory first, stops the affected servers, migrates,
moves the jars, recreates any container whose image the registry has moved past, and starts exactly
what it stopped. A server moves together or not at all. A service that refuses to stop means
nothing is installed. Checksums are verified where the source has one; GitHub release assets carry
none. The server jar is the newest `STABLE` build of `SERVER_VERSION` in `compose.yml`.

A run renews `postgres` and `caddy` like any other service, named in the countdown first.
steward-agent never recreates itself: an update to a newer release, or one that finds the agent out of
date, is carried out by a one-shot steward-agent at that release, which renews the agent last. Until
the one-shot settles the run, the agent stays up and answers, and claims nothing. The one-shot's log
outlives its container in `steward-backups/runs/<id>.log`.

### Replacing one service, from this checkout

A development loop, not a delivery: a locally built image stands until the next `pull`.

```bash
sh gradlew :steward-agent:build
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f ./compose.yml build steward-agent
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f ./compose.yml up -d --no-deps steward-agent
```

`--no-deps` keeps it to one service. `steward` and `discord-bot` run the jar from their jar
volume, not the one in the image, so for them copy the built jar into that volume and restart. Check
which jar runs with `docker exec <container> cat /proc/1/cmdline | tr '\0' ' '`.

## Locally

The same `compose.yml` and images, with `deploy/dev.env` and jars from `build/libs`:

`dev` is the Java program in `:dev`; it needs Java and Docker on any operating system. Each command
is a run configuration in IntelliJ's `dev:` folders, or `./gradlew -q :dev:run --args="<command>"`:

```text
dev init          # write deploy/dev.env, generate the secrets, make the directories
dev up            # build the jars and images, then start the stack
dev deploy smp    # rebuild :smp, replace the jar, restart that container
dev help          # every command
```

Then join `localhost`. After editing `deploy/dev.env` run `dev up`: `deploy` restarts the old
container with its old environment. `console` sends typed lines to a server from IntelliJ's Run
window; in a terminal, `docker compose --env-file deploy/dev.env exec smp console` attaches instead.

The local stack differs in: no `bot` profile by default, images built on a `:dev` tag, plugin
folders as bind mounts under `deploy/servers/`, and small heaps. It has no hand-built world and no
Discord guild.

### The interface

```text
dev ui
```

starts the stack plus steward and steward-agent, then Vite in the foreground on
http://localhost:5173, which proxies `/api` and `/auth` to `127.0.0.1:8080`. Stopping it stops only
Vite; `dev stop` stops the rest. Node is downloaded by Gradle under `steward/build/nodejs/`.
To run the Java half from Gradle (`:steward:run`), stop the container first, since both want
`:8080`. `STEWARD_PUBLIC_URL` must be the browser's address, or Discord and WebAuthn sign-in fail.

### The resource pack

```text
dev pack
```

builds the pack, serves it through the `devpack` profile on `PACK_PORT`, and sets the proxy's pack
to its URL and SHA-1. Rerun it after any change under `resource-pack/src/`; a
`FAILED_DOWNLOAD` in the client is almost always a stale hash. To draw the pack, install it straight
into a Minecraft instance instead; see [resource-pack/README.md](../resource-pack/README.md).

## Stopping

```bash
docker compose stop smp          # graceful, up to the 180 s grace period
docker compose down              # the whole stack; volumes survive
```

Never `docker kill`. `up` and `down` must use the same profile selection, or `down` leaves services
running.

### The standby services

`proxy-standby` and `limbo-standby` hold the network while a proxy or limbo is replaced. They are in
the `standby` profile only and never belong in `COMPOSE_PROFILES`:

```bash
docker compose --profile standby up -d proxy-standby
docker compose --profile standby down proxy-standby
```

steward-agent copies the live service's `plugins/` to them after every update. Both proxies sit
behind `caddy`, which also carries voice chat's UDP to whichever proxy answers.

## Backups

Everything that cannot be rebuilt is in PostgreSQL and the world volume, and steward-agent backs
up both. The nightly run is `steward#backup.at`; `/backup now` asks for one. A run counts down,
dumps the database with `pg_dump` inside the postgres container (nothing stops for it), stops the
configured services, tars each volume through `zstd`, applies retention and starts everything again.
Every archive is read back before it loses its `.partial` suffix.

compose.yml is the backup set: every volume mounted under steward-agent's `/backup-sources` is saved,
and every service labelled `eu.nordtal.backup: stop` is stopped while it is. The set must include
`mc-smp` and must never include `postgres-data`, since a copy of a running data directory is torn;
`TopologyDeploymentTest` holds both. Retention defaults to 14 days, then 8 weeks, then 6 months. There is no
offsite copy yet, so every archive shares a disk with its source.

A `<archive>.unverified` file means a service's stop could not be confirmed; the archive is readable,
but the moment it was taken is uncertain.

### Restoring

A restore is a run, asked for on Steward's Backups page by choosing the archive and typing what it
replaces: the volume's name, or `nordtal` for a dump. The run saves what the archive replaces first,
counts down, stops what runs on it and starts it again. A volume archive **replaces** the volume; a
dump **replaces** the database in one transaction, and the run then runs the `migrate` service to
bring it to this release's schema. Every process's settings are rows in that database, so they come back with the dump.

`deploy/restore.sh` is for a host where steward-agent does not run:

```bash
sudo bash deploy/restore.sh --list                                  # what is on the disk
sudo bash deploy/restore.sh nordtal-s2_mc-smp-<stamp>.tar.zst       # a volume
sudo bash deploy/restore.sh nordtal-<stamp>.dump                    # the database
```

It stops what mounts the volume without a countdown, asks for the volume's name typed back, unpacks
and starts the services again. A dump goes into a new database `restore_<stamp>` beside the live
one; promoting it is up to you.

## Voice chat

Simple Voice Chat needs **UDP 25565** open next to TCP 25565 and no configuration. It is optional:
a player without the mod notices nothing, and no server requires it.

## Third-party plugins

- **DisplayTags and PacketEvents** are required on `smp`; steward-agent resolves both.
- **CoreProtect** is optional, with its own SQLite file.
- **Terralith and Dungeons and Taverns** are the season's terrain, fetched from `SMP_DATAPACK_URLS`
  before the first start; `smp` refuses to start without them.

Each server's plugins are its `eu.nordtal.plugins` label in `compose.yml`, `artifact[=jar prefix][?]`
per plugin. steward-agent reads the label and installs them; the entrypoint reads the same string as
`SERVER_PLUGINS` and refuses to start while a plugin without `?` is missing. Extra jars are allowed.

## Troubleshooting

| symptom                                                         | cause                                                                                                     |
| --------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| a log names a setting                                           | a stored value was refused; the message names the setting, and Steward shows it on the group              |
| `FATAL: set EULA=true`                                          | the image does not accept the EULA for you                                                                |
| every login fails with _"Unable to connect you to the backend"_ | the forwarding secret differs somewhere; see [above](#the-forwarding-secret)                              |
| Velocity exits with _"Your configuration is invalid"_           | `velocity.toml` names a server its `[servers]` does not define                                            |
| the proxy shows a "network misconfigured" screen                | the proxy could not start; its log says why                                                               |
| a backend answers _"Server full"_                               | the backend was not restarted after a limit change; see [Who limits the players](#who-limits-the-players) |
| everybody is refused with a countdown                           | the phase is `PRE_LAUNCH`; `/phase set PRE_EVENT` opens the network                                       |
| `docker rm -f` fails with _"did not receive an exit event"_     | see [below](#never-mirror-the-console-with-tmux-pipe-pane); only a daemon restart helps                   |

## Never mirror the console with `tmux pipe-pane`

`tmux pipe-pane … > /proc/1/fd/1` wedges the container: SIGTERM never reaches PID 1, and the
container survives SIGKILL until the Docker daemon restarts. The entrypoint pipes the pane into
`logs/console.log` instead and tails `logs/latest.log` to stdout. `:common`'s `EntrypointRulesTest`
holds this and two ordering rules: `remain-on-exit` is set globally before `new-session`, and
`pipe-pane` is attached in the same `tmux` call as `new-session`.
