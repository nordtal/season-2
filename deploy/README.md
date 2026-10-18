# deploy

Season 2 runs as one `docker compose` stack on one host, started from the repository root. The
project overview is [`../README.md`](../README.md).

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

`steward-agent` is the one service that creates containers, and its image contains `compose.yml`
(see [`../steward-agent/README.md`](../steward-agent/README.md)). `steward` mounts no Docker socket.

## First deployment

A deploy pulls images and never builds them. [`release.yml`](../.github/workflows/release.yml) pushes
the images `minecraft`, `steward`, `steward-agent`, `steward-bunq`, `discord-bot` and `caddy` to
`ghcr.io/nordtal` when a release is published.

1. Publish a release and wait until `release.yml` has finished.
2. Set all six packages to Public. A pull of a private package fails with `denied`.
3. Run the installer in the installation directory. Every volume is a folder in that directory.

   ```bash
   mkdir -p /srv/nordtal && cd /srv/nordtal
   curl -fsSL https://raw.githubusercontent.com/nordtal/season-2/main/deploy/nordtal.sh | bash
   ```

4. Upload the hand-built worlds (see [Limits, secret, worlds, console](#limits-secret-worlds-console)).
5. Join with a real client. Nothing on the host tests the login path.

The installer asks for the values that only a person knows. These are `STEWARD_HOST`,
`STEWARD_ACME_EMAIL`, the EULA, the bot token, the client id and secret of the Discord login and the
guild, and optionally bunq and `NORDTAL_MOJANG_ASSETS`. The installer generates the secrets and waits
until `STEWARD_HOST` resolves to this host. Then it pulls `steward-agent` at the newest release and
starts the stack. A second run asks only for what is missing. With an existing `postgres-data` it
asks for `POSTGRES_PASSWORD`. Each service logs in as a database role of its own, with the password
`POSTGRES_<SERVICE>_PASSWORD`. `steward-agent` sets these passwords at every start.

After the first run, the installer is `./nordtal.sh` in the installation directory:

```bash
./nordtal.sh                # the menu: show the settings, change one, then deploy
./nordtal.sh --deploy       # ask only for what is missing, then deploy
./nordtal.sh --check        # run every check and change nothing
./nordtal.sh --from f       # read the answers from a file
```

Every run executes the copy of the script that belongs to the newest release and is fetched from its
tag. `NORDTAL_RELEASE=<version>` names another release. When a healthy `steward-agent` runs, a deploy
is an ordinary update run. Otherwise a temporary agent container runs `up` and repairs the stack.

**Users:** every container except `steward-agent` runs as the account that its image creates. The
container has no capability and sets `no-new-privileges`. The Minecraft image uses `minecraft` (10000).
`discord-bot`, `steward` and `steward-bunq` use accounts of their own (10001 to 10003). The `user:`
entries of `compose.yml` name the same uids. Before `steward-agent` starts or creates such a
container, it gives everything that the service mounts to that uid. It does this in a temporary
container of the service's own image (`find … -exec chown`, as root with only `CHOWN` and
`DAC_READ_SEARCH`). Files that are already owned by the uid stay unchanged. A hand-typed
`docker compose up` skips this step, and the first start of a volume owned by root fails with
`Permission denied` in the container log.

**The environment file:** `STEWARD_ENV_FILE` names it. It has mode 600, lies outside the
installation directory and holds every secret that compose passes to a service. `steward-agent`
mounts its directory, so the next compose command reads every edit. The next update run recreates the
services whose values changed.

**A secret that only one service reads:** it is in `secrets.env` of that service. The file lies in
the service's directory of `NORDTAL_SECRETS_DIR` (`/etc/nordtal-secrets/season-2/<service>/`), has
mode 600 and belongs to the uid of the service. Compose mounts the directory read-only at
`/app/secrets` into that service alone and never interpolates its values.
[`jvm/secrets.sh`](jvm/secrets.sh) exports them before the JVM starts. `steward-agent` mounts only
its own directory, so it reads neither the bank key nor the bot token:

| service         | secrets of the service (the name in the environment file, where it differs)                                                                                                                                     |
| --------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `steward-bunq`  | `NORDTAL_STEWARD_BUNQ_API_KEY`, `NORDTAL_STEWARD_BUNQ_ACCOUNT_ID`                                                                                                                                               |
| `discord-bot`   | `NORDTAL_BOT_TOKEN`                                                                                                                                                                                             |
| `steward`       | `NORDTAL_STEWARD_WEB_DISCORD_CLIENT_SECRET` (`STEWARD_DISCORD_CLIENT_SECRET`), `NORDTAL_STEWARD_WEB_WEB_PUSH_PRIVATE_KEY` (`STEWARD_WEB_PUSH_PRIVATE_KEY`), `NORDTAL_STEWARD_WEB_TOTP_KEY` (`STEWARD_TOTP_KEY`) |
| `steward-agent` | `NORDTAL_STEWARD_AGENT_OFFSITE_PASSWORD` (`STEWARD_OFFSITE_PASSWORD`)                                                                                                                                           |

`nordtal.sh` asks for these values and writes them into the service's file. Every run (the menu,
`--deploy` and `update`) moves such a line out of the environment file. A line that someone puts back
by hand counts as newer. If the running `steward-agent` is of a release whose `compose.yml` still
reads the values from the environment file, the run only copies them. `update` moves them after the
run has recreated the agent. `--check` fails while a line is still in the environment file. Compose
does not see the content of these files, so a change gives the service a new
`SECRETS_REVISION_<SERVICE>` in the environment file, and the next run recreates the service.

**A hand-typed `docker compose`:** it needs `--env-file` and the release. Without the file, Compose
interpolates empty strings. The release is the tag of the running `steward-agent`:

```bash
export NORDTAL_RELEASE="$(docker ps --filter label=com.docker.compose.service=steward-agent --format '{{.Image}}' | sed 's/.*://')"
docker compose --env-file /etc/nordtal/season-2.env ps
```

The `migrate` service applies the schema and exits, and every service with a database login waits for
it. After that, `steward-agent` fills every empty volume and writes the readiness marker that the
Minecraft services wait for. With `bootstrap: false` in its `runs` group, the servers refuse to start
until an update run has installed them. Every image of ours has the tag of the running release. The
plugins, Paper and Velocity are files in volumes.

## First-start seeding

The entrypoint writes these values only when they are missing, unless the item says otherwise:

- Proxy `velocity.toml`: forwarding mode, `bind`, `online-mode`, `[servers]`, `try` and an empty `[forced-hosts]`.
- Proxy `forwarding.secret` and `accepts-transfers = true` under `[advanced]`: written at every start.
- `config/paper-global.yml` of each backend: `proxies.velocity.enabled: true`.
- `config/paper-world-defaults.yml` of each backend: `locate-structures-outside-world-border: true`
  at every start, so that an eye of ender finds a stronghold beyond the border.
- `server.properties` of each backend: `online-mode=false` and `white-list=false` at every start,
  `level-name=$LEVEL_NAME` once, and `level-seed=$LEVEL_SEED` while the world has no `level.dat`.

A `level-name` that differs from the volume stops the container. Paper's default world `world` is
replaced unless somebody has played in it.

## Limits, secret, worlds, console

- **Players:** the network's `players` setting limits the players. An admin edits it in Steward. The
  server list shows it, and the proxy enforces it at login. A backend admits every player that the
  proxy sends. Admins can join a full network. A change applies without a restart. The MOTD is the
  `motd` section of the proxy bundle. The messages page of Steward overrides it.
- **Forwarding secret:** all four servers share `VELOCITY_FORWARDING_SECRET`. A mismatch shows as
  _"Unable to connect you to the backend server"_. To rotate the secret, set a new value
  (`openssl rand -hex 24`) and edit it in `config/paper-global.yml` of each backend too.

A world goes into a volume while its server is stopped. The target folder is the `LEVEL_NAME` of the
service, which `ComposeWorldTest` holds equal to the `world-name` of the plugin:

```bash
docker compose stop hunger-games
docker cp ./world-hunger-games/. nordtal-s2-hunger-games-1:/data/hunger_games/
docker compose start hunger-games
```

The server runs in tmux. `docker exec` reaches it with two scripts:

```bash
console            # attach; detach with Ctrl-b then d. Ctrl-C goes to the server.
mc <command>       # send one command; the output goes to the container log
```

`tmux pipe-pane … > /proc/1/fd/1` blocks the container, and SIGTERM never reaches PID 1. The
entrypoint therefore pipes the pane into `logs/console.log` and tails `logs/latest.log` to stdout.
`EntrypointRulesTest` checks this and the order of `remain-on-exit` and `pipe-pane`.

A plugin that refuses the start writes its reason to `refused-start` in the data folder before the
server stops. The entrypoint then prints `[nordtal] REFUSED: <reason>` and holds the container
running and unhealthy, because Docker's restart policy would start it into the same refusal again. An
update run reads that line and fails the service with the reason at once. The next start of the
container removes the file. Steward tries a change to the settings of `smp` or `hunger-games` on the
running server before it saves it, and refuses a change the server would not start with.

## Updating

`/update` in the admin channel or in game reports what is newer. **Update now** runs the update after
a confirmation and a 30 second countdown. During the countdown the players of a server that is about
to stop move to `limbo`. From the host:

```bash
./nordtal.sh update                # update the whole network and wait for the report
./nordtal.sh update --restart      # restart everything, install nothing
./nordtal.sh update --backup       # run one backup
./nordtal.sh update --down smp     # stop one service and keep it down
./nordtal.sh update --start        # release every hold, or the hold of one service
./nordtal.sh update --in 10        # count down ten minutes first
./nordtal.sh update --no-wait      # print the request id and return
./nordtal.sh update --replace-local  # replace builds made on this host
```

An update stops before it changes anything when it would replace an image built on this host or a
plugin jar built outside a release. It names those services, and Steward marks them as local builds.
In a terminal, the command asks whether to go on. Without a terminal, it says to ask again with
`--replace-local`. The update dialog of Steward lists the services and asks the same question.

The command asks `steward-agent` through `docker exec`, so it meets the same refusals as a button.
Each request is a row in `steward_inbox`, and the `outcome` of the row is the report. One run is open
at a time. Before it asks, an installed `nordtal.sh` replaces itself with the copy of the newest
release and runs that copy. The new copy knows the flags of that release and generates the secrets
that the release adds. After an update that ends `DONE`, `nordtal.sh` is the copy of the release that
the run installed. It reads the version from the image of `steward-agent`.

A run downloads everything into a staging directory and stops the affected servers. It migrates the
database, moves the jars and recreates every container whose image the registry has replaced. Then it
starts the services it stopped. A server moves together with its jars or not at all. If a service
refuses to stop, the run installs nothing. The server jar is the newest `STABLE` build of
`SERVER_VERSION`. `postgres` and `caddy` are recreated like any service, and the countdown names them.
Recreating `caddy` disconnects every player. Its image is tagged with a hash of `deploy/caddy`, not with
the release, so a run recreates it only when that directory changed.

`steward-agent` never recreates itself. A temporary `steward-agent` container of the new release runs
an update to that release and recreates the agent last. Its log is `steward-backups/runs/<id>.log`.
While the agent is gone, the command keeps asking for 300 seconds, which is the start period of the
agent's health check. After that it gives up. A bad release is fixed by a newer release.

A replacement of one service from a checkout is a local build. It stays until the next `pull`:

```bash
sh gradlew :steward-agent:build
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f ./compose.yml build steward-agent
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f ./compose.yml up -d --no-deps steward-agent
```

Steward shows a container as outdated when the `docker compose` of the host has recreated it. The
config hash comes from another Compose version than the agent's. After a build, recreate the service
through the agent instead:
`docker exec <project>-steward-agent-1 steward-agent request recreate <service>`. To recreate
`steward-agent` itself, run `up --detach --no-deps --force-recreate steward-agent` in a temporary
container of its own image (`docker run --rm --entrypoint docker`, with the mounts and variables that
`nordtal.sh` gives its setup container, then `compose --project-directory /app --file /app/compose.yml
--env-file /app/env/<file>`).

`docker exec <container> cat /proc/1/cmdline | tr '\0' ' '` shows which jar runs.

## Locally

The local stack uses the same `compose.yml` and images, with `deploy/dev.env` and the jars of this
checkout. `dev` is the Java program of `:dev`. Each command is a run configuration in the `dev:`
folders of IntelliJ, or `./gradlew -q :dev:run --args="<command>"`:

```text
dev init          # write deploy/dev.env, generate the secrets, create the directories
dev up            # build the jars and images, then start the stack
dev deploy smp    # rebuild :smp, replace the jar, restart that container
dev ui            # start the stack, steward and steward-agent, then Vite on http://localhost:5173
dev pack          # build the pack, serve it through the devpack profile, point the proxy at it
dev help          # list every command
```

Then join `localhost`. After an edit of `deploy/dev.env`, run `dev up`, because `deploy` restarts a
container with its old environment. The local stack differs from the live stack in these points:
it has no `bot` profile by default, its images have the tag `:dev`, its plugin folders are bind mounts
under `deploy/servers/`, its heaps are small, and it has no hand-built world and no Discord guild.
A started server owns its plugin folder, so `up` and `deploy` copy a jar in through a temporary
container of the Minecraft image that runs as root. A developer who is not root on Linux can
replace the jar this way.

- `dev ui` stops only Vite when it is interrupted, and `dev stop` stops the rest. Vite proxies `/api`
  and `/auth` to `127.0.0.1:8080`, so `:steward:run` needs the container to be stopped. Gradle
  provides Node under `steward/build/nodejs/`. `STEWARD_PUBLIC_URL` is the address of the browser.
  With another address, the sign-in through Discord and WebAuthn fails.
- `dev pack` must run again after every change under `resource-pack/src/`. `FAILED_DOWNLOAD` in the
  client means a stale hash. To draw the pack, install it into a client (see
  [resource-pack/README.md](../resource-pack/README.md)).
- `console` types into a server from the Run window of IntelliJ. In a terminal,
  `docker compose --env-file deploy/dev.env exec smp console` attaches.

## Stopping and the standbys

```bash
docker compose stop smp          # stop gracefully, within the 180 s grace period
docker compose down              # stop the whole stack; the volumes stay
```

`up` and `down` need the same profile selection. Otherwise `down` leaves services running. Never run
`docker kill`.

`proxy-standby` and `limbo-standby` serve the network while a proxy or a limbo is replaced. They are
in the `standby` profile only and never in `COMPOSE_PROFILES`. After every update, `steward-agent`
copies the `plugins/` folder of the live service to them. `caddy` forwards the UDP traffic of voice
chat to whichever proxy answers. The standby sends its players back when the live proxy itself
answers on `network#live-address` (`proxy:25565`). The public address cannot tell, because Caddy
forwards it to the standby meanwhile:

```bash
docker compose --profile standby up -d proxy-standby
docker compose --profile standby down proxy-standby
```

## Backups

PostgreSQL and the two world volumes hold everything that cannot be rebuilt. The setting
`steward#backup.at` sets the nightly run, and `/backup now` asks for one. A run counts down and dumps
the database with `pg_dump` inside the postgres container. Then it stops the configured services,
packs each volume with `tar` and `zstd`, applies the retention and starts everything again. The run
reads each archive back before the archive loses its `.partial` suffix. An `<archive>.unverified`
file records the run and the services whose stop it could not confirm.

`compose.yml` is the backup set. The run saves every volume that `steward-agent` mounts under
`/backup-sources` and stops every service with the label `eu.nordtal.backup: stop`. The set includes
`mc-smp` and `mc-hunger-games` and never `postgres-data`, and `TopologyDeploymentTest` checks both.
The retention is 14 days, 8 weeks and 6 months, within a disk budget of 30 percent of the filesystem.
Beyond the budget, the run deletes the oldest archive of the largest series, but never the newest
verified archive of a series. A backup that would leave less than 10 percent of the disk free is
refused before anything stops, and the failed run raises an alert. The `runs` settings
`backup.budget-percent` and `backup.keep-free-percent` hold both shares. An update run that changed
something finally removes the images that no container uses and the unused build cache.

**Offsite:** when everything runs again, the run copies the newest archive of every series with
restic into `STEWARD_OFFSITE_REPOSITORY`. That repository is a Storage Box over SFTP, encrypted with
`STEWARD_OFFSITE_PASSWORD`, and it keeps the same retention. `offsite-key` and `offsite-known-hosts`
lie beside the environment file. A failed copy fails the run. A copied archive gets a marker under
`steward-backups/offsite/`. The backup alert turns red when the newest copied archive is older than
the backup age. On a new host:

```bash
export RESTIC_REPOSITORY=sftp://u123456@u123456.your-storagebox.de:23/nordtal-s2 RESTIC_PASSWORD=...
restic -o sftp.args="-i offsite-key" snapshots
restic -o sftp.args="-i offsite-key" restore latest --target ./restored
```

**Restoring:** a restore is a run. An admin asks for it on the Backups page of Steward by choosing
the archive and typing the volume's name, or `nordtal` for a dump. The run saves what the archive
replaces, counts down, stops what runs on the target and starts it again. A volume archive replaces
the volume. A dump replaces the database in one transaction, and the run then runs `migrate` to the
schema of this release. The settings of every process are rows in that database, so they come back
with it. A 4 GiB smp world is offline for about 40 seconds after the countdown.

Where `steward-agent` does not run, `restore.sh` stops what mounts the volume, asks for the name of
the volume, unpacks the archive and starts the services. A dump goes into a new database
`restore_<stamp>` beside the live one:

```bash
sudo bash deploy/restore.sh --list                                  # list the archives on the disk
sudo bash deploy/restore.sh nordtal-s2_mc-smp-<stamp>.tar.zst       # restore a volume
sudo bash deploy/restore.sh nordtal-<stamp>.dump                    # restore the database
```

## Watching from outside

Every alert runs inside the stack. An uptime service outside the host therefore checks three targets
every minute from four locations and posts to a webhook in the admin channel:

| check            | target                                                            | down means                                                  |
| ---------------- | ----------------------------------------------------------------- | ----------------------------------------------------------- |
| Steward          | `https://<STEWARD_HOST>/api/health`, 200                          | Steward, Caddy, PostgreSQL or steward-agent does not answer |
| game port        | TCP `<NETWORK_PUBLIC_ADDRESS>`                                    | the host, Docker or Caddy is down                           |
| game, end to end | `https://api.mcsrvstat.us/simple/<address>`, 200, every 5 minutes | no proxy answers a status ping                              |

When all three fail, the host is down. Reach it through the console of the provider, or set the
stack up again on a new host from the backups.

## The firewall

The nftables table `inet nordtal` is the host's filter. It also filters the forward hook that every
published port passes. From outside it admits SSH, 80 and 443 (UDP too) and the game port, TCP and
UDP. The standby port is open only while `proxy-standby` runs. `standby-port.sh` follows the events
of Docker and keeps the `standby` set of the table in step.

```bash
install -m 644 deploy/firewall/nordtal.nft /etc/nordtal/firewall.nft    # set its interface and SSH port first
install -m 755 deploy/firewall/standby-port.sh /usr/local/sbin/nordtal-standby-port
install -m 644 deploy/firewall/*.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now nordtal-firewall nordtal-standby-port
```

`systemctl restart nordtal-firewall` applies a changed file. To test a change, set a timed rollback
(`systemd-run --on-active=10min nft delete table inet nordtal`) and stop the timer after a new SSH
connection works. `nft list set inet nordtal standby` shows whether the port is open.

## Plugins

Simple Voice Chat needs UDP 25565 open next to TCP and no configuration. No server requires it.

- **PacketEvents:** `smp` requires it, and `steward-agent` resolves it.
- **CoreProtect:** it is optional and keeps its own SQLite file.
- **Terralith and Dungeons and Taverns:** the entrypoint fetches them from `SMP_DATAPACK_URLS` before
  the first start, and `smp` refuses to start without them. The entrypoint owns the `datapacks/`
  folder of the world. On start it removes every zip there that the variable does not name.

The label `eu.nordtal.plugins` of a server in `compose.yml` lists its plugins as
`artifact[=jar prefix][?]` per plugin. `steward-agent` installs them. The entrypoint reads the same
string as `SERVER_PLUGINS` and refuses to start while a plugin without `?` is missing.

## The season number

`season=` in `gradle.properties` is the one number. The compose project `nordtal-s<n>`, the
repository `nordtal/season-<n>` and the env file `/etc/nordtal/season-<n>.env` spell it. So do the
files that cannot read it at run time: `compose.yml`, `nordtal.sh`, `restore.sh`, `.env.example` and
the READMEs. The image names `ghcr.io/nordtal/<service>` carry no season, so release tags stay unique
across seasons.

```bash
sh gradlew forkSeason --to=3   # set season= and rewrite every other spelling in tracked files
sh gradlew checkSeasonNames    # part of check: fail on a spelling of another season
```

A running installation keeps its project name and therefore the names of its volumes. `forkSeason`
is for a repository forked into a new season, which starts on an empty host.

## Troubleshooting

| symptom                                                         | cause                                                                           |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| a log names a setting                                           | a stored value was refused; Steward shows the reason on the group               |
| the log ends with `[nordtal] REFUSED:`                          | the plugin refused the start; fix the cause, then restart the container         |
| `FATAL: set EULA=true`                                          | the image does not accept the EULA for you                                      |
| every login fails with _"Unable to connect you to the backend"_ | the forwarding secret differs somewhere                                         |
| Velocity exits with _"Your configuration is invalid"_           | `velocity.toml` names a server that its `[servers]` does not define             |
| the proxy shows a "network misconfigured" screen                | the proxy could not start; its log says why                                     |
| a backend answers _"Server full"_                               | the backend was not restarted after a limit change                              |
| everybody is refused with a countdown                           | the phase is `PRE_LAUNCH`; `/phase set PRE_EVENT` opens the network             |
| `docker rm -f` fails with _"did not receive an exit event"_     | a console is mirrored with `tmux pipe-pane`; only a Docker daemon restart helps |
