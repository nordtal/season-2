# resource-pack

The nordtal.eu resource pack: glyphs in the Unicode private use area, HUD sprites, menu panels and a
few vanilla overrides.

**[`glyphs.json`](glyphs.json) owns every code point.** The build writes the font files,
`:pack-rendering`'s `Glyphs`, the advance tables the plugins carry and the glyph list of Steward's
translation editor from it, so there is no second copy to keep in step. A glyph of
`minecraft:default` also has a name, written as `<glyph:name>` in a message.

## Editing the art

Everything runs from IntelliJ's Run menu, folder **resource pack**, on any operating system. You
need Minecraft 26.2.

1. Run **pack: 1. choose Minecraft instance** once and pick the folder holding `options.txt` and
   `resourcepacks`.
2. Edit an image under `src/assets/nordtal/textures` and save it in place.
3. Run **pack: 2. install into Minecraft**. It names every image it cannot use, copies the pack in
   as `nordtal-dev` and enables it if the game is closed; otherwise enable it once under Options >
   Resource Packs.
4. Press **F3+T** in the game.

A new glyph is its image plus one entry in `glyphs.json`: a free code point in the block it belongs
to, its constant, its texture and, in `minecraft:default`, its name. The next build checks the file
and names every rule an entry breaks.

A server's pack covers every local one. To see yours on the network, an admin chooses **Skip
resource pack** on your row under Users in Steward; from your next login you get no pack, until
**Enforce resource pack** on the same row.

## Building

```bash
./gradlew :resource-pack:packZip
```

writes `resource-pack/build/distributions/nordtal-resource-pack-<version>.zip` and its `.sha1`. The
zip's root is the assembled pack in `build/pack`: [`src/`](src/) plus the fonts the build writes.
`pack_format` is **88** (Minecraft 26.2). `dev pack` points a local stack at a fresh build.

## Hosting

The zip and its hash are attached to each GitHub release, and the proxy offers the pack while the
player waits in `limbo`. The build is reproducible, so a version always has the same hash.

URL and hash are the proxy's `pack` settings, which an update run sets; both default to empty, and
the proxy fails closed until they are set.

- Use the `github.com/<owner>/<repo>/releases/download/<tag>/<file>` URL, never the signed
  `release-assets.githubusercontent.com` address it redirects to, which expires within the hour.
- `sha1` is only checked to be 40 hex characters. A wrong hash shows in the client as
  `FAILED_DOWNLOAD`.

## What the build writes

`generateGlyphs` reads `glyphs.json` once and writes everything below; none of it is committed.

- The font files, one per font and six for `nordtal:gui_r{row}`. `assemblePack` puts them beside
  `src/` in `build/pack`, which the zip, `installPack` and the tests read.
- `Glyphs`, compiled into `:pack-rendering`: a constant per glyph and per space, the lists, and
  `named()` for `<glyph:name>`.
- `nordtal/hud/bossbar-advances.properties` and `nordtal/menu/gui-row-advances.properties`, the
  widths the plugins need to compose pills and rows, derived from the written fonts by the client's
  rule. They ship in `:pack-rendering`'s jar, so a changed width reaches a server with the next
  plugin rollout, not with the pack alone. `BossBarAdvancesTest` and `MenuFontTest` derive them again
  independently.
- `manifest.json` and one PNG per named glyph, the glyph list Steward's translation editor shows.

# glyphs.json

`fonts` lists one entry per font, and `Glyphs` follows its order. The `about` of a font, block, glyph
or list is what it is for; the build makes it the Java doc of its constant.

## A font

| key         | meaning                                                                                                    |
| ----------- | ---------------------------------------------------------------------------------------------------------- |
| `font`      | the font id, such as `nordtal:board`; with `{row}` in it, one font per row                                 |
| `rows`      | how many row fonts `{row}` writes, from 0                                                                  |
| `pitch`     | how many pixels each row sits below the one before it; set exactly when `rows` is                          |
| `constant`  | the Java constant of the font id, or of the list of row ids                                                |
| `numbering` | `"own"` lets the font's code points repeat another font's; see below                                       |
| `spaces`    | the font's `space` provider: each entry a `code`, its `advance` in pixels and an optional `constant`       |
| `blocks`    | the font's code points, in ascending order                                                                 |
| `lists`     | Java lists, each a `constant` and an `about`; a glyph joins one with `list`, and the list keeps file order |

## A block

A block is a run of code points under one title. What it does not use is reserved for its growth.

| key                | meaning                                                                                                     |
| ------------------ | ----------------------------------------------------------------------------------------------------------- |
| `range`            | `FE000-FE00F`, the block's code points                                                                      |
| `title`            | the block's name in `Glyphs` and in every message about it                                                  |
| `texture`          | makes the block one sheet: its glyphs are the cells of one row, left to right                               |
| `chars`            | instead of `range` and `glyphs`: rows of ordinary characters, one per row of the sheet; `\u0000` is no cell |
| `height`, `ascent` | the default of every glyph in the block; a sheet needs both                                                 |
| `status`           | the default of every glyph in the block                                                                     |
| `glyphs`           | the allocated code points, ascending                                                                        |

## A glyph

| key                | meaning                                                                                                 |
| ------------------ | ------------------------------------------------------------------------------------------------------- |
| `code`             | the code point in capital hex, such as `FE004`                                                          |
| `constant`         | its constant in `Glyphs`; a glyph without one is reached through its list                               |
| `name`             | its `<glyph:name>`, lowercase and hyphenated; every glyph of `minecraft:default` has one, no other does |
| `list`             | the list it belongs to                                                                                  |
| `texture`          | `nordtal:<path>.png`, under `src/assets/nordtal/textures`; a sheet's cells take the block's             |
| `height`, `ascent` | the bitmap provider's, unless the block sets them                                                       |
| `status`           | how far the art is                                                                                      |

**Status:** _keep_ is shipping art; _final candidate_ is drawn geometry good enough to ship;
_placeholder_ exists for testing until the real design. Where it is left out, nobody has judged the
art.

## What the build holds it to

Every rule is checked before anything is written. A broken file fails the build, naming each
broken rule and where it is, such as `glyphs.json > nordtal:bossbar > Status icons`; the rules
across entries run once every entry is sound.

- **Every code point lies in Supplementary Private Use Area A**, `U+F0000` to `U+FFFFD`; a `space`
  may also be the ordinary space. The basic plane's `U+E000` to `U+F8FF` is where other glyph plugins
  auto-assign, and Minecraft's unused `unifont_pua` provider would draw real glyphs there.
- **Blocks and their glyphs ascend and do not overlap**, and a glyph lies inside its block, so the
  file reads in code point order and a free code point is visibly free. A font draws a code point
  once.
- **The fonts allocate independently, but only `nordtal:bossbar` numbers on its own**: its bar
  segments are named after their width in decimal. The blocks of every other font never overlap one
  another's, so a code point in a log names one thing.
- **A space means one width.** A code point declared as a space advances the same in every font that
  declares it, so a constant of one font serves the others.
- Constants are unique across the file, names unique among the glyphs, and no text holds `*/`,
  which would end a doc comment.

Reusing a freed code point is safe, since nothing persists a glyph character.

## Naming the font

The fonts allocate **independently**: `U+FE001` means something different in `minecraft:default`
and in `nordtal:bossbar`. A component that names no font draws whatever `minecraft:default` holds at
that code point, so every component carrying a `nordtal:` glyph names its font; `:architecture` lets
only `BossBarLine` name a boss bar.

Every code point is a surrogate pair in UTF-16. `Glyphs` writes them as `cp(0xFE004)`, the font
files as escaped pairs, and no message bundle carries one (`TabListTest`); a glyph reaches a bundle
as a `{parameter}` or as `<glyph:name>`.

# The fonts

## `nordtal:board`

The frame never draws after the content, because the client alone knows how wide a text line is. A
row draws its left edge, walks right by the configured width (`boards[].width` in `smp`'s `config`
group, 32 to 240 px), draws the right edge, and walks back. A line longer than that overdraws the
edge. The frame uses the menu panels' `highlight` and `accent` colours, since it hangs on a dark
background. The divider is separate from the horizontal edge so the inner rule can look different
from the border.

## `nordtal:bossbar`

Its spaces are a `space` provider whose code point's low digits are the advance in decimal, so there
is no +64 or +128: `FFF128` would leave the plane. Negative advances let a line draw over itself.
Never allocate an advance onto an assigned character: the client would replace it.

A HUD line is one rounded pill per piece of information. The body is translucent with a lighter
rim. The build derives every advance in this font the way the client does, for the plugins.

The four land flags are season 1 art, identical but for the banner colour; season 2 draws none of
them yet.

## `nordtal:gui`

**Menu panels, drawn from the inventory title.** A menu is a chest inventory whose title carries a
bitmap as large as the window on a high `ascent`; labels render after the background, so the panel
covers `generic_54.png`. The panel is opaque because the vanilla background has to stay for every
ordinary chest.

A title needs **−8** to reach the window's left edge and **−169** (−128 −32 −8 −1) to walk back from
the panel's 177 px advance. The positive spaces are the negative ones one bit higher, `+16` at
`U+FF816`, and every row font declares them too, since a row is composed left to right.

A chest window is `114 + 18 × rows` pixels tall, hence six panels. A hand-drawn panel of the same
size drops in without Java changes. The drawable cell starts at **(7, 17)**, not (8, 18), because
`ChestScreen` draws the player rows one pixel higher than the texture. Re-measure at every version
bump. All twelve panels share one header: flat ground and one hairline at `y 16`. A list menu draws
a plate across a row, which a recess would show around, hence the panels without recesses.

**One panel and overlays, not a panel per state.** Each card state is a card-sized glyph declared
once per card row with the ascent that lands it there (`13 − y`); the title walks back to the card's
x before drawing it. `MenuTitle.Canvas` in `:smp` composes it and `MenuTitleTest` checks every
overlay lands on its card.

The wheel sits two columns left of centre to free columns 5 to 8 for its button and text. The bar's
track is baked into the card and the fill is at most four power-of-two glyphs.

## `nordtal:gui_r0` to `nordtal:gui_r5`

**Six copies of one font, one per chest row.** A font's `ascent` is a glyph's only vertical control,
and text code points are not ours to pick, so the row lives in the font: `nordtal:gui_r2` draws
everything on chest row 2. The entry declares row 0, and every row draws each bitmap 18 pixels lower
than the one above. Each row declares the same characters at three ascents, centred in the 18 px
cell:

| layer     | height | top        | ascent      | what                                       |
| --------- | ------ | ---------- | ----------- | ------------------------------------------ |
| furniture | 14     | `19 + 18r` | `−6 − 18r`  | a plate: pill, active frame, button        |
| icons     | 8      | `22 + 18r` | `−9 − 18r`  | an 8 × 8 pictogram, centred in the plate   |
| text      | 5      | `23 + 18r` | `−10 − 18r` | the five-pixel sheet, centred in the plate |

Text lands at **+4**, not +3. `MenuFontTest` checks each ascent against `SlotGeometry`.

### The five-pixel sheet

`ui/gui/row_text.png`, an 8 × 7 grid of 5 × 5 cells: `A` to `Z`, `0` to `9`,
`. , : / - + % ( ) ! ? ' "`, `Ä Ö Ü ß`, `∙`, and `█` `░` for a text bar. A space is a `space`
provider at **+3**, since an empty cell would advance one pixel.

It is all capitals: `:smp`'s `MenuFont` folds lower case onto them (except `ß`) and maps unknown
characters to `?`, because a missing-glyph box is six pixels wide and would shift everything after
it. `0`, `O` and `8` are drawn apart:

| glyph | rows                  | what tells it apart                                                                                                                        |
| ----- | --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `O`   | `.#. #.# #.# #.# .#.` | round, tapered top and bottom: the shape every other letter loop on this sheet has (`C G Q`)                                               |
| `0`   | `### #.# #.# #.# ###` | square, flat top and bottom: the shape the other digits have (`1 2 3 5 7`), so a zero reads as a digit rather than as the letter beside it |
| `8`   | `### #.# .#. #.# ###` | square and **waisted**: two loops joined in the middle. Three pixels from the zero rather than one, and the waist is visible at 1×         |

All three stay three pixels wide, so columns of numbers line up. `MenuFontTest` checks their
distances and that no other two characters match, except the listed `U`/`V`.

## Vanilla overrides

| file                                                           | what it does                                                                      |
| -------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `minecraft/textures/gui/sprites/boss_bar/white_background.png` | makes the vanilla boss bar frame invisible, so the composed HUD is all that shows |
| `minecraft/textures/gui/sprites/boss_bar/white_progress.png`   | likewise, the progress fill                                                       |
| `minecraft/lang/en_us.json`                                    | `menu.returnToGame`, `menu.game` (the logo glyph) and `menu.disconnect`           |
| `minecraft/lang/de_de.json`                                    | the same three keys in German                                                     |

`de_de.json` is the one place where the client's language decides what a player sees, since a lang
file cannot read `discord_user.locale`; it is limited to these three pause-menu strings.

## A code point in a language file is a code point

`minecraft/lang/*.json` may name a glyph (`menu.game` draws the logo), which makes it the one
place besides `glyphs.json` that writes a code point by hand. `ResourcePackTest` parses those files
against the written `minecraft:default`; a text search would miss the `\u` escapes.
