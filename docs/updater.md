# updater — the module that owns versions and the schema

**Decided 2026-09-01, and all six steps are built.** This document is the design; the implementation
follows it, and [state-of-play.md](state-of-play.md) is where any gap between the two is tracked.

The updater is **a container that runs all the time and a command you can run by hand**, and they
are the same program:

```
docker compose up -d updater             # `serve`: migrate, then wait for requests. What compose runs.
docker compose run --rm updater report   # resolve, compare, report. Changes nothing.
docker compose run --rm updater migrate  # apply the database schema, nothing else.
docker compose run --rm updater apply    # migrate, then fetch and place the files.
```

The read-only report writes nothing into a Minecraft volume, so it is safe against a live
deployment at any moment. `apply` installs the jars and writes the proxy's `pack.yml` — and **still
restarts nothing**: it prints what it did and stops, which is the point of the restart being a
separate button.

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

**`serve` is not a scheduler.** At startup it applies the schema and installs what is *missing*,
and then does nothing at all until somebody writes a row into `update_request` — no timer, no
watching, no "check for updates on boot". A crash restart at three in the morning must not move a
version, and that is the rule this whole module is built around; a long-running container does not
change it, because the loop has nothing to do on its own.

**The startup install cannot move a version**, and that is what lets it sit next to that rule rather
than against it. `UpdatePlan#onlyMissing()` drops everything but `MISSING`, so an artefact that
already has a jar keeps it however old it is: a restart of a live network finds nothing missing and
installs nothing. What it is for is the other case — a brand new stack, where every volume is empty
and a Minecraft server refuses to start without plugins. That used to need
`docker compose run --rm updater apply` typed on the host, and **Arcane deploys by pulling images
and has no way to type it**, so the whole stack could never reach a running state on its own. It is
`bootstrap` in `updater.yml`, on by default.

Read [../deploy/README.md](../deploy/README.md) first if you want to know how the stack runs today —
everything below changes how it is *updated*, not how it is shaped.

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
  going a year stale. *(Since 2026-09-02 they seed an empty cache and nothing else — see below.)*

## What it owns

Two things, and they are one thing: **the versions of everything that runs, and the database
schema.** They belong together because a release that adds a table is a release that adds a
migration — the coupling is real, and today it is held only by an operator rule written in prose
("bring the bot up first, it is the only process that migrates").

That rule is gone, carried out the same day. `AccessBot.java:95` was the sole `migrate()` call in
the repository and every plugin says in its own class comment that it never migrates; the call is
in the updater now, the migration SQL stayed where it is in `:common`, and the bot became a client
like every other module — one that checks the schema and refuses a database it was not built
against, rather than one that fixes it.

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
| Paper, Velocity | PaperMC Fill API, newest `STABLE` of the pinned minor | the entrypoint speaks the same API, but only to seed an empty `.server/` from `PAPER_BUILD` / `VELOCITY_BUILD`; from then on it runs whatever build of the version this module put there |

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
3. **Swap.** Fetch into a staging directory inside each server's own volume, verify, and only then
   move everything in and delete the superseded jars — supersede-by-prefix, the same rule
   `entrypoint.sh` used. **Two phases, which the entrypoint did not do**, and for a reason it did
   not have: this moves eight artefacts across four servers at once, and a network running four
   servers on two versions of the season is worse than one that did not update. A server whose
   plugins cannot all be resolved is skipped whole — DisplayTags is a *required* plugin of `smp`
   and PacketEvents is required under it, so a partial swap there is a server that does not start.
   The server jar is outside that rule (2026-09-02): a build Fill could not answer for is its own
   skipped row and the plugins move regardless, because they depend on the version, not the build.
4. **Set the pack.** Write the release's pack URL and the `.sha1` asset's content where
   network-control reads them.
5. **Report.** Post the result: per artefact, old → new, "unchanged", or **"skipped, and here is
   why"** — which is a third answer and not a quiet fourth kind of "fine". A run where every server
   was skipped because its volume was not mounted did no work and had no failure, and it must not
   close with "Nothing needed doing"; that sentence is how somebody shuts the report believing the
   network is current. **This all happens before anything restarts**, which is the whole reason the
   order is this way round.
6. **Restart, on a button.** One Arcane redeploy of the whole project, sixty seconds after it is
   asked for, with every player on the network counted down towards it.

**The updater installs its own new jar and does not run it.** It cannot: no process swaps the jar it
is executing and keeps going. It does not need to — the redeploy takes the whole stack down and
back up, the updater included, so the next start picks up the new jar by itself. Which is only true
because the updater, like the bot, **runs from a volume rather than from a jar baked into its
image** (decided 2026-09-01; the baked jar is a floor for the first deployment and nothing else).
Until that changed, the paragraph above was wrong: a redeploy brought the same image back with the
same jar in it.

The implementation consequence is that the redeploy call is fire-and-forget. Arcane answers
long-running operations as a stream of newline-delimited JSON, and the updater is killed part-way
through its own request — so the call waits only for the response to *begin*, and being killed
there is the successful outcome. It is recognised as one: the request row is left `RUNNING`, and the
next start of the container reads a `RESTART` in that state as "the redeploy happened". That is
inference rather than proof, and the row says so rather than claiming certainty.

## The restart, and why not the Docker socket

Arcane exposes a REST API with token authentication — `X-Api-Key`, tokens generated in
Settings → API Keys — and knows project deploy, redeploy, pull and build as streaming operations.
The endpoint is

```
POST /api/environments/{id}/projects/{projectId}/redeploy
```

read from Arcane's own source on 2026-09-01 (`backend/internal/project/handler.go`, release
v2.10.0), because the public documentation names the operations and not their paths.

**Both segments are IDs, and that is the whole of what a person still has to look up.** The
environment is `0` for Arcane's own host and a UUID for a remote agent; the project is a UUID Arcane
generated — *not* the compose project name, which is `nordtal-s2` in every other file in this
repository and answers 404 here. `arcane.project` therefore has no default and the updater refuses
to start without it once `arcane.base-url` is set: an ID is not a thing anybody guesses, and finding
out at the moment somebody has pressed the button is the wrong time.

**The path stays a setting and not a constant**, even though it is no longer a guess: Arcane does not
publish it, so nothing stops a version from moving it, and a moved path should be a line in
`updater.yml` rather than a release of ours. `arcane.base-url` empty means no restart is possible
anywhere, and that is a supported state: the button and the command both answer "Arcane is not
configured" and tell you to click Redeploy yourself.

**One thing this cannot detect.**
[getarcaneapp/arcane#1943](https://github.com/getarcaneapp/arcane/issues/1943) reports a redeploy of
an *already running* project doing nothing while still answering success — which is exactly this
case, since the stack is up when the button is pressed. Reported on one agent at v1.15.3, closed as
*not planned*. The stream that would say what really happened is one this container is killed part
way through reading, so no code here can tell the difference; the check is a human watching the
containers cycle the first time, and it is in `todo.md`.

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

Two surfaces, and neither of them is the updater's own.

- **Discord**, in the admin channel that already exists as `NORDTAL_ACCESS_CHANNELS_ADMIN`:
  `/update` reports as an embed, an **Install** button under it installs, and a **Restart the
  network** button under that starts the countdown. Admin-only, by `discord_user.admin`, checked
  again on every click — a role can be taken away while a message sits on screen.
- **In game**, under `/smp` for admins: `/smp update`, `/smp update apply`,
  `/smp update restart` and `/smp update restart cancel`.

Neither the bot nor the SMP plugin can call the updater directly — it is a separate container. They
reach it **through the database**: a row in `update_request` plus a `pg_notify`, the updater
listening, and the answer written back into the same row. That is the machinery this project
already uses for phase switches, so it is not a new kind of wiring, and it means the request
survives an updater that happens to be restarting.

**Every surface shows the updater's own report, verbatim.** The Discord embed and the chat lines
are the same text `updater apply` prints on the host, rendered once by the process that did the
work. A second rendering is the thing that would eventually disagree with the first.

### The one-minute countdown

A restart takes the whole stack down, so the request is written with `not_before` sixty seconds in
the future and **network-control counts every player down towards it** — wherever they are, limbo
and Hunger Games included. That is why the proxy owns the announcement and not the SMP plugin: the
proxy is the only process that sees everybody, and a restart asked for *in Discord* has to warn
people too.

The countdown is also the confirmation. A chat line has no button to press, so `/smp update restart`
does not ask "are you sure" — it starts a minute that everybody sees and that
`/smp update restart cancel` (or the button in Discord) stops. The length is a constant in `:common`
rather than a setting, because three processes submit restarts and a fourth renders the countdown:
a value configured in four files is a counter that reaches zero while nothing happens.

### Poll first, notify second

The updater holds a dedicated `LISTEN nordtal_update` connection outside its pool **and** polls
every fifteen seconds, exactly as network-control does for the phase. The poll is the guarantee;
the notification is what makes a button feel instant. Measured on a container against a real
PostgreSQL on 2026-09-01: **160 ms with the notification, 17 s without it.** The wait is also
shortened automatically when a countdown ends sooner than the next poll — an 8-second countdown
fired after 8.97 s, not after 15.

`LISTEN` crossing a container boundary was an open assumption in
[state-of-play.md](state-of-play.md) when this was designed. It is closed: the two numbers above are
from two containers on a Docker network. What is still open is the *reconnect* behaviour under a
real dropped socket, which is the same open item the phase listener has.

The bot and the SMP plugin **poll and do not listen** — the bot re-reads one indexed row every two
seconds while an admin waits, and the proxy every five seconds for the countdown. A second dedicated
connection per backend would be real cost for a countdown that is already honest about the number of
seconds it is showing.

## What it deliberately does not do

- **It does not update on its own.** No schedule, no watching. A crash restart at three in the
  morning must not move a version — the container comes back on exactly what it was running. This
  was the first decision taken and everything else follows from it.

  The startup bootstrap (2026-09-02) is the one thing that runs unasked, and it is bounded so that
  it cannot break this: it installs only artefacts with *nothing* installed at all. An empty volume
  is not a version to preserve. Anything already carrying a jar is left exactly as it is, so the
  sentence above stays literally true for every container that has ever run.
- **It does not roll back by itself.** A run can be given an explicit tag instead of "newest" —
  `season-release`, `display-tags-release`, and since 2026-09-02 `paper-build` / `velocity-build`
  for the platform — which is the rollback, and it is a person's decision.
- **It does not touch worlds, configuration files inside volumes, or anything a player built.** It
  moves jars, one zip's URL and hash, and the schema.

## Why not the alternatives

| instead | why not |
|---|---|
| A host script (`deploy/update.sh`) | Simplest by far, and it was the recommendation. Rejected because the button in chat was wanted, and a host script cannot offer one. |
| Inside the discord bot | No new container, and the buttons are already there. Rejected because a process cannot update itself — and the bot's own version is one of the things that moves. |
| Each container updates itself on restart | Then a nightly crash splits the network across versions and nobody ordered it. |
| Watchtower-style image watching | The plugins are jars in volumes, not images. It would cover the bot and nothing else. |

## Four things this displaced, decided 2026-09-01

None of them was in the original design; each came out of building a step and finding that the step
did not fit the deployment as it stood.

**The updater owns the plugin jars, and `entrypoint.sh` stops fetching them.** *(Carried out in step 3.)* They would otherwise
collide: the script pulls `<module>-${SEASON_VERSION}.jar` on every start and deletes, by prefix,
every other version of the same plugin. An updater that puts `0.3.0` into a volume while `.env`
still says `0.2.0` would have the next restart delete exactly the jar it just fetched. So
`SEASON_PLUGINS` and `EXTRA_PLUGIN_URLS` go away in step 3 and the entrypoint keeps only the server
jar and the datapacks. The price is stated plainly: **a volume that no updater run has touched has
no plugins**, and the container's "refuse to start rather than run an older jar" guard goes with
them. What replaces it is `EXPECTED_PLUGINS`: each service names the filename prefixes it has to
have, and a folder missing any of them stops the container. It counted jars until 2026-09-02, which
is one bootstrap outage away from a server that starts with a third-party plugin and no season. That is consistent rather than new — this module is the bootstrap already, because it owns
the schema.

*The server jar followed on 2026-09-02, for the identical reason found a day late:* this module
installed build 125 into `.server/` and superseded 121, the entrypoint built the name
`paper-26.2-121.jar` from `PAPER_BUILD`, found it gone, fetched it again and deleted 125 — on every
restart after every apply, with a Fill API call in the middle of each one. The entrypoint now runs
whichever build of `SERVER_VERSION` is in the cache and reads `SERVER_BUILD` only into an empty one.

**The pack's URL and hash live in `pack.yml`, not in the environment.** *(Carried out in step 3.)* They were made compose
variables earlier the same day, for a good reason: they were reachable only by editing a file
inside a volume. That is being partly taken back, because of how jcore's config system works —
**an environment override wins over the file and is never written back to it.** An updater writing
a new sha1 into `pack.yml` while `NORDTAL_NETWORK_CONTROL_PACK_SHA1` is set would be writing into
a value nothing reads: a swap that reports success and changes nothing, which is the worst outcome
on the list. So `PACK_URL` and `PACK_SHA1` leave `compose.yml` and `.env.example` again in step 3,
and the hand-copying of a hash out of a release disappears instead of moving.

**The updater stopped being a command and became a service.** *(Carried out in step 4.)* Steps 1 to
3 were one-shot runs and the container was documented as one — "it must never be given a restart
policy". A button in Discord needs something to answer it, so `serve` exists, the container has no
profile, and it comes back with `restart: unless-stopped` like everything else. **The rule it was
protecting is untouched**: `serve` has no timer and no watch, and does nothing whatever until a row
appears. What changed is that a process is there to be asked; what did not change is that nothing
asks on its own.

**Everything now waits for the schema, and compose says so.** *(Carried out in step 4.)* A redeploy
starts the whole stack at once, and the updater is the only process that migrates — so a plugin
could come up against a schema older than itself, which is the failure this module exists to
prevent. The updater touches `/tmp/updater-ready` once the schema is current, that is its
healthcheck, and every other service has `depends_on: updater: service_healthy`. This is safe where
the deliberate *absence* of a `depends_on` on the database is not: the updater has no profile, so
depending on it cannot drag in a service nobody asked for.

## Open, with a fallback each

- **Arcane's redeploy endpoint.** Narrowed on 2026-09-01 and not closed. The path is no longer
  unknown — it was read from Arcane's own source — but the two IDs are on Till's instance and so is
  the token. Everything around it is built and tested: the URL both IDs are substituted into, the
  header, and what each answer is reported as, against a real HTTP server; a 404 was driven end to
  end through the container. **A 2xx from a real Arcane has never been seen**, and arcane#1943 says
  a 2xx would not by itself prove the restart happened. *If the API turns out not to expose a usable
  redeploy, or answers success without doing anything:* leave `arcane.base-url` empty and everything
  else still works; the restart is a click in Arcane, and both surfaces say so rather than
  pretending. [`../../todo.md`](../../todo.md) carries both checks.
- **The startup bootstrap has never filled a real empty volume.** Built and unit-tested on
  2026-09-02: the filter is pinned by `UpdatePlanTest` (an `OUTDATED` row can never reach it, an
  unreachable source is not mistaken for an empty volume), and the whole path is the same
  `Runs.apply` that `updater apply` uses, so it is not new code doing the installing. **What has not
  happened is one real first deployment**: four server jars and every plugin downloaded before the
  readiness marker, inside the fifteen minutes the healthcheck allows. *If it turns out too slow or
  it fails part way:* the container becomes ready anyway and the Minecraft entrypoint stops with the
  name of the empty folder, so the failure is legible; `docker compose run --rm updater apply` is
  the same work with a person watching, and `UPDATER_BOOTSTRAP=false` turns it off.
  [`../../todo.md`](../../todo.md) carries the check.
- **The four GHCR packages are private until somebody makes them public.** A package under an
  organisation is private on its first push, and a private package answers a pull with exactly the
  `denied` that a non-existent one does — the error that started this, and an error that names
  neither cause. *If making them public is not wanted:* Arcane takes a registry credential instead,
  at the cost of a token that expires and whose expiry looks like this same error a year later.
  [`../../todo.md`](../../todo.md) carries it.
- ~~**`LISTEN` across containers.**~~ **Closed 2026-09-01**: 160 ms with the notification against
  17 s without it, two containers on a Docker network. The reconnect path under a real dropped
  socket is still untested, which is the phase listener's open item and not a new one.
- ~~**The bot becomes a jar.**~~ **Built 2026-09-01.** It runs `discord-bot-*.jar` out of the
  `bot-jar` volume, which the updater fills like any plugins folder. The image is still built and
  still pushed, and the jar inside it is now a **floor**: it is used only while the volume is empty,
  which is a first deployment and nothing else. The updater's own container works the same way, and
  had to — without it, a redeploy brought the same image back with the same jar in it and the
  self-update paragraph above was false.
- **What happens to a migration that fails.** Flyway stops, the jars have not moved yet — that is
  why migrate comes before the swap — and the report says so. A failed migration is the one outcome
  where the updater must refuse to continue rather than do half a run. In `serve` it is stronger
  still: the container never becomes healthy, and `compose.yml` makes every other service wait for
  that, so nothing starts against a schema this build does not know.
- **Two updaters at once.** The daemon and a hand-run `apply` can overlap, and on a bootstrap day
  they will. An apply takes a PostgreSQL advisory lock (`nordtal1`, held on a dedicated connection
  so the pool cannot leak it) and the second one to ask is **refused rather than queued** — a plan
  resolved now would be stale by the time it got its turn. Verified in containers on 2026-09-01,
  from both directions.

## Implementation order

Built piece by piece, and each piece is useful on its own:

1. ~~Resolving versions from all four sources, and reporting the difference. No writes at all.~~
   **Built 2026-09-01.** 57 tests against payloads recorded from the live GitHub, Modrinth and
   Fill APIs that day, plus a volume tree on disk. `TopologyTest` reads the real `compose.yml`, so
   a fifth backend server added there and not here fails the build.
2. ~~Flyway moves from the bot into the updater; the bot becomes a client.~~ **Built 2026-09-01.**
   `AccessBot`'s `migrate()` became `SchemaCheck.validate()`: the bot runs Flyway's own validation
   at startup and refuses a database it was not built against, naming `updater migrate`. Tested
   against a real PostgreSQL, unmigrated and migrated. The plugins do not validate and will not —
   that needs Flyway, and Flyway must never be shaded into a Paper plugin.
3. ~~Swapping jars into the volumes, and setting the pack URL and sha1.~~ **Built 2026-09-01.**
   Two-phase: everything a server needs is staged inside that server's own volume and only moved in
   once all of it is there, so a failed download leaves the server as it was. A server whose
   artefacts cannot all be resolved is skipped whole. `SEASON_PLUGINS`, `EXTRA_PLUGIN_URLS`,
   `PACK_URL`, `PACK_SHA1` and `SEASON_RELEASE` are gone from the deployment, `entrypoint.sh` no
   longer fetches plugins and refuses to start unless every plugin its `EXPECTED_PLUGINS` names is
   present instead, and the four compose mounts are writable. 75 tests.
4. ~~The Discord command, the embed, and the restart button.~~ **Built 2026-09-01.** `/update`,
   admin-only by `discord_user.admin` and re-checked on every click. One command; the rest are
   buttons, in the order that makes them safe — report, then install, then restart. The bot never
   blocks a thread waiting: the answer row is re-read on the timer it already has, so an install
   that takes four minutes costs nothing while it runs.
5. ~~The Arcane redeploy.~~ **Built 2026-09-01**, and configurable rather than guessed at: the base
   URL, the token, the project and **the path** are all settings, because the path is not published
   and a wrong constant would have to be a release. A 404 explains itself. An empty base URL is a
   supported state.
6. ~~`/smp update` in game, over `NOTIFY`.~~ **Built 2026-09-01**, with the restart included and a
   one-minute countdown that every player on the network sees — announced by the proxy, not by the
   SMP plugin, because a restart takes down limbo and Hunger Games too.

Two things this order got right and one it did not. Step 1 was the one worth building carefully, as
predicted: an updater that resolves the wrong version is worse than no updater. Step 3 before step 2
avoided a deployment that was temporarily worse than the one before it. What the order missed is
that **steps 4 and 6 need a process that is running**, which the first three did not — the container
stopped being a command and became a service, and that was not written down anywhere until it had
to be.
