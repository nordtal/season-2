# pack-rendering

`pack-rendering` draws from the resource pack: the glyph constants in `Glyphs`, the `<glyph:name>`
tag, and the boss bar and tab list layers in `hud`. Only the Minecraft plugins depend on it.

`:resource-pack`'s `generateGlyphs` writes `Glyphs` and the advance tables of the boss bar and the
menu rows from `resource-pack/glyphs.json` on every build. They are never committed. The build
copies `Glyphs` into this module's build directory before compiling, because Checkstyle and Error
Prone skip generated sources there.

`LanguageFlags` chooses the flag of a language.

The test fixtures publish `FontFile` and `PackAdvances`, which read the pack's fonts as the client
does.
