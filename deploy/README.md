# deploy

Season 2's deployment: one `docker compose` stack on one host, started from the repository root.
The project overview is [`../README.md`](../README.md).

```
compose.yml            services in six profiles: db, bot, mc, steward, devpack (local), standby
.env.example           every setting, as a reference; nobody fills it in by hand
deploy/
  minecraft/           one image for all four Minecraft services
    entrypoint.sh      PID 1: resolve the jar, seed the config, run tmux, trap SIGTERM
    scripts/console    attach to the server console
    scripts/mc         send one console command, no TTY needed
  nordtal.sh           the host's installer and menu; runs the newest release's own copy
  firewall/            the host's nftables table and the service that opens the standby port
  restore.sh           put one archive back: a volume, or a dump into a new database
  dev.env.example      the local stack's settings
  *-test.sh            the scripts' checks, run on `check` without Docker
  servers/, pack/      not in git: the local stack's plugin folders and resource pack
```

`compose.yml` is baked into `steward-agent`'s image, the one service that creates containers; see
[`../steward-agent/README.md`](../steward-agent/README.md). `steward` holds no Docker socket.

## First deployment

A deploy pulls and never builds. The images (`minecraft`, `steward`, `steward-agent`, `steward-bunq`,
`discord-bot`, `caddy`) are pushed to `ghcr.io/nordtal` by [`release.yml`](../.github/workflows/release.yml)
when a release is published.

1. Publish a release and let `release.yml` finish.
2. Set all six packages to Public: a private package answers a pull with `denied`.
3. Run the installer in the installation directory; every volume is a folder in it.

   ```bash
   mkdir -p /srv/nordtal && cd /srv/nordtal
   curl -fsSL https://raw.githubusercontent.com/nordtal/season-2/main/deploy/nordtal.sh | bash
   ```

4. Upload the hand-built worlds, see [Limits, secret, worlds, console](#limits-secret-worlds-console).
5. Join with a real client; nothing on the host proves the login path.

The installer asks for what only a person knows (`STEWARD_HOST`, `STEWARD_ACME_EMAIL`, the EULA, the
bot token, the Discord login's client id and secret, the guild, optionally bunq and
`NORDTAL_MOJANG_ASSETS`), generates the secrets, waits until `STEWARD_HOST` resolves to this host,
pulls `steward-agent` at the newest release and brings the stack up. A second run asks only for what
is missing. With an existing `postgres-data` it asks for `POSTGRES_PASSWORD`. Each service logs in as
a database role of its own, `POSTGRES_<SERVICE>_PASSWORD`, which steward-agent sets at every start.

Afterwards the script is `./nordtal.sh` in the installation directory:

```bash
./nordtal.sh                # the menu: what is set, change one, then deploy
./nordtal.sh --deploy       # ask only for what is missing, then deploy
./nordtal.sh --check        # every check, and change nothing
./nordtal.sh --from f       # take the answers from a file
```

Every run executes the newest release's own copy of the script, fetched from its tag, so the
installer matches the images it installs; `NORDTAL_RELEASE=<version>` names another release. Where a
healthy steward-agent runs, a deploy is an ordinary update run; otherwise a throwaway agent's `up`
repairs the stack.

**The environment file** is at `STEWARD_ENV_FILE`, mode 600, outside the installation directory, and
holds every secret. steward-agent mounts its directory, so an edit by any means is read by the next
compose command, and the next update run renews what it changed.

**A hand-typed `docker compose` needs `--env-file` and the release**; without the file Compose
interpolates empty strings. The release is the tag of the running steward-agent:

```bash
export NORDTAL_RELEASE="$(docker ps --filter label=com.docker.compose.service=steward-agent --format '{{.Image}}' | sed 's/.*://')"
docker compose --env-file /etc/nordtal/season-2.env ps
```

The `migrate` service applies the schema and exits; every service with a database login waits for
it. steward-agent then fills every empty volume and writes the readiness marker the Minecraft
services wait for. With `bootstrap: false` in its `runs` group the servers refuse to start until an
update run has installed them. Every image of ours is tagged with the running release; the plugins,
Paper and Velocity are files in volumes.

## First-start seeding

The entrypoint writes these only when missing, unless noted:

- proxy `velocity.toml`: forwarding mode, `bind`, `online-mode`, `[servers]`, `try`, an empty `[forced-hosts]`.
- proxy `forwarding.secret` and `accepts-transfers = true` under `[advanced]`: every start.
- each backend's `config/paper-global.yml`: `proxies.velocity.enabled: true`.
- each backend's `server.properties`: `online-mode=false` every start; `level-name=$LEVEL_NAME` once;
  `level-seed=$LEVEL_SEED` while the world has no `level.dat`.

A `level-name` that disagrees with the volume stops the container, since Paper would start a second,
empty world. Paper's default `world` is replaced unless somebody has played in it.

## Limits, secret, worlds, console

- **Players.** The network's `players` setting, edited in Steward, is what the server browser shows
  and the proxy enforces at login; a backend lets in whoever the proxy sends. Admins pass a full
  network. A change applies without a restart. The MOTD is the proxy bundle's `motd` section,
  overridden on Steward's messages page.
- **Forwarding secret.** All four servers share `VELOCITY_FORWARDING_SECRET`; a mismatch shows as
  _"Unable to connect you to the backend server"_. To rotate it, set a new value (`openssl rand -hex 24`)
  and edit it in each backend's `config/paper-global.yml` too.

A world goes into a volume with its server stopped; the folder is the service's `LEVEL_NAME`, which
`ComposeWorldTest` holds equal to the plugin's `world-name`:

```bash
docker compose stop hunger-games
docker cp ./world-hunger-games/. nordtal-s2-hunger-games-1:/data/hunger_games/
docker compose start hunger-games
```

The server runs in tmux, so `docker exec` reaches it through two scripts:

```bash
console            # attach; detach with Ctrl-b then d. Ctrl-C goes to the server.
mc <command>       # send one command; the output goes to the container log
```

`tmux pipe-pane … > /proc/1/fd/1` wedges the container (SIGTERM never reaches PID 1), so the
entrypoint pipes the pane into `logs/console.log` and tails `logs/latest.log` to stdout.
`EntrypointRulesTest` holds that and the ordering of `remain-on-exit` and `pipe-pane`.

## Updating

`/update` in the admin channel or in game reports what is newer; **Update now** runs it after a
confirmation and a 30 second countdown, moving players on a server about to stop to `limbo`. From
the host:

```bash
./nordtal.sh update                # the whole network, and wait for the report
./nordtal.sh update --restart      # restart everything, install nothing
./nordtal.sh update --backup       # one backup run
./nordtal.sh update --down smp     # stop one service and hold it down
./nordtal.sh update --start        # release every hold, or one service
./nordtal.sh update --in 10        # count down ten minutes first
./nordtal.sh update --no-wait      # print the request id and return
```

The command asks steward-agent through `docker exec`, so it passes the same refusals as a button.
Each request is a row in `steward_inbox` whose `outcome` is the report; one run is open at a time.

A run downloads everything into a staging directory, stops the affected servers, migrates, moves the
jars, recreates every container whose image the registry has moved past and starts what it stopped.
A server moves together or not at all, and a service that refuses to stop means nothing is
installed. The server jar is the newest `STABLE` build of `SERVER_VERSION`. `postgres` and `caddy`
are renewed like any service, named in the countdown.

steward-agent never recreates itself: an update to a newer release is carried out by a one-shot
steward-agent at that release, which renews the agent last; its log is
`steward-backups/runs/<id>.log`. A bad release is fixed by a better one.

Replacing one service from a checkout is a local build that stands until the next `pull`:

```bash
sh gradlew :steward-agent:build
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f ./compose.yml build steward-agent
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f ./compose.yml up -d --no-deps steward-agent
```

`docker exec <container> cat /proc/1/cmdline | tr '\0' ' '` shows which jar runs.

## Locally

The same `compose.yml` and images, with `deploy/dev.env` and jars from this checkout. `dev` is the
Java program in `:dev`; each command is a run configuration in IntelliJ's `dev:` folders or
`./gradlew -q :dev:run --args="<command>"`:

```text
dev init          # write deploy/dev.env, generate the secrets, make the directories
dev up            # build the jars and images, then start the stack
dev deploy smp    # rebuild :smp, replace the jar, restart that container
dev ui            # stack plus steward and steward-agent, then Vite on http://localhost:5173
dev pack          # build the pack, serve it through the devpack profile, point the proxy at it
dev help          # every command
```

Then join `localhost`. After editing `deploy/dev.env` run `dev up`; `deploy` restarts a container with
its old environment. The local stack has no `bot` profile by default, images on a `:dev` tag, plugin
folders as bind mounts under `deploy/servers/`, small heaps, no hand-built world and no Discord guild.

- `dev ui` stops only Vite when interrupted; `dev stop` stops the rest. Vite proxies `/api` and
  `/auth` to `127.0.0.1:8080`, so `:steward:run` needs the container stopped. Node comes from Gradle
  under `steward/build/nodejs/`. `STEWARD_PUBLIC_URL` is the browser's address, or Discord and
  WebAuthn sign-in fail.
- `dev pack` is rerun after any change under `resource-pack/src/`; `FAILED_DOWNLOAD` in the client
  is a stale hash. To draw the pack, install it into a client, see
  [resource-pack/README.md](../resource-pack/README.md).
- `console` types into a server from IntelliJ's Run window; in a terminal,
  `docker compose --env-file deploy/dev.env exec smp console` attaches.

## Stopping and the standbys

```bash
docker compose stop smp          # graceful, up to the 180 s grace period
docker compose down              # the whole stack; volumes survive
```

`up` and `down` use the same profile selection, or `down` leaves services running. Never `docker kill`.

`proxy-standby` and `limbo-standby` hold the network while a proxy or limbo is replaced. They are in
the `standby` profile only, never in `COMPOSE_PROFILES`; steward-agent copies the live service's
`plugins/` to them after every update, and `caddy` carries voice chat's UDP to whichever proxy answers:

```bash
docker compose --profile standby up -d proxy-standby
docker compose --profile standby down proxy-standby
```

## Backups

Everything that cannot be rebuilt is in PostgreSQL and the two world volumes. The nightly run is
`steward#backup.at`; `/backup now` asks for one. A run counts down, dumps the database with `pg_dump`
inside the postgres container, stops the configured services, tars each volume through `zstd`,
applies retention and starts everything again. Each archive is read back before it loses its
`.partial` suffix; an `<archive>.unverified` file means a service's stop could not be confirmed.

`compose.yml` is the backup set: every volume mounted under steward-agent's `/backup-sources` is saved
and every service labelled `eu.nordtal.backup: stop` is stopped meanwhile. It includes `mc-smp` and
`mc-hunger-games` and never `postgres-data`; `TopologyDeploymentTest` holds both. Retention is 14
days, 8 weeks, 6 months, within a disk budget of 30 percent of the filesystem: past it the oldest
archive of the largest series goes, never a series' newest verified one. A backup that would leave
less than 10 percent of the disk free is refused before anything stops, and the failed run alerts.
Both shares are `runs` settings (`backup.budget-percent`, `backup.keep-free-percent`). An update run
that changed something removes the images no container uses and the unused build cache last.

**Offsite.** Once everything runs again, the newest archive of every series is copied with restic into
`STEWARD_OFFSITE_REPOSITORY` (a Storage Box over SFTP, encrypted with `STEWARD_OFFSITE_PASSWORD`),
which gets the same retention. `offsite-key` and `offsite-known-hosts` sit beside the environment
file. A failed copy fails the run, a copied archive is marked under `steward-backups/offsite/`, and the
backup alert is red when the newest copied archive is older than the backup age. On a new host:

```bash
export RESTIC_REPOSITORY=sftp://u123456@u123456.your-storagebox.de:23/nordtal-s2 RESTIC_PASSWORD=...
restic -o sftp.args="-i offsite-key" snapshots
restic -o sftp.args="-i offsite-key" restore latest --target ./restored
```

**Restoring** is a run, asked for on Steward's Backups page by choosing the archive and typing the
volume's name, or `nordtal` for a dump. The run saves what the archive replaces, counts down, stops
what runs on it and starts it again. A volume archive replaces the volume; a dump replaces the
database in one transaction and the run then runs `migrate` to this release's schema. Every
process's settings are rows in that database, so they come back with it. A 4 GiB smp world is
offline for about 40 seconds after the countdown.

Where steward-agent does not run, `restore.sh` stops what mounts the volume, asks for the volume's
name, unpacks and starts the services. A dump goes into a new database `restore_<stamp>` beside the
live one:

```bash
sudo bash deploy/restore.sh --list                                  # what is on the disk
sudo bash deploy/restore.sh nordtal-s2_mc-smp-<stamp>.tar.zst       # a volume
sudo bash deploy/restore.sh nordtal-<stamp>.dump                    # the database
```

## Watching from outside

Every alert runs inside the stack, so an uptime service outside the host checks three things every
minute from four locations and posts to a webhook in the admin channel:

| check            | target                                                            | down means                                                  |
| ---------------- | ----------------------------------------------------------------- | ----------------------------------------------------------- |
| Steward          | `https://<STEWARD_HOST>/api/health`, 200                          | Steward, Caddy, PostgreSQL or steward-agent does not answer |
| game port        | TCP `<NETWORK_PUBLIC_ADDRESS>`                                    | the host, Docker or Caddy is down                           |
| game, end to end | `https://api.mcsrvstat.us/simple/<address>`, 200, every 5 minutes | no proxy answers a status ping                              |

All three failing means the host is down: reach it through the provider's console, or set the stack
up again on a new host from the backups.

## The firewall

The host's filter is the nftables table `inet nordtal`, which also filters the forward hook every
published port passes. From outside it lets through SSH, 80 and 443 (UDP too) and the game port, TCP
and UDP. The standby port opens only while `proxy-standby` runs; `standby-port.sh` follows Docker's
events and keeps the table's `standby` set in step.

```bash
install -m 644 deploy/firewall/nordtal.nft /etc/nordtal/firewall.nft    # its interface and SSH port first
install -m 755 deploy/firewall/standby-port.sh /usr/local/sbin/nordtal-standby-port
install -m 644 deploy/firewall/*.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now nordtal-firewall nordtal-standby-port
```

A changed file is applied with `systemctl restart nordtal-firewall`; try it with a timed rollback
(`systemd-run --on-active=10min nft delete table inet nordtal`) and stop the timer once a new SSH
connection works. `nft list set inet nordtal standby` shows whether the port is open.

## Plugins

Simple Voice Chat needs UDP 25565 open next to TCP and no configuration; no server requires it.

- **PacketEvents** is required on `smp`; steward-agent resolves it.
- **CoreProtect** is optional, with its own SQLite file.
- **Terralith and Dungeons and Taverns** are fetched from `SMP_DATAPACK_URLS` before the first start;
  `smp` refuses to start without them.

Each server's plugins are its `eu.nordtal.plugins` label in `compose.yml`, `artifact[=jar prefix][?]`
per plugin. steward-agent installs them; the entrypoint reads the same string as `SERVER_PLUGINS` and
refuses to start while a plugin without `?` is missing.

## Troubleshooting

| symptom                                                         | cause                                                                        |
| --------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| a log names a setting                                           | a stored value was refused; Steward shows the reason on the group            |
| `FATAL: set EULA=true`                                          | the image does not accept the EULA for you                                   |
| every login fails with _"Unable to connect you to the backend"_ | the forwarding secret differs somewhere                                      |
| Velocity exits with _"Your configuration is invalid"_           | `velocity.toml` names a server its `[servers]` does not define               |
| the proxy shows a "network misconfigured" screen                | the proxy could not start; its log says why                                  |
| a backend answers _"Server full"_                               | the backend was not restarted after a limit change                           |
| everybody is refused with a countdown                           | the phase is `PRE_LAUNCH`; `/phase set PRE_EVENT` opens the network          |
| `docker rm -f` fails with _"did not receive an exit event"_     | a console mirrored with `tmux pipe-pane`; only a Docker daemon restart helps |
