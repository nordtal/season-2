# steward-worker

Owns the versions of everything the network runs, and the database schema. It is one program that is
both a service and a set of commands:

```bash
docker compose up -d steward-worker               # serve: migrate, then wait for requests
docker compose run --rm steward-worker report     # resolve and report, changes nothing
docker compose run --rm steward-worker migrate    # apply the schema, nothing else
docker compose run --rm steward-worker bootstrap  # migrate, then fetch and place the files
```

It was called `updater` until 2026-09-12: the three-part system it belongs to is **Steward**, and a
service name is runtime identity — the compose service, the two volumes, the image and the
`NORDTAL_STEWARD_*` environment prefix all carry it.

It has **no compose profile** — it is in every selection, and every other service waits for it to
become healthy, which happens once the schema is current.

**This container is the bootstrap of a deployment, not a tool used on one.** It is the only process
that runs Flyway: without a run of it there is no schema, so no bot and no server. The bot refuses a
database it was not built against and names `steward-worker migrate`; a Minecraft container refuses
an empty `plugins/` folder.

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
- **Two workers cannot serve at once**, and none can move jars concurrently. Both are PostgreSQL
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

One file, `config/steward.yml`, one namespace `NORDTAL_STEWARD_*`. The environment wins over the file
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
The worker refuses to claim the row before that instant, so the countdown is real and cancellable for
its whole length.

The restart itself is **one stop and one start per container**, and **not** a project-wide redeploy:
that does both in a single request and would take this container down with everything else, leaving
nothing to report whether the network came back. Until the cutover it goes over Arcane's REST API:

```
GET  /api/environments/{environment}/projects/{project}/runtime
POST /api/environments/{environment}/containers/{container}/{start|stop}
```

Both path segments are IDs: the environment is `0` for Arcane's own host, and the project is a UUID,
not the compose project name. They stay settings because Arcane does not publish them. An empty
`arcane.base-url` refuses an update **before** a version is resolved or a file is touched, and says
so by name.

## The docker socket

**It is mounted here since 2026-09-12, and it was not before.** The sentence this file used to carry
— *not the Docker socket, which is mounted nowhere in this deployment* — was true of the arrangement
where Arcane did the container work. The concept's §3 draws the line in a different place: the part
that must not hold the socket is the **web interface**, because that is what an attacker reaches
first. This container already downloads files from the internet and puts them where servers execute
them; the socket does not widen that, and it removes a whole service from the path.

What it does with it:

| | |
|---|---|
| reads | state, health, image, uptime, CPU and memory per container, the log stream, `/system/df` |
| asks a registry | `GET /distribution/{ref}/json` against the image's own digests — the drift check Arcane never performed (`todo.md` A24) |
| writes | one stop, one start, and `mc <command>` into the four Minecraft consoles |
| **refuses** | creating a container. That needs the compose file, which `steward-deployer` owns, and a container rebuilt from an inspect would drift from it silently |

`:ro` on the mount would be theatre: it restricts the socket *file*, not the API behind it. The
restraint is in the code — `DockerOps` — and in the fact that `steward-ui` has no socket at all.

Without the socket nothing here fails. The drift check and the metrics report that they **could not
look**, which is a different answer from *everything is current* — and confusing those two is
precisely what let four releases run behind unnoticed.

## The curves

Every 30 seconds `serve` writes one row per series into `metric_sample`: the host's load, CPU,
memory and disk, and CPU and memory per container. Eleven series, about 32 000 rows a day, some
60 MB after 30 days — after which raw samples become hourly means, a thirtieth of the size with the
year still in them.

That table is dumped with the rest of the database, so **the retention is also a decision about how
big every backup is**. It is `docker.metrics` in `steward.yml`, and turning it off costs the start
page its curves and nothing else.

## The backup

Two halves, because a database and a world are not the same problem.

**The database is dumped, not tarred.** `pg_dump` inside the postgres container — inside, so the
client can never be older than the server it dumps, which is a refusal rather than a warning. It
takes an MVCC snapshot, so it is consistent as of the moment it starts and **nothing has to be
stopped for it**: it runs before the servers go down, which is minutes off the outage for free.

**The volumes are tarred** from read-only mounts, piped through `zstd`, into `/backups`. Measured
on this host on 2026-09-13 against the real SMP world: 657 MiB in, 512.9 MiB out, **4.0 seconds**,
of which about 1.6 s is reading the archive back to check it. `-1` and not `-3`: the extra three
seconds of downtime bought 0.6 % — region files are already deflated.

Both write `<name>.partial` and rename only after the archive has been read back. A half-written
file that looks like every other one is worse than none: it is the one the retention sweep keeps
and the one a restore picks.

**Saved means a file exists.** Every line in the report carries the size and the duration, and a
volume that produced nothing is FAILED even if every call succeeded. That is `todo.md` A23: run 23
reported a successful backup having saved zero volumes, and nothing made it visible.

**The clock is here since 2026-09-13** (§9a). It used to be `smp`'s, because `serve` was not
allowed to schedule anything — and a season with `smp` down therefore had no backup and nothing
said so. The protection that mattered is kept: this clock writes a request row and nothing else,
and everything after that row is the path `/backup now` already took. The farm world reset no
longer trusts a clock either; it asks the database whether a backup actually succeeded.

**What is not built: the offsite copy.** §9a's Storage Box does not exist yet, so every archive is
on the same disk as the thing it is a copy of. Fourteen of them protect against a mistake and
against nothing else. There is deliberately no untested S3 path in this code — see `todo.md` A29.

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
- **The worker never recreates itself**, for the reason it never stops itself: the call would end
  the run from inside. Its own stale image is a note naming the Redeploy button.
- **Nor anything it does not own.** `postgres` and the backup sidecar are named in a note and left
  alone — a sequence that recreates a container it never stopped is one nobody can predict from the
  report they confirmed.
- **One hazard, upstream's:** Arcane recreates with `RecreateDependencies: RecreateDiverged`, and
  every backend depends on this container. A `compose.yml` that has changed the worker's own
  definition can therefore have it recreated as a diverged dependency, mid-run. Each recreate is
  reported *before* it is asked for, so the last line written names where a run stopped. See
  `nordtal/todo.md`, A19.

## Tests

`./gradlew :steward-worker:test` — no network, no container. Fixtures in `src/test/resources/fixtures/` were
recorded from the live GitHub, Modrinth and PaperMC APIs. `TopologyTest` reads the real `compose.yml`,
so a backend added there and not to `Topology` fails the build. The migration is covered from the
other side by `:discord-bot`'s `SchemaCheckTest` against a real PostgreSQL.

**A 2xx from a real Arcane has never been observed.** Every Arcane path here was read out of its own
Go source — v2.10.2, dates in `StewardSpec.ArcaneSpec` — and the update-payload shapes in
`ArcaneImagesTest` are written from those type definitions rather than recorded from a live instance.
Capturing a real one is an item in `nordtal/todo.md` (A19).

## Output

The report goes to **stdout**, every log line to **stderr**. That separation is why this module has
its own `logback.xml`, and why it uses `<encoder>` rather than `<layout>` — a deprecated `<layout>`
makes logback print its startup status report on stdout, on top of the thing a person is meant to
read.
