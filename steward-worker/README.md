# steward-worker

The part of Steward that owns the database schema and the version of everything the network runs.
It is the only process that runs Flyway, so every other service waits for it to become healthy.

```bash
docker compose up -d steward-worker               # serve: migrate, then wait for requests
docker compose run --rm steward-worker report     # resolve and report, changes nothing
docker compose run --rm steward-worker migrate    # apply the schema, nothing else
docker compose run --rm steward-worker bootstrap  # migrate, then fetch and place the files
```

With no argument it prints the read-only report. `docker compose run` falls through to the service's
own `command`, so always name the subcommand there.

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

- `serve` is not a scheduler. It migrates at startup and then acts only on rows in `update_request`.
- An update stops the services whose jars change, migrates, installs, starts them and waits for
  healthy. `bootstrap` fills empty slots and restarts nothing. A report writes nothing.
- Two workers cannot serve or move jars at once; both are advisory locks, and the second is refused.
- Artefacts are staged in `.nordtal-staging` inside the server's volume and move only when all are
  present. A server moves together or not at all.
- Nothing it does not account for is deleted, and only after the new jar is in place.
- A version not tagged for the platform is refused. "Skipped" is distinct from success and failure.

## What it does

- **Updates.** `/update` in Discord and in game writes a row; `serve` listens on `nordtal_update` and
  polls every fifteen seconds. A restart is due sixty seconds out, and the proxy counts players down.
- **Handover.** No process replaces the jar it runs, so an update that brings a newer worker places
  only that jar, returns the request to the inbox and exits. The new worker migrates and finishes the
  request, so a release's migrations are applied by that release.
- **Containers.** It holds the Docker socket to read state, health and metrics, to stop and start
  containers and to write into the Minecraft consoles. It never creates a container; that is
  `steward-deployer`'s.
- **Images.** It checks each service's image against the registry before a run, and reports an image
  it could not check as unchecked, never as current.
- **Metrics.** Every 30 seconds it writes host and container samples into `metric_sample`, and folds
  them into hourly means after 30 days (`docker.metrics` in `steward.yml`).
- **Backups.** `pg_dump` inside the postgres container, then the volumes as zstd tars read back once
  before the rename from `.partial`. The nightly clock only writes a request row. There is no offsite
  copy.
- **Payments.** It is the only container holding a bunq credential. `NORDTAL_STEWARD_BUNQ_API_KEY`
  and `NORDTAL_STEWARD_BUNQ_ACCOUNT_ID` are set together or not at all, and every start logs one line
  saying whether bunq is on. `bunq-context` is regenerated after a move, never copied.

## Configuration

`config/steward.yml`, overridable as `NORDTAL_STEWARD_*`. Its defaults are the real values.

## Tests

`./gradlew :steward-worker:test` needs no network. Fixtures in `src/test/resources/fixtures/` were
recorded from the live GitHub, Modrinth and PaperMC APIs, and `TopologyTest` reads the real
`compose.yml`.

## Output

The report goes to stdout and every log line to stderr, which is why this module has its own
`logback.xml`.
