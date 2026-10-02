# steward

Steward in one process: the web interface and its API, the database schema, the version of
everything the network runs, the runs that move it, payments, metrics and the nightly backup. It is
the only process that runs Flyway, so every other service waits for it to become healthy.

```bash
docker compose up -d steward               # serve: migrate, then the interface and the inbox
docker compose run --rm steward report     # resolve and report, changes nothing
docker compose run --rm steward migrate    # apply the schema, nothing else
docker compose run --rm steward bootstrap  # migrate, then fetch and place the files
docker exec nordtal-s2-steward-1 steward forget-factors <discord-id>   # a lost security key
docker run --rm ghcr.io/nordtal/steward generate-vapid-keys            # a Web Push keypair
```

With no argument it prints the read-only report. `docker compose run` falls through to the service's
own `command`, so always name the subcommand there.

## Why one process

The interface and the runs used to be two services, joined by an internal HTTP API with a shared
token, a proxy in the interface that forwarded every route to it, two configuration sets, two
database pools and two ways to follow a container's log. All of that existed only to carry calls
between two halves that serve the same admin. Now the stack routes (`api/Routes`) sit on the same
Javalin as the rest and pass the same gates: a read needs a signed-in admin with a key
(`KEY_HELD`), a change a fresh one (`KEY_FRESH`). The plugin "added by" comes from the session, and a
long log follow re-checks the session once a second, so a sign-out ends it.

The process on the internet holds no Docker socket. Everything Docker knows comes from
`steward-agent` through `AgentClient`: the containers and their last sample, logs, the console,
image drift, the host's numbers and the archives. The runs still write jars into the `/volumes`
mounts themselves.

It logs in as the database owner, because it migrates. The role `nordtal_steward` (`DatabaseRole.STEWARD`)
holds the grants the interface needs and is what steward logs in as once migrating is someone
else's job.

## Where a version comes from

| what                                                 | source                                                     |
| ---------------------------------------------------- | ---------------------------------------------------------- |
| the season-2 jars, the resource pack and its `.sha1` | GitHub releases, `nordtal/season-2`                        |
| DisplayTags                                          | GitHub releases, `nordtal/papermc-display-tags`            |
| PacketEvents                                         | Modrinth v2, filtered to the Minecraft version and `paper` |
| Paper, Velocity                                      | PaperMC Fill v3, newest `STABLE` build                     |
| what is installed                                    | the volumes under `volumes-root`                           |
| what pack the proxy offers                           | the proxy's `pack` settings, `url` and `sha1`              |

Every repository is read through `/releases/latest`, which skips drafts and pre-releases. There is no
pin and no rollback: a bad release is corrected by publishing a better one.

## Rules

- `serve` is not a scheduler. It migrates at startup and then acts only on rows in its inbox, `steward_inbox`.
- An update stops the services whose jars change, migrates, installs, starts them and waits for
  healthy. `bootstrap` fills empty slots and restarts nothing. A report writes nothing.
- Two steward processes cannot serve or move jars at once; both are advisory locks, and the second is
  refused.
- Artefacts are staged in `.nordtal-staging` inside the server's volume and move only when all are
  present. A server moves together or not at all.
- Nothing it does not account for is deleted, and only after the new jar is in place.
- A version not tagged for the platform is refused. "Skipped" is distinct from success and failure.

## What it does

- **Updates.** `/update` in Discord and in game writes a row; `serve` listens on `nordtal_update` and
  polls every fifteen seconds. A restart is due sixty seconds out, and the proxy counts players down.
- **Handover.** No process replaces the jar it runs, so an update that brings a newer steward places
  only that jar, returns the request to the inbox and exits. The new steward migrates and finishes the
  request, so a release's migrations are applied by that release.
- **Containers.** It asks `steward-agent` for state, health and the last sample, to stop and start
  containers and to write into the Minecraft consoles, naming who typed the line. It never touches
  the socket.
- **Images.** It checks each service's image against the registry before a run, and reports an image
  it could not check as unchecked, never as current.
- **Metrics.** Every 30 seconds it copies the agent's new sampler rounds into `metric_sample`, and
  folds them into hourly means after 30 days.
- **Backups.** It drives the run; `steward-agent` runs `pg_dump` inside the postgres container and
  writes the volumes as zstd tars, read back once before the rename from `.partial`. The nightly clock only writes a request row. There is no offsite
  copy.
- **Alerts.** Every alert is a row in `admin_alert`, raised by whoever saw it: steward measures the
  stack every 30 seconds against the `web` group's thresholds and raises a failed run; the bot raises
  what it could not do in Discord or with a payment. Steward routes each row once, to Web Push and to the admin channel through the
  bot's inbox, per alert type and per admin. The browser only displays `GET /api/alerts`.
- **Payments.** It books them and holds no bank credential: every question to bunq goes to
  `steward-bunq` (`steward#bunq`, its address and the token they share). At start it asks
  `steward-bunq` for the account for up to thirty seconds. When it answers, the poll starts and the
  bot learns whether payments are on; when it does not, the log says so at ERROR and payments stay
  off until the next start.

## Configuration

The connection and every secret come from the environment; everything else is three groups in the
database, `steward`, `web` and `alerts`, which Steward publishes at start and edits on its settings
pages.

| group      | environment                  | holds                                                             |
| ---------- | ---------------------------- | ----------------------------------------------------------------- |
| `steward`  | `NORDTAL_STEWARD_*`          | sources, backups, the agent's and steward-bunq's addresses        |
| `web`      | `NORDTAL_STEWARD_WEB_*`      | the port, the public address, Discord sign-in, WebAuthn, Web Push |
| `alerts`   | `NORDTAL_STEWARD_ALERTS_*`   | the thresholds steward's measured alerts fire on                  |
| `database` | `NORDTAL_STEWARD_DATABASE_*` | the connection, from the environment alone                        |

`StewardSettings` loads and checks them before the web starts, so a wrong address or port stops the
start rather than the first sign-in. A change to `steward` re-arms both clocks without a restart,
and one to `alerts` applies at the next reading. The first start finds the last installation's
`steward.yml` and `web.yml` in `steward-config`, imports what differs from the defaults and deletes
them.

## Tests

`./gradlew :steward:test` needs no network. Fixtures in `src/test/resources/fixtures/` were
recorded from the live GitHub, Modrinth and PaperMC APIs, and `TopologyTest` reads the real
`compose.yml`.

## Output

The report goes to stdout and every log line to stderr, which is where the one
`deploy/jvm/logback.xml` every JVM service shares writes them.
