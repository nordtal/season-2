# season 2 — project knowledge base

The shared understanding of what season 2 is, how its pieces fit together and why they are the way
they are. Written for whoever — human or agent — picks up a piece of it next.

**Read this file first, then the one document that covers your task.** The documents are cut so
that a task needs one of them, not all of them.

## The system in one picture

```mermaid
flowchart TB
    P["Player"] --> NC["network-control<br/>Velocity"]
    NC --> LB["limbo<br/>waiting room + resource pack"]
    LB -.->|"ready"| NC
    NC -->|"phase PRE_EVENT / START_EVENT"| HG["hunger-games"]
    NC -->|"phase SMP"| SMP["smp<br/>Nordtal · farm world · Nether · End"]

    BOT["discord-bot<br/>access · hunger games · admin"] <--> DC["Discord"]
    BOT <--> BQ["bunq"]

    DB[("PostgreSQL<br/>access · phase · teams · language")]
    NC --> DB
    HG --> DB
    SMP --> DB
    BOT --> DB

    classDef built fill:#1f6f3f,stroke:#0f3f22,color:#fff
    classDef partly fill:#7a5c11,stroke:#4a3708,color:#fff
    classDef planned fill:#3b3b3b,stroke:#222,color:#fff
    class BOT,DC,BQ partly
    class NC partly
    class LB,HG,SMP planned
    class DB built
```

The database is the source of truth for access, language, phase and event state. Discord roles are
a projection of it; LuckPerms is not involved anywhere.

## The documents

| document | what it answers |
|---|---|
| [architecture.md](architecture.md) | Which modules exist, what depends on what, who owns the schema, what the login path looks like end to end |
| [season-phases.md](season-phases.md) | The four phases, who gets in during each, where the phase lives and how a switch propagates |
| [i18n.md](i18n.md) | How German and English work everywhere, and how a third language is added without a release |
| [hunger-games.md](hunger-games.md) | The start event in full: registration, teams, border, loot, HUD, winning |
| [smp.md](smp.md) | The SMP in full: worlds, travel, milestones, aura, prestige, duels, graves, POIs |
| [operations.md](operations.md) | Deployment, secrets, pack hosting, the release path, and everything still unverified |
| [access-system.md](access-system.md) | The paid access concept: product, rules, payment matching, linking |
| [state-of-play.md](state-of-play.md) | Where the **code** stands against all of the above, what can be built today, and what still needs a decision |
| [../resource-pack/README.md](../resource-pack/README.md) | **The glyph code point allocation** — both fonts, one table, and the only place that owns it |

Repository rules — build conventions, platform versions, package layout, what not to shade — live
in [../CLAUDE.md](../CLAUDE.md), and the cross-repository map lives in
[../../CLAUDE.md](../../CLAUDE.md). This knowledge base does not repeat them.

## What is built and what is not

| area | state |
|---|---|
| Access system: schema, `AccessDirectory`, purchase flow, bunq matching, linking, login gate | **built** — stages A–C, 2026-08-30 |
| Message system in `:common` (`Messages`, `Locales`) | **built** |
| Resource pack: glyphs, boss bar sprites, reproducible zip | **built**, carries season 1 leftovers |
| Glyph code point allocation, both fonts | **decided 2026-08-31** — the table exists, the artwork does not |
| Command surfaces in every module | **unblocked 2026-08-31** — Brigadier directly, no framework |
| Phase model, phase-aware gate, phase propagation | **built** in `network-control`, 2026-08-31 |
| Phase routing: re-route on a switch, `MAINTENANCE` into `limbo` | **built** in `network-control`, 2026-08-31 — minus the limbo-first pack station, which needs `limbo` |
| `limbo` waiting room and pack enforcement | **designed, not built** |
| Language config list, plugin-side locale lookup | **designed, not built** |
| Hunger games, both halves | **designed, not built** |
| SMP: worlds, travel, aura, prestige, duels, graves, POIs | **designed, not built** — 2026-08-31 |
| SMP: the milestone track — chain, budgets, targets, pots, participation gates | **designed, not built** — 2026-08-31, the last open design decision |

That table is a summary. [state-of-play.md](state-of-play.md) is the same question answered from the
code, module by module, with the places where these documents and the code disagree — **nine of
them** as of 2026-08-31, three of which came out of that day's planning session.

**No design decision is open any more.** The milestone track — the last one, and the season's spine
— was designed on 2026-08-31 and is in [smp.md](smp.md#the-track). Everything left in
[state-of-play.md §3](state-of-play.md#3-what-still-needs-a-decision) is a config default, a
drawing, a text, or a build.

`smp-farm-world` → `smp`, `resource-pack-coercion` → `limbo` and `access-bot` → `discord-bot` were
all carried out by 2026-08-31. One rename is still part of the plan and is cheap only until
something runs in production: the `SeasonPhase` values. See
[architecture.md](architecture.md#modules).

## Decisions, and when they were taken

Everything below was decided **2026-08-30** unless noted. Where an alternative was rejected, the
reason is in the linked document — that is what stops it from being reopened by accident.

| decision | where |
|---|---|
| The database is the source of truth; Discord roles are a projection; no LuckPerms, no DiscordSRV | [access-system.md](access-system.md) |
| Exactly one process migrates — the bot. The migration SQL lives in `:common`; Flyway never leaves the bot (changed 2026-08-31) | [architecture.md](architecture.md#schema-ownership) |
| Four phases — `PRE_EVENT`, `START_EVENT`, `SMP`, `MAINTENANCE` — decide who gets in | [season-phases.md](season-phases.md) |
| Access is required only from `SMP`; the start event is free for every linked member | [season-phases.md](season-phases.md) |
| The phase is one database row, switched from Discord *and* from the proxy, propagated by `NOTIFY` with polling as a safety net | [season-phases.md](season-phases.md) |
| One Velocity plugin with `gate` / `pack` / `phase` / `routing` packages — not several small proxy plugins | [architecture.md](architecture.md#the-login-path-end-to-end) |
| The pack is enforced on a Paper waiting room, not by the proxy; proxy-only enforcement stays a later experiment | [operations.md](operations.md#open-verification) |
| Players download the pack from the GitHub release asset | [operations.md](operations.md#resource-pack-hosting) |
| Languages are a config list with `en` mandatory; no language role means English | [i18n.md](i18n.md) |
| A plugin reads a player's language from the database at join and holds it for the session | [i18n.md](i18n.md) |
| Hunger games: team **names**, generated colours, one winner, friendly fire always on | [hunger-games.md](hunger-games.md) |
| The border shrinks a fixed step per death **and** slowly with time, so the game cannot stall | [hunger-games.md](hunger-games.md#the-border) |
| A disconnected player's body stays and stays vulnerable | [hunger-games.md](hunger-games.md#disconnects) |
| Spectator and cross-teaming rules are announced, not enforced | [hunger-games.md](hunger-games.md#the-lobby) |
| **Decided 2026-08-31 — the SMP** | |
| The SMP is peaceful by agreement: PvP is on everywhere, but nothing is designed against griefing, raiding or theft | [smp.md](smp.md#what-kind-of-server-this-is) |
| No teleport commands at all — the only *given* fast travel is the balloon, and it only reaches world spawns | [smp.md](smp.md#travel) |
| Nordtal Nether portals stay dead until the Nether milestone, then link to the Nether exactly like vanilla, 1:8 mapping included; stronghold End portals stay inactive; farm-world portals lead to the spawn | [smp.md](smp.md#travel) |
| Aura is prestige and buys nothing; it may go negative | [smp.md](smp.md#aura--recognition-not-currency) |
| Prestige is a 13-tier crest earned by online time, AFK included; the tab list sorts by it | [smp.md](smp.md#prestige--a-crest-earned-by-time) |
| Milestones are defined in a reloadable YAML file, unlocked **only** by objectives, never by time | [smp.md](smp.md#milestones--the-community-objective-system) |
| The farm world is swapped, not rebuilt in place: tomorrow's world pre-generates in the background | [smp.md](smp.md#the-farm-world-reset) |
| Graves everywhere but the duel arena; they stand forever and anyone may open them | [smp.md](smp.md#death-and-graves) |
| No navigation to players; `/navigate` knows world spawns, the last death and public POIs | [smp.md](smp.md#navigate) |
| Spawn protection is a list of regions in our own plugin, not WorldGuard | [smp.md](smp.md#spawns) |
| No LuckPerms: the admin flag comes from the database, Bukkit permissions from a `PermissionAttachment` | [smp.md](smp.md#admins) |
| Chat is per Paper server; `limbo` has none, shows nothing and nobody, only a title | [smp.md](smp.md#chat) |
| Play time is counted by the proxy into `player_playtime`; the prestige tier is derived, never stored | [smp.md](smp.md#prestige--a-crest-earned-by-time) |
| No web map and no Discord map render | [smp.md](smp.md#discord) |
| There is no fixed season end date, so nothing may depend on one | [smp.md](smp.md#what-kind-of-server-this-is) |
| **Decided 2026-08-31 — the planning session on commands, glyphs, loose ends and verification** | |
| **No command framework.** Brigadier directly: Paper's `Commands` on the Lifecycle API, Velocity's `BrigadierCommand`. Cloud 2.0.0 and Lamp 4.0.0-rc.18 were both verified compatible and both rejected — on 879,377 shaded bytes per Paper jar and on eighteen release candidates respectively | [architecture.md](architecture.md#commands) |
| No shared Brigadier helper in `:common`: two different brigadier artefacts, neither on Maven Central, for one command on the proxy | [architecture.md](architecture.md#commands) |
| `BasicCommand` is not used — one command shape across the repository | [architecture.md](architecture.md#commands) |
| **One table owns every glyph code point**, in `resource-pack/README.md`, covering both fonts; `Glyphs`, `default.json` and `bossbar.json` are mirrors of it and the concept documents carry none | [resource-pack/README.md](../resource-pack/README.md#code-point-allocation) |
| HUD arrows and icons are allocated in `nordtal:bossbar`, not `default.json` — the two fonts have different metrics, and an arrow in the wrong one sits on the wrong baseline | [resource-pack/README.md](../resource-pack/README.md#nordtalbossbar) |
| **The HUD needs no digit glyphs**: the boss bar font already overrides printable ASCII at the bar's metrics | [resource-pack/README.md](../resource-pack/README.md#---and-above--ascii-override) |
| `\uE000`–`\uE003`, freed by deleting season 1's role tags, are re-used rather than left empty — nothing persists a glyph, so a code point cannot be read back meaning the wrong thing | [resource-pack/README.md](../resource-pack/README.md#minecraftdefault) |
| A phase switch to `SMP` **disconnects** a player who now lacks access, with the login gate's own message — it does not push them to `limbo` | [season-phases.md](season-phases.md#routing) |
| **`MAINTENANCE` holds non-admins in `limbo` rather than disconnecting them**; admission during maintenance is the same rule as the event phases, and `discord_user.admin` decides only that an admin is *not* moved. An unlinked player is still refused with a link code | [season-phases.md](season-phases.md#the-phases) |
| A phase's backend is named in `gate.yml`, defaulting to the module directory name — nothing here says what `velocity.toml` calls them. A missing backend disconnects the player (the maintenance screen during `MAINTENANCE`, `gate.no-server` otherwise) rather than dropping them somewhere undefined | [season-phases.md](season-phases.md#routing) |
| Phase propagation polls every **30 s** on channel `nordtal_phase`, and `NOTIFY` is built in the first pass rather than deferred | [season-phases.md](season-phases.md#source-of-truth-and-propagation) |
| The proxy's emergency `/phase` is authorised by `discord_user.admin`, not by console — with the outage case written down rather than left to be rediscovered | [season-phases.md](season-phases.md#who-may-switch-it) |
| `access.yml#link-code-ttl-minutes` is retired; `gate.yml`'s copy is the only one | [../CLAUDE.md](../CLAUDE.md) |
| **`network-control` fails closed on a bad config**: a deny-all `LoginEvent` handler, which is the per-plugin disable Velocity does not give you | [operations.md](operations.md#configuration-and-secrets) |
| Hunger games start: hard minimum 2 participants (the border step divides by `participants − 1`), soft minimum 4 behind a confirmation | [hunger-games.md](hunger-games.md#start) |
| Spectators may join at any time, and **there is no team chat** — one per-server chat, as everywhere else | [hunger-games.md](hunger-games.md#the-lobby) |
| **SimpleCloud runs Minecraft 26.2** — confirmed by the owner against the v3 dashboard; the platform question is closed and the API-artefact question is now its own row | [operations.md](operations.md#closed-2026-08-31) |
| The open-verification table is ordered by **which session owns the answer**, and every row now says **what happens if the answer is no** — an unverified assumption with no written fallback is a decision nobody has made | [operations.md](operations.md#open-verification) |
| **Decided 2026-08-31 — the milestone track and block logging** | |
| The track is **20 · 43 · 99 · 400 · Nether · End · 900 · 4000** — eight milestones, of which `departure` (43) is opened by an admin at the season's start and six are objective-driven. The Nether and the End are their own milestones and carry no border step | [smp.md](smp.md#the-track) |
| **Border 20 and 43 are a physical gate, not ceremony**: the balloon stands outside radius 10 and inside radius 21.5, so the opening expansion is what hands the community the farm world. This is a hard constraint on the spawn build | [smp.md](smp.md#spawns) |
| Objective **budgets are community play hours sized against a pessimistic population** — the final milestone is 8 players × 14 days × 1.5 h = 170 h. A strong turnout therefore finishes early, and the answer to that is to append a milestone, not to scale targets to the live player count | [smp.md](smp.md#how-every-number-in-that-table-was-derived) |
| `pot = round((budget ÷ objectives) × 5, to 10)`, **no minimum pot** — a minimum flattens the ramp the track exists to create | [smp.md](smp.md#how-every-number-in-that-table-was-derived) |
| **Every milestone carries exactly one `ADVANCEMENT` participation gate** (10 · 10 · 8 · 8 · 6 · 5 distinct players) — the only objective type three people cannot finish alone, and the only one whose progress survives a player leaving | [smp.md](smp.md#the-rules-the-content-has-to-obey) |
| `STATISTIC` objectives use **active statistics only** — never distance walked or time played, which would pay every present player a contribution share | [smp.md](smp.md#the-rules-the-content-has-to-obey) |
| `HAND_IN` **deliberately demands farmable materials in rising quantities** from M3 onward, so that building the farm becomes the content of the late game; the no-automation constraint is met by the budget, not by banning farms | [smp.md](smp.md#the-rules-the-content-has-to-obey) |
| Contribution payout is a **split of the pot — 30 % equally among qualifiers, 70 % by share** — with a 2 % qualifying threshold and a minimum of 1 aura per qualifier. The old absolute floor was replaced because it could exceed a small objective's whole pot | [smp.md](smp.md#contribution-payout) |
| Three escape hatches for an impossible objective — lower the target and reload, complete one objective, unlock the milestone — and **every admin completion pays `pot × (reached ÷ target)`**, so a rescue neither robs the contributors nor mints aura | [smp.md](smp.md#when-an-objective-turns-out-to-be-impossible) |
| **Deaths cost aura**: −5 ordinarily, −20 for a listed cause, nothing in the duel arena. No exemptions beyond the arena and no protection against a death drain — the same agreement that governs raiding governs this | [smp.md](smp.md#deaths-cost-aura) |
| Advancements grant **2–10** aura, not 5–25; a duel only ever moves ±10 between two players | [smp.md](smp.md#aura--recognition-not-currency) |
| **Nordtal is pre-generated once, to its final border of 4000, before the phase opens.** A milestone unlock moves a number and never starts a generator; the Nether and End borders are a fixed 2000 | [smp.md](smp.md#worlds) |
| Block logging is **CoreProtect on its own SQLite file**, and we wait for its 26.2 release — checked 2026-08-31: it has none, its `master` builds against 26.2-alpha, and Prism 4.4 is the only released 26.2 option and the documented fallback. Nothing blocks on it | [smp.md](smp.md#block-logging--checked-2026-08-31) |
| The claim that **grave-emptying is traceable through the block log was struck** — graves are plugin-managed inventories and there may be no logger running at all | [smp.md](smp.md#death-and-graves) |

## Working rules that apply to all of it

- **"It compiles" is not verification.** Anything touching packets, world state, player visibility
  or the login path needs a running server and real clients before it is called done.
- **Never assume a version, coordinate or protocol constant from memory** — check the repository
  metadata or the vendor's own artifacts, and note the date.
- **Nothing is ever migrated between seasons.** Each season is a full restart: new database, new
  Discord application, new configuration.
- **Ids never get real defaults.** Channel and role ids default to empty and the process refuses to
  start until they are filled in.
- When a document and the code disagree, **the code is the fact and the document is the bug** —
  unless the document says "designed, not built", in which case it is the plan.
