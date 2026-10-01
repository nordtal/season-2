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

What the split bought was that the process on the internet held no Docker socket. That is given up
for now and comes back when the Docker, file and run code moves into `steward-agent`: steward then
asks the agent for all of it. Until then steward reads, stops and starts containers through the
socket, and only `steward-agent` creates one.

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
| what pack the proxy offers                           | `pack.yml` in the `proxy` volume                           |

Every repository is read through `/releases/latest`, which skips drafts and pre-releases. There is no
pin and no rollback: a bad release is corrected by publishing a better one.

## Rules

- `serve` is not a scheduler. It migrates at startup and then acts only on rows in its inbox, `worker_inbox`.
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
- **Containers.** It holds the Docker socket to read state, health and metrics, to stop and start
  containers and to write into the Minecraft consoles. It never creates a container; that is
  `steward-agent`'s.
- **Images.** It checks each service's image against the registry before a run, and reports an image
  it could not check as unchecked, never as current.
- **Metrics.** Every 30 seconds it writes host and container samples into `metric_sample`, and folds
  them into hourly means after 30 days (`docker.metrics` in `steward.yml`).
- **Backups.** `pg_dump` inside the postgres container, then the volumes as zstd tars read back once
  before the rename from `.partial`. The nightly clock only writes a request row. There is no offsite
  copy.
- **Payments.** It books them and holds no bank credential: every question to bunq goes to
  `steward-bunq` (`steward.yml#bunq`, its address and the token they share). At start it asks
  `steward-bunq` for the account for up to thirty seconds. When it answers, the poll starts and the
  bot learns whether payments are on; when it does not, the log says so at ERROR and payments stay
  off until the next start.

## Configuration

Three files in one volume, `steward-config`, each overridable from the environment; their defaults
are the real values.

| file           | environment                  | holds                                                                     |
| -------------- | ---------------------------- | ------------------------------------------------------------------------- |
| `steward.yml`  | `NORDTAL_STEWARD_*`          | sources, Docker, backups, the agent's and steward-bunq's addresses        |
| `web.yml`      | `NORDTAL_STEWARD_WEB_*`      | the port, the public address, Discord sign-in, WebAuthn, alerts, Web Push |
| `database.yml` | `NORDTAL_STEWARD_DATABASE_*` | the connection, as for every process                                      |

`StewardSettings` loads and checks all three before the database is opened, so a wrong address or
port stops the start rather than the first sign-in.

## Tests

`./gradlew :steward:test` needs no network. Fixtures in `src/test/resources/fixtures/` were
recorded from the live GitHub, Modrinth and PaperMC APIs, and `TopologyTest` reads the real
`compose.yml`.

## Output

The report goes to stdout and every log line to stderr, which is where the one
`deploy/jvm/logback.xml` every JVM service shares writes them.
