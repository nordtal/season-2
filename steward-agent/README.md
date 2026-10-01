# steward-agent

The one service allowed to create containers. It carries `compose.yml` inside its own image, so
a change to the deployment is a new image of this service, renewed by the setup script on the host.
Jars inside volumes are steward's business; anything that needs a new container goes
through here.

## What it will not do

- **Recreate itself.** `steward-agent` is refused wherever a service name is accepted, since
  the new container would kill the process handling the request.
- **Pull dependencies along.** Every `up` carries `--no-deps`.
- **Schedule.** Deployments happen only when somebody, or steward, asks.

## Run it

    steward-agent up      # the setup script: pull, then up, wait, exit with the code
    steward-agent serve   # the HTTP API steward calls (default in the container)

| Endpoint                           | What it does                                                                  |
| ---------------------------------- | ----------------------------------------------------------------------------- |
| `GET /api/health`                  | the only route without the token                                              |
| `GET /api/state`                   | `docker compose ps --all --format json`                                       |
| `GET /api/services`                | every service in the baked file, with the image it runs                       |
| `POST /api/deploy`                 | `{"services": []}`, empty meaning the whole project; answers `202` with a job |
| `POST /api/recreate/{service}`     | `up -d --no-deps --force-recreate` from the local image                       |
| `GET /api/jobs` · `/api/jobs/{id}` | what ran, and its output                                                      |
| `GET /api/jobs/{id}/stream`        | the same output as SSE, replayed from the start on connect                    |

Every route but `/api/health` needs `X-Steward-Token`, and the service refuses to start without it.

A deployment pulls every image before it stops anything. A failed pull is tolerated only when the
image is already on the host.

## Building

    ./gradlew :steward-agent:build          # jar, and compose.yml staged into build/compose/
    docker build -t ghcr.io/nordtal/steward-agent:dev steward-agent

## Where things live

- `Compose`: every `docker compose` command line.
- `Jobs`: the in-memory job queue and its output.
- `StewardAgent`: the two entry points and the HTTP routes.
