# pack-rendering

`pack-rendering` draws from the resource pack: the glyph constants in `Glyphs`, the `<glyph:name>`
tag, the boss bar and tab list layers in `hud`, the chest menu title in `menu` and the frame of a
Text Display board in `board`. Only the Minecraft plugins depend on it.

`:resource-pack`'s `generateGlyphs` writes `Glyphs` and the advance tables of the boss bar and the
menu rows from `resource-pack/glyphs.json` on every build. They are never committed. The build
copies `Glyphs` into this module's build directory before compiling, because Checkstyle and Error
Prone skip generated sources there.

`LanguageFlags` chooses the flag of a language.

The test fixtures publish `FontFile` and `PackAdvances`, which read the pack's fonts as the client
does.

## Glyph previews

`renderGlyphPreviews` draws one glyph in the places the plugins compose: the chest menu panel, the
HUD line in the boss bar and the board. It calls `MenuTitle`, `BossBarLine` and `BoardFrame`, so a
preview is what a player sees. Nordtal Designer runs it and hands each component to the bridge mod.

```bash
sh gradlew :pack-rendering:renderGlyphPreviews -Pfont='nordtal:gui_r{row}' -Pcode=FE100 -Prow=1
```

`-Pfont` and `-Pcode` name the glyph as `glyphs.json` does. The optional `-Prows` (3) is the panel's
row count, `-Prow` (0) the row of a row glyph, `-Px` (7) the window x of a row glyph or a surface,
`-Ptext` (`Nordtal`) the text beside the glyph and `-PboardWidth` (180) the board's content width.
The task prints the path of `build/glyph-previews.json` and fails without writing it when the glyph
does not fit. The file holds one object:

| field   | value                          |
| ------- | ------------------------------ |
| `font`  | the font as given              |
| `code`  | the code point in capital hex  |
| `menu`  | the chest menu title, or null  |
| `hud`   | the boss bar name, or null     |
| `board` | the text of the board, or null |

Each component is Minecraft text-component JSON as Adventure's Gson serializer writes it. A place
that the font never reaches is null:

| font                 | menu                                         | hud                  | board                     |
| -------------------- | -------------------------------------------- | -------------------- | ------------------------- |
| `nordtal:gui`        | the panel if 176 wide, else drawn on it at x | null                 | null                      |
| `nordtal:gui_r{row}` | drawn on its row at x                        | null                 | null                      |
| `nordtal:bossbar`    | null                                         | the icon of one pill | null                      |
| `nordtal:board`      | null                                         | null                 | the frame, with no lines  |
| `minecraft:default`  | in the title before the text                 | null                 | in a line before the text |
