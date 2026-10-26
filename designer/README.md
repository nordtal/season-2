# designer

The Nordtal plugin of Nordtal Designer, the Aseprite build of `nordtal/aseprite`. The app's
bootstrap loads `init.lua` from the clone of this repository. The plugin requires version 5 of the
fork's Lua API (`app.forkApiVersion`) and shows an alert to update the app on an older one.

The Nordtal tab holds the tree of the resource pack, the embedded editor and the properties of the
selected entry, side by side. The properties stay on the side of the editor away from the tree. The
tree and the properties have widths of their own and the editor takes the rest, so a longer node of
the tree scrolls. The plugin runs `:resource-pack:describePack` with the clone's Gradle wrapper and
builds the tree from `build/pack-model.json`. Every task starts as `java -jar
gradle/wrapper/gradle-wrapper.jar` on the JDK of the bootstrap's `JAVA_HOME`, so no shell and no
batch file reads an option. A task that cannot start shows why in the status line. A click on an
entry opens its texture in the editor, zoomed to fit, unless its PNG lies outside
`resource-pack/src/` or its source outside `resource-pack/art/`. Then the editor stays empty and the
tab says why. When the sprite leaves the editor without the tab, such as with **File > Close**, the
tree selects nothing, so a click opens the entry again. Each save of that sprite writes its
`.aseprite` source under `resource-pack/art/` and its PNG: the first frame for a glyph or an item,
every frame for a vanilla override. **View > Nordtal Tree on the Right** moves the tree to the right
of the editor and the properties to its left.

The properties panel shows each value it edits in a sunken box. The mouse wheel scrolls the panel. A
click on a box asks for the new value in a form. OK runs the resource pack's task that writes it:
`renameGlyph` for a name, `editGlyph` for status, display height, ascent and about, `scaleTexture`
for the scaling of a GUI sprite and `deleteGlyph` for the button below. The tasks run one at a time,
and a click while one runs says that a change is being written. The tab reads the pack again after
them. A refused task shows its reason at the top of the panel, with each place that still uses the
glyph.

A new pixel size resizes the sprite with nearest neighbour and saves it. A size that is no whole
multiple of the old one up asks first, since it loses or doubles rows and columns of pixels. A save
of a glyph whose pixel height changed runs `resizeGlyph`, which scales the display height while the
switch under the size couples them. The switch is on by default. The plugin's preferences keep its
state. A save of a vanilla override writes all its frames as a column of the PNG and runs
`animateTexture` with their durations. The status line shows the reason when a task that a save ran
is refused, and the model keeps the old size until such a task succeeds. A save while its task still
waits runs after it. An override with an animation and no source yet opens with one frame per frame
it plays.

**File > New Texture...** opens a dialog that asks for the kind first: glyph, glyph block, sheet, item
or vanilla override. It then shows the options of the kind's task in `resource-pack/README.md`, with
font and block to choose and namespace and path for the id. Create runs the task, which checks every
value. A refusal shows below the field it names, and the dialog stays open with what was typed. Once
the task succeeded, the tab reads the pack again and opens the new texture in the editor. A vanilla
override is chosen from the textures of the client jar, which `listVanillaTextures` lists once per
session, with a search for the words of its id. The tree marks a texture that differs from the last
commit, a draft, with the icon of a modified tab.

The interface strings are Aseprite string files in `strings/`, in English and German. The plugin
reads the one of Aseprite's language setting, and English for each key it lacks.

```bash
# Run the Lua tests with Lua 5.4.6 and the current busted and dkjson, as CI does.
cd designer && busted
```
