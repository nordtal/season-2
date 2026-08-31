# State of play

Where season 2 actually stands, what can be built without another planning session, and what
cannot. Written **2026-08-31**, after the SMP concept landed (`f16c298`) and after the two
mechanical changes that concept implied were carried out: `smp-farm-world` → `smp`, and the
migration SQL moving into `:common`.

**Revised 2026-08-31**, later the same day, after the planning session that settled the command
framework, the glyph allocation, six loose single decisions and the ordering of the open
verifications. Three new findings came out of that session and are numbered 7 to 9 below.

This document is a survey of the **code**, not of the plan. Every claim below was read out of the
repository; where a document says something the code does not support, that is recorded as a
finding rather than smoothed over. The plan lives in the other documents and is not repeated here.

It is expected to go stale. Re-derive it rather than trust it once a module has been implemented.

## 1. Where we stand

### The short version

| module | main Java | what actually runs | what the plan says it owns |
|---|---|---|---|
| `access-bot` | 4326 lines | Access sales, bunq matching, linking, roles, admin commands, the schema | plus the hunger games half, `/phase`, the admin-flag mirror, a language list |
| `common` | 1489 lines | `AccessDirectory`, `Messages`, `Locales`, `Glyphs`, the migration SQL | plus the phase model, the join-time locale component, season 2's glyph inventory |
| `network-control` | 875 lines | The login gate, the fallback cache, the mid-session expiry watch | plus phase, pack enforcement, routing, network-wide play time |
| `resource-pack` | — | Season 1's glyphs, boss bar sprites, the reproducible zip | season 2's glyph inventory |
| `limbo` | 22 lines | Two log lines | the whole `limbo` waiting room |
| `hunger-games` | 22 lines | Two log lines | the whole start event |
| `smp` | 23 lines | Two log lines | the whole SMP |

Three of the seven modules are a `JavaPlugin` subclass that logs on enable and on disable. That is
not a criticism — it is the accurate picture of how much of season 2 exists, and it is why the
question "what can be built today" has a long answer.

### `network-control`

Built, and it works as documented as far as it goes:

- `LoginGate` — one `accessState` call, then linked? member? access active?, each with its own
  disconnect screen, with the fallback cache under it.
- `FallbackCache` — the four rules, driven by a settable `Clock`, 10 tests.
- `ExpiryWatch` — re-reads every connected player's access on a timer, warns once, disconnects at
  the deadline (`ExpiryWatch.java:96`).
- `Configs` / `DatabaseSpec` / `GateSpec` — `database.yml` and `gate.yml` through jcore, 6 tests.

Built since this section was first written, on 2026-08-31:

- **Phase.** `PhaseWatch` (the 30-second poll and the last-known-phase fallback), `PhaseListener`
  (`LISTEN nordtal_phase` on a dedicated connection), `PhaseCommand` (the emergency `/phase`).
- **Play time.** `PlaytimeWriter` / `PlaytimeStore` into `player_playtime`, flushed on disconnect,
  on shutdown and every `gate.yml#playtime-flush-interval-seconds` (300, decided 2026-08-31).
- **Routing, in part.** The `routing` package: `PhaseServers` (the phase-to-backend table),
  `PhaseRouting` (the decision, in memory and exhaustively tested) and `PlayerRouter` (the Velocity
  glue). A phase change re-routes every connected player; a `MAINTENANCE` login is put into
  `limbo`; a switch to `SMP` still disconnects a player without access.

Still not built:

- **The limbo-first pack station.** `PlayerRouter` deliberately leaves the initial server alone in
  every phase but `MAINTENANCE`, because [architecture.md](architecture.md#the-login-path-end-to-end)
  puts *every* login through `limbo` for the resource pack and then back out on a `nordtal:` plugin
  message — and `limbo` is a scaffold with no pack code and no channel. Routing a `PRE_EVENT` login
  straight to `hunger-games` would be a different design, not that one. The `limbo` session owns it.
- **Pack enforcement.** No resource-pack code of any kind on the proxy.
- `app.simplecloud.api:api` is still a declared `compileOnly` dependency that **no source file in
  the module imports** — routing named backends from `gate.yml` and never needed it, which is what
  [operations.md](operations.md#open-verification) said would happen.

### `common`

`AccessDirectory` and the message system are real and well covered (47 tests, 34 of them against a
PostgreSQL container). The rest of what the module is supposed to own is not there:

- `SeasonPhase` (`SeasonPhase.java:10-16`) still holds **`RESOURCE_PACK_INSTALL`, `START_EVENT`,
  `SMP`** — three values. The agreed enum is `PRE_EVENT`, `START_EVENT`, `SMP`, `MAINTENANCE`.
  Nothing reads it, so replacing it costs nothing today.
- `Glyphs` (`Glyphs.java:17-32`) is season 1's inventory: the four role tags that
  [smp.md](smp.md#what-a-player-looks-like) says must be **deleted**, the admin `A`, five flags and
  two logo sizes. The donor star, the 13 prestige crests, the HUD arrows and digits and the board
  frames do not exist in `Glyphs`, in `default.json`, or as image files.
- There is **no join-time locale component**. [i18n.md](i18n.md) specifies "a shared component in
  `:common` does the loading, the caching and the default, so all four modules behave identically".
  `Locales` converts a tag to a `Locale` and back; that is all.

### `access-bot`

The access half is built end to end and is the only part of season 2 that could be deployed today.
Four admin commands exist (`grant-access`, `revoke-access`, `access-status`, `settle`) plus
`/unlink`. What the plan gives this module and the code does not have:

- **No `hungergames` package.** Registration, team names, partner invitations — none of it.
- **No `/phase set`.** [season-phases.md](season-phases.md#who-may-switch-it) makes this the normal
  path for switching the phase.
- **No admin-role mirror.** `discord_user` (`V1__access.sql:22`) has `locale`, `member_state` and
  `donor`, and **no admin flag**. `AccessSpec.RolesSpec` has `access`, `donor`, `german`,
  `english` and `admin-ping` — `admin-ping` is who gets mentioned in the admin channel, not who is
  an admin. The decision in [season-phases.md](season-phases.md#how-an-admin-is-recognised) is
  therefore unimplemented in both the schema and the config, and
  [smp.md](smp.md#admins)'s `PermissionAttachment` has nothing to read.
- **Languages are two fixed roles**, `roles.german` and `roles.english`
  (`AccessSpec.java:191-203`), and four fixed channels. [i18n.md](i18n.md#the-configuration-model)
  replaces both with a list. The mirroring itself works (`GuildState.java:177-189`).

### `resource-pack`

The zip builds reproducibly with its SHA-1, `pack_format` is 88, and the boss bar override plus the
power-of-two background segments season 2's HUD design depends on are already there
(`assets/nordtal/textures/ui/bossbar/`). The **content** is season 1's: settler, citizen, knight and
lord tags in `default.json`, in `Glyphs` and in the pack's README table, and an `en_us.json` that
still says "Return to nordtal smp". Nothing season 2 needs has been added.

### `limbo`, `hunger-games`, `smp`

Scaffolds. Each is one class with `onEnable`/`onDisable` logging its own name, a `paper-plugin.yml`,
and a `build.gradle.kts` holding a single `plugins {}` block. Each shades `:common`, so each jar
carries ~14 KB of SQL it never reads — the accepted cost of the schema move — and weighs **about
34 KB in total**, not the ~3 MB this document and `season-2/CLAUDE.md` claimed. See
finding 7.

### Where the documents and the code disagree

**Nine findings**, in descending order of how much they matter. Findings 7 to 9 are new on
2026-08-31; finding 3 is closed.

> **This list has not been rewritten since the work it describes was done, and says so rather than
> being trusted.** As of 2026-08-31 finding **1** is fixed (the gate is phase-aware, and
> `MAINTENANCE` now holds players in `limbo` rather than refusing them), finding **2** is fixed
> (`MisconfiguredGate` is the deny-all handler), and finding **4** is out of date in one direction:
> phase, play time and the routing rules that do not need `limbo` all have code now. Finding **5**
> is unchanged and, if anything, more true — routing was built and did not import
> `app.simplecloud.api`. Re-deriving the whole list from the code belongs to the session that next
> audits this document; the individual entries below are left as they were written so the diff is
> readable.

1. **The login gate is phase-blind, and therefore behaves as if the network were permanently in
   `SMP`.** `LoginGate.java:78` refuses any linked member without active access, unconditionally;
   `AccessState.mayJoin()` (`AccessState.java:66`) encodes the same rule. The whole point of the
   phase model ([season-phases.md](season-phases.md)) is that access is required **only from
   `SMP`** and that the start event is free for every linked member. As the code stands, a
   `PRE_EVENT` network would refuse everyone who has not paid. Nothing is deployed, so this is an
   unfinished feature rather than a live fault — but it is the sharpest reason the phase work has
   to come before anything that opens the network to players.

2. **`network-control` accepts logins un-gated when its own config is broken, and that is now
   decided against.** A bad `database.yml` or `gate.yml` is logged and the gate is simply never
   registered. Settled 2026-08-31: it must fail closed, with a `LoginEvent` handler that refuses
   everybody — see [operations.md](operations.md#configuration-and-secrets). Decided, **not
   implemented**; until it is, a mistyped key silently opens the network.

3. ~~Two source files point at a document that was deleted.~~ **Closed 2026-08-31.** `GateSpec.java`
   and `NetworkControlPlugin.java` both referred to "the stage C completion report", removed in
   `a640dd9`. Both javadocs were rewritten in this session and now carry the decision itself
   instead of a pointer to nothing.

4. **`docs/README.md` marks `network-control` "partly", which understates how little of that module
   exists.** Four of the five things [architecture.md](architecture.md#modules) assigns to it —
   phase, pack, routing, play time — have no code at all.

5. **`app.simplecloud.api:api` is a dependency of a module that does not use it.** Correctly
   `compileOnly`, correctly never shaded, and imported by **no source file**. It is a placeholder
   for routing that has not been written. Worth knowing before anyone concludes from the build file
   that SimpleCloud integration exists — and worth knowing that
   [operations.md](operations.md#open-verification) now records a fallback which needs the
   dependency not at all.

6. **The `link-code-ttl-minutes` duplication is still in the code, though no longer undecided.**
   `access.yml` carries a copy no code path reads and `gate.yml` carries the one the proxy uses.
   Settled 2026-08-31: the bot's copy is retired. Decided, **not carried out** — and it is free
   only until something is deployed, because a key the interface does not declare stops the load.

7. **The jar-size claim was wrong, and backwards.** `season-2/CLAUDE.md` and this document both
   said a Paper plugin jar had grown "from ~20 KB to ~3.0 MB". Rebuilt and measured
   **2026-08-31**:

   | jar | bytes |
   |---|---|
   | `smp-2.0.0.jar` | 34,745 |
   | `hunger-games-2.0.0.jar` | 34,784 |
   | `limbo-2.0.0.jar` | 34,886 |
   | `network-control-2.0.0.jar` | 5,196,184 |

   The three Paper plugins carry `:common`'s classes plus the SQL and nothing else, because
   `:common` declares JDBI, HikariCP and slf4j `compileOnly` and none of the scaffolds has opted
   into `libs.bundles.access-persistence`. `network-control` weighs 5 MB because it *did* opt in,
   through `jcore`. **The 3.12 MB in `common/build.gradle.kts` is a counterfactual** — what the
   jars would weigh with those declarations changed — and it was copied into two documents as if it
   described the jars that exist. Corrected in both documents, and the build file now says so
   itself. The design worked; the documentation reported the opposite.

8. **A whole font was undocumented.** `resource-pack/src/assets/nordtal/font/bossbar.json` defines
   `nordtal:bossbar` — nine bar-background bitmaps at `\uE000`–`\uE128`, five icons at
   `\uEF00`–`\uEF04` (one of them a compass, which two documents were about to ask for again),
   fifteen space advances, and a full printable-ASCII override — and **none of it appeared in the
   pack's README, in `Glyphs`, or anywhere in this knowledge base.** The glyph inventory was
   described everywhere as if `default.json` were the whole of it.
   **Closed by this session**: the pack's own README
   ([the allocation table](../resource-pack/README.md#code-point-allocation)) now owns both fonts,
   and `Glyphs` still names nothing from the second one — which is now a recorded gap rather than
   an invisible one.

9. **The boss bar font's positive space advances sit outside the private-use area.** `\uFF01`,
   `\uFF02`, `\uFF04`, `\uFF08`, `\uFF16` and `\uFF32` are `FULLWIDTH EXCLAMATION MARK` and
   friends, not private use, while the pack's README states its range as `\uE000`–`\uF8FF`. The
   override is confined to `nordtal:bossbar`, so ordinary chat is unaffected and nothing is broken
   today. Recorded rather than fixed: moving them changes whatever HUD code composes the bar, and
   no such code exists yet.

## 2. What can be built today

Everything here is fully decided. The order is a dependency order, not a size order: each entry
names what it unblocks.

**The command framework question is gone.** It blocked four modules and was the single largest
entry in §3; [architecture.md](architecture.md#commands) now settles it, so every command surface
below is buildable without waiting for anything.

### a. The phase model

**Specified by** [season-phases.md](season-phases.md) and
[architecture.md](architecture.md#the-login-path-end-to-end). **Touches** `:common` (the enum, the
switch-and-audit method), the migration SQL (the phase row), `access-bot` (`/phase set`),
`network-control` (reading, `LISTEN`/`NOTIFY`, the poll, the emergency command). **Depends on**
nothing.

This is first because it is what every other piece of player-facing work sits on: routing needs a
phase to route by, `limbo` needs to know what to say a player is waiting for, and the login gate is
wrong until it has one (finding 1).

**Nothing here is open any more.** The poll interval is 30 seconds and the channel is
`nordtal_phase`; a switch to `SMP` *disconnects* a player who lacks access rather than bouncing
them to `limbo`; the proxy's emergency command is a Velocity `BrigadierCommand` authorised by the
admin flag; and `LISTEN`/`NOTIFY` is built in this pass rather than deferred, so that verification
row belongs to this session too.

### b. The admin flag

**Specified by** [season-phases.md](season-phases.md#how-an-admin-is-recognised) and
[smp.md](smp.md#admins). **Touches** the migration SQL (a column on `discord_user`, so a new `V4`,
never an edit to `V1`–`V3`), `:common` (`AccessState` gains a field), `access-bot` (the role id in
config, the mirror in `GuildState`, the reconcile). **Depends on** nothing.

Small, decided, and it unblocks two things rather than one: the `MAINTENANCE` phase, which is
admins-only, **and the proxy's emergency `/phase` command**, which is authorised by this flag.
Do it alongside (a).

### c. The language list and the join-time locale component

**Specified by** [i18n.md](i18n.md). **Touches** `access-bot` (`access.yml` becomes a list, with
`DefaultTiers`' `Specs.createUnsafe` as the worked example), `:common` (the shared join-time
lookup). **Depends on** nothing, but every user-facing string in every later module wants the
`:common` component to exist first, so building it late means retrofitting four modules.

### d. The two config decisions in `network-control`

**Specified by** [operations.md](operations.md#configuration-and-secrets) and
`GateSpec`'s class doc. **Touches** `network-control` (a deny-all `LoginEvent` handler for the
fail-closed path) and `access-bot` (deleting `link-code-ttl-minutes` from `AccessSpec`).
**Depends on** nothing.

New in §2 as of 2026-08-31 — both were open questions until this session. Findings 2 and 6. The
`AccessSpec` deletion is free **only while nothing is deployed**, which argues for doing it now
rather than remembering it later.

### e. `limbo`

**Specified by** [architecture.md](architecture.md#modules) (what it shows: nothing, and a title)
and the login path sequence. **Touches** `limbo`, and
`network-control` (the `nordtal:` plugin channel and the routing that answers it). **Depends on**
(a) for the phase, (c) for the title's language.

The rename itself is the cheapest of the three remaining renames and should be done as the first
commit of that session, exactly as `smp-farm-world` → `smp` was. This session also owns three of
the open verifications: the GitHub redirect, the proxy's forced pack offer, and whether the
SimpleCloud API coordinate is safe to route through.

### f. The glyph clean-up, and everything but the drawings

**Specified by** [`resource-pack/README.md`](../resource-pack/README.md#code-point-allocation),
which owns the allocation as of 2026-08-31. **Touches** `resource-pack`'s two font files and
`:common`'s `Glyphs` — both, always, in one commit. **Depends on** nothing for the deletions;
the additions depend only on the images existing.

Bigger than it was. The deletions (four role tags, the `en_us.json` string) were already decided;
what is new is that **every code point is now allocated**, so the font entries and the `Glyphs`
constants can be written in the same session that draws the sprites, rather than waiting on a
second decision. `Glyphs` should also gain the boss bar font it has never named (finding 8).

Still not decided here, and out of scope for code: the **drawings themselves** — the donor star,
thirteen crests, four dimension icons, four HUD status icons, sixteen bearing arrows and the board
frame pieces. That is design work.

### g. `network-control`'s play time counter

**Specified by** [smp.md](smp.md#prestige--a-crest-earned-by-time) and
[architecture.md](architecture.md#schema-ownership). **Touches** the migration SQL
(`player_playtime`), `network-control`. **Depends on** nothing.

Independent of everything else and worth building early: prestige tiers are derived from the
seconds, so the sooner the counter runs, the less the first weeks of the season under-report.

## 3. What still needs a decision

Much shorter than it was. Everything the 2026-08-31 planning session settled has moved into §2 or
into the documents themselves.

### One design pass, and it is the season's spine

**The milestone track.** Which objectives sit on which milestone, in what order, with what targets,
and what each objective's aura pot is. The mechanism is fully decided — one linear chain, every
objective of a milestone required, three objective types, YAML definition and database progress
([smp.md](smp.md#milestones--the-community-objective-system)) — and the mechanism does not imply
the content. This is **its own session**, and it was deliberately not squeezed into the one that
settled everything else.

Its constraints are already written down: the Nether and End reachable in the first days, the last
border expansion a genuine fortnight of effort, hand-in reachable without automation, and a player
count of 15–30. One thing has changed underneath it and must be checked against rather than
assumed: **Nether portals now link vanilla with 1:8 mapping once unlocked, and Nether highways are
accepted**, which changes what a large border number actually costs a player.

### Config defaults, cheapest to propose in a diff

Each of these is an item list or a number. Proposing them as a config default in an implementation
session and reviewing the diff is cheaper than arguing them in prose.

| open point | source |
|---|---|
| Hunger games loot pools, item by item, for four refill tiers | [hunger-games.md](hunger-games.md#still-open) |
| Duel loadouts for sword and bow | [smp.md](smp.md#still-open) |
| The advancement list that grants aura, and the value of each | [smp.md](smp.md#still-open) |
| The wheel of fortune's prize pool and weights | [smp.md](smp.md#numbers-that-are-proposals-not-decisions) |
| Quiet period and passive shrink rate for the hunger games border | [hunger-games.md](hunger-games.md#still-open) |

### Not decisions at all — writing, drawing and building

These cannot be done by an implementation session, and each gates a rehearsal that nothing else
substitutes for.

| open point | source |
|---|---|
| The server rules as written for players, both languages — must exist before the SMP phase opens | [smp.md](smp.md#still-open) |
| The spawn build: tavern, balloon model, duel platforms, boards, NPC | [smp.md](smp.md#still-open) |
| The hunger games world folder, lobby, towers, loot points, and the aerial images per language | [hunger-games.md](hunger-games.md#the-lobby) |
| Every new glyph's artwork — donor star, 13 crests, icons, arrows, frame pieces | [resource-pack/README.md](../resource-pack/README.md#code-point-allocation) |

### The unverified assumptions

They no longer live here. [operations.md](operations.md#open-verification) now orders them by the
session that owns each one and says, for every row, **what happens if the answer is no** — which is
what makes them risks with a plan rather than open questions. SimpleCloud on 26.2, which used to
head that list and block everything behind it, was confirmed by the owner on 2026-08-31.

## 4. Recommendation

**The next planning session covers exactly one thing: the milestone track.** It is what is left of
the two-item list this section carried this morning; the command framework is decided. It deserves
a session of its own because it is the only remaining item that a design pass has to *work out*
rather than pick a number for, and because it determines how much objective machinery the first
SMP implementation needs.

**Do not wait for it to start implementing.** In parallel, and in this order:

- **Build the phase model, the admin flag, the two `network-control` config decisions and the play
  time counter** (§2a, §2b, §2d, §2g). All four are fully decided and none needs anything from the
  milestone session. This also closes findings 1, 2 and 6 — the three code-versus-plan gaps with a
  production consequence.
- **Build the language list and the `:common` locale component** (§2c). Late is expensive; it is
  cheap now, and every later module depends on it behaving one way.
- **Do the glyph clean-up as far as the drawings allow** (§2f): delete the four role tags, fix
  `en_us.json`, and give `Glyphs` the boss bar font it has never named. The new sprites wait on
  design, but nothing else does.

That leaves `limbo`, `hunger-games` and `smp` as the three sessions that follow — in that order,
because `limbo` is on every login path, the start event comes before the SMP in the season, and the
SMP is by a wide margin the largest of the three.

One thing to keep in view throughout: **nothing here has been exercised against a running server,
a real client, Discord or bunq.** The test suite covers the access API, the config specs, the
fallback cache and the tier arithmetic; it touches no packet, no player and no bank. The command
framework decision was verified against published artefacts and a constant-pool resolution, which
is a stronger check than a README and still not a running server. Every session above ends with a
rehearsal, not with a green build.
