# steward-deployer

The one service allowed to create containers.

Everything else in this stack can be updated without it: steward-worker replaces jars inside
volumes, and a plugin or a server jar needs no new image. What needs a new *container* — a changed
`compose.yml`, a new image, a service that has to come back from scratch — goes through here, and
through nothing else.

## Why it carries `compose.yml`

The file is baked into the image. Two consequences, both wanted:

- **"Which compose file is live" has a version number for an answer.** Not a commit hash, not a
  directory on the host that somebody edited during an incident and nobody reverted. That was the
  standing hazard with a GitOps checkout: a local edit survives exactly until the next sync.
- **A compose change rides on a step that already exists.** This service cannot renew itself, so it
  is renewed from outside — by the setup script on the host. That is the same operation as shipping
  a new `compose.yml`, rather than a second one.

The price is stated plainly: a change to the deployment needs a new image of this service. For
every jar in this repository that is already true.

## What it will not do

- **It never recreates itself.** `steward-deployer` is refused wherever a service name is accepted,
  in `Compose`, before a command line is assembled. A recreate would replace the container running
  the request, and the caller would never learn how it ended.
- **It never pulls dependencies along.** Every `up` carries `--no-deps`. The alternative is what
  Arcane did with `RecreateDependencies = RecreateDiverged`, where recreating one backend could
  recreate the service every backend waits for.
- **It does not schedule.** There is no clock in here. Deployments happen because somebody, or
  steward-ui, asked for one.

## Two ways in

    steward-deployer up      # the setup script: pull, then up, wait, exit with the code
    steward-deployer serve   # the HTTP API steward-ui calls (default in the container)

`up` is for the moment before anything exists — no interface, no session, nothing to click. `serve`
never waits: a deployment is a job, and its output is read as it appears.

| Endpoint | What it does |
|---|---|
| `GET /api/health` | the only route without the token |
| `GET /api/state` | `docker compose ps --all --format json` |
| `GET /api/services` | every service in the baked file, with the image it runs |
| `POST /api/deploy` | `{"services": []}` — empty means the whole project. Answers `202` with a job |
| `POST /api/recreate/{service}` | pull that one image, then `up -d --no-deps --force-recreate` |
| `GET /api/jobs` · `/api/jobs/{id}` | what ran, and its output |
| `GET /api/jobs/{id}/stream` | the same output as SSE, replayed from the start on connect |

Every route but `/api/health` needs `X-Steward-Token`. **The service refuses to start without that
secret** — a process that can recreate every container in the stack, reachable unauthenticated on a
shared network, is a remote root shell with extra steps.

## Pull before stop, and the one tolerated failure

A deployment pulls every image it is going to need **before** it takes anything down. An image that
cannot be fetched halfway through a run is a stack that is already stopped with nothing to start.

There is one exception, and it is temporary: during the alpha `steward-ui` is built on this host and
pushed to no registry, so pulling it answers exactly like an image that does not exist. A failed
pull is therefore tolerated **only** when the image is already present locally — the difference
between "we have it" and "we cannot get it". It falls away with steward-ui's first release.

## Building

    ./gradlew :steward-deployer:build          # jar, and compose.yml staged into build/compose/
    docker build -t ghcr.io/nordtal/steward-deployer:dev steward-deployer

The docker CLI and the compose plugin come out of the official `docker:28-cli` image rather than
from a package manager. Checked on this host 2026-09-12: docker 28.5.2, compose v2.40.3.
