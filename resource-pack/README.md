# resource-pack

The nordtal.eu resource pack. It holds glyphs in the Unicode private use area, HUD sprites, menu
panels and a few vanilla overrides.

**[`glyphs.json`](glyphs.json) owns every code point.** The build writes the font files, the
`Glyphs` class of `:pack-rendering`, the advance tables of the plugins and the glyph list of
Steward's translation editor from it. A glyph of `minecraft:default` also has a name, which a message
writes as `<glyph:name>`.

```
glyphs.json           every font, block and glyph
src/assets/nordtal/   textures and font providers
src/assets/minecraft/ the vanilla overrides
build/pack            the assembled pack: src/ plus the fonts the build writes
```

## Editing the art

The steps run from the Run menu of IntelliJ, folder **resource pack**, on any operating system, with
Minecraft 26.2.

1. Run **pack: 1. choose Minecraft instance** once and pick the folder that holds `options.txt` and `resourcepacks`.
2. Edit an image under `src/assets/nordtal/textures` and save it in place.
3. Run **pack: 2. install into Minecraft**. It names every image it cannot use and copies the pack
   in as `nordtal-dev`. It enables the pack when the game is closed. Otherwise enable it once under
   Options > Resource Packs.
4. Press **F3+T** in the game.

A new glyph is an image plus one entry in `glyphs.json`: a free code point in its block, a constant,
a texture and, in `minecraft:default`, a name. The next build names every rule that an entry breaks.

An admin chooses **Skip resource pack** on a user's row under Users in Steward to show that user the
network with their own pack. **Enforce resource pack** undoes it.

## Building and hosting

```bash
./gradlew :resource-pack:packZip
```

The task writes `build/distributions/nordtal-resource-pack-<version>.zip` and its `.sha1`. The build
is reproducible, so a version always has the same hash. `pack.mcmeta` declares the formats **88** to
**97** (Minecraft 26.2 and 26.3). Both files are attached to each GitHub release. The proxy offers the
pack while the player waits in `limbo`. `dev pack` points a local stack at a fresh build.

The URL and the hash are the proxy's `pack` settings, and both are empty by default. The proxy
fails closed while one is empty. An update run sets both to the assets of the installed release.
Steward raises the pack alert when the URL stops answering.

- The URL has the form `github.com/<owner>/<repo>/releases/download/<tag>/<file>`. The signed
  `release-assets.githubusercontent.com` address expires within the hour.
- Steward checks only that `sha1` has 40 hex characters. A wrong hash shows in the client as
  `FAILED_DOWNLOAD`.

## What the build writes

`generateGlyphs` reads `glyphs.json` once and writes the files below. None of them is committed.

- The font files: one per font and six for `nordtal:gui_r{row}`. `assemblePack` copies them into `build/pack`.
- `Glyphs`, compiled into `:pack-rendering`. It holds a constant per glyph and per space, the lists
  and `named()` for `<glyph:name>`.
- `nordtal/hud/bossbar-advances.properties` and `nordtal/menu/gui-row-advances.properties`. They hold
  the widths that the plugins compose pills and rows with, derived from the written fonts by the
  client's rule, and ship in the jar of `:pack-rendering`. `BossBarAdvancesTest` and `MenuFontTest`
  derive the widths again independently.
- `manifest.json` and one PNG per named glyph, which Steward's translation editor lists.

## glyphs.json

`fonts` lists one entry per font, in the order that `Glyphs` follows. The `about` of a font, block,
glyph or list becomes the doc comment of its constant.

| level | key                          | meaning                                                                                                         |
| ----- | ---------------------------- | --------------------------------------------------------------------------------------------------------------- |
| font  | `font`                       | the font id, such as `nordtal:board`; with `{row}` in it, one font per row                                      |
| font  | `rows`, `pitch`              | the number of row fonts that `{row}` writes, counted from 0, and the pixels between one row and the next        |
| font  | `constant`                   | the Java constant of the font id, or of the list of row ids                                                     |
| font  | `numbering`                  | `"own"` lets the code points of the font repeat those of another font                                           |
| font  | `spaces`                     | the `space` provider: each entry has a `code`, an `advance` in pixels and an optional `constant`                |
| font  | `blocks`, `lists`            | the code points in ascending order; Java lists (`constant`, `about`) that a glyph joins with `list`             |
| block | `range`, `title`             | a range such as `FE000-FE00F`, and the name of the block in `Glyphs` and in every message about it              |
| block | `texture`                    | makes the block one sheet whose glyphs are the cells of one row, left to right                                  |
| block | `chars`                      | replaces `range` and `glyphs`: rows of ordinary characters, one per sheet row; `\u0000` is no cell             |
| block | `height`, `ascent`, `status` | the default of every glyph in the block; a sheet needs both `height` and `ascent`                               |
| glyph | `code`, `constant`           | the code point in capital hex and its constant in `Glyphs`; a glyph without a constant is reached by its list   |
| glyph | `name`                       | its `<glyph:name>`, lowercase and hyphenated; every glyph of `minecraft:default` has one and no other does      |
| glyph | `texture`                    | `nordtal:<path>.png` under `src/assets/nordtal/textures`; the cells of a sheet take the texture of the block    |
| glyph | `height`, `ascent`           | the values of the bitmap provider, unless the block sets them                                                   |
| glyph | `status`                     | `keep` is shipping art, `final candidate` can ship, `placeholder` is for testing; unset is unjudged            |

The build checks every rule before it writes anything. It names each broken rule by its place, such as
`glyphs.json > nordtal:bossbar > Status icons`.

- Every code point lies in Supplementary Private Use Area A, `U+F0000` to `U+FFFFD`. A `space` may
  also be the ordinary space. Other glyph plugins assign the private use area of the basic plane.
- Blocks and their glyphs ascend, do not overlap and stay inside their block. A font draws a code point once.
- Only `nordtal:bossbar` numbers on its own, and its bar segments are named by their width in
  decimal. The blocks of every other font never overlap the blocks of another font, so a code point in
  a log names one glyph.
- A code point declared as a space has the same advance in every font that declares it.
- Constants are unique across the file, names are unique among the glyphs, and no text holds `*/`.

## The fonts

`U+FE001` is a different glyph in `minecraft:default` than in `nordtal:bossbar`. A component without
a font draws `minecraft:default`, so every component with a `nordtal:` glyph names its font.
`:architecture` lets only `BossBarLine` name a boss bar. Every code point is a surrogate pair in
UTF-16: `Glyphs` writes `cp(0xFE004)`, and no message bundle holds one (`TabListTest`). A glyph reaches
a bundle as a `{parameter}` or as `<glyph:name>`.

- **`nordtal:board`:** a row draws its left edge, advances by the configured width
  (`boards[].width` in the `config` group of `smp`, 32 to 240 px), draws the right edge and goes back.
  Only the client knows the width of a text line.
- **`nordtal:bossbar`:** its spaces are a `space` provider. The low digits of a code point are its
  advance in decimal, negative advances included, so a line can draw over itself. A HUD line is one
  rounded pill per piece of information. No advance may be allocated onto an assigned character.
- **`nordtal:gui`:** menu panels, drawn from the inventory title. A title holds a bitmap as large as
  the window on a high `ascent`, over `generic_54.png`. A title needs **-8** to reach the left edge of
  the window and **-169** to go back from the 177 px advance of the panel. The positive spaces are the
  negative ones one bit higher, and every row font declares them too. A chest window is
  `114 + 18 × rows` pixels tall, hence six panels, and the drawable cell starts at **(7, 17)**.
  Each card state is a card-sized overlay glyph. It is declared once per card row with the ascent
  that places it there (`13 - y`). `MenuTitle.Canvas` composes it and `MenuTitleTest` checks every overlay.
- **`nordtal:gui_r0` to `gui_r5`:** six copies of one font, one per chest row. The `ascent` of a font
  is the only vertical control of a glyph. Each row draws its bitmaps 18 pixels lower than the row
  above, with the same characters at three ascents centred in the 18 px cell:

| layer     | height | top        | ascent      | content                                    |
| --------- | ------ | ---------- | ----------- | ------------------------------------------ |
| furniture | 14     | `19 + 18r` | `-6 - 18r`  | a plate: pill, active frame, button        |
| icons     | 8      | `22 + 18r` | `-9 - 18r`  | an 8 × 8 pictogram, centred in the plate   |
| text      | 5      | `23 + 18r` | `-10 - 18r` | the five-pixel sheet, centred in the plate |

Text lands at **+4**, and `MenuFontTest` checks each ascent against `SlotGeometry`.

`ui/gui/row_text.png` is the five-pixel sheet. It is an 8 × 7 grid of 5 × 5 cells that hold `A` to `Z`,
`0` to `9`, `. , : / - + % ( ) ! ? ' "`, `Ä Ö Ü ß`, `∙` and `█` `░` for a text bar. A space is a `space`
provider at **+3**. The sheet has capitals only. `MenuFont` in `:paper-common` folds lower case onto
them, except `ß`, and maps unknown characters to `?`, because a missing-glyph box is six pixels wide.
`O`, `0` and `8` are three pixels wide and drawn apart. `MenuFontTest` checks their distances:

| glyph | rows                  | distinguishing feature                             |
| ----- | --------------------- | -------------------------------------------------- |
| `O`   | `.#. #.# #.# #.# .#.` | round, tapered top and bottom, like `C G Q`        |
| `0`   | `### #.# #.# #.# ###` | square, flat top and bottom, like `1 2 3 5 7`      |
| `8`   | `### #.# .#. #.# ###` | square and waisted: two loops joined in the middle |

## Vanilla overrides

| file                                                           | effect                                                                     |
| -------------------------------------------------------------- | -------------------------------------------------------------------------- |
| `minecraft/textures/gui/sprites/boss_bar/white_background.png` | makes the vanilla boss bar frame invisible, so only the composed HUD shows |
| `minecraft/textures/gui/sprites/boss_bar/white_progress.png`   | makes the progress fill invisible                                          |
| `minecraft/lang/en_us.json`                                    | sets `menu.returnToGame`, `menu.game` (the logo glyph) and `menu.disconnect` |
| `minecraft/lang/de_de.json`                                    | sets the same three keys in German                                         |

`de_de.json` is the only place where the client language decides what a player sees, and it holds
these three pause-menu strings only. `menu.game` names a glyph, so a language file is the one place
besides `glyphs.json` that writes a code point by hand. `ResourcePackTest` parses the language files
against the written `minecraft:default`.
