# updater

Owns the versions of everything the network runs, and the database schema. It is one program that is
both a service and a set of commands:

```bash
docker compose up -d updater                  # serve: migrate, then wait for requests
docker compose run --rm updater report        # resolve and report, changes nothing
docker compose run --rm updater migrate       # apply the schema, nothing else
docker compose run --rm updater bootstrap     # migrate, then fetch and place the files
```

It has **no compose profile** — it is in every selection, and every other service waits for it to
become healthy, which happens once the schema is current.

**This container is the bootstrap of a deployment, not a tool used on one.** It is the only process
that runs Flyway: without a run of it there is no schema, so no bot and no server. The bot refuses a
database it was not built against and names `updater migrate`; a Minecraft container refuses an empty
`plugins/` folder.

Running it with no argument gives the read-only report, so a container started by accident does the
harmless thing. That default is *not* reachable through `docker compose run`, which falls through to
the service's own `command` — always name the subcommand.

## Where a version comes from

| what | source |
|---|---|
| the season-2 jars, the resource pack and its `.sha1` | GitHub releases, `nordtal/season-2` |
| DisplayTags | GitHub releases, `nordtal/papermc-display-tags` |
| PacketEvents, Chunky | Modrinth v2, filtered to the Minecraft version and `paper` |
| Paper, Velocity | PaperMC Fill v3, newest `STABLE` build |
| what is installed | the six volumes under `volumes-root` |
| what pack the proxy offers | `pack.yml` in the `network-control` volume |

The report prints one row per artefact per server: up to date, `OUTDATED old -> new`, not installed,
*unknown*, or *UNRESOLVED*. The last two exist so that "nothing to do" and "nothing could be asked"
never look the same.

## Rules

- **`serve` is not a scheduler and must never become one.** It migrates at startup and then does
  nothing until a row appears in `update_request`. A crash restart at three in the morning must not
  move a version.
- **The runs that write are not the same run.** An update stops the services whose jars change,
  migrates, installs, starts them and waits for healthy. `bootstrap` fills empty slots and stops and
  starts nothing. A report writes nothing and restarts nothing.
- **Two updaters cannot serve at once**, and none can move jars concurrently. Both are PostgreSQL
  advisory locks; the second asker is refused rather than queued, because a plan resolved now is
  stale by the time a queued run would start.
- **A swap is two phases.** Artefacts are downloaded and verified in `.nordtal-staging` inside the
  server's own volume; only when all of them are present does anything move into place. A download
  that fails half way leaves the server exactly as it was.
- **A server moves together or not at all.** If one artefact cannot be resolved the whole server is
  skipped — a partial swap is a server that does not start.
- **Nothing it does not account for is ever deleted**, and only after the new jar is in place.
- **A source that cannot be reached costs its own rows and nobody else's.**
- **A version not tagged for the platform is refused, never worked around.**
- **"Skipped" is a third answer**, distinct from success and failure.

Two traps the code is shaped by: PacketEvents publishes a `-sources.jar` in the same Modrinth
version, so the artefact is the one with `"primary": true`; and a filename split on the last `-` is
the identity of what is installed, matching `entrypoint.sh`, because two programs disagreeing about
which jar supersedes which shows up as Paper loading two copies of one plugin in silence.

## Configuration

One file, `config/updater.yml`, one namespace `NORDTAL_UPDATER_*`. The environment wins over the file
and is never written back. Its defaults are the real values — the repositories, project ids and
platform versions are facts about this project, not about a deployment.

**Which release it follows is not a setting.** There is no `season-release` key and no environment
variable for it — both were removed on 2026-09-09 — and there is no way to pin a tag. Every repository
is read through GitHub's `/releases/latest`, and the resolved tag is printed on every run.

That endpoint **skips drafts and pre-releases** by GitHub's own definition. It is what we want and it
is the one trap left: a release sitting as a draft is invisible here, so an update that "did not
arrive" is usually a release nobody pressed Publish on. Check the releases page first.

**There is no rollback.** A bad release is corrected by publishing a better one. That is the same
trade this project already took on the Paper build, and the same reasoning: a pin is a version number
kept somewhere other than `gradle.properties`, and every one of those this repository kept went
stale.

## What it owns

The database schema, every plugin jar, the server jars in `.server/`, and the proxy's `pack.yml`. The
schema and the versions are one thing — a release that adds a table is a release that adds a
migration — which is the argument for one owner rather than two. Migrations run **before** any jar
moves, and a failed migration stops the run before anything is fetched.

It also owns the bot's jar and its own; both containers fall back to the jar baked into their image
only while the volume is empty. Its own jar takes effect on the next start, since no process replaces
the jar it is executing.

It does not own worlds or anything a player built.

## The two surfaces

Nothing calls this container — it is reached through the database. `/update` in Discord and in game
write a row into `update_request` and read the answer back from it. `serve` holds a
`LISTEN nordtal_update` connection outside its pool *and* polls every fifteen seconds: the poll is the
guarantee, the notification is the speed.

A restart is written with an instant sixty seconds out and the proxy counts every player down to it.
The updater refuses to claim the row before that instant, so the countdown is real and cancellable for
its whole length.

The restart itself is **one stop and one start per container** over Arcane's REST API — not the
Docker socket, which is mounted nowhere in this deployment, and **not** a project-wide redeploy,
which does both in a single request and would take this container down with everything else, leaving
nothing to report whether the network came back:

```
GET  /api/environments/{environment}/projects/{project}/runtime
POST /api/environments/{environment}/containers/{container}/{start|stop}
```

Both path segments are IDs: the environment is `0` for Arcane's own host, and the project is a UUID,
not the compose project name. They stay settings because Arcane does not publish them. An empty
`arcane.base-url` refuses an update **before** a version is resolved or a file is touched, and says
so by name.

## The images

A `start` hands a container back to Docker on exactly the image it was created from. So the jars move
and `entrypoint.sh`, the JRE under it and every change to `compose.yml` stay on whatever was pulled
at the last deploy — for ever, because nothing on the automatic path ever pulls. Two more calls close
that:

```
GET  /api/environments/{environment}/projects/{project}/updates
POST /api/environments/{environment}/projects/{project}/update-services   {"services":["smp"]}
```

The first is read **before anything is stopped**, so a stale image is a row in the plan a person
confirms rather than a step discovered after they said yes to something else. A service it names is
then **recreated** instead of started, one service per call, and the report says which. Volumes are
not touched on that path.

- **Arcane answers from its own persisted checks.** It does not ask a registry when asked, so a
  project whose image update check is off reports *"nobody has looked"* — a note in the report, never
  a quiet "up to date", and then nothing is recreated. Turn the check on.
- **The updater never recreates itself**, for the reason it never stops itself: the call would end
  the run from inside. Its own stale image is a note naming the Redeploy button.
- **Nor anything it does not own.** `postgres` and the backup sidecar are named in a note and left
  alone — a sequence that recreates a container it never stopped is one nobody can predict from the
  report they confirmed.
- **One hazard, upstream's:** Arcane recreates with `RecreateDependencies: RecreateDiverged`, and
  every backend depends on this container. A `compose.yml` that has changed the updater's own
  definition can therefore have it recreated as a diverged dependency, mid-run. Each recreate is
  reported *before* it is asked for, so the last line written names where a run stopped. See
  `nordtal/todo.md`, A19.

## Tests

`./gradlew :updater:test` — no network, no container. Fixtures in `src/test/resources/fixtures/` were
recorded from the live GitHub, Modrinth and PaperMC APIs. `TopologyTest` reads the real `compose.yml`,
so a backend added there and not to `Topology` fails the build. The migration is covered from the
other side by `:discord-bot`'s `SchemaCheckTest` against a real PostgreSQL.

**A 2xx from a real Arcane has never been observed.** Every Arcane path here was read out of its own
Go source — v2.10.2, dates in `UpdaterSpec.ArcaneSpec` — and the update-payload shapes in
`ArcaneImagesTest` are written from those type definitions rather than recorded from a live instance.
Capturing a real one is an item in `nordtal/todo.md` (A19).

## Output

The report goes to **stdout**, every log line to **stderr**. That separation is why this module has
its own `logback.xml`, and why it uses `<encoder>` rather than `<layout>` — a deprecated `<layout>`
makes logback print its startup status report on stdout, on top of the thing a person is meant to
read.
