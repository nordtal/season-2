# designer

The Nordtal plugin of Nordtal Designer, the Aseprite build of `nordtal/aseprite`. The app's
bootstrap loads `init.lua` from the clone of this repository. The plugin requires version 5 of the
fork's Lua API (`app.forkApiVersion`) and shows an alert to update the app on an older one.

The Nordtal tab holds the tree of the resource pack, the embedded editor and the properties of the
selected entry, side by side; the properties stay on the side of the editor away from the tree. The
tree and the properties have widths of their own and the editor takes the rest, so a longer node of
the tree scrolls. The plugin runs `:resource-pack:describePack` with the clone's `gradlew` and
builds the tree from `build/pack-model.json`. A click on an entry opens its texture in the editor,
zoomed to fit, unless its PNG lies outside `resource-pack/src/` or its source outside
`resource-pack/art/`; then the editor stays empty and the tab says why. When the sprite leaves the
editor without the tab, such as with **File > Close**, the tree selects nothing, so a click opens
the entry again. Each save of that sprite writes its `.aseprite` source under `resource-pack/art/`
and exports its first frame as the PNG. **View > Nordtal Tree on the Right** moves the tree to the
right of the editor and the properties to its left.

The properties panel shows each value it edits in a sunken box. The mouse wheel scrolls
the panel. A click on a box asks for the new value in a form. OK runs the resource pack's task that writes it:
`renameGlyph` for a name, `editGlyph` for status, display height, ascent and about, `scaleTexture`
for the scaling of a GUI sprite and `deleteGlyph` for the button below. The tasks run one at a time.
The tab reads the pack again after them. A refused task shows its reason at the top of the panel,
with each place that still uses the glyph. A new pixel size resizes the sprite and saves it. A save
of a glyph whose pixel height changed runs `resizeGlyph`, which scales the display height while the
switch under the size couples them. The switch is on by default. The plugin's preferences keep
its state. A save of a vanilla override writes all its frames as a column of the PNG and runs
`animateTexture` with their durations. An override with an animation and no source yet opens with
one frame per frame it plays.

The interface strings are Aseprite string files in `strings/`, in English and German. The plugin
reads the one of Aseprite's language setting, and English for each key it lacks.

```bash
# Run the Lua tests with Lua 5.4.6 and the current busted and dkjson, as CI does.
cd designer && busted
```
