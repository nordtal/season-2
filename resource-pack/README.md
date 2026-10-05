# resource-pack

The nordtal.eu resource pack: glyphs in the Unicode private use area, HUD sprites, menu panels and a
few vanilla overrides.

**[`glyphs.json`](glyphs.json) owns every code point.** The build writes the font files,
`:pack-rendering`'s `Glyphs`, the advance tables the plugins carry and the glyph list of Steward's
translation editor from it. A glyph of `minecraft:default` also has a name, written as
`<glyph:name>` in a message.

```
glyphs.json           every font, block and glyph
src/assets/nordtal/   textures and font providers
src/assets/minecraft/ the vanilla overrides
build/pack            the assembled pack: src/ plus the fonts the build writes
```

## Editing the art

Everything runs from IntelliJ's Run menu, folder **resource pack**, on any operating system, with Minecraft 26.2.

1. Run **pack: 1. choose Minecraft instance** once and pick the folder holding `options.txt` and `resourcepacks`.
2. Edit an image under `src/assets/nordtal/textures` and save it in place.
3. Run **pack: 2. install into Minecraft**: it names every image it cannot use, copies the pack in
   as `nordtal-dev` and enables it if the game is closed (otherwise enable it once under Options > Resource Packs).
4. Press **F3+T** in the game.

A new glyph is its image plus one entry in `glyphs.json`: a free code point in its block, its
constant, its texture and, in `minecraft:default`, its name. The next build names every rule an entry
breaks. To see your own pack on the network, an admin chooses **Skip resource pack** on your row under
Users in Steward, and **Enforce resource pack** to undo it.

## Building and hosting

```bash
./gradlew :resource-pack:packZip
```

writes `build/distributions/nordtal-resource-pack-<version>.zip` and its `.sha1`; `pack_format` is
**88** (Minecraft 26.2). The build is reproducible, so a version always has the same hash. Both files
are attached to each GitHub release, and the proxy offers the pack while the player waits in `limbo`.
`dev pack` points a local stack at a fresh build.

URL and hash are the proxy's `pack` settings, which an update run sets; both are empty by default and
the proxy fails closed until they are set.

- Use the `github.com/<owner>/<repo>/releases/download/<tag>/<file>` URL, not the signed
  `release-assets.githubusercontent.com` address it redirects to, which expires within the hour.
- `sha1` is checked only to be 40 hex characters; a wrong hash shows as `FAILED_DOWNLOAD` in the client.

## What the build writes

`generateGlyphs` reads `glyphs.json` once and writes everything below; none of it is committed.

- The font files, one per font and six for `nordtal:gui_r{row}`, which `assemblePack` puts in `build/pack`.
- `Glyphs`, compiled into `:pack-rendering`: a constant per glyph and per space, the lists and
  `named()` for `<glyph:name>`.
- `nordtal/hud/bossbar-advances.properties` and `nordtal/menu/gui-row-advances.properties`, the widths
  the plugins compose pills and rows with, derived from the written fonts by the client's rule. They
  ship in `:pack-rendering`'s jar, so a changed width reaches a server with the next plugin rollout;
  `BossBarAdvancesTest` and `MenuFontTest` derive them again independently.
- `manifest.json` and one PNG per named glyph, the glyph list of Steward's translation editor.

## glyphs.json

`fonts` lists one entry per font, in the order `Glyphs` follows. The `about` of a font, block, glyph or
list becomes the Java doc of its constant.

| level | key                          | meaning                                                                                                           |
| ----- | ---------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| font  | `font`                       | the font id, such as `nordtal:board`; with `{row}` in it, one font per row                                        |
| font  | `rows`, `pitch`              | how many row fonts `{row}` writes, from 0, and how many pixels each row sits below the one before it              |
| font  | `constant`                   | the Java constant of the font id, or of the list of row ids                                                       |
| font  | `numbering`                  | `"own"` lets the font's code points repeat another font's                                                         |
| font  | `spaces`                     | the `space` provider: each entry a `code`, its `advance` in pixels and an optional `constant`                     |
| font  | `blocks`, `lists`            | the code points in ascending order; Java lists (`constant`, `about`) a glyph joins with `list`                    |
| block | `range`, `title`             | `FE000-FE00F`, and the block's name in `Glyphs` and in every message about it                                     |
| block | `texture`                    | makes the block one sheet: its glyphs are the cells of one row, left to right                                     |
| block | `chars`                      | instead of `range` and `glyphs`: rows of ordinary characters, one per sheet row; `\u0000` is no cell              |
| block | `height`, `ascent`, `status` | the default of every glyph in the block; a sheet needs both height and ascent                                     |
| glyph | `code`, `constant`           | the code point in capital hex, and its constant in `Glyphs` (a glyph without one is reached by its list)          |
| glyph | `name`                       | its `<glyph:name>`, lowercase and hyphenated; every glyph of `minecraft:default` has one, no other does           |
| glyph | `texture`                    | `nordtal:<path>.png`, under `src/assets/nordtal/textures`; a sheet's cells take the block's                       |
| glyph | `height`, `ascent`           | the bitmap provider's, unless the block sets them                                                                 |
| glyph | `status`                     | `keep` is shipping art, `final candidate` is good enough to ship, `placeholder` is for testing; unset is unjudged |

The build checks every rule before it writes anything and names each broken one by its place, such as
`glyphs.json > nordtal:bossbar > Status icons`:

- Every code point lies in Supplementary Private Use Area A, `U+F0000` to `U+FFFFD` (a `space` may
  also be the ordinary space); the basic plane's private use area is where other glyph plugins assign.
- Blocks and their glyphs ascend, do not overlap and stay inside their block, and a font draws a code point once.
- Fonts allocate independently, and only `nordtal:bossbar` numbers on its own (its bar segments are
  named by their width in decimal); every other font's blocks never overlap another's, so a code point
  in a log names one thing.
- A code point declared as a space advances the same in every font that declares it.
- Constants are unique across the file, names unique among the glyphs, and no text holds `*/`.

## The fonts

`U+FE001` means something different in `minecraft:default` and in `nordtal:bossbar`, and a component
naming no font draws `minecraft:default`, so every component carrying a `nordtal:` glyph names its
font; `:architecture` lets only `BossBarLine` name a boss bar. Every code point is a surrogate pair
in UTF-16: `Glyphs` writes `cp(0xFE004)`, and no message bundle carries one (`TabListTest`); a glyph
reaches a bundle as a `{parameter}` or as `<glyph:name>`.

- **`nordtal:board`**: a row draws its left edge, walks right by the configured width
  (`boards[].width` in `smp`'s `config` group, 32 to 240 px), draws the right edge and walks back,
  since only the client knows how wide a text line is.
- **`nordtal:bossbar`**: its spaces are a `space` provider whose code point's low digits are the
  advance in decimal, negative ones included, so a line draws over itself. A HUD line is one rounded
  pill per piece of information. Never allocate an advance onto an assigned character.
- **`nordtal:gui`**: menu panels, drawn from the inventory title. A title carries a bitmap as large as
  the window on a high `ascent`, over `generic_54.png`. A title needs **-8** to reach the window's
  left edge and **-169** to walk back from the panel's 177 px advance; the positive spaces are the
  negative ones one bit higher, and every row font declares them too. A chest window is
  `114 + 18 × rows` pixels tall, hence six panels, and the drawable cell starts at **(7, 17)**.
  Each card state is a card-sized overlay glyph declared once per card row with the ascent that lands
  it there (`13 - y`); `MenuTitle.Canvas` composes it and `MenuTitleTest` checks every overlay.
- **`nordtal:gui_r0` to `gui_r5`**: six copies of one font, one per chest row, because a font's
  `ascent` is a glyph's only vertical control. Each row draws its bitmaps 18 pixels lower than the one
  above, with the same characters at three ascents centred in the 18 px cell:

| layer     | height | top        | ascent      | what                                       |
| --------- | ------ | ---------- | ----------- | ------------------------------------------ |
| furniture | 14     | `19 + 18r` | `-6 - 18r`  | a plate: pill, active frame, button        |
| icons     | 8      | `22 + 18r` | `-9 - 18r`  | an 8 × 8 pictogram, centred in the plate   |
| text      | 5      | `23 + 18r` | `-10 - 18r` | the five-pixel sheet, centred in the plate |

Text lands at **+4**; `MenuFontTest` checks each ascent against `SlotGeometry`.

`ui/gui/row_text.png` is the five-pixel sheet: an 8 × 7 grid of 5 × 5 cells holding `A` to `Z`,
`0` to `9`, `. , : / - + % ( ) ! ? ' "`, `Ä Ö Ü ß`, `∙` and `█` `░` for a text bar. A space is a
`space` provider at **+3**. It is all capitals: `:paper-common`'s `MenuFont` folds lower case onto
them (except `ß`) and maps unknown characters to `?`, since a missing-glyph box is six pixels wide.
`O`, `0` and `8` stay three pixels wide and are drawn apart, and `MenuFontTest` checks their distances:

| glyph | rows                  | what tells it apart                                |
| ----- | --------------------- | -------------------------------------------------- |
| `O`   | `.#. #.# #.# #.# .#.` | round, tapered top and bottom, like `C G Q`        |
| `0`   | `### #.# #.# #.# ###` | square, flat top and bottom, like `1 2 3 5 7`      |
| `8`   | `### #.# .#. #.# ###` | square and waisted: two loops joined in the middle |

## Vanilla overrides

| file                                                           | what it does                                                                      |
| -------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `minecraft/textures/gui/sprites/boss_bar/white_background.png` | makes the vanilla boss bar frame invisible, so the composed HUD is all that shows |
| `minecraft/textures/gui/sprites/boss_bar/white_progress.png`   | likewise, the progress fill                                                       |
| `minecraft/lang/en_us.json`                                    | `menu.returnToGame`, `menu.game` (the logo glyph) and `menu.disconnect`           |
| `minecraft/lang/de_de.json`                                    | the same three keys in German                                                     |

`de_de.json` is the one place the client's language decides what a player sees, limited to these
three pause-menu strings. A language file may name a glyph (`menu.game`), the one place besides
`glyphs.json` that writes a code point by hand; `ResourcePackTest` parses them against the written
`minecraft:default`.
