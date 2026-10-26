# designer

The Nordtal plugin of Nordtal Designer, the Aseprite build of `nordtal/aseprite`. The app's
bootstrap loads `init.lua` from the clone of this repository. The plugin requires version 5 of the
fork's Lua API (`app.forkApiVersion`) and shows an alert to update the app on an older one.

The Nordtal tab holds the tree of the resource pack, the embedded editor and the properties of the
selected entry, side by side, below the Minecraft bar. The bar ends in the status line. The
properties stay on the side of the editor away from the tree. The tree and the properties have
widths of their own and the editor takes the rest, so a longer node of the tree scrolls. The plugin
runs `:resource-pack:describePack` with the clone's Gradle wrapper and builds the tree from
`build/pack-model.json`. Every task starts as `java -jar gradle/wrapper/gradle-wrapper.jar` on the
JDK of the bootstrap's `JAVA_HOME`, so no shell and no batch file reads an option. A task that
cannot start shows why in the status line. A click on an entry opens its texture in the editor,
zoomed to fit, unless its PNG lies outside `resource-pack/src/` or its source outside
`resource-pack/art/`. Then the editor stays empty and the tab says why. When the sprite leaves the
editor without the tab, such as with **File > Close**, the tree selects nothing, so a click opens
the entry again. Each save of that sprite writes its `.aseprite` source under `resource-pack/art/`
and its PNG: the first frame for a glyph or an item, every frame for a vanilla override. **View >
Nordtal Tree on the Right** moves the tree to the right of the editor and the properties to its
left.

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
of its PNG. A save passes the frame count the sprite was opened with as `from`, and the status line
shows each notice that `animateTexture` printed.

A click on an item runs `readItem`, and the tree shows the models of its definition below it: each
case, entry, tint and model that the definition nests, such as the fallback of a `select`. The panel
edits the item's own fields and the type, the property and the fields of the node's model, as the
task's `types` name them, with a button to add each case, entry, tint or optional model and one to
remove a node that its parent does not require. OK runs `editItem` for that one change, and the tab
reads the definition and the pack again. When `editItem` names required fields that the change
still lacks, a form asks for them and the change runs again with them. A field of the kind `json`
takes a text that is no JSON as a string. The panel lists the textures of a `minecraft:model`, and
the first one that the pack holds opens in the editor. Its save writes every frame and the
animation, as for an override. A texture that only the client jar holds shows its id. On a node that
draws one model or nothing, **Import Model...** runs `importBlockbench` for a Blockbench project of
the format Java Block/Item or for a model that Blockbench exported as JSON. The task writes the model
and its textures and makes the node draw it in one run. The model ID it proposes lies in the
namespace `nordtal`. An item that the pack overrides from the client jar is edited the same way.

**File > New Texture...** opens a dialog that asks for the kind first: glyph, glyph block, sheet, item,
vanilla item or vanilla override. It then shows the options of the kind's task in
`resource-pack/README.md`, with font and block to choose and namespace and path for the id. Create runs the task, which checks every
value. A refusal shows below the field it names, and the dialog stays open with what was typed. Once
the task succeeded, the tab reads the pack again and opens the new texture in the editor. A vanilla
override is chosen from the textures of the client jar, which `listVanillaAssets` lists once per
session together with its items, with a search for the words of its id. A search that hides the
chosen id clears the choice. A choice hides the refusal of Create. A vanilla item is chosen the same
way from the items that the pack does not override yet. Create runs `editItem` without a change,
which writes the item's definition from the client jar into the pack, and the item editor opens it.

The tree marks each draft of the pack model with the icon of a modified tab: a texture with its
entries, or the fields of a block or a font. A node with drafts inside it shows their count in
brackets as its badge, such as `(2)`, so a collapsed font or block shows them too. A draft outside
the tree, such as a language file, is listed only in the publish dialog. The panel of a draft has
**Discard Changes**, which runs `discardDraft` once confirmed. **File > Publish...** lists every
draft with a check, and a title that names the drafts chosen until it is edited. **Discard
Chosen...** discards the chosen drafts once confirmed. **Publish** writes the title to
`resource-pack/build/publish-message.txt` and runs `.githooks/commit-msg` on it through `git hook
run` with the bootstrap's git. A refusal of the hook shows in the dialog over at most six lines, and the
dialog stays open. The last line ends with `...` when the refusal is longer. An accepted title runs `publishDrafts` with the bootstrap's gh, and the status
line shows the address of the pull request. After a publish, failed or not, the tab reads the pack
again. Every task runs with the folder of the bootstrap's git first on `PATH`. A pack with drafts or
pull requests runs `syncReviews` after the tab's first read and every five minutes, and the tab reads
the pack again after a merge, a close, a put back of a pull that stopped halfway, a move of main or a
change of a pull request.

A draft that main changed too has a conflict. The tree marks it and each node above it with the
theme's warning box, and the publish dialog leaves it unchosen and refuses it. Its panel lists the
files and fields of the conflict and has **Resolve Conflict...**, which runs `readConflict` and shows
both versions of the texture side by side, the clone's on the left. The dialog asks which version of
the files to keep and which side of each field, the clone's chosen at first. OK runs
`resolveConflict`, and a texture that takes main's version opens again once the pack was read. The
status line then names the folder that holds the clone's version. A value that one side removed reads
as such.

A node in review shows the number of its pull request as its badge, such as `#42`, followed by
**failed** while a check fails. A glyph shows it in place of its code point. The panel shows the pull request's
review decision, its checks and its comments, and **Open on GitHub** opens it in the browser. Replies
are written on GitHub.

The Minecraft bar along the top of the tab installs the pack into a Minecraft instance of any
launcher. The bar shows only the status until `describeInstance` has answered once. The instance box
shows the folder name of the instance, and its tip shows the whole path. A click on it opens a
folder dialog and runs `chooseMinecraftInstance`, and the tab then reads the instance with
`describeInstance`. **Install Pack** appears once an instance is chosen and runs `installPack`. With
**Live sync**, which is on at first, each save of a texture runs it too. After an install, the bridge
mod reloads the pack, or the status line asks for F3+T. **Install Bridge Mod** appears for an
instance with Fabric and runs `installBridgeMod` with the bootstrap's gh. It reads **Update Bridge
Mod** once a jar of the bridge mod is in the instance. The tab connects to the bridge mod with the
port and token of the instance. The state of the bridge mod is one coloured word. A refused token
closes the connection, and the word becomes a button. A click on it reads the instance again and
connects again. The word reads **Connecting** while that read runs and becomes the button again
when the read fails. The tab also connects again when `describeInstance` reads another port or
token. A divider separates the status line from the state of the bridge mod. The bar shortens the
instance box from its start and the status line from its end, each down to a few characters, and
removes no item. When the items do not fit even then, **Install Pack** and the button of the bridge
mod show a short label. The status line takes the freed room. When the items still do not fit, the
status line shortens below its few characters and ends with "..." inside the bar. Moving the
pointer onto an item shows its long text in Aseprite's tip above the status bar. A status line that
the bar cuts shows whole in that tip when it is set.

The panel lists the previews of the selected entry. A glyph or a cell runs `renderGlyphPreviews`
first, with **Show Previews**, and then offers itself alone in each place the bridge mod shows text,
and the menu, HUD line or board that its font draws. An item offers a give of each of its `states`, an
override under `block` or `item` the block or item of its name. With Fabric, a click sends the
preview to the bridge mod. Without Fabric, each preview is a list of vanilla commands, and a click
copies one. A text of the panel wraps only at a space or after the last of a run of punctuation
marks. It never wraps at an underscore or at a full stop between two letters or digits, and it
splits a word only when the word is longer than the line. A command writes each character beyond
ASCII as an SNBT escape, `\U000F0000` for U+F0000, so the text shown is the text copied. A command
longer than the chat's 256 characters is shown with its length and is not copied.

The interface strings are Aseprite string files in `strings/`, in English and German. The plugin
reads the one of Aseprite's language setting, and English for each key it lacks.

```bash
# Run the Lua tests with Lua 5.4.6 and the current busted and dkjson, as CI does.
cd designer && busted
```
