# updater

The container that owns the versions of everything the network runs — and, from step 2 of
[../docs/updater.md](../docs/updater.md) on, the database schema.

**Step 1 of six is built.** It resolves, compares and reports. It does not download, swap, migrate
or restart anything.

```bash
docker compose --profile updater run --rm updater
```

## What a run does today

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
- **It writes nothing outside its own config volume.** The four Minecraft mounts are `:ro`; the one
  file it writes is `config/updater.yml`, on a first run. Step 3 is what makes the mounts writable,
  and that is a change you should be able to see in a diff.
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

## Tests

`./gradlew :updater:test` — 57 tests, no network and no container. Every API fixture in
`src/test/resources/fixtures/` was recorded from the live GitHub, Modrinth and PaperMC APIs on
2026-09-01, including the release that carried the scaffold `smp` and `limbo` jars.
`TopologyTest` reads the real `deploy/compose.yml`: a fifth backend server added there and not to
`Topology` fails the build, because the updater would otherwise quietly never touch it.
