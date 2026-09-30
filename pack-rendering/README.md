# pack-rendering

What is drawn from the resource pack: the glyph constants (`Glyphs`, mirroring the table in
`resource-pack/README.md`), the `<glyph:name>` tag, and the boss bar and tab list layers under
`hud`. The boss bar advance table is derived from the assembled pack on every build and never
committed. Only the Minecraft plugins depend on this module.

The test fixtures publish `FontFile` and `PackAdvances`, which read the pack's fonts the way the
client does.
