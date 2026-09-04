# Presentation

What Nordtal looks like, and the rules that keep six surfaces looking like one server.

**This document exists because the decision behind it was never written down.** The owner's
intention from the start was a *clean, polished Nordtal* — custom menu surfaces in the manner of
Origin Realms, not a stack of default plugin output. That was agreed and then lived only in his
head: on 2026-09-04 a full-text search of `docs/`, `resource-pack/README.md` and every `CLAUDE.md`
found no mention of it. `docs/smp.md` says "a GUI" five times and never says what a GUI looks like,
and lists "the contents of the hand-in and wheel GUIs" as still open. That is the same class of
problem as the world seed that existed nowhere but in a conversation (`state-of-play.md` finding 36),
and it is fixed the same way: by writing it down.

The technical facts below were measured on 2026-09-04 against the extracted 26.2 vanilla assets,
not taken from a tutorial. **Re-measure them at every version bump.** 1.21.9 moved the villager
trading result slot by one pixel; this is not a theoretical risk.

---

## 1. Where a glyph can appear, and where it cannot

This is the first rule because it bounds everything else. **A resource pack glyph only renders once
the client has downloaded and applied the pack.** Three surfaces are therefore permanently out of
reach, and one is uncertain:

| surface | glyphs? | why |
|---|---|---|
| server list MOTD | **no** | drawn in the client's own font before any pack is fetched |
| resource pack prompt | **no** | shown to decide whether to fetch the pack |
| disconnect / kick screens | **probably not** | the pack is unloaded when the world is left. **Reasoned, not tested** — see `todo.md` |
| the waiting room (`limbo`) | yes | the pack is applied by the time a player is held there |
| everything in-world | yes | |

The kick screens matter more than the list suggests: **every login-gate screen is a kick message.**
Nothing is designed against glyphs being available there until somebody has held a real client in
front of it.

What *is* available on the server list without any pack: the **64 × 64 favicon**, which travels in
the ping as its own PNG, and colour and formatting in the MOTD text.

---

## 2. Menu panels

### The technique

A menu is a normal chest inventory. The panel is a **bitmap glyph in the inventory title**, drawn
from a font with a large positive `ascent` so it rises out of the title's baseline and covers the
window. The slots stay vanilla slots and draw over it.

The title component is composed in this order:

1. a negative-space glyph, to move the cursor from the title anchor to the window's left edge
2. the panel glyph itself, in the `nordtal:gui` font, **explicitly `<white>`**
3. a negative-space glyph, to move the cursor back
4. the readable title, in the default font, positioned with further negative space

### The numbers

| quantity | value | note |
|---|---|---|
| drawn region of a 9 × 6 chest | **176 × 222 px** | measured from `generic_54.png` |
| chest `imageHeight` | `114 + 18 × rows` | 132, 150, 168, 186, 204, 222 — **all even** |
| title anchor | `x = 8`, `y = 6` | relative to the window's top-left |
| baseline | `y = 13` | anchor plus the default font's ascent of 7 |
| `ascent` for a flush panel | **13** | positive. `13 + N` lifts the glyph N px above the window's top edge |
| horizontal shift to the left edge | **−8 px** | |
| glyph advance | **trimmed width + 1** | a 176 px panel moves the cursor 177 |
| maximum glyph size | **256 × 256 px** | hard cap; a wider panel is several glyphs joined by negative space |
| title colour | `0x404040` | hardcoded by vanilla — white art comes out grey without an explicit `<white>` |

### Three decisions that follow

**The panel covers the vanilla background; it does not replace it.** Making
`gui/container/generic_54.png` transparent is what several polished servers do — they can, because
every container that opens on their server is opened by their own plugin and brings its own panel.
On an SMP whose entire concept is bases and chests that is false: every player chest in the world
would be frameless. So the panel is opaque and at least 176 × 222, and no vanilla container texture
is touched.

**Every menu is a chest, and only a chest.** Menus may differ in row count — all six chest heights
are even, so they centre identically and a panel drawn for one row count lands correctly for
another. The moment a menu becomes a hopper (`imageHeight` 133, odd) or a dispenser, that stops
being true and panels drift by a pixel against window height. A test asserts no menu opens a
non-chest inventory.

**Panels are authored at 1 texture pixel = 1 GUI pixel**, with `height` equal to the texture's own
pixel height, so nothing is resampled. Vanilla GUI scale is an integer multiple, so this stays sharp
at every scale; art authored at 2× or 4× does not.

### What we deliberately do not use

`minecraft:dialog` (1.21.6+) is a real, native, data-driven screen with proper widgets — text
fields, checkboxes, dropdowns, sliders — and Paper has an API for it. It is not used here. It has
no item slots, it cannot carry a custom background, and the client stamps a warning sign beside its
title. One picture across six surfaces is worth more than widgets on two of them. Revisit this if a
surface ever genuinely needs text input.

---

## 3. Frame style

**One visual language for menu panels and for the boards.** The GUI panels set it, because they are
the larger surface; the 22 `nordtal:board` textures are regenerated to match. Both are produced by
`resource-pack/tools/`, which means the style is a script change rather than 22 hand edits.

The boards themselves are Text Display entities, not inventories, so they share the *look* and
nothing else. Board width is computed from the longest entry, and the divider between title and
content is drawn.

Panel art is **programmatic placeholder** for now — frame, title bar, slot recesses, in the exact
measurements. That is deliberate and it is not a shortcut: the offsets are anchored to the
measurements, not to the image, so a hand-drawn panel of the same dimensions drops in without a
line of code changing.

---

## 4. Sound

**Nine categories, and a call site can only name a category.** Not a sound. This is a structural
rule, not a matter of discipline: a codebase where each call site names its own sound drifts into
nine different chimes for the same kind of event, and no amount of care prevents it.

| category | when | default key (`smp`) | pitch |
|---|---|---|---|
| `SMALL_SUCCESS` | something small went right — objective handed in, POI added or removed | `entity.experience_orb.pickup` | 1.4 |
| `BIG_SUCCESS` | something that took work — a milestone *you* closed, a duel won, a wheel prize | `entity.player.levelup` | 1.0 |
| `REFUSED` | the server said no — spawn-protected, not your POI, no spin left, nothing to show | `block.note_block.bass` | 0.7 |
| `LOSS` | something was taken — a duel lost, a death penalty, a spin spent on a broken prize | `entity.villager.no` | 0.9 |
| `SURFACE_OPEN` / `SURFACE_CLOSE` | a menu or a grave | `block.barrel.open` / `.close` | 1.2 |
| `SELECT` | a click that picked something — a menu entry, a duel platform stepped onto | `ui.button.click` | 1.0 |
| `TRAVEL` | going somewhere — balloon, farm reset, duel arena | `block.beacon.power_select` | 1.0 |
| `COUNTDOWN_TICK` | a clock running out — duel 3-2-1-**Go**, farm reset, restart, border | `block.note_block.hat` | 1.0 |
| `NETWORK_EVENT` | everyone hears it — a milestone somebody *else* closed, a phase switch | `ui.toast.challenge_complete` | 1.0 |

Nine categories, **ten constants**: open and close are the two halves of one. Every key above was
resolved against `org.bukkit.Sound` as compiled for `paper-api:26.2.build.121-stable` on 2026-09-04,
and `SoundDefaultsTest` re-resolves all ten on every build — a Minecraft release that retires one
turns the build red instead of turning a chime silent. **Nobody has heard them next to each other**;
they are proposals, and retuning one is a config edit.

**A key is a namespaced registry key, never a Bukkit constant** — `minecraft:ui.button.click`, not
`UI_BUTTON_CLICK`. The constants are generated from the registry and documented as removable between
versions; the key is what the resource pack, `/playsound` and the protocol all use. It is also how a
custom sound out of `resource-pack/` arrives later with no Java change: a key that parses but names
nothing the server knows simply plays nothing.

**An empty key silences that category**, which is the escape hatch for a sound that turns out to be
irritating with twenty people in a tavern — and it works everywhere in the module at once, which is
the point of a call site choosing a category rather than a sound. In `smp` that lives in
**`sounds.yml`, not `config.yml`**, and the separation is load-bearing: `config.yml` is deliberately
not reloadable (the plugin binds worlds, borders and coordinates once at enable and would not notice
them changing), so an escape hatch inside it would have cost a restart of the season to use.
`/smp reload` re-reads the sounds, the track and the message bundles, each reported separately
because each fails on its own.

**Nothing about a bad sound stops a server.** A key that does not parse, or a volume or pitch that
makes no sense, is reported once in the console and the category is silenced or the number corrected;
a sound that throws when it is played is silenced for the rest of the run. That deviates from this
repository's usual "a bad config is `getServer().shutdown()`" and it is deliberate — a typo in a
chime is not worth a season offline.

### Where the vocabulary is deliberately silent

Naming these is as much a decision as the rest, and each is a comment at its call site so the next
session does not "finish" it:

- **an objective closing** — the middle rung of a ladder. Handing something in is `SMALL_SUCCESS`
  for the one player; a milestone is `BIG_SUCCESS` for whoever closed it and `NETWORK_EVENT` for
  everybody else. There are several objectives per milestone, so a network-wide sound on each would
  make the milestone's own sound mean less.
- **a duel queued** — whoever stepped on second already heard `SELECT` in the same tick.
- **a farm reset postponed** — the countdown ticks because the world is about to be taken away; a
  reset that did not happen takes nothing.
- **a duel interrupted by the server stopping** — it cost nobody anything.

### What the proxy cannot do

**Velocity cannot play a sound the ordinary way.** `velocity-api:4.1.1` documents both
`Player#playSound(Sound)` and the coordinate overload as *"not currently implemented in Velocity and
will not perform any actions"* — they are empty default methods (read from the 4.1.1 sources jar,
2026-09-04). Only `playSound(Sound, Sound.Emitter)` works, on 1.19.3+, and it "requires a present
`getCurrentServer()` for the emitting player as well as this player". `Player` is itself a
`Sound.Emitter`, so `player.playSound(sound, player)` is the shape that could work for a player
already on a backend — **untested, and it needs a real client**. Until somebody has heard it, a
restart countdown from the proxy is chat and title only.

## 5. Chat and system messages

Every line a player reads all day. Vanilla's own wording is replaced, not decorated:

- **chat** — flag, name, prestige crest, a glyph separator, then the message
- **join and leave** — our own, in each reader's language
- **death** — our own, and the vanilla message suppressed
- **advancement** — intercepted; `PlayerAdvancementDoneEvent#message` is nullable, so an
  advancement can be announced in our wording or not announced at all

All of it goes through `MessageRenderer`, which parses MiniMessage and escapes substituted values —
a player named `<red>` cannot colour the rest of the line. All of it is overridable per key from
the data folder without a release.

---

## 6. What stays vanilla, on purpose

Naming these is as much a decision as naming the rest, and it stops the next session from
"finishing" something that was left alone deliberately.

- **The HUD chrome** — hearts, hotbar, experience bar, food, armour, crosshair. They are global pack
  overrides with no server control, and a season that repaints them is fighting the game rather
  than dressing it.
- **Container textures for real chests, barrels and shulkers.** See §2.
- **The terrain-loading screen.** There is no API for it; `limbo` is the answer to that problem.
- **The scoreboard sidebar.** Not used at all — the boards are Text Displays in the world, which is
  a deliberate choice recorded in `smp.md`.
- **The MOTD's typography.** It cannot carry glyphs (§1), so it is colour and wording only.

---

## 7. Where the authority lives

| thing | owned by |
|---|---|
| code point allocation, font metrics, panel measurements | [`../resource-pack/README.md`](../resource-pack/README.md) |
| what each surface says, in which language | the modules' `messages/` bundles and [`i18n.md`](i18n.md) |
| the rules above | this file |
| whether a surface has actually been seen by a human | `todo.md`, outside this repository |

`Glyphs`, the three font files and the allocation table are mirrors of one table, and
`ResourcePackTest` fails when they drift. Nothing else in the repository names a code point.
