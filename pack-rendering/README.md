# pack-rendering

What is drawn from the resource pack: the glyph constants (`Glyphs`), the `<glyph:name>` tag, and
the boss bar and tab list layers under `hud`. Only the Minecraft plugins depend on this module.

`Glyphs` and the advance tables of the boss bar and the menu rows are written by `:resource-pack`'s
`generateGlyphs` from `resource-pack/glyphs.json` on every build and never committed. The class is
synced into this module's own build directory before it compiles, because that is where Checkstyle
and Error Prone leave generated sources alone. Which flag a language gets is `LanguageFlags`, since a
choice is logic rather than an allocation.

The test fixtures publish `FontFile` and `PackAdvances`, which read the pack's fonts the way the
client does.
