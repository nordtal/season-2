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

| category | when | examples |
|---|---|---|
| `SMALL_SUCCESS` | something small went right | objective handed in, spin won |
| `BIG_SUCCESS` | something that took work | milestone completed, duel won |
| `REFUSED` | the server said no | spawn-protected, not your POI, no spin left |
| `LOSS` | something was taken | duel lost, death penalty |
| `SURFACE_OPEN` / `SURFACE_CLOSE` | a menu or a grave | every GUI |
| `SELECT` | a click inside a menu | picking a navigation target |
| `TRAVEL` | going somewhere | balloon, duel arena |
| `COUNTDOWN_TICK` | a clock running out | farm reset, restart, duel start, border |
| `NETWORK_EVENT` | everyone hears it | milestone, hunger games start, phase switch |

The enum lives in `:common`; each module maps category → sound, pitch and volume in its own
`config.yml`. **An empty mapping silences that category**, which is the escape hatch for a sound
that turns out to be irritating in practice. A test asserts that no call site outside the mapping
names a sound directly.

---

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
