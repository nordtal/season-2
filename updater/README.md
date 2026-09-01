# updater

The container that owns the versions of everything the network runs — and, from step 2 of
[../docs/updater.md](../docs/updater.md) on, the database schema.

**Steps 1 and 3 of six are built.** It resolves, compares, reports, and — when asked by name —
installs. It does not migrate or restart anything.

```bash
docker compose --profile updater run --rm updater          # resolve and report, changes nothing
docker compose --profile updater run --rm updater apply    # fetch the files and put them in place
```

The default is the read-only one on purpose: a container started by accident, or with a misspelled
argument, does the harmless thing.

## Where a version comes from

| what | where it is read from |
|---|---|
| the six season-2 jars and the resource pack + its `.sha1` | GitHub releases, `nordtal/season-2` |
| DisplayTags | GitHub releases, `nordtal/papermc-display-tags` |
| PacketEvents, Chunky | Modrinth v2, filtered to the pinned Minecraft version and `paper` |
| Paper, Velocity | PaperMC Fill v3, newest `STABLE` build of the pinned version |
| what is installed | the four Minecraft volumes, mounted read-only under `volumes-root` |
| what pack the proxy offers | `pack.yml` in the `network-control` volume |

Then it prints one row per artefact per server: up to date, `OUTDATED old -> new`, not installed,
or — the two that matter — *unknown* and *UNRESOLVED*. Those two exist so that **"nothing to do" and
"nothing could be asked" never look the same**, which is the entire value of the report.

## Rules, and why each one is a rule

- **It is a command, not a service.** No restart policy, no schedule, and `updater` must not appear
  in `COMPOSE_PROFILES`. A crash restart at three in the morning must not move a version: the
  network comes back on exactly what it was running.
- **A report run puts nothing into a Minecraft volume.** Only `apply` does, and `apply` still
  restarts nothing: it prints what it did and stops, because a person reading a half-done run
  before the network goes down on it is the entire point of the restart being step 6.
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

It owns every plugin jar in every `plugins/` folder, the server jars in `.server/`, and the two
lines of the proxy's `pack.yml`. `deploy/minecraft/entrypoint.sh` fetched the plugins until
2026-09-01 and does not any more: two owners of the same file is one owner too many, because that
script deletes by filename prefix and would have deleted the jar the updater had just fetched. What
the entrypoint still owns is resolving the pinned server build and the world-generation datapacks,
and refusing to start on an empty `plugins/` folder — the coarse remnant of its old "never run an
older jar" guard.

It does not own worlds, anything a player built, any other file inside a volume, the database
schema (that is step 2), or the restart (steps 5 and 6).

## Tests

`./gradlew :updater:test` — 75 tests, no network and no container. Every API fixture in
`src/test/resources/fixtures/` was recorded from the live GitHub, Modrinth and PaperMC APIs on
2026-09-01, including the release that carried the scaffold `smp` and `limbo` jars.
`TopologyTest` reads the real `deploy/compose.yml`: a fifth backend server added there and not to
`Topology` fails the build, and so does re-adding `SEASON_PLUGINS`, `EXTRA_PLUGIN_URLS` or the two
`PACK_*` variables.

Verified beyond the tests on 2026-09-01, in the container, against the live APIs: Chunky 1.5.3
fetched and its sha512 matching Modrinth's published hash, the superseded 1.5.2 deleted, a jar
nothing accounts for left alone, `pack.yml` rewritten with its comments intact, and no staging
directory left behind.
