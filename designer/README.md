# designer

The Nordtal plugin of Nordtal Designer, the Aseprite build of `nordtal/aseprite`. The app's bootstrap
loads `init.lua` from the clone of this repository. The plugin requires version 4 of the fork's Lua API
(`app.forkApiVersion`) and shows an alert to update the app on an older one.

The Nordtal tab holds the tree of the resource pack, the embedded editor and the properties of the
selected entry, side by side; the properties stay on the side of the editor away from the tree. The plugin runs `:resource-pack:describePack` with the clone's `gradlew` and builds the
tree from `build/pack-model.json`. A click on an entry opens its texture in the editor, zoomed to fit, unless its PNG lies
outside `resource-pack/src/` or its source outside `resource-pack/art/`. When the sprite leaves the
editor without the tab, such as with **File > Close**, the tree selects nothing, so a click opens the
entry again. Each save of
that sprite writes its `.aseprite` source under `resource-pack/art/` and exports its first frame as the
PNG. **View > Nordtal Tree on the Right** moves the tree to the right of the editor and the properties to its left.

The interface strings are Aseprite string files in `strings/`, in English and German. The plugin reads
the one of Aseprite's language setting, and English for each key it lacks.

```bash
# Run the Lua tests with Lua 5.4.6 and the current busted and dkjson, as CI does.
cd designer && busted
```
