# updater

The container that owns the versions of everything the network runs, and the database schema
([../docs/updater.md](../docs/updater.md)).

**All six steps are built.** It resolves, compares, reports, migrates, installs, and restarts the
whole stack through Arcane when somebody presses the button. It is **a service and a set of
commands**, and they are the same program:

```bash
docker compose up -d updater             # `serve`: migrate, then wait for requests. What compose runs.
docker compose run --rm updater report   # resolve and report, changes nothing
docker compose run --rm updater migrate  # apply the database schema, nothing else
docker compose run --rm updater apply    # migrate, then fetch and place the files
```

It has **no compose profile**: it is in every selection, and every other service waits for it to be
healthy, which it becomes once the schema is current.

**This container is the bootstrap of a deployment, not a tool used on one.** Since 2026-09-01 it is
the only process that runs Flyway: without a run of it there is no schema, so there is no bot and no
server. The bot checks the schema and refuses a database it was not built against, naming
`updater migrate`; a Minecraft container refuses an empty `plugins/` folder. Both say so by name
rather than failing later.

The default with no argument at all is the read-only one, on purpose: a container started by
accident, or with a misspelled argument, does the harmless thing, and everything that writes has to
be asked for by name.

**That default is not reachable through `docker compose run`, which is why `report` also has a
name.** Compose hands a `run` that names no subcommand the service's own `command` — `serve` — and
when the service defines none it falls through to the image's `CMD` instead. Both were measured on
2026-09-02. So the bare invocation, documented in five places as the harmless report, actually
started a second long-running daemon: it migrated, ran the bootstrap, began listening on
`nordtal_update`, and left the operator watching a terminal that never returned. Type
`updater report`.

## Where a version comes from

| what | where it is read from |
|---|---|
| the six season-2 jars and the resource pack + its `.sha1` | GitHub releases, `nordtal/season-2` |
| DisplayTags | GitHub releases, `nordtal/papermc-display-tags` |
| PacketEvents, Chunky | Modrinth v2, filtered to the pinned Minecraft version and `paper` |
| Paper, Velocity | PaperMC Fill v3, newest `STABLE` build of the pinned version |
| what is installed | six volumes under `volumes-root`: the four Minecraft ones, the bot's and its own |
| what pack the proxy offers | `pack.yml` in the `network-control` volume |

All six are mounted **writable** — `apply` is what puts the jars there. A report run still writes
nothing into any of them; the two are separated by command, not by mount, so that the read-only one
is what a container does when nobody asked for anything.

Then it prints one row per artefact per server: up to date, `OUTDATED old -> new`, not installed,
or — the two that matter — *unknown* and *UNRESOLVED*. Those two exist so that **"nothing to do" and
"nothing could be asked" never look the same**, which is the entire value of the report.

## Rules, and why each one is a rule

- **`serve` runs all the time and is not a scheduler.** It migrates once at startup and then does
  nothing at all until a row appears in `update_request` — no timer, no watch, no "check for
  updates on boot". A crash restart at three in the morning must not move a version: the network
  comes back on exactly what it was running. The container having a restart policy does not change
  that; adding a timer would, and nothing here may.
- **A report run puts nothing into a Minecraft volume.** Only `apply` does, and `apply` still
  restarts nothing: it prints what it did and stops, because a person reading a half-done run
  before the network goes down on it is the entire point of the restart being a separate button.
- **Two updaters cannot *serve* at once.** `serve` takes a second PostgreSQL advisory lock
  (`nordtalS`) for its whole life, and a second one refuses to start rather than joining in. It is
  what makes `settleOrphans` correct: that method closes every row left `RUNNING` on the reasoning
  that nothing can still be running them, which is true of one serve and false of two - the second
  one settles the first one's in-flight `APPLY` as a failure and the real report is lost. It waits
  up to 30 s first, because on a redeploy the replacement starts while its predecessor is still
  shutting down.
- **Two updaters cannot move jars at once.** An apply takes the PostgreSQL advisory lock
  `nordtal1`, on a dedicated connection so a pool cannot leak it, and the second asker is **refused
  rather than queued** — a plan resolved now is stale by the time a queued run would start. The
  daemon and a hand-run `apply` overlap on exactly the day somebody is bootstrapping.
- **"Skipped" is a third answer.** A run where every volume was unmounted did no work and had no
  failure; closing it with "Nothing needed doing" is how somebody reads it as "the network is
  current".
- **A swap is two phases.** Everything a server needs is downloaded into `.nordtal-staging` inside
  that server's own volume and verified there; only when all of it is present does anything move
  into `plugins/` or `.server/`. A download that fails half way leaves the server exactly as it
  was. `entrypoint.sh` never worked this way and did not need to — this moves eight artefacts
  across four servers at once, and a network running four servers on two versions of the season is
  worse than one that did not update.
- **A server moves together or not at all.** If one of a server's artefacts cannot be resolved, the
  whole server is skipped. DisplayTags is a *required* plugin of `smp` and PacketEvents is required
  under it, so a partial swap there is a server that does not start.
- **Nothing it does not account for is ever deleted.** Only a jar whose filename prefix matches the
  one just installed, and only after the new jar is in place.
- **A source that cannot be reached costs its own rows and nobody else's.** A Modrinth outage must
  not hide that our own jars moved.
- **A version that is not tagged for the platform is refused, never worked around.** "Chunky has no
  26.2 build yet" must not become "install the 26.1 build instead".

## The two traps that shaped the code

- **PacketEvents publishes a `-sources.jar` in the same Modrinth version.** The real artefact is the
  one with `"primary": true`. Matching on `.jar` alone puts source code in a plugins folder, where
  it loads as a plugin with no code in it. A version with no primary file is an error here, not a
  guess.
- **A filename is the identity of what is installed**, split on the last `-` — which is
  `${file%-*.jar}` out of `deploy/minecraft/entrypoint.sh`. It is not a good rule; it is *that*
  rule, and two programs disagreeing about which jar supersedes which shows up as Paper loading two
  versions of one plugin without complaining. `JarName`'s tests pin all ten real names, and the
  documented gap — a `-SNAPSHOT` qualifier — is pinned too.

## Configuration

One file, `config/updater.yml`, one namespace, `NORDTAL_UPDATER_*` (the setting's path with `-`
becoming `_`; the environment wins over the file and is never written back).

**Its defaults are the real values, and a freshly written file is what you want** — the unusual case
in this repository. The repositories, the two Modrinth project ids and the platform versions are
facts about this project, not about a deployment, and an updater that refuses to start until an
operator retypes `nordtal/season-2` is one that gets started with a typo in it. The exception is
`github-token`: empty, optional, and only there for the rate limit.

`season-release` takes `latest` or an exact tag. `latest` asks GitHub's `/releases/latest`, which
**skips drafts and pre-releases** — so a release nobody pressed Publish on is invisible from in
here, and the resolved tag is printed on every run precisely so that case is recognisable. An exact
tag is how a rollback is expressed, and it is a person's decision.

## What it owns, and what it does not

It owns **the database schema**, every plugin jar in every `plugins/` folder, the server jars in
`.server/`, and the two lines of the proxy's `pack.yml`. The schema and the versions are one thing —
a release that adds a table is a release that adds a migration — which is the whole argument for one
owner rather than two. The SQL itself did not move: it stays in
`common/src/main/resources/db/migration/` and reaches this jar because `:common` is shaded into it,
exactly as it reached the bot's.

`apply` migrates **before** it moves a single jar, so a plugin never comes up against a schema older
than itself, and a migration that fails stops the run there: nothing is fetched, nothing is written. `deploy/minecraft/entrypoint.sh` fetched the plugins until
2026-09-01 and does not any more: two owners of the same file is one owner too many, because that
script deletes by filename prefix and would have deleted the jar the updater had just fetched. What
the entrypoint still owns is resolving the pinned server build and the world-generation datapacks,
and refusing to start on an empty `plugins/` folder — the coarse remnant of its old "never run an
older jar" guard.

It also owns **the bot's jar and its own**. Both containers run `<name>-*.jar` out of a volume this
module fills, and fall back to the jar baked into their image only while that volume is empty —
which is a first deployment and nothing else. Its own jar is installed exactly like any other and
takes effect on the next start: no process replaces the jar it is executing, so the restart is what
brings the new updater up. Deleting the superseded jar while running from it is safe **only because
this is Linux** — unlinking an open file leaves the inode alive for whoever holds it — and that is
written down in `Applier` rather than assumed.

It does not own worlds, anything a player built, or any other file inside a volume.

## The two surfaces

Nothing calls this container: it is reached through the database. `/update` in Discord and
`/smp update` in game write a row into `update_request` and read the answer back out of the same
row; `serve` holds a `LISTEN nordtal_update` connection outside its pool **and** polls every fifteen
seconds. The poll is the guarantee, the notification is the speed — measured on 2026-09-01 across
two containers: **160 ms with it, 17 s without**.

A restart is written with an instant sixty seconds out, and network-control counts every player on
the network down towards that instant. The updater refuses to claim the row before it, so the
countdown is real and cancellable for its whole length. The wait is shortened when a countdown ends
sooner than the next poll: an 8-second countdown fired after 8.97 s, not after 15.

The restart itself is one Arcane redeploy, over its REST API — **not the Docker socket**, which is
not mounted anywhere in this deployment:

```
POST /api/environments/{environment}/projects/{project}/redeploy
```

read from Arcane's own source on 2026-09-01 (`backend/internal/project/handler.go`, v2.10.0), since
the public documentation names the operation and not its path. **Both segments are IDs** — the
environment is `0` for Arcane's own host, the project is a UUID and emphatically not the compose
project name — so `arcane.project` has no default and the updater refuses to start without it once
`arcane.base-url` is set. The path stays a setting because Arcane does not publish it and a version
could move it. An empty `arcane.base-url` is a supported state: everything else works and both
surfaces say the restart has to be clicked in Arcane.

## Tests

`./gradlew :updater:test` — no network and no container. The migration itself is covered
from the other side, in `:discord-bot`'s `SchemaCheckTest`, against a real PostgreSQL: an unmigrated
database is refused with a message naming `updater migrate`, and a migrated one passes. Every API fixture in
`src/test/resources/fixtures/` was recorded from the live GitHub, Modrinth and PaperMC APIs on
2026-09-01, including the release that carried the scaffold `smp` and `limbo` jars.
`TopologyTest` reads the real `compose.yml`: a fifth backend server added there and not to
`Topology` fails the build, and so does re-adding `SEASON_PLUGINS`, `EXTRA_PLUGIN_URLS` or the two
`PACK_*` variables.

Verified beyond the tests on 2026-09-01, in the container, against the live APIs and a real
PostgreSQL 17:

- all six migrations applied to an empty database, `flyway_schema_history` read back;
- Chunky 1.5.3 fetched, its sha512 matching Modrinth's published hash, the superseded 1.5.2 deleted,
  a jar nothing accounts for left alone, and no staging directory left behind;
- `pack.yml` rewritten with its comments intact;
- a wrong database password stopping the run before a single jar moved;
- the report alone on stdout, every log line on stderr.

And again on 2026-09-01 for `serve`, two containers on a Docker network:

- seven migrations applied at startup, the container turning `healthy` only after that;
- a request written with no notification picked up by the poll in 17 s, and one with `pg_notify` in
  **160 ms** — which closes the "does `LISTEN` cross a container boundary" question;
- an 8-second countdown firing after **8.97 s**, not after the 15-second poll;
- a restart cancelled inside its countdown never claimed, twenty seconds later;
- a `RESTART` left `RUNNING` by a killed container read as **DONE** on the next start, and an
  `APPLY` in the same state read as **FAILED** — the restart is the one request that is supposed to
  end that way;
- a second `apply` refused while the advisory lock was held, from both directions, and going
  through the moment it was released;
- the Arcane call reaching an nginx with the right User-Agent, and its 404 producing the sentence
  that pointed at where the real path comes from. *That run went to `POST
  /api/projects/nordtal-s2/redeploy`, which was the default at the time and is not the default any
  more* — the endpoint and the two IDs replaced it later the same day, and `ArcaneTest` covers the
  new one against a real HTTP server. The measurement is left as it was taken.

**A 2xx from a real Arcane has never been seen**, and
[arcane#1943](https://github.com/getarcaneapp/arcane/issues/1943) says a 2xx would not by itself
prove the restart happened: it reports a redeploy of an already running project answering success
and doing nothing. Nothing here can detect that — the stream that would say so is one the redeploy
kills this container part way through reading. Both are Till's instance to answer.

## Output

The report is **stdout**. Every log line goes to **stderr**. That separation is the reason this
module has its own `logback.xml`, and the reason it uses `<encoder>` rather than `<layout>`: a
deprecated `<layout>` makes logback print its entire startup status report on stdout, which is
twenty-five lines of configurator chatter on top of the thing a person is meant to read.
