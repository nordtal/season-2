# updater — the module that owns versions and the schema

**Decided 2026-09-01. Step 1 is built; steps 2 to 6 are not.** This document is the design; the
implementation follows it piece by piece, and [state-of-play.md](state-of-play.md) is where the gap
between the two is tracked. What exists today is the `updater` module: it resolves the newest
version of everything from all four sources, compares it against the jars actually lying in the
volumes, and prints the difference. **It writes nothing outside its own config volume** — the
four Minecraft mounts are read-only, and the one file it does write is `config/updater.yml` on a
first run — which is why it is safe to run against a live deployment at any moment:

```
docker compose --profile updater run --rm updater
``` Read [../deploy/README.md](../deploy/README.md) first if
you want to know how the stack runs today — everything below changes how it is *updated*, not how
it is shaped.

## The problem it exists for

Today a version is a hand edit. `SEASON_VERSION`, `SEASON_RELEASE` and `BOT_VERSION` sit in `.env`,
the container is cache-first by filename, and a plugin update is "change three lines, run
`docker compose up -d`". That is a defensible design and it has one fault that showed up the first
time anybody looked:

**On 2026-09-01 the published release `v0.1.0` carried a `smp-0.1.0.jar` of 51 279 bytes and a
`limbo-0.1.0.jar` of the same size — both the scaffold's two log lines.** The same version built
from `main` that day was 4 820 904 and 4 576 946 bytes. Twenty-six commits sat between the tag and
`HEAD`, two whole modules among them, and `.env.example` pinned exactly that release. A deployment
following the runbook to the letter would have started an SMP server with no SMP in it, reported
nothing wrong, and been discovered by a player.

Nothing there was a bug. Every part behaved as designed; what was missing is that **the pin has to
be moved by hand, and a hand that is busy building does not move it.** The updater is the answer to
that: a version stops being something you remember and becomes something you ask for.

Two smaller faults found in the same audit are folded in here because they have the same shape —
a value that has to be carried by hand from a release to a config file:

- The proxy's resource pack `url` and `sha1` were reachable only by editing `pack.yml` inside the
  `mc-network-control` volume. They are settings now (fixed 2026-09-01, before this module exists),
  but the sha1 still has to be copied out of the release by a person. The updater removes that.
- `PAPER_BUILD` and `VELOCITY_BUILD` are pinned exactly, correctly, and nobody would notice them
  going a year stale.

## What it owns

Two things, and they are one thing: **the versions of everything that runs, and the database
schema.** They belong together because a release that adds a table is a release that adds a
migration — the coupling is real, and today it is held only by an operator rule written in prose
("bring the bot up first, it is the only process that migrates").

That rule goes away. `AccessBot.java:95` is currently the sole `migrate()` call in the repository
and every plugin says in its own class comment that it never migrates; the call moves to the
updater, the migration SQL stays where it is in `:common`, and the bot becomes a client like every
other module.

**The consequence is deliberate and worth stating plainly: the updater is no longer a tool, it is
the bootstrap.** Without it there is no schema, so there is no bot and no server. A first
deployment starts with the updater, not with the database and the bot.

## Where versions come from

| what | source | measured 2026-09-01 |
|---|---|---|
| The six season-2 jars, the resource pack, its `.sha1` | GitHub releases API on `nordtal/season-2` | `v0.1.0` carries 7 assets; from `0.2.0` on there are 8, because the updater's own jar is one of them |
| DisplayTags | GitHub releases API on `nordtal/papermc-display-tags` | our own fork, 2.0.0 on `main` |
| PacketEvents | Modrinth API, `game_versions=["26.2"] loaders=["paper"]` | **exactly one** version: `2.13.0+spigot`, published 2026-06-22 |
| Chunky | Modrinth API, same filter | **exactly one** version: `1.5.3`, published 2026-05-04 |
| Paper, Velocity | PaperMC Fill API, newest `STABLE` of the pinned minor | the entrypoint already speaks this API |

The Modrinth answers were queried against the live API on 2026-09-01 and the filenames come back
identical to what `compose.yml` pins today — `packetevents-spigot-2.13.0.jar` and
`Chunky-Bukkit-1.5.3.jar`. Two details that will bite an implementation that skips them:

- **PacketEvents ships a `-sources.jar` in the same version.** Modrinth marks the real artefact
  with `primary: true`; matching on `.jar` alone puts a sources jar in a plugins folder.
- **Modrinth hands out sha512 per file.** That is the same hash the datapack fetch already
  verifies, so the machinery exists; there is no sha256 anywhere in this project's downloads.

Paper and Velocity are followed automatically, by decision. It is the one entry in that table where
a single update changes the platform under all four servers at once and nothing in this repository
tests against it — so it is also the first thing to look at when something breaks after an update.

## What a run does, in order

1. **Resolve.** Ask every source above what the newest thing is. Compare against what is on disk.
   Nothing is downloaded yet.
2. **Migrate.** Apply Flyway. This happens before any jar moves, so a plugin never comes up against
   a schema that is older than it is.
3. **Swap.** Fetch into the volumes and delete the superseded jars — the same cache-first,
   supersede-by-prefix logic `entrypoint.sh` already uses, so a version that is already there is
   not fetched again and the container never silently keeps an older jar.
4. **Set the pack.** Write the release's pack URL and the `.sha1` asset's content where
   network-control reads them.
5. **Report.** Post the result: per artefact, old → new, or "unchanged". **This happens before
   anything restarts**, which is the whole reason the order is this way round.
6. **Restart, on a button.** One Arcane redeploy of the whole project.

**The updater stages its own new jar last and does not run it.** It cannot: no process swaps its
own jar and keeps going. It does not need to — the redeploy in step 6 takes the whole stack down
and back up, the updater included, so it comes back on the new jar by itself. The one implementation
consequence is that the redeploy call is fire-and-forget: Arcane answers long-running operations as
a stream of newline-delimited JSON, and the updater will be killed part-way through its own request.
So it says "restart triggered" *before* it calls, and the confirmation that anything worked comes
from the next instance announcing itself on boot.

## The restart, and why not the Docker socket

Arcane exposes a REST API with token authentication — `X-Api-Key`, tokens generated in
Settings → API Keys — and knows project deploy, redeploy, pull and build as streaming operations
(read from the public documentation 2026-09-01; the endpoint paths are not in it and have to be
read from `/api/docs` on our own instance).

The alternative was mounting `/var/run/docker.sock`. It is the usual way and it was rejected: a
container with the socket can do anything on the host, and this is a container whose entire job is
to download files from the internet and put them where servers will execute them. That combination
is worth avoiding for the cost of one API token. The redeploy also then appears in Arcane's own
history rather than happening behind its back.

**This is the part of the design that cannot be verified from here.** The token, the endpoints and
whether a redeploy through the API behaves like one from the UI are all on Till's instance —
[`../../todo.md`](../../todo.md) carries the check. *If the API turns out not to expose a usable
redeploy:* the fallback is that the updater does everything except step 6 and says so, and the
restart is a click in Arcane. Every other part of this module still works.

## How it is operated

Two surfaces, and one of them is not the updater's own.

- **Discord**, in the admin channel that already exists as
  `NORDTAL_ACCESS_CHANNELS_ADMIN`: a slash command, the result as an embed, and a restart button
  underneath it. The bot already has buttons, ephemeral replies and that channel.
- **In game**, under `/smp` for admins.

Neither the bot nor the SMP plugin can call the updater directly — it is a separate container. They
reach it **through the database**: a request row plus a `pg_notify`, the updater listening and
answering the same way. That is the machinery this project already uses for phase switches and
milestone unlocks, so it is not a new kind of wiring, and it means the request survives an updater
that happens to be restarting.

**Said honestly: that `LISTEN` reaches a process in another container has never been verified on a
real deployment.** It is already an open item in `todo.md` for the phase system, and this module
would be the second thing resting on it. *If it turns out not to work across containers:* both
surfaces fall back to polling the same table on a short interval, which is what the phase system
already does as its safety net, and nothing about this design changes.

## What it deliberately does not do

- **It does not update on its own.** No schedule, no watching. A crash restart at three in the
  morning must not move a version — the container comes back on exactly what it was running. This
  was the first decision taken and everything else follows from it.
- **It does not roll back by itself.** A run can be given an explicit tag instead of "newest",
  which is the rollback, and it is a person's decision.
- **It does not touch worlds, configuration files inside volumes, or anything a player built.** It
  moves jars, one zip's URL and hash, and the schema.

## Why not the alternatives

| instead | why not |
|---|---|
| A host script (`deploy/update.sh`) | Simplest by far, and it was the recommendation. Rejected because the button in chat was wanted, and a host script cannot offer one. |
| Inside the discord bot | No new container, and the buttons are already there. Rejected because a process cannot update itself — and the bot's own version is one of the things that moves. |
| Each container updates itself on restart | Then a nightly crash splits the network across versions and nobody ordered it. |
| Watchtower-style image watching | The plugins are jars in volumes, not images. It would cover the bot and nothing else. |

## Two things this displaced, decided 2026-09-01

Both came out of writing step 1 and neither was in the original design.

**The updater owns the plugin jars, and `entrypoint.sh` stops fetching them.** They would otherwise
collide: the script pulls `<module>-${SEASON_VERSION}.jar` on every start and deletes, by prefix,
every other version of the same plugin. An updater that puts `0.3.0` into a volume while `.env`
still says `0.2.0` would have the next restart delete exactly the jar it just fetched. So
`SEASON_PLUGINS` and `EXTRA_PLUGIN_URLS` go away in step 3 and the entrypoint keeps only the server
jar and the datapacks. The price is stated plainly: **a volume that no updater run has touched has
no plugins**, and the container's "refuse to start rather than run an older jar" guard goes with
them. That is consistent rather than new — this module is the bootstrap already, because it owns
the schema.

**The pack's URL and hash live in `pack.yml`, not in the environment.** They were made compose
variables earlier the same day, for a good reason: they were reachable only by editing a file
inside a volume. That is being partly taken back, because of how jcore's config system works —
**an environment override wins over the file and is never written back to it.** An updater writing
a new sha1 into `pack.yml` while `NORDTAL_NETWORK_CONTROL_PACK_SHA1` is set would be writing into
a value nothing reads: a swap that reports success and changes nothing, which is the worst outcome
on the list. So `PACK_URL` and `PACK_SHA1` leave `compose.yml` and `.env.example` again in step 3,
and the hand-copying of a hash out of a release disappears instead of moving.

## Open, with a fallback each

- **Arcane's redeploy endpoint** — unverified, see above.
- **`LISTEN` across containers** — unverified, see above.
- **The bot becomes a jar.** Decided 2026-09-01: it stops being a GHCR image so that all five
  modules move by exactly the same mechanism and roll back the same way. That means a small Java
  image in `compose.yml`, and `.github/workflows/release.yml` loses its image build. *If running
  the bot from a volume turns out to be worse than the image:* it goes back to an image and its
  version is the one thing the updater cannot move, which is where this started.
- **What happens to a migration that fails.** Flyway stops, the jars have not moved yet — that is
  why migrate is step 2 and not step 4 — and the report says so. A failed migration is the one
  outcome where the updater must refuse to continue rather than do half a run.

## Implementation order

Built piece by piece, and each piece is useful on its own:

1. ~~Resolving versions from all four sources, and reporting the difference. No writes at all.~~
   **Built 2026-09-01.** 57 tests against payloads recorded from the live GitHub, Modrinth and
   Fill APIs that day, plus a volume tree on disk. `TopologyTest` reads the real `compose.yml`, so
   a fifth backend server added there and not here fails the build.
2. Flyway moves from the bot into the updater; the bot becomes a client.
3. Swapping jars into the volumes, and setting the pack URL and sha1 — which is also where
   `SEASON_PLUGINS`, `EXTRA_PLUGIN_URLS`, `PACK_URL` and `PACK_SHA1` are removed, and where the
   four compose mounts stop being read-only. See *Two things this displaced* above.
4. The Discord command, the embed, and the restart button.
5. The Arcane redeploy.
6. `/smp update` in game, over `NOTIFY`.

Step 1 is the one worth building carefully: an updater that resolves the wrong version is worse
than no updater, and it is the only step that can be tested completely without a running stack.
