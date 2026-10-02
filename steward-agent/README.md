# steward-agent

The one door to Docker and to the volumes. It holds the Docker socket and carries `compose.yml`
inside its own image, so a change to the deployment is a new image of this service, renewed by the
setup script on the host. `steward` mounts no socket and reaches all of it through
`:internal-api`'s `AgentClient`; `:architecture` refuses `java.net.UnixDomainSocketAddress` anywhere
else, and any class of this module inside `steward`.

## What it will not do

- **Recreate itself.** `steward-agent` is refused wherever a service name is accepted, since
  the new container would kill the process handling the request.
- **Pull dependencies along.** Every `up` carries `--no-deps`.
- **Schedule.** Deployments happen only when somebody, or steward, asks. The sampler is the one
  thing it does on its own clock, and it only reads.
- **Hand out secrets.** Nothing on the wire carries a container's environment or the env file.

## Run it

    steward-agent up      # the setup script: pull, then up, wait, exit with the code
    steward-agent serve   # the HTTP API steward calls (default in the container)

## The contract

The paths and records are `AgentWire`, the client is `AgentClient`; both live in `:internal-api`,
so steward and this service compile against one definition. `{service}` is a compose service name.

| Route                                  | Answer                                                                                 |
| -------------------------------------- | -------------------------------------------------------------------------------------- |
| `GET /api/health`                      | the only route without the token                                                       |
| `GET /api/topology`                    | `Topology`: every service in the baked file, its image and its labels' meaning         |
| `GET /api/containers`                  | `Containers`: every container of the project with the sampler's last `Reading`        |
| `GET /api/containers/{service}`        | one `Container`, with the registry digests of its image; `404` when there is none      |
| `POST /api/stop/{id}`, `/api/start/{id}` | `RedeployResult`; a run's stop and start                                             |
| `GET /api/images`                      | `ImageResult`: each running image against its registry, slow on purpose                |
| `GET /api/containers/{service}/logs`   | SSE: `line` and `run` events, the backlog first; `end` or `gone` when it stops         |
| `GET /api/containers/{service}/log-capacity?max=` | `LogCapacity`: how many lines the backlog can fill                          |
| `POST /api/containers/{service}/console` | `ConsoleLine` (`command`, `actor`); `202`, the answer lands in the log               |
| `GET /api/host`                        | `Host`: `/proc`, the root filesystem and Docker's disk use                             |
| `GET /api/samples?after=`              | the sampler's `Round`s after an ISO instant, oldest first; all it holds without one    |
| `GET /api/backups`                     | `Archive`s, newest first                                                               |
| `GET /api/backups/{name}`              | one finished archive's bytes; `400` for a name that is not one, `404` when it is gone  |
| `POST /api/backup/database`, `/volume`, `/mark`, `/prune` | the steps of a backup run, answered by `SnapshotResult`, `Mark` or names |
| `POST /api/deploy`                     | `{"services": []}`, empty meaning the whole project; answers `202` with a job          |
| `POST /api/recreate/{service}`         | `up -d --no-deps --force-recreate` from the local image                                |
| `GET /api/jobs`, `/api/jobs/{id}`      | what ran, and its output                                                               |
| `GET /api/jobs/{id}/stream`            | the same output as SSE, replayed from the start on connect                             |

A refusal is a `Refusal` (`error`, the sentence to show, and `where`): `502` with `where` `docker`
when the daemon failed, `502` with `compose` when a Compose command did, `400` with
`steward-agent` for a request it will not run. steward passes `where` through, so the interface
names the daemon and not the agent.

Every route but `/api/health` needs `X-Steward-Token`, and the service refuses to start without it.
The gate is `:internal-api`'s, the same one `steward-bunq` runs behind. The service sits on the
internal `agent` network with `steward` alone, so nothing else can even knock.

The console set is the label `eu.nordtal.console: "true"` in `compose.yml`, read through
`docker compose config`; a line goes to `mc` as one argument, never through a shell, and the log
names who typed it. The sampler reads `docker stats` and `/proc` every 30 seconds and keeps the
last hour; steward copies what it took into `metric_sample`, asking after the newest round it
already holds, so a restart of either loses nothing.

A deployment pulls every image before it stops anything. A failed pull is tolerated only when the
image is already on the host.

| Setting (`NORDTAL_STEWARD_AGENT_*`) | Default                | What                                                    |
| ----------------------------------- | ---------------------- | ------------------------------------------------------- |
| `TOKEN`                             | none, required         | the secret steward sends                                |
| `PORT`                              | `8081`                 |                                                         |
| `DOCKER_SOCKET`                     | `/var/run/docker.sock` |                                                         |
| `VOLUMES_ROOT`                      | `/volumes`             | the servers' data, read-only, for the rotated logs      |
| `BACKUP_SOURCES`                    | `/backup-sources`      | one read-only mount per volume a backup saves           |
| `BACKUPS`                           | `/backups`             | the archives, the same volume postgres dumps into       |

## Building

    ./gradlew :steward-agent:build          # jar, and compose.yml staged into build/compose/
    docker build -f deploy/jvm/Dockerfile --build-arg MODULE=steward-agent -t ghcr.io/nordtal/steward-agent:dev .

## Where things live

- `Compose`: every `docker compose` command line, this service's and `dev`'s. `dev` builds its
  lines here and runs them on its own terminal, so the local stack and the deployment cannot drift
  apart in how they call Compose; `:architecture` lets it take nothing else of this module.
- `Jobs`: the in-memory job queue and its output.
- `StewardAgent`: the two entry points; the server and its gate are `:internal-api`'s.
- `AgentApi`: every other route, composed in one place: `docker` (the socket, containers, the
  console), `logs`, `measure` (host, sampler), `backup` and `topology`.
- Test fixtures: `FakeDaemon`, a socket that answers like Docker, and `AgentStandIn`, this API over
  it, which steward's own tests talk to through the real client.
