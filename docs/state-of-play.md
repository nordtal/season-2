# State of play

Where season 2 actually stands, what can be built without another planning session, and what
cannot. Written **2026-08-31**, after the SMP concept landed (`f16c298`) and after the two
mechanical changes that concept implied were carried out: `smp-farm-world` → `smp`, and the
migration SQL moving into `:common`.

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
| `resource-pack-coercion` | 22 lines | Two log lines | the whole `limbo` waiting room |
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

Not built, and each is named in [architecture.md](architecture.md#modules) as this module's:

- **Phase.** Nothing reads or writes a phase. `SeasonPhase` is imported by
  `NetworkControlPlugin` for a javadoc `{@link}` and for nothing else.
- **Routing.** There is no `PlayerChooseInitialServerEvent` handler and no plugin-message channel.
  The class's own javadoc says so (`NetworkControlPlugin.java:37`). `app.simplecloud.api:api` is a
  declared `compileOnly` dependency that **no source file in the module imports**.
- **Pack enforcement.** No resource-pack code of any kind on the proxy.
- **Play time.** No `player_playtime` writer, and no such table in the schema.

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

### `resource-pack-coercion`, `hunger-games`, `smp`

Scaffolds. Each is one class with `onEnable`/`onDisable` logging its own name, a `paper-plugin.yml`,
and a `build.gradle.kts` holding a single `plugins {}` block. Each shades `:common`, so each jar is
~3 MB and now also carries ~14 KB of SQL it never reads — the accepted cost of the schema move.

### Where the documents and the code disagree

Six findings, in descending order of how much they matter.

1. **The login gate is phase-blind, and therefore behaves as if the network were permanently in
   `SMP`.** `LoginGate.java:78` refuses any linked member without active access, unconditionally;
   `AccessState.mayJoin()` (`AccessState.java:66`) encodes the same rule. The whole point of the
   phase model ([season-phases.md](season-phases.md)) is that access is required **only from
   `SMP`** and that the start event is free for every linked member. As the code stands, a
   `PRE_EVENT` network would refuse everyone who has not paid. Nothing is deployed, so this is an
   unfinished feature rather than a live fault — but it is the sharpest reason the phase work has
   to come before anything that opens the network to players.

2. **`docs/README.md` marks the access system "built" including the login gate, which is true, and
   `network-control` "partly", which understates how little of that module exists.** Four of the
   five things [architecture.md](architecture.md#modules) assigns to `network-control` — phase,
   pack, routing, play time — have no code at all.

3. **Two source files point at a document that was deleted.** `GateSpec.java:21` and
   `NetworkControlPlugin.java:45` both refer to "the stage C completion report"; the three stage
   documents were removed in `a640dd9`. A reader following either pointer finds nothing.

4. **`app.simplecloud.api:api` is a dependency of a module that does not use it.** It is correctly
   `compileOnly` and correctly never shaded, but no source file imports it. It is a placeholder for
   routing that has not been written — worth knowing before anyone concludes from the build file
   that SimpleCloud integration exists.

5. **The `link-code-ttl-minutes` duplication is still live.** `access.yml` carries a copy no code
   path reads and `gate.yml` carries the one the proxy uses. Documented in
   [../CLAUDE.md](../CLAUDE.md) as open, and still open.

6. **`Glyphs` and the pack disagree with [smp.md](smp.md).** The four season-1 role tags are still
   defined in `default.json`, still shipped as PNGs, still constants in `Glyphs`, and still listed
   in the pack's README table — in a season whose concept says they are gone.

## 2. What can be built today

Everything here is fully decided. The order is a dependency order, not a size order: each entry
names what it unblocks.

### a. The phase model

**Specified by** [season-phases.md](season-phases.md) and
[architecture.md](architecture.md#the-login-path-end-to-end). **Touches** `:common` (the enum, the
switch-and-audit method), the migration SQL (the phase row), `access-bot` (`/phase set`),
`network-control` (reading, `LISTEN`/`NOTIFY`, poll, the emergency command). **Depends on** nothing.

This is first because it is what every other piece of player-facing work sits on: routing needs a
phase to route by, `limbo` needs to know what to say a player is waiting for, and the login gate is
wrong until it has one (finding 1). Two numbers are open — the poll interval and the `NOTIFY`
channel name — and both are config keys, not decisions.

One thing here is genuinely unverified rather than merely unbuilt: `LISTEN`/`NOTIFY` needs a
dedicated connection outside the Hikari pool, and every reconnect has to re-read the row. Build the
polling path first and treat `NOTIFY` as the optimisation it is.

### b. The admin flag

**Specified by** [season-phases.md](season-phases.md#how-an-admin-is-recognised) and
[smp.md](smp.md#admins). **Touches** the migration SQL (a column on `discord_user`, so a new `V4`,
never an edit to `V1`–`V3`), `:common` (`AccessState` gains a field), `access-bot` (the role id in
config, the mirror in `GuildState`, the reconcile). **Depends on** nothing.

Small, decided, and it unblocks the `MAINTENANCE` phase, which is admins-only and cannot be built
without it. Do it alongside (a).

### c. The language list and the join-time locale component

**Specified by** [i18n.md](i18n.md). **Touches** `access-bot` (`access.yml` becomes a list, with
`DefaultTiers`' `Specs.createUnsafe` as the worked example), `:common` (the shared join-time
lookup). **Depends on** nothing, but every user-facing string in every later module wants the
`:common` component to exist first, so building it late means retrofitting four modules.

### d. `limbo`

**Specified by** [architecture.md](architecture.md#modules) (what it shows: nothing, and a title)
and the login path sequence. **Touches** `resource-pack-coercion` → renamed `limbo`, and
`network-control` (the `nordtal:` plugin channel and the routing that answers it). **Depends on**
(a) for the phase, (c) for the title's language.

The rename itself is the cheapest of the three remaining renames and should be done as the first
commit of that session, exactly as `smp-farm-world` → `smp` was.

### e. The glyph inventory and the pack clean-up

**Specified by** [smp.md](smp.md#what-a-player-looks-like) for the delete list and the keep list.
**Touches** `resource-pack`, `:common`'s `Glyphs` and the pack README table — all three in one
change, always. **Depends on** nothing for the deletions.

The deletions (four role tags) and the `en_us.json` clean-up are decided and can be done now. The
**new** glyphs cannot: their code points are still open (§3).

### f. `network-control`'s play time counter

**Specified by** [smp.md](smp.md#prestige--a-crest-earned-by-time) and
[architecture.md](architecture.md#schema-ownership). **Touches** the migration SQL
(`player_playtime`), `network-control`. **Depends on** nothing.

Independent of everything else and worth building early: prestige tiers are derived from the
seconds, so the sooner the counter runs, the less the first weeks of the season under-report.

### g. The two dangling documentation pointers

Findings 3 and 5 above. Minutes of work; do them in whichever session touches those files next.

## 3. What still needs a decision

### The two that run through everything

**No command framework has been chosen.** Season 1 used Incendo Cloud; season 2 has chosen nothing
([../CLAUDE.md](../CLAUDE.md)). Every module still to be written has a command surface: `smp` has
`/navigate`, POI management, admin commands and `/smp reload`; `access-bot` needs `/phase set`;
`network-control` needs the emergency phase command on Velocity, which is a *different* command API
from Paper's. This is the single decision that blocks the most work, and it is not a detail: it is a
dependency in four modules, and picking it after the first command has been written means rewriting
that command. **Own decision, but a short one** — one framework choice, plus whether the proxy
shares it.

**Four unverified assumptions can each overturn a design.** From
[operations.md](operations.md#open-verification), in the order they should be settled:

| what | why it is first, second or later |
|---|---|
| **SimpleCloud on Minecraft 26.2** | **First, before anything else.** Its docs name no supported versions and it ships only `0.1.0-platform.NN-dev.*` snapshots. If it does not support 26.2, the deployment platform reopens — and routing, server groups and `limbo` as a separate server group are all designed on top of it. Every hour spent on routing before this is settled is at risk. |
| **The client follows GitHub's redirect** to `objects.githubusercontent.com` | **Second.** One real client against one real release asset settles it. The whole pack-hosting decision rests on it, and `limbo` exists largely to apply that pack. The fallback — a small HTTP host — is cheap, but it is a configuration and operations change nobody should discover on event day. |
| **Farm-world pre-generation without perceptible lag** | Later, but it is the biggest technical risk in the SMP. It cannot be measured before the SMP module can generate a world, so it belongs at the start of the SMP implementation, not before it. If it fails, the farm world shrinks — a config change, not a redesign. |
| **A block-logging plugin for 26.2** | Later, and cheapest of the four: a search, not an experiment. Nothing in the design depends on it, so its absence is a known fact to record rather than a blocker. Settle it before the SMP phase opens. |

The proxy-only pack enforcement experiment, `LISTEN`/`NOTIFY` through the pool, Simple Voice Chat
on 26.2, and Paper unloading and deleting a loaded world at runtime are the remaining entries in
that table. Only the last one is load-bearing, and like the pre-generation it can only be drilled
once the SMP module exists.

### Decisions belonging to a module, listed by module

| open point | source | size |
|---|---|---|
| The milestone track: which objectives, in what order, with what targets | [smp.md](smp.md#still-open) | **own session** — this is the spine of the season and the mechanism alone does not imply it |
| Hunger games loot pools, item by item, for four refill tiers | [hunger-games.md](hunger-games.md#still-open) | **own session**, though it can be proposed as config and reviewed |
| Duel loadouts for sword and bow | [smp.md](smp.md#still-open) | detail, but it needs play-testing to settle |
| The advancement list that grants aura, and the value of each | [smp.md](smp.md#still-open) | detail |
| Glyph code points: donor star, 13 crests, HUD arrows and digits, board frames | [smp.md](smp.md#still-open), [hunger-games.md](hunger-games.md#still-open) | detail, but it blocks §2e's additions and every HUD |
| Minimum player count to allow a hunger games start | [hunger-games.md](hunger-games.md#still-open) | detail |
| Quiet period and passive shrink rate | [hunger-games.md](hunger-games.md#still-open) | detail |
| Whether spectators may join after the countdown, and what they see | [hunger-games.md](hunger-games.md#still-open) | detail |
| Does a phase switch kick or move a player who now lacks access? | [season-phases.md](season-phases.md#open-questions) | detail, but it must be answered before the gate is finished |
| Poll interval and `NOTIFY` channel name | [season-phases.md](season-phases.md#open-questions) | config keys, not a decision |
| Which of the two `link-code-ttl-minutes` copies survives | [../CLAUDE.md](../CLAUDE.md) | detail |
| Whether `network-control` should refuse to gate rather than run un-gated on a bad config | [operations.md](operations.md#configuration-and-secrets) | detail, but it is a security posture and was flagged rather than decided |
| The server rules as written for players, in both languages | [smp.md](smp.md#still-open) | not a design decision; it is writing, and it gates the phase opening |
| The spawn build: tavern, balloon, platforms, boards, NPC | [smp.md](smp.md#still-open) | build work, not code; it gates any SMP rehearsal |
| The hunger games world folder, lobby, towers, POIs, and the aerial images per language | [hunger-games.md](hunger-games.md#the-lobby), [operations.md](operations.md#event-day-runbook--hunger-games) | build and design work; it gates the event rehearsal |

The last three are worth separating from the rest: they are not decisions and not code, they cannot
be done by an implementation session, and each of them gates a rehearsal that nothing else can
substitute for.

## 4. Recommendation

**The next planning session should cover exactly two things**, and neither of them is a feature:

1. **The command framework.** It blocks four modules, it is one choice, and it gets more expensive
   with every command written before it. Include the Velocity side in the question — Paper and
   Velocity have different command APIs, and deciding only for Paper leaves the emergency phase
   command undesigned.
2. **The milestone track.** It is the spine of the SMP and the one open point in
   [smp.md](smp.md) that a design pass genuinely has to work out rather than pick a number for. It
   is also what determines how much objective machinery the first SMP implementation needs.

Everything else in §3 is either a config default that can be proposed in an implementation session
and reviewed in the diff, or an experiment that produces its own answer.

**Do not wait for that session to start implementing.** In parallel, and in this order:

- **Settle SimpleCloud on 26.2 today.** It is a server group and a boot, it costs an afternoon, and
  it can invalidate the deployment platform. Nothing about routing should be written before the
  answer is in.
- **Build the phase model, the admin flag and the play time counter** (§2a, §2b, §2f). All three
  are fully decided, none needs a command framework — `/phase set` is a JDA slash command, which
  the bot already does, and the proxy's emergency command is the one piece to defer until the
  framework question is answered. This also closes finding 1, which is the one code-versus-plan gap
  with a production consequence.
- **Build the language list and the `:common` locale component** (§2c). Late is expensive; it is
  cheap now and every later module depends on it behaving one way.
- **Delete season 1's role tags** from the pack, `Glyphs` and the README table (§2e), and fix the
  two dangling pointers (§2g). Small, decided, and they stop a reader from trusting stale things.

That leaves `limbo`, `hunger-games` and `smp` as the three sessions that follow — in that order,
because `limbo` is on every login path, the start event comes before the SMP in the season, and the
SMP is by a wide margin the largest of the three.

One thing to keep in view throughout: **nothing here has been exercised against a running server,
a real client, Discord or bunq.** The test suite covers the access API, the config specs, the
fallback cache and the tier arithmetic; it touches no packet, no player and no bank. Every session
above ends with a rehearsal, not with a green build.
