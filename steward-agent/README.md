# steward-agent

The one door to Docker and to the volumes, and the process that carries out every run. It holds the
Docker socket and carries `compose.yml` inside its own image, so a change to the deployment is a new
image of this service, renewed by the run of that release. Its image is also
the `migrate` service, the only process that runs Flyway's `migrate`: it creates every role, applies
the schema and exits, and every service with a database login waits for it to succeed. `serve`
serves the API, installs whatever slot is still empty, reports ready and then claims runs from
`steward_inbox`; the Minecraft services wait for it to become healthy. `steward` mounts no socket and reaches all of it through
`:internal-api`'s `AgentClient`; `:architecture` refuses `java.net.UnixDomainSocketAddress` anywhere
else, and any class of this module inside `steward`.

## What it will not do

- **Recreate itself.** `steward-agent` is refused wherever a service name is accepted, since
  the new container would kill the process handling the request. A one-shot renews it.
- **Run another release.** An update to a newer release goes to a one-shot at that release.
- **Pull dependencies along.** Every `up` carries `--no-deps`.
- **Schedule.** A run happens only when a row in `steward_inbox` asks for one: a button in steward,
  `/update` in Discord or in game, a clock in steward, or `steward-agent request` on the host. The
  sampler is the one thing it does on its own clock, and it only reads.
- **Hand out secrets.** Nothing on the wire carries a container's environment or the env file.

## Run it

    steward-agent up                          # the setup script: pull, then up, wait, exit with the code
    steward-agent serve                       # the API, then runs (default in the container)
    steward-agent migrate                     # the migrate service: roles, schema, exit code
    steward-agent run ID                      # the one-shot: the run handed to it, then exit
    steward-agent request KIND [a,b] [MIN]    # ask for a run, as a button would; prints its id
    steward-agent status ID                   # the run's status, a tab and its report

The host reaches the last two through `docker exec <project>-steward-agent-1`, which is what
`./nordtal.sh update` does. They write and read the same row a button does, so the open run in the
inbox is the one lock for the host as well.

## Runs

Every kind is one sequence, `Run`: plan, open the standbys, count down, evacuate, wait until empty,
stop, carry out the payload, start, verify, close the standbys and settle. `Kinds` holds one planner
per kind; a plan that stops nothing (a `START`, or an update with nothing to do) counts nobody down.
The kinds are `UPDATE`, `RESTART`, `BACKUP`, `DOWN`, `START`, `RECREATE`, `DEPLOY`, `RESTORE` and
`REMOVE_PLUGIN`; a kind's payload is the request's, typed in `StewardRequest`.

A `RECREATE` makes the named containers again from the images on this host, a `DEPLOY` pulls first.
Caddy, pack-host and postgres are made again only once the rest is back, and only in a run that
counts down, since every server's connections go through them. A `REMOVE_PLUGIN` stops its one
server, deletes the added plugin's jar and data folder and starts it again.

A `RESTORE` puts one archive back. A volume archive stops what mounts the volume, saves the volume as
it is, then unpacks the archive into it. A dump is preceded by a fresh dump with everything running,
stops every service of ours that runs on the database, and replaces the `public` schema in one
transaction, so a failed restore leaves the database as it was. The run then runs the `migrate` service
against it, and the run's row, which the dump did not hold as it is now, is carried across; rows the dump
held open are failed. `deploy/restore.sh` remains for the host when steward-agent itself is down.

The run never stops steward-agent. A run that names it is refused.

## Another release

`serve` carries out runs of its own release only. An update whose newest release is later than the
agent, or that finds the agent's own container out of date, is handed to a one-shot:

1. `serve` writes the one-shot's name, `<project>-steward-agent-run`, into the row's `runner` column.
   The row stays `RUNNING` and is the lock: the inbox holds one open run.
2. It pulls `steward-agent` at that release, copies that image's `compose.yml` out and starts the
   one-shot from it with `compose run --rm`, so the one-shot is made exactly as that release defines
   the agent. Nothing has stopped yet.
3. The one-shot (`steward-agent run ID`) plans again at its release, counts down, stops what changes,
   runs `migrate` with those servers stopped, installs, starts and verifies.
4. Last it makes the long-running `steward-agent` again at its release and waits for it to be healthy,
   then settles the row and exits.

The new agent starts while the row is still open and leaves it alone, since its one-shot still runs.
A row whose one-shot is gone without settling it is failed at the next wake-up, which lets the lock go.
An older release is refused: no schema goes back.

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

- `serve` is not a scheduler. It acts only on rows in its inbox, `steward_inbox`, and never migrates.
- An update stops the services whose jars or containers change, runs `migrate`, installs, starts
  them and waits for healthy. `bootstrap` fills empty slots and restarts nothing. A report writes nothing.
- Two steward processes cannot serve or move jars at once; both are advisory locks, and the second is
  refused.
- Artefacts are staged in `.nordtal-staging` inside the server's volume and move only when all are
  present. A server moves together or not at all.
- Nothing it does not account for is deleted, and only after the new jar is in place.
- Every file a run or `bootstrap` moves into place is noted in `plugin_file` with the agent's own
  release, one row per server and artefact. Images carry their release in the tag; the plugins and
  Paper and Velocity are files, so this note is how Steward's plugins tab names the release that
  installed each jar. A jar copied in by hand has no note and shows none.
- A version not tagged for the platform is refused. "Skipped" is distinct from success and failure.

## The contract

The paths and records are `AgentWire`, the client is `AgentClient`; both live in `:internal-api`,
so steward and this service compile against one definition. `{service}` is a compose service name.

| Route                                             | Answer                                                                                    |
| ------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `GET /api/health`                                 | the only route without the token                                                          |
| `GET /api/topology`                               | `Topology`: every service in the baked file, its image and its labels' meaning            |
| `GET /api/containers`                             | `Containers`: every container of the project with the sampler's last `Reading`            |
| `GET /api/containers/{service}`                   | one `Container`, with the registry digests of its image; `404` when there is none         |
| `GET /api/images`                                 | `ImageResult`: each running image against its registry, slow on purpose                   |
| `GET /api/containers/{service}/logs`              | SSE: `line` and `run` events, the backlog first; `end` or `gone` when it stops            |
| `GET /api/containers/{service}/log-capacity?max=` | `LogCapacity`: how many lines the backlog can fill                                        |
| `POST /api/containers/{service}/console`          | `ConsoleLine` (`command`, `actor`); `202`, the answer lands in the log                    |
| `GET /api/host`                                   | `Host`: `/proc`, the root filesystem and Docker's disk use                                |
| `GET /api/volumes/{service}/disk`                 | `Disk`: `du` of that service's volume; `404` for one not mounted here                     |
| `GET /api/bundles`                                | `BundleRef`s: every message bundle a jar carries, before it is opened                     |
| `GET /api/bundles/{service}?module=`              | one `MessageBundle`, packaged text and overrides side by side                             |
| `POST /api/bundles/{service}?module=`             | `BundleChanges`; `SavedBundle` with the dropped placeholders, `400` for an undeclared one |
| `GET /api/samples?after=`                         | the sampler's `Round`s after an ISO instant, oldest first; all it holds without one       |
| `GET /api/backups`                                | `Archive`s, newest first                                                                  |
| `GET /api/backups/{name}`                         | one finished archive's bytes; `400` for a name that is not one, `404` when it is gone     |
| `GET /api/plan`                                   | what the next update would change, resolved now; changes nothing                          |
| `GET /api/plugins/{service}`                      | the managed plugins of one server                                                         |
| `GET /api/plugins/{service}/search?q=`            | Modrinth's answer for that server's platform                                              |
| `POST /api/plugins/{service}?by=`                 | adds a plugin to the list the next run installs; removing one is a `REMOVE_PLUGIN` run    |

A refusal is a `Refusal` (`error`, the sentence to show, and `where`): `502` with `where` `docker`
when the daemon failed, `502` with `compose` when a Compose command did, `400` with
`steward-agent` for a request it will not run. steward passes `where` through, so the interface
names the daemon and not the agent.

Every route but `/api/health` needs `X-Steward-Token`, and the service refuses to start without it.
The gate is `:internal-api`'s, the same one `steward-bunq` runs behind. steward reaches it on the
internal `agent` network. It reaches postgres on the internal `agent-database` network and GitHub,
Modrinth and PaperMC through `agent-egress`, a network nobody else is on.

The console set is the label `eu.nordtal.console: "true"` in `compose.yml`, read through
`docker compose config`; a line goes to `mc` as one argument, never through a shell, and the log
names who typed it. The backup set is the agent's own mounts under `/backup-sources`, and the stop
set the label `eu.nordtal.backup: stop`; `/api/topology` serves both, and steward keeps no list of
either.

The servers and the network page's picture are labels too, and nothing in Java or TypeScript mirrors
them:

| label                   | on                               | says                                                                         |
| ----------------------- | -------------------------------- | ---------------------------------------------------------------------------- |
| `eu.nordtal.server`     | the four Minecraft servers       | `paper` or `velocity`; the service has a plugins folder                      |
| `eu.nordtal.plugins`    | the same                         | `artifact[=jar prefix][?]` per plugin, `?` where it may be missing           |
| `eu.nordtal.standby-of` | `proxy-standby`, `limbo-standby` | the service it stands in for, whose `plugins/` it gets a copy of             |
| `eu.nordtal.renew`      | every service a run makes again  | `run` (stopped, renewed, started), `after` (once the rest is back) or `last` |
| `eu.nordtal.section`    | every service Steward draws      | the heading the network page groups it under; without it, it is not drawn    |
| `eu.nordtal.entry`      | `proxy`, `caddy`                 | `true`: players reach it from outside                                        |
| `eu.nordtal.reaches`    | `proxy`, `steward`, `caddy`      | the services it sends requests to, space separated                           |
| `eu.nordtal.stores-in`  | every service with a login       | the services it keeps its data in, space separated                           |

The Minecraft entrypoint reads `eu.nordtal.server` and `eu.nordtal.plugins` as `SERVER_KIND` and
`SERVER_PLUGINS`, YAML aliases of the labels, so the guard and the agent read one string. The sampler reads `docker stats` and `/proc` every 30 seconds and keeps the
last hour; steward copies what it took into `metric_sample`, asking after the newest round it
already holds, so a restart of either loses nothing.

A deployment pulls every image before it stops anything. A failed pull is tolerated only when the
image is already on the host.

The connection comes from the environment (`NORDTAL_STEWARD_AGENT_DATABASE_*`, with one
`..._<ROLE>_PASSWORD` per service role it creates before migrating); everything a run reads is the
`runs` group in the database, edited in Steward: the release sources, the volumes root, the timeouts,
the backup retention and how long a backup waits for a server to stop.

| Setting (`NORDTAL_STEWARD_AGENT_*`) | Default                | What                                                            |
| ----------------------------------- | ---------------------- | --------------------------------------------------------------- |
| `TOKEN`                             | none, required         | the secret steward sends                                        |
| `PORT`                              | `8081`                 |                                                                 |
| `DOCKER_SOCKET`                     | `/var/run/docker.sock` |                                                                 |
| `CONFIGS`                           | `/configs`             | each service's plugins folder, where the message overrides live |
| `VOLUMES_ROOT`                      | `/volumes`             | the `runs` group's `volumes-root`: what a run installs into     |
| `BACKUP_SOURCES`                    | `/backup-sources`      | one mount per volume a backup saves and a restore writes back   |
| `BACKUPS`                           | `/backups`             | the archives, the same volume postgres dumps into               |

## Tests

`./gradlew :steward-agent:test` needs no network. Fixtures in `src/test/resources/fixtures/` were
recorded from the live GitHub, Modrinth and PaperMC APIs, and `TopologyTest` reads the real
`compose.yml`.

## Building

    ./gradlew :steward-agent:build          # jar, and compose.yml staged into build/compose/
    docker build -f deploy/jvm/Dockerfile --build-arg MODULE=steward-agent -t ghcr.io/nordtal/steward-agent:dev .

## Where things live

- `Compose`: every `docker compose` command line, this service's and `dev`'s. `dev` builds its
  lines here and runs them on its own terminal, so the local stack and the deployment cannot drift
  apart in how they call Compose; `:architecture` lets it take nothing else of this module.
- `StewardAgent`: the entry points; the server and its gate are `:internal-api`'s. `HostRequests`
  is `request` and `status`.
- `run`: the inbox loop (`UpdateServer`, `Runner`), the one sequence (`Run`, `Choreography`,
  `UpdateRun`) and the kinds (`Kinds`). `LocalStack` and `LocalSnapshots` are the containers and the
  archives a run acts on.
- `plan`, `apply`, `source`, `plugin`: resolving what is current, placing it, where versions come
  from, and the managed plugins. `schema`: the migration and the roles.
- `AgentApi`: every other route, composed in one place: `docker` (the socket, containers, the
  console), `logs`, `measure` (host, sampler), `backup` and `topology`.
- Test fixtures: `FakeDaemon`, a socket that answers like Docker, and `AgentStandIn`, this API over
  it, which steward's own tests talk to through the real client.
