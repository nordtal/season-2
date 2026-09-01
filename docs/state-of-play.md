# State of play

Where season 2 actually stands, what can be built without another planning session, and what
cannot.

**Re-derived from the code on 2026-09-01, and again the same day after `limbo` and the pack station
were built.** An earlier version of this document was written on
2026-08-31 *before* that evening's implementation work and then committed *after* it (`6fcede9`,
23:33, against `e1dd4ba`, 21:17): it described `hunger-games` as a 22-line scaffold, `common`'s
`SeasonPhase` as holding three wrong values, `Glyphs` as season 1's inventory and the whole of §2 as
unbuilt, all of which had stopped being true hours earlier. That is the failure mode this document
warns about in its own last line, so the numbers below were read out of the repository again rather
than edited.

This is a survey of the **code**, not of the plan. Where a document says something the code does not
support, that is recorded as a finding rather than smoothed over. The plan lives in the other
documents and is not repeated here.

It is expected to go stale. Re-derive it rather than trust it once a module has been implemented.

## 1. Where we stand

### The short version

| module | main Java | tests | what actually runs |
|---|---|---|---|
| `discord-bot` | 43 files, 6054 lines | 112 | Access end to end, the phase command, the admin mirror, the language list, hunger games registration |
| `hunger-games` | 41 files, 3971 lines | 57 | The start event, essentially in full |
| `network-control` | 29 files, 4110 lines | 120 | Login gate, phase, play time, routing, **the pack station** |
| `common` | 26 files, 2671 lines | 98 | Access API, messages, locales, phase, glyphs, **the limbo protocol**, V1–V6 |
| `resource-pack` | — | — | Three fonts, every code point allocated and drawn |
| `limbo` | 10 files, 1139 lines | 11 | The waiting room, in full |
| `smp` | 22 files, 2912 lines | 56 | The track, aura, prestige and the milestone engine — no world |

454 tests, none skipped, all green with a Docker daemon present (`./gradlew build`, 2026-09-01).
(This line read 435 while the module rows below it added up to 438; both are re-counted from the
JUnit XML above.)

**No module is a scaffold any more.** What is left is not a module but a half of one: everything in
`smp` that touches a world.

### `common`

Everything the plan gives this module exists:

- `access/` — `AccessDirectory` and the login query, unchanged and well covered.
- `message/` — `Messages`, `Locales`, and **`PlayerLocales`**, the join-time component
  [i18n.md](i18n.md) asks for: loaded once at join, held for the session, never re-queried from a
  render path, English for a player nobody called `join` for.
- `phase/` — `PhaseDirectory`, `PhaseDao`, `JdbiPhaseDirectory`, `PhaseChange`. The switch and its
  audit entry are one method, which is what [season-phases.md](season-phases.md#who-may-switch-it)
  demands of two writers.
- `SeasonPhase` — the four agreed values, with `fromDatabase` falling back to `MAINTENANCE`.
- `Glyphs` — season 2's inventory across all three fonts: the donor star, thirteen prestige crests,
  the board frame pieces, the bossbar icons and arrows.
- `limbo/` — `LimboProtocol` and `WaitReason`, the `nordtal:limbo` wire format (2026-09-01). It is
  here rather than in either module that speaks it because both ends are in this repository and a
  format written twice drifts; a plugin message that does not parse looks exactly like one that was
  never sent, so the failure would be a player sitting in the waiting room forever with nothing in
  any log.
- `db/migration/` — V1 access, V2 bot state, V3 bot setting, V4 phase/admin/playtime, V5 hunger
  games, **V6 the SMP** (2026-09-01).

### `network-control`

- `gate/` — `LoginGate` decides per phase; `MisconfiguredGate` is the deny-all `LoginEvent` handler
  that makes a broken config fail closed; `FallbackCache` and `ExpiryWatch` unchanged.
- `phase/` — `PhaseWatch` (30-second poll, last-known-phase fallback), `PhaseListener` and
  `PostgresPhaseNotifications` (`LISTEN nordtal_phase` on a dedicated connection outside the pool),
  `PhaseCommand` (the emergency `/phase`, authorised by the admin flag).
- `playtime/` — `PlaytimeWriter` / `PlaytimeStore` / `PlaytimeDao` into `player_playtime`, flushed
  on disconnect, on shutdown and every 300 seconds.
- `routing/` — `PhaseServers`, `PhaseRouting`, `PlayerRouter`, `RouteDecision`. A phase change
  re-routes everyone; a `MAINTENANCE` login lands in `limbo`; a switch to `SMP` disconnects a player
  without access, and `decideInitial` puts every login in `limbo` first.
- `pack/` — **built 2026-09-01, and it was the module's last gap.** `PackStation` (the forced
  offer, the `nordtal:limbo` channel, the release), `PackOffer` (one `ResourcePackInfo` per language,
  id derived from the pack's own hash), `PackMessages` and `LimboHold` — the last of which is the
  pure rule deciding what a waiting player is told, and the only part of the station a test can
  reach.

`PlayerRouter` now sets `limbo` as the initial server in **every** phase, `PhaseRouting#decideInitial`
is that rule, and a proxy with no waiting room refuses the login rather than falling through to
`velocity.toml`'s `try` list. `pack.yml` is new and is the first config file in this repository whose
values change on a release cadence.

**What this module still owes is a rehearsal, not code.** It owns three open verifications and none
of them can be answered here — see [the unverified assumptions](#the-unverified-assumptions) below.

### `discord-bot`

- `access/` — the sales, matching, linking and admin surface, unchanged and deployable.
- `discord/PhaseCommand` — `/phase set`, the normal path for switching the phase.
- `discord/AdminFlagDao` — the admin-role mirror into `discord_user.admin`.
- `config/Languages` + `DefaultLanguages` — the `languages` list; a third language is a config edit,
  proven by a test that drives an `fr` entry no source file mentions.
- `hungergames/` — `RegisterFlow`, `Teams`, `RegisterMessages`, the invitation accept/decline, the
  managed message per language channel.
- The bunq environment is a config key rather than a hardcoded `PRODUCTION`.

What it does not have is an SMP surface — announcements, admin commands — and that is correct: the
module it would talk to does not exist.

### `hunger-games`

Built on 2026-08-31 and covering nearly the whole of [hunger-games.md](hunger-games.md):
`border/` (`BorderController`, `BorderMath`), `loot/` (`LootRefill` with four configured tiers),
`hud/` (`HudRenderer`, `BossBarWidth`, `Bearing`), `lobby/` (`Lobby`, `LobbyMaps`), `body/`
(`PlayerBodies` — the disconnected body that stays and stays vulnerable), `color/` (`TeamColours`,
the generated palette and its nearest-named mapping), `game/` (`HungerGamesManager`, `SpawnTowers`,
`WinTracker`, `Tiebreak`, `Demotion`, `Ceremony`), `db/` and `command/HungerGamesCommand`
(`/hg start`, `confirm`, `ready`, `ready-status`, Brigadier only).

The loot pools, the quiet period and the passive shrink rate — three of the four "still open" config
points in that document — now exist as defaults in `HungerGamesSpec` and `DefaultRefillTiers`.

**Changed on 2026-09-01, from outside its own session:** the join-time language lookup moved off the
main thread and `database.yml` gained a `query-timeout-seconds`. Nothing about the game changed —
see finding 12 below for why a `limbo` session touched this module at all.

**Changed again on 2026-09-01, second pass** — findings 15 and 16, plus a message-key sweep:

- `/hg start` no longer runs its database work on the main thread, and the roster read moved out of
  the release tick.
- The **lobby countdown speaks**. It said nothing at all: players were teleported onto a tower,
  frozen and released a minute later with no text on screen. `Countdown` is the (tested) rule for
  when it speaks; `hg.start.countdown` had been written and translated the whole time.
- A **demoted duo is told it was demoted.** `Participant#demotedToSolo` had been computed and
  tested since the module was built and read by nothing.
- The **ceremony can tell the four endings apart**, so a win decided on the kill count is announced
  as one.
- Nine unreachable message keys: three wired up as above, three deleted (a duplicate of the winner
  line, a console guard Brigadier makes unreachable, a database-error text with no catch block near
  it), and two — `hg.lobby.map-missing` and `hg.loot.point-lost` — annotated in both language files
  as deliberately unsent, with the reason. `MessageBundlesTest` now holds the two files symmetric.

What is missing is not code:

- The **world folder** — the hand-built map, lobby box, spawn towers and loot points. The plugin
  disables itself rather than run without it.
- The **aerial and rules images**. `lobby/map-en.png` and `map-de.png` ship as dummy placeholders,
  and `LobbyMaps` is deliberately tolerant of them being wrong.
- The **winner's head start**, which is now the SMP's job and not this module's — see below.

### `resource-pack`

Season 1's leftovers are gone: the four role tags are deleted from `default.json` and from the
textures, and `en_us.json` is season 2's. Three fonts are documented and mirrored in `Glyphs`:
`minecraft:default`, `nordtal:bossbar` and `nordtal:board` (added 2026-08-31, so the board frame
does not have to share `default.json`'s metrics).

**Every allocated code point now has a PNG**, generated by `tools/generate_dummy_textures.py` on
2026-08-31 and marked in the pack's README as either `generated — final candidate` or
`generated — placeholder`. That is what makes the HUD and the boards exercisable on a running server
before the real design work happens. The balloon has a scaffolded item model with flat-colour
textures standing in for the 3D model.

### `limbo`

Built 2026-09-01, and it is small on purpose — its entire interface is one title.

- `world/` — `VoidChunkGenerator` (every `shouldGenerate*` false) and `WaitingWorld`, which creates
  the empty world, pins its gamerules and owns the one location anybody stands in. Players are put
  there by `AsyncPlayerSpawnLocationEvent`, *before* they are spawned, so the server's own
  `level-name` world is never seen for a frame.
- `waiting/WaitingRoom` — adventure mode, flying, invulnerable, blind, and the title. The title is
  re-sent on a timer because a Minecraft title expires, and an expired one here is a completely
  black screen with nothing on it, which looks exactly like a hung client. A *change* of reason
  fades in; a refresh does not.
- `net/LimboChannel` — `READY` out, `WAIT` in.
- `listener/PresenceListener` — no join message, no chat, no damage, no hunger, no interaction, and
  every player hidden from every other in both directions.
- `config/`, `db/` — `config.yml`, `database.yml`, and a HikariCP pool of three connections for the
  one query this module ever makes.

The `GameRule` constants were all renamed in 1.21.11 and the old names are `@Deprecated(forRemoval)`;
this module uses `GameRules` throughout, which is worth knowing before the SMP writes its own worlds.
`World#setKeepSpawnInMemory` is a no-op since 1.21.9 and is not called.

### `smp`

**Half built, 2026-09-01: the half that needs no world.** The split is deliberate and follows
docs/state-of-play.md's own §2b/§2c division - what is here can be asserted in a test, and what is
not needs a running server and real clients before it means anything.

- `config/` - `SmpSpec` (`config.yml`), `DatabaseSpec`, and **`MilestonesSpec` + `DefaultTrack`**,
  which is the track of docs/smp.md#the-track written out entry for entry. `DefaultSmp` carries the
  six config points that used to be listed as open: the advancement awards, the death causes, both
  duel loadouts, the winner's head start, the wheel's pool and the spawn regions.
- `milestone/` - `MilestoneTrack`, `Milestone`, `Objective`, the three enums, `StoredProgress`,
  `TrackShape` (is this file a track at all), `TrackValidation` (may it replace the running one) and
  `ObjectiveProgress`.
- `aura/` - `AuraPayout` (the 30/70 split, the 2 % threshold, the minimum share and the admin
  scaling), `AuraReason`, `DeathPenalty`.
- `prestige/` - `Prestige`, thirteen tiers derived from `player_playtime.seconds` and never stored.

`build.gradle.kts` takes jcore, HikariCP, jdbi3-postgres and - `compileOnly`, never shaded -
`com.github.nordtal:papermc-display-tags:2.0.0`; `paper-plugin.yml` declares DisplayTags with
`load: BEFORE` and `required: true`. The jar is 4,619,974 bytes.

**What is not here is the whole world half**, and it is the larger half: the farm-world swap, the
balloon and the travel rules, portal gating, spawn protection, graves, POIs, `/navigate`, the duel
arenas, the per-player Text Display boards, the wheel, the HUD, the commands, the DAO, and the
plugin main class that would wire any of it together. `SmpPlugin` is still the scaffold's two log
lines.

### Where the documents and the code disagree

The nine findings of the previous version are resolved as follows, with everything found since
appended. **Only finding 9 still stands** — the sentence here read "only two still stand" while
the table underneath it had one row marked as standing, which is the same class of drift the table
exists to record.

| # | what it was | now |
|---|---|---|
| 1 | The login gate is phase-blind | **closed** — `LoginGate` decides per phase, `MAINTENANCE` holds in `limbo` |
| 2 | The proxy accepts logins un-gated on a broken config | **closed** — `MisconfiguredGate` |
| 3 | Two files point at a deleted document | **closed** 2026-08-31 |
| 4 | `docs/README.md` overstates `network-control` | **closed** — four of the five things are built; only the pack station is not |
| 5 | `app.simplecloud.api:api` is a dependency nothing imports | **closed 2026-09-01** — the dependency, its two repositories and its catalog entry were removed. Routing resolves backends by `gate.yml` name through `ProxyServer.getServer(name)`, which is exactly the fallback that had been written down for it |
| 6 | `link-code-ttl-minutes` duplicated | **closed** — retired from `AccessSpec`, with a test asserting the key is refused |
| 7 | The jar-size claim was wrong | **closed** — corrected in both documents |
| 8 | A whole font was undocumented | **closed** — the pack's README owns all three fonts |
| 9 | The bossbar font's positive space advances sit outside the private-use area | **stands.** `！` and friends are `FULLWIDTH EXCLAMATION MARK`, not private use, while the pack states its range as ``–``. Confined to `nordtal:bossbar` and `nordtal:board`, so nothing is broken; moving them would change the HUD code that composes the bar, which now exists |
| 13 | **New 2026-09-01:** jcore's config system had never been asked for two levels of nesting, and nothing said whether it could do it | **closed by building it.** It can - a `List<NestedSpec>` inside a `NestedSpec` round-trips - but **every nested interface needs its own `@ConfigSpec`**, and one without it fails as a Gson error about making `java.lang.reflect.Proxy#h` accessible, which names nothing useful. `MilestonesTest` is the standing proof; this is worth knowing before the next nested config is written |
| 12 | **New 2026-09-01:** `hunger-games` read a player's language with a blocking JDBC call from its `PlayerJoinEvent` handler, on the main thread, and its pool had no timeout of any kind | **closed the same day, in both modules.** `PlayerLocales#joinAsync` runs the lookup on Bukkit's async scheduler; `limbo` redraws its title when the value lands, `hunger-games` needs no redraw. Both `database.yml` files gained `query-timeout-seconds` (default 3), setting HikariCP's `connectionTimeout` and the driver's `socketTimeout`. Found while building `limbo`, where the same line would have frozen the server every login passes through |
| 11 | **New 2026-09-01:** the knowledge base named `objects.githubusercontent.com` as what a GitHub release asset redirects to | **closed by measurement.** It is `release-assets.githubusercontent.com`, one hop, with a signed URL that expires within the hour. Corrected in [resource-pack/README.md](../resource-pack/README.md#hosting) and in `PackSpec`'s own comment, which now says in capitals not to paste the resolved address into the config |
| 10 | **New 2026-09-01:** the knowledge base described three modules as unbuilt that were built hours earlier, and `season-2/CLAUDE.md` still opens with "`hunger-games`, `smp` and `limbo` are still scaffolds" | **closed by this pass** in both places. The cause is worth keeping: a documentation commit that lands *after* an implementation commit is not evidence that it describes it |
| 14 | **New 2026-09-01, second pass:** §3 of this document still listed six config defaults as open — duel loadouts, the advancement awards, the "embarrassing" death causes, the objectives' items, the wheel's pool, the winner's head start — while its own §2b, `docs/smp.md#still-open`, `DefaultSmp` and `DefaultTrack` all said they had been written the same day | **closed by reading the code.** All six are in `DefaultSmp`/`DefaultTrack`; §3 now says so. The cause is the one this document keeps rediscovering: a list that lives in a different file from the thing it describes goes stale silently, and a *closing* edit is as easy to miss as an opening one |
| 15 | **New 2026-09-01, second pass:** `/hg start` did every one of its database calls on the main thread. `HungerGamesCommand#runAsAdmin` checked the admin flag asynchronously and then handed the *action* back to `runTask`, so `dao.game`, `dao.roster`, `HungerGamesManager#start`'s second roster read and its one colour write per team all landed on the server thread — and `winTracker.reset(dao.activeMembersOf(…))` ran inside the `onReleased` callback, in the tick every participant is released | **closed the same day.** `runAsAdmin` runs the action on the async task it is already on, the messages hop to the main thread through a `tell` helper, and the roster read moved up into `HungerGamesPlugin#startGame`, which also closes a race the callback had. `HungerGamesManager#start`'s own closing `runTask` had been the standing evidence that it was written to be called from off-thread; nothing read it. This is **finding 12 again, in the same module**, three weeks of nothing between them |
| 16 | **New 2026-09-01, second pass:** `WinTracker.Outcome` carried a `tie` flag no caller ever read, and `Outcome::win` set it to `false` even for a win the tiebreaker produced. A game decided on the kill count was announced as an ordinary win, and `hg.win.tie-broken` and `hg.win.no-winner` — written, translated and carrying `{winnerKills}`/`{loserKills}`/`{kills}` — were unreachable | **closed the same day.** `Outcome` now has four constructors for the four endings and carries the two kill counts; `Ceremony` prints all four. "Everybody dead, no simultaneous pair" is `noWinner()` rather than a tie, because nothing was compared |

## 2. What can be built today

Everything in §2 of the previous version — the phase model, the admin flag, the language list and
the join-time locale component, the two `network-control` config decisions, the glyph clean-up and
the play time counter — **is built**. What follows is what is genuinely left, in dependency order.

### a. `limbo`, and the pack station in `network-control` — **built 2026-09-01**

The three artefacts no document named have been decided and written:

- **The channel is `nordtal:limbo`**, and it runs both ways: `READY` from the backend once per join,
  `WAIT <reason>` from the proxy whenever the reason changes. Two bytes of header, one UTF string for
  a `WAIT`, and the codec in `:common` so the two ends cannot drift.
- **The waiting reasons are `PACK`, `BACKEND`, `MAINTENANCE` — and `UNKNOWN`**, which is the fourth
  nobody asked for and which exists because a black screen with no text on it is what a crash looks
  like. All four are translated in both languages.
- **The pack URL and hash live in `pack.yml`**, a new file in `network-control` with its own
  environment namespace. Not `gate.yml`: these change on every pack release and that file decides
  who may join.

**What is left is the rehearsal, and it cannot be done from a build machine.** The three open
verifications this session owned are still open, each is now a numbered step in the owner's
checklist, each is a row in [the table below](#the-unverified-assumptions) with its written fallback —
so the phase is *built and unproven*, which is a different thing from *unbuilt*.

One measurement did come out of it. `curl` against a real GitHub release asset on 2026-09-01: one
`302`, to **`release-assets.githubusercontent.com`**, with a signed URL that expires within the
hour. This knowledge base said `objects.githubusercontent.com`, which is wrong and is corrected. It
does not settle whether a Minecraft client follows the redirect — only where it goes.

### b. The SMP's server-free core — **built 2026-09-01**

**Specified by** [smp.md](smp.md). **Touches** `smp`. **Depends on** V6, which exists.

Three pieces of the SMP are pure logic, testable the way `BorderMath` and `Demotion` are tested in
`hunger-games`, and none of them needs a world, a packet or a player:

- **Aura.** The ledger, the death penalties, the duel stake, and the payout formula — 30 % equally
  among qualifiers, 70 % by share, a 2 % qualifying threshold, a minimum of 1 aura per qualifier
  taken out of the proportional part, everything floored. The rounding cases are exactly the kind of
  arithmetic that is cheap to test and expensive to discover in production.
- **Prestige.** The tier derived from `player_playtime.seconds` against the thirteen configured
  thresholds. Never stored, so it is a pure function.
- **The milestone engine.** The YAML loader and its validation — which must refuse a change that
  orphans stored progress while explicitly permitting a lowered `target`, or the first escape hatch
  does not exist — progress accounting per objective type, and the three escape hatches with their
  `pot × (reached ÷ target)` payout.

### c. The SMP's world half — **the one implementation session left**

Same document, same module, but every feature ends at a rehearsal rather than a green build: the
farm-world swap, the balloon and the travel rules, portal gating, spawn protection, graves, POIs,
`/navigate`, the duel arenas, the per-player Text Display boards and the wheel.

**Measure Nordtal's one-off pre-generation to border 4000 in this session's first hour**, not its
last. It is the cheapest measurement in the plan and it is the one that decides whether the final
milestone is deliverable at all.

**Nametags come from `papermc-display-tags`** — decided 2026-09-01. `smp` takes
`com.github.nordtal:papermc-display-tags:2.0.0` from JitPack as `compileOnly` and declares
DisplayTags in its `paper-plugin.yml`. The consequence is operational and is stated in
[../deploy/README.md](../deploy/README.md#third-party-plugins): **PacketEvents becomes a required plugin on the
SMP server**, which makes it the network's first mandatory third-party runtime dependency —
CoreProtect, the only other one, may be absent without anything failing.

### d. The hunger games winner's head start

**Specified by** [hunger-games.md](hunger-games.md#after-the-game) and
[smp.md](smp.md#the-hunger-games-winners-head-start). **Touches** `smp` only.

Decided 2026-09-01, and the decision moved the work: **the SMP grants it on the winner's first
join**, deriving the winner from `hg_game.winner_member_id` of the `DECIDED` game.
`hunger-games` writes nothing into the SMP's tables. V6's
`smp_player.hg_winner_reward_granted` is the only state involved, because it is the only thing the
SMP cannot derive.

## 3. What still needs a decision

No design decision blocks an implementation session. What is listed here is either concept work of
its own, or a config default that is cheaper to propose in a diff than to argue in prose.

### Concept work — the one item here is now built

**PostgreSQL backup and restore — built 2026-09-01, and the cycle was run.** `deploy/`'s new
`backup` profile dumps the database into a `postgres-dumps` volume of its own, daily and at
start-up, and Arcane's volume backup ships *that* volume to S3. The shape was decided by reading
Arcane's source rather than its release notes: it stops the containers using a volume only when the
policy's `StopContainers` flag is set, and for a live PGDATA both settings are wrong — off gives a
torn copy that fails at restore rather than at backup, on gives a nightly outage of every process in
the stack. Dump → `pg_restore` into an empty database → both seeded tables back, plus a retention
sweep and a SIGTERM, all run on 2026-09-01; see
[../deploy/README.md](../deploy/README.md#what-was-measured). The first run on a fresh named volume
*failed*, on ownership, which is the argument for running it.

**What is still owed is the drill on the real thing**: a dump pulled back out of S3 and restored.
That needs the host and is in the owner's checklist. Backups that have never been restored are a
belief, not a backup — which is what this row said when it was a gap, and it still applies.

**The deployment runbook is no longer part of that gap at all.** SimpleCloud was dropped on
2026-09-01 in favour of a single `docker compose` stack, and the stack was built the same day:
[`deploy/`](../deploy) holds the compose file, the image for all four Minecraft services, the
entrypoint and the runbook. It is **measured, not just written** — a Velocity and a Paper container
were run from that image, the pinned build resolved and checksum-verified through the Fill API, the
console proved writable from a `docker exec`, and `docker stop` shut Paper down in 3 s with
`All dimensions are saved` in the log. See
[../deploy/README.md](../deploy/README.md#what-was-measured-and-what-it-cost), which also records the one design that had to be
thrown away.

What is left there is one open verification and one manual step, not a build: whether the
*interactive* `console` attach behaves inside Arcane's browser terminal (`mc <command>` through a
plain exec is already proven, and is the fallback), and pasting the Velocity forwarding secret into
each backend's `config/paper-global.yml`. Neither blocks anything before the first deployment.

### Config defaults — **there are none left open**

This section used to carry a table of six. **All six were proposed as defaults on 2026-09-01**, in
the same session that built the SMP's server-free half, and they are in the code: `DefaultSmp`
holds the advancement awards, the "embarrassing" death causes, both duel loadouts, the winner's
items, the wheel's prizes and weights and the spawn regions, and `DefaultTrack` holds the items and
advancements behind every objective of the track. [smp.md](smp.md#still-open) says the same thing.
The table survived that session because it lives in a different document from the code it described
— see finding 14.

They are *proposals*, and the whole point of putting a proposal in a config file is that correcting
it is a diff rather than an argument. [The numbers that are proposals rather than
decisions](smp.md#numbers-that-are-proposals-not-decisions) is the list to read before retuning any
of them.

The hunger games' own three — loot pools, quiet period, passive shrink rate — were proposed as
config defaults on 2026-08-31 and are likewise no longer open.

### Technical choices an SMP session has to make for itself

None of these is a design decision the concept withheld; they are implementation choices the concept
never had reason to name, and an implementation session should make them and write them down rather
than stop.

- **What the spawn NPC is.** A villager with AI off, a custom entity, or a player-skin NPC. Citizens
  would be a second mandatory third-party dependency and should be argued for explicitly if chosen.
- **The milestone YAML's file format** — the table in [the track](smp.md#the-track) is the content,
  not a schema.
- **How `smp_grave.contents` is serialised.** The column is `bytea` precisely so this stays the
  plugin's choice.
- **The config shape of the spawn protection regions** — a list of boxes, per world.
- The contents of the balloon, hand-in and wheel GUIs beyond what the concept states.

### Not decisions at all — writing, drawing and building

| open point | source |
|---|---|
| The server rules as written for players, both languages — before the SMP phase opens | [smp.md](smp.md#still-open) |
| The spawn build: tavern, balloon model, duel platforms, boards, NPC — with the balloon between radius 10 and 21.5 | [smp.md](smp.md#spawns) |
| The hunger games world folder, lobby, towers, loot points, and the aerial images per language | [hunger-games.md](hunger-games.md#the-lobby) |
| The real artwork behind the generated placeholder glyphs | [resource-pack/README.md](../resource-pack/README.md#code-point-allocation) |

### The unverified assumptions

Nothing below has been confirmed, and **every one of them has a written fallback** — which is the
point: an unverified assumption with no fallback is a decision nobody has made yet, and one with a
fallback can be answered *no* without blocking anything. The fallbacks are design and live here.
**The steps that produce the answers do not**: they need a running server, a real client or the
production host, so they are a checklist for the owner rather than a document, and they live in a
`todo.md` outside this repository. Ask for it; it is deliberately not published.

| what is unverified | who owns the answer | **if the answer is no** |
|---|---|---|
| **A Minecraft client follows GitHub's redirect** when downloading the pack. The redirect itself is measured ([resource-pack/README.md](../resource-pack/README.md#hosting)); whether a *client* follows it is not | the login-path rehearsal | Host the zip on a small static HTTP host. The URL and hash are already configuration, so it is a config edit plus one more thing to keep alive and certificated on event day — cheap, but nobody should discover it that morning |
| **A forced pack offer sent by the proxy while the player is being moved to `limbo`** behaves, and both refusal paths work — including whether `PackStation`'s own `disconnect()` wins the race against Velocity's generic forced-pack kick, which is the only way our `DECLINED` text reaches a 1.17+ client | the login-path rehearsal | Offer the pack from `limbo` itself on join — which is the job that module was originally named for, so the prompt simply appears one hop later. If only the *decline text* loses the race, accept Velocity's own wording for that one path and keep everything else: the player is still refused, just less kindly |
| **Disconnecting a player from `PlayerChooseInitialServerEvent`.** Since 2026-09-01 a proxy with no waiting room refuses *every* login rather than letting anybody past the pack station. `player.disconnect()` is documented and the event is `@AwaitingEvent`, but a login-allowed player being kicked *during* initial server selection has not been seen happen | the login-path rehearsal | Set the initial server to one that exists and disconnect from `ServerPostConnectEvent`, or move the check back into the `LoginEvent` gate as a maintenance refusal — the pre-2026-08-31 behaviour, a one-line reversal |
| **Background pre-generation of a 2000 × 2000 world without perceptible lag.** The concept's own biggest technical risk | the SMP session, measured on the real host with players online | The farm world gets smaller — 2000 × 2000 is [a proposal](smp.md#numbers-that-are-proposals-not-decisions) and halving the radius quarters the work. If even a small world lags, pre-generate off-peak only or in a separate process and move the folder in. Operational, never a redesign |
| **Nordtal's one-off pre-generation to border 4000** is affordable in wall clock and disk | the SMP session, on the real host | The final border is the number to reconsider. It is a config default in `milestones.yml`, and [the track](smp.md#the-track) is explicit that every number in it is a default while the *rules* are the decision — so a smaller frontier is a retune |
| **Paper unloading and deleting a loaded world at runtime**, then loading a replacement under the same name | the SMP session, before the reset is built on top of it | **Alternate two names instead of reusing one** — pre-generate into `farm-world-b` while `-a` is live, load `-b`, move players, then delete `-a`. That removes the same-name re-load, the part Paper is least likely to tolerate. Only if unloading fails *at all* does the reset need a restart, and then it becomes an announced daily restart at the quiet hour |
| **`LISTEN`/`NOTIFY` through the pool** — a dedicated connection outside Hikari, a `getNotifications(timeout)` thread, an unconditional re-read on every reconnect | whoever next touches the phase model; [it is built in the first pass](season-phases.md#source-of-truth-and-propagation) | Drop `NOTIFY`, keep the 30-second poll that was always the actual guarantee. A switch takes up to thirty seconds instead of feeling instant. No redesign, one paragraph deleted |
| **`console` — the *interactive* attach — behaves inside Arcane's browser terminal.** `mc <command>` through a plain `docker exec` is [already verified](../deploy/README.md#what-was-measured-and-what-it-cost) | the first deployment | Use `mc` plus the log view — send-and-read without a TTY, which covers every command a runbook issues. Only live scrollback is lost, and `docker exec -it <container> console` over SSH still gives it |
| **Simple Voice Chat on 26.2** | before the event rehearsal | Dropped without replacement, as [hunger-games.md](hunger-games.md#still-open) already says. It needs a client mod, so vanilla players could never have used it. Zero cost |
| **Proxy-only pack enforcement**, which would make `limbo` unnecessary for packs | after the event, never on the critical path | Nothing changes — `limbo` stays, which is the current design. The one row that can only *save* work, which is why it is last |

**When one of these is answered, move the row out of this table** and write what was actually
observed, with the date. A *no* is a result, not a failure.

## 4. Recommendation

One implementation session is left, plus two rehearsals. The concept session that used to be
here was the backup concept, and it was built on 2026-09-01.

- ~~**`limbo` and the pack station** (§2a)~~ — **built 2026-09-01.** What it left behind is a
  thirteen-step rehearsal against a running proxy and a real client, which is an afternoon with a
  Minecraft account and not a session. Three rows of the table above fall out of it.
- ~~**The backup concept** (§3)~~ — **built 2026-09-01.** What it left behind is one restore drill
  against S3 and the host, which is an afternoon and not a session.
- **The SMP's world half** (§2c) plus the winner's head start (§2d). §2b is built: the milestone
  YAML, the aura payout, the prestige function and the milestone engine, 56 tests. What is left is
  every feature that ends at a rehearsal rather than at a green build.
- **The pre-generation measurement**, which does not need this repository at all and should happen
  before the world half is designed around a number nobody has. It is two numbers — Nordtal's
  one-off generation to border 4000, and what the farm world's daily generation does to the 95th
  percentile of tick time with players online — and the recipe is in the owner's checklist.

One thing to keep in view throughout: **nothing here has been exercised against a running server, a
real client, Discord or bunq.** The test suite covers the access API, the config specs, the fallback
cache, the tier arithmetic, the border and colour maths and the phase directory against a real
PostgreSQL container. It touches no packet, no player and no bank. Every session above ends with a
rehearsal, not with a green build.
