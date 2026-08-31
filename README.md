# season-2

Everything nordtal.eu season 2 deploys: the plugins, the Discord bot and the resource pack.

The network is a Velocity proxy with three backend servers, run by
[SimpleCloud](https://simplecloud.app):

```
                  ┌──────────────────────────┐
   players ─────► │ Velocity  network-control│  season phase + routing
                  └────┬─────────┬─────────┬─┘
                       │         │         │
        resource-pack-coercion  hunger-games       smp
         (pack install)         (start event)  (the SMP)
```

`access-bot` runs alongside as a standalone Discord bot, and `resource-pack` holds the pack
those servers serve.

The concepts behind all of it - phases, languages, the start event, the SMP, operations and the
decisions already taken - live in [docs/](docs/README.md).

## Modules

| module | platform | what it owns |
|---|---|---|
| `network-control` | Velocity | Which phase of season 2 we are in, and which backend a player belongs on. |
| `resource-pack-coercion` | Paper | Applying and enforcing the resource pack before a player goes anywhere else. |
| `hunger-games` | Paper | The hunger games start event. |
| `smp` | Paper | The SMP: Nordtal, the farm world, the Nether and the End, milestones, aura, prestige, duels. |
| `access-bot` | JVM app | Discord bot: sells access periods, books bunq payments, owns the access schema. |
| `common` | library | Shared: the access API (`AccessDirectory`), the message system, glyph constants, the phase enum. |
| `resource-pack` | assets | The pack itself, and the zip + SHA-1 the release ships. |

`DisplayTags` also runs on this network but ships from its own repo,
[nordtal/papermc-display-tags](https://github.com/nordtal/papermc-display-tags).

## Building

Requires JDK 25.

```bash
./gradlew build
```

`season-2` produces no combined artifact — each module builds its own. To produce exactly what a
release ships:

```bash
./gradlew releaseArtifacts
```

Each Paper module has a local test server, and the proxy module can be run the same way:

```bash
./gradlew :hunger-games:runServer
```

## Releasing

The version in `gradle.properties` is the single source of truth. To release, set it, commit, and
push a matching tag:

```bash
git tag v2.0.0 && git push origin v2.0.0
```

The `release` workflow refuses a tag that disagrees with `gradle.properties`, then builds every
module and attaches to one GitHub release: four plugin jars, the bot jar, and the resource pack
zip with its SHA-1. It also pushes `ghcr.io/nordtal/access-bot:<version>`.

## Configuration

This repository is public and contains no secrets. Every credential — the Discord bot token, the
bunq API key, database access — is supplied through environment variables at runtime. Committed
configuration files are examples only.
