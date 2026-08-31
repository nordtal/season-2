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
| `discord-bot` | 43 files, 6054 lines | 109 | Access end to end, the phase command, the admin mirror, the language list, hunger games registration |
| `hunger-games` | 40 files, 3777 lines | 41 | The start event, essentially in full |
| `network-control` | 29 files, 4110 lines | 120 | Login gate, phase, play time, routing, **the pack station** |
| `common` | 26 files, 2671 lines | 98 | Access API, messages, locales, phase, glyphs, **the limbo protocol**, V1–V6 |
| `resource-pack` | — | — | Three fonts, every code point allocated and drawn |
| `limbo` | 10 files, 1139 lines | 11 | The waiting room, in full |
| `smp` | 1 file, 23 lines | — | Two log lines |

379 tests, none skipped, all green with a Docker daemon present (`./gradlew build`, 2026-09-01).

**One of the seven modules is a scaffold**, and it is the one that carries the most design: the
phase the season spends its life in.

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
of them can be answered here — see [operations.md](operations.md#rehearsal--the-login-path).

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

A scaffold: one class, `onEnable`/`onDisable` logging its own name, a `paper-plugin.yml`, and a
`build.gradle.kts` holding a single `plugins {}` block. It has taken a dependency on nothing.

### Where the documents and the code disagree

The nine findings of the previous version are resolved as follows. **Only two still stand.**

| # | what it was | now |
|---|---|---|
| 1 | The login gate is phase-blind | **closed** — `LoginGate` decides per phase, `MAINTENANCE` holds in `limbo` |
| 2 | The proxy accepts logins un-gated on a broken config | **closed** — `MisconfiguredGate` |
| 3 | Two files point at a deleted document | **closed** 2026-08-31 |
| 4 | `docs/README.md` overstates `network-control` | **closed** — four of the five things are built; only the pack station is not |
| 5 | `app.simplecloud.api:api` is a dependency nothing imports | **closed 2026-09-01** — the dependency, its two repositories and its catalog entry were removed. Routing resolves backends by `gate.yml` name through `ProxyServer.getServer(name)`, which is exactly the fallback [operations.md](operations.md#open-verification) had written down |
| 6 | `link-code-ttl-minutes` duplicated | **closed** — retired from `AccessSpec`, with a test asserting the key is refused |
| 7 | The jar-size claim was wrong | **closed** — corrected in both documents |
| 8 | A whole font was undocumented | **closed** — the pack's README owns all three fonts |
| 9 | The bossbar font's positive space advances sit outside the private-use area | **stands.** `！` and friends are `FULLWIDTH EXCLAMATION MARK`, not private use, while the pack states its range as ``–``. Confined to `nordtal:bossbar` and `nordtal:board`, so nothing is broken; moving them would change the HUD code that composes the bar, which now exists |
| 12 | **New 2026-09-01:** `hunger-games` read a player's language with a blocking JDBC call from its `PlayerJoinEvent` handler, on the main thread, and its pool had no timeout of any kind | **closed the same day, in both modules.** `PlayerLocales#joinAsync` runs the lookup on Bukkit's async scheduler; `limbo` redraws its title when the value lands, `hunger-games` needs no redraw. Both `database.yml` files gained `query-timeout-seconds` (default 3), setting HikariCP's `connectionTimeout` and the driver's `socketTimeout`. Found while building `limbo`, where the same line would have frozen the server every login passes through |
| 11 | **New 2026-09-01:** the knowledge base named `objects.githubusercontent.com` as what a GitHub release asset redirects to | **closed by measurement.** It is `release-assets.githubusercontent.com`, one hop, with a signed URL that expires within the hour. Corrected in `operations.md` and in `PackSpec`'s own comment, which now says in capitals not to paste the resolved address into the config |
| 10 | **New 2026-09-01:** the knowledge base described three modules as unbuilt that were built hours earlier, and `season-2/CLAUDE.md` still opens with "`hunger-games`, `smp` and `limbo` are still scaffolds" | **closed by this pass** in both places. The cause is worth keeping: a documentation commit that lands *after* an implementation commit is not evidence that it describes it |

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
verifications this session owned are still open, each is now a numbered step in
[operations.md](operations.md#rehearsal--the-login-path), and each still has its written fallback —
so the phase is *built and unproven*, which is a different thing from *unbuilt*.

One measurement did come out of it. `curl` against a real GitHub release asset on 2026-09-01: one
`302`, to **`release-assets.githubusercontent.com`**, with a signed URL that expires within the
hour. This knowledge base said `objects.githubusercontent.com`, which is wrong and is corrected. It
does not settle whether a Minecraft client follows the redirect — only where it goes.

### b. The SMP's server-free core

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

### c. The SMP's world half

Same document, same module, but every feature ends at a rehearsal rather than a green build: the
farm-world swap, the balloon and the travel rules, portal gating, spawn protection, graves, POIs,
`/navigate`, the duel arenas, the per-player Text Display boards and the wheel.

**Measure Nordtal's one-off pre-generation to border 4000 in this session's first hour**, not its
last. It is the cheapest measurement in the plan and it is the one that decides whether the final
milestone is deliverable at all.

**Nametags come from `papermc-display-tags`** — decided 2026-09-01. `smp` takes
`com.github.nordtal:papermc-display-tags:2.0.0` from JitPack as `compileOnly` and declares
DisplayTags in its `paper-plugin.yml`. The consequence is operational and is stated in
[operations.md](operations.md#third-party-plugins): **PacketEvents becomes a required plugin on the
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

### Concept work, and it has no home yet

**PostgreSQL backup and restore.** The entire season lives in one database — access periods,
payments, aura, milestone progress, graves. [operations.md](operations.md) describes deployment in
one sentence and says nothing about backups, restore, or how a restore would be tested. This is the
only genuinely irreversible risk in the project and it is the one thing here that is not a
config default, a drawing or a build. It belongs before the SMP phase opens.

The SimpleCloud runbook — what the groups and templates look like, how jars and configs reach the
host — is the same gap one step less urgent, and it will largely write itself during the first real
deployment.

### Config defaults, cheapest to propose in a diff

| open point | source |
|---|---|
| Duel loadouts for sword and bow | [smp.md](smp.md#still-open) |
| The advancement list that grants aura, and the value of each (2–10) | [smp.md](smp.md#still-open) |
| The damage types that count as an "embarrassing" death and cost −20 rather than −5 | [smp.md](smp.md#deaths-cost-aura) |
| The items and advancements behind each of the track's objectives | [smp.md](smp.md#the-objectives) |
| The wheel of fortune's prize pool and weights | [smp.md](smp.md#numbers-that-are-proposals-not-decisions) |
| The winner's head start: how much aura, and which one or two items | [smp.md](smp.md#the-hunger-games-winners-head-start) |

The hunger games' own three — loot pools, quiet period, passive shrink rate — were proposed as
config defaults on 2026-08-31 and are no longer open.

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

They live in [operations.md](operations.md#open-verification), ordered by the session that owns each
one, and every row says what happens if the answer is no.

## 4. Recommendation

One implementation session and one concept session are left, plus one rehearsal.

- ~~**`limbo` and the pack station** (§2a)~~ — **built 2026-09-01.** What it left behind is a
  rehearsal against a running proxy and a real client, which is an afternoon with a Minecraft
  account and not a session.
- **The backup concept** (§3). Not code, and it should not wait for the SMP: the database it
  protects already holds real payment records the moment the bot is deployed.
- **The SMP** (§2b, §2c, §2d), by a wide margin the largest and now the only implementation session
  left. Start with the pre-generation measurement and the milestone YAML, then the three server-free
  pieces, then the world half.

One thing to keep in view throughout: **nothing here has been exercised against a running server, a
real client, Discord or bunq.** The test suite covers the access API, the config specs, the fallback
cache, the tier arithmetic, the border and colour maths and the phase directory against a real
PostgreSQL container. It touches no packet, no player and no bank. Every session above ends with a
rehearsal, not with a green build.
