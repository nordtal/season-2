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
art/                  the .aseprite sources, each at the path of its PNG under src/
build/pack            the assembled pack: src/ plus the fonts the build writes
```

`art/assets/nordtal/textures/x.aseprite` is the source of `src/assets/nordtal/textures/x.png`. The
pack holds only the PNG, and `checkPack` refuses a source whose PNG is missing.

## Editing the art

The steps run from the Run menu of IntelliJ, folder **resource pack**, on any operating system, with
Minecraft 26.3.

1. Run **pack: 1. choose Minecraft instance** once and pick the folder of the instance. A folder that
   never ran is taken when its launcher marked it: `versions` or `.fabric` in it, or `mmc-pack.json`
   of Prism or MultiMC.
2. Edit an image under `src/assets/nordtal/textures` and save it in place.
3. Run **pack: 2. install into Minecraft**. It names every image it cannot use and copies the pack
   in as `nordtal-dev`. It enables the pack when the game is closed. Otherwise enable it once under
   Options > Resource Packs.
4. Press **F3+T** in the game.

`describeInstance` prints the chosen instance as one line of JSON: `instance`, `fabric`, `bridge` (the
name of the installed bridge mod), `problem` (a sentence while `mods/` holds more than one), `port` and
`token`. `installBridgeMod -Pgh=<path>` downloads the bridge mod of the newest release with that gh into
`mods/` of an instance with Fabric, deletes every other version of it there and writes its settings
once. It stops before the copy when an old version cannot be deleted.

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
**97** (Minecraft 26.2 and 26.3). Both files are attached to each GitHub release, for installing the
pack into a client by hand.

The pack the proxy offers is not that zip. steward-agent builds its own from the files its image
ships and smp's wheel settings: at its start when the image or the wheel changed, and 30 seconds after
an admin's last change of the wheel prizes or texts. Each build lands in `PACK_DIR` as `<sha1>.zip`,
which Caddy serves under `PACK_BASE_URL` (`https://PACK_HOST` by default). Only a good build sets the
proxy's `pack` settings, `url` and `sha1`; a failed one leaves the active pack, and Steward lists every
build. The proxy offers the pack while the player waits in `limbo`, and fails closed while either
setting is empty. Steward raises the pack alert when the URL stops answering.

The local stack works the same way: after a change under `src/`, `dev up` builds a new steward-agent
image, which builds the pack at its start. A wrong hash shows in the client as `FAILED_DOWNLOAD`.

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
- `nordtal/menu/item-sprites.properties`, written by `writeItemSprites` from `src/` and the client jar, ships in the jar
  of `:pack-rendering`. It names the atlas sprite of each item that a text draws before the item's name: the `layer0`,
  else the `particle`, of an item whose definition is one `minecraft:model` without tints, where that texture lies in an
  atlas. Every other item, such as a chest, a leaf or a compass, shows its name alone.
- The wooden wheel, written by `:pack-builder`: `generateWheel` writes its pack files for the default prizes, which
  `:smp` writes from `DefaultSmp`, and `writeWheelTimetable` writes `nordtal/wheel/wheel.properties`, which ships in
  the jar of `:pack-rendering`. The wheel draws a count for each amount above 1 on the list; a prize whose item vanilla
  lacks turns as a "?". `extractVanillaFacts` writes the few files of the client jar the wheel reads to
  `build/vanilla-facts/vanilla-facts.zip`, which the configuration `vanillaFacts` offers.

## The pack model

`./gradlew -q :resource-pack:describePack` writes `build/pack-model.json`, the tree and the properties
of Nordtal Designer. It reads `glyphs.json` through the same reader as `generateGlyphs`. Every path in
it is relative to this directory, and every code point is capital hex without `U+`.

```
statuses      the statuses a glyph or block can have
fonts[]       font, rows, constant, about, draft, review, blocks[]
  blocks[]    kind "block": title, range, status, about, height, ascent, draft, review, glyphs[]
              kind "sheet": title, range, status, about, height, ascent, draft, review, characters, texture, cells[]
  glyphs[]    code, constant, name, list, status, effectiveStatus, height, ascent, about, texture
  cells[]     row, column, code, height, ascent, and for a glyph cell constant, list, status, effectiveStatus, about
items[]       category (the namespace), entries[]: id, file, draft, review, model (the type of the root model), texture
overrides[]   category (the folder under textures/), entries[]: texture, mcmeta, animation, frames, scaling, guiSprite, block, item
textures[]    category (namespace or namespace:folder), entries[] as in overrides[]: each PNG outside minecraft that no font or item shows
texture       id, png, aseprite, asepriteExists, draft, review, width, height
languages     the languages of the pack's language files, such as en_us
texts[]       key, values (language to text), owner, draft, review
sounds[]      id, replace, subtitle, subtitles (language to text), draft, review, sounds[]
  sounds[]    name (with its namespace), file, and the fields of the entry: volume, pitch, weight, stream, ...
drafts[]      id, label, state, files, keys, sounds, review, conflict: files, fields[]: name, clone, main
reviews[]     number, url, decision, checks, comments[]: author, body, path, url
```

The `status` of a glyph or cell is its own value, null when it inherits the block's, and
`effectiveStatus` is the one that applies. The `height` and `ascent` of a cell are its block's. The
`texture` of an item is the `layer0` of a `minecraft:model` in the pack, or null, also when it is no
identifier.
`width` and `height` of a texture are its pixels, or null while its PNG is missing. `animation` and `scaling` are the `animation` and
`gui.scaling` objects of the `.png.mcmeta`, or null. `block` and `item` are the `minecraft:` id of the
PNG's name for an override under `block` or `item`, or null. `frames` lists each frame of the PNG once, as `x`, `y`,
`width`, `height` in the PNG and `duration` in milliseconds, the time of the first entry of the animation's `frames`
that plays it. It is null when the animation's frames do not divide the PNG. `guiSprite` is true for a texture under
`textures/gui/sprites/`.
`texts` lists every key of the language files under `src/assets/` once. Its `owner` is the id that
`drafts` gives the item or block the key names, or null for a text of its own. A key names the item
or block of the longest `item.<namespace>.<path>` or `block.<namespace>.<path>` that equals it or is
followed by a dot in it, where the pack holds that asset. A text's `draft` is the draft that holds
its key, and its `review` that of its owner. `keys` of a draft are the keys of the language files
that it holds.
`sounds` lists every event of the pack's `sounds.json` files. `file` of a sound is the pack's `.ogg`,
or null for a sound of Minecraft or an event. A subtitle's text is part of its event's draft, so it
is no text of its own. `sounds` of a draft are the events that it holds.
`draft` is the id of the draft that a texture, block, font or item is, as `drafts` names it, which the
next section describes, or null where it is none. An item without a texture is the draft of its file.
The file of an item with a texture is part of its texture's draft. `review` is the number of the pull request that holds the texture, block,
font or item, or null. `conflict` is null for a draft without a conflict with
main. `reviews` lists the pull requests that `publishDrafts` opened, as the last `syncReviews` read
them. `describePack` reads git on every run.

## New textures

Nordtal Designer creates each kind of texture with a task. Each writes a transparent PNG and the JSON
entries its kind needs, prints the absolute path of every file it wrote, one per line, and fails with
the reason when it refuses. The app writes the `.aseprite` source itself.

```bash
./gradlew -q :resource-pack:createGlyph -Pfont=minecraft:default "-Pblock=Player badges" \
  -Ptexture=nordtal:badges/moon.png -Pname=half-moon -Pwidth=7 -Pheight=7
./gradlew -q :resource-pack:createGlyphBlock -Pfont=nordtal:bossbar -Ptitle=Arrows -Pheight=8 -Pascent=-9
./gradlew -q :resource-pack:createGlyphSheet -Pfont=nordtal:bossbar -Ptitle=Arrows \
  -Ptexture=nordtal:hud/arrows.png -Pconstants=ARROW_LEFT,ARROW_RIGHT -Pwidth=6 -Pheight=5 -Pascent=-4
./gradlew -q :resource-pack:createItem -Pitem=nordtal:wand
./gradlew -q :resource-pack:createTexture -Ptexture=nordtal:gui/frame.png -Pwidth=24 -Pheight=12
```

| task               | options                                                                       | writes                                                                |
| ------------------ | ----------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| `createGlyph`      | `font`, `block`, `texture`, `width`, `height`; `name`, `constant`, `ascent`   | the glyph in `glyphs.json`, its PNG                                   |
| `createGlyphBlock` | `font`, `title`; `range`, `size` (16), `height`, `ascent`                     | an empty block in `glyphs.json`                                       |
| `createGlyphSheet` | `font`, `title`, `texture`, `constants`, `width`, `height`, `ascent`; `range` | the sheet and its cells in `glyphs.json`, its PNG of one row of cells |
| `createItem`       | `item`; `width`, `height` (16)                                                | `items/`, a `minecraft:item/generated` model and its PNG              |
| `createTexture`    | `texture`, `width`, `height`                                                  | a transparent PNG that nothing draws yet                              |

A vanilla override is created with `copyVanillaTexture`, under _Vanilla overrides_.

- `block` is a block's `title`. `width` and `height` are pixels, and `height` is also the display
  height. `ascent` falls back to the block's. `constant` falls back to the `name` in capitals.
- A new glyph takes the code point after the last glyph of its block. It is provisional: a publish
  assigns the final one with `assignCodePoints`.
- A new block or sheet takes the first run of `size` free code points, aligned to `size`, of the
  numbering its font shares, unless `range` names one.
- Every id is a resource location with an explicit namespace, as the client accepts it: `a-z`, `0-9`,
  `_`, `-` and `.`, and `/` in the path. A path segment is never empty, `.` or `..`, and a texture
  ends in `.png`.
- A task refuses a file that exists, and writes nothing until `glyphs.json` passes every rule below.
  It writes `glyphs.json` in the layout that oxfmt checks, with every untouched line as it was.
- A refusal first prints `refused` and the field it concerns on a line of its own, so the app shows it
  at that field: an option such as `font`, `block` or `width`, or `namespace` or `path` of the id.
  A rule of `glyphs.json` that the result breaks is `name` or `constant` for a glyph, `title` for a
  block and `constants` for a sheet.

## Changing glyphs

Four tasks change a glyph, which `glyph` names by its `name` or its `constant`. A fifth gives new
glyphs their final code points. Each writes nothing until `glyphs.json` passes every rule below, and
fails with the reason when it refuses.

```bash
./gradlew -q :resource-pack:renameGlyph -Pglyph=half-moon -Pto=crescent
./gradlew -q :resource-pack:editGlyph -Pglyph=crescent -Pstatus=keep -Pabout='The moon'
./gradlew -q :resource-pack:resizeGlyph -Pglyph=crescent -Pfrom=7
./gradlew -q :resource-pack:deleteGlyph -Pglyph=crescent
./gradlew -q :resource-pack:assignCodePoints
```

| task               | options                                        | prints                                                        |
| ------------------ | ---------------------------------------------- | ------------------------------------------------------------- |
| `renameGlyph`      | `glyph`, `to`                                  | the absolute path of `glyphs.json`                            |
| `editGlyph`        | `glyph`; `status`, `height`, `ascent`, `about` | the absolute path of `glyphs.json`                            |
| `resizeGlyph`      | `glyph`, `from`; `coupled` (true)              | the absolute path of `glyphs.json`, or nothing when decoupled |
| `deleteGlyph`      | `glyph`                                        | the absolute paths of `glyphs.json` and each file it removed  |
| `assignCodePoints` | `main` (`origin/main`)                         | one line `font old new constant` per glyph that moved         |

- A rename sets the `name` to `to` and the `constant` to the name in capitals. A glyph without a name,
  outside `minecraft:default`, takes `to` as its constant.
- An edit sets each option it is given. An empty `status` or `about` removes it, and a `height` or
  `ascent` equal to the block's leaves the glyph without its own. A cell takes its `height` and `ascent`
  from its sheet and refuses both.
- A rename and a delete refuse while a file that git tracks or would add uses the glyph, and the
  refusal lists each place as `path:line`. A use is `Glyphs.CONSTANT` in Java or Kotlin, the bare
  `CONSTANT` in a file that imports it or `Glyphs.*`, or a `<glyph:name>` tag. A tag that code builds
  at runtime is not found.
- A delete removes the entry, and its PNG under `src/` and its `.aseprite` under `art/` when no other
  glyph, sheet or item model draws them, all or nothing. A cell of a sheet loses only its entry, and
  only the last cell can go, since a sheet's cells are the columns of its texture.
- A resize reads the new pixel height from the PNG. `from` is the pixel height before. Coupled, the
  `height` and `ascent` scale by the new height over `from`; decoupled, both stay, so the glyph shows
  more detail at the same size. A cell takes its size from its sheet and is refused.
- `assignCodePoints` reads `glyphs.json` at the ref `main` without fetching it, so the publish fetches
  first. A block is main's when range and title match, or when the clone's last commit has a block of
  that range too, so a renamed block keeps its range. A glyph that main lacks keeps a code point that
  main has not taken, and otherwise moves behind the last glyph of its block on both sides. A cell of a
  sheet is its code point, so a renamed cell keeps its place. A new block that overlaps a block of main
  moves to the first free run with its glyphs.

## Changing fonts, blocks and sheets

Six tasks create, change and delete a font, and change and delete a block or sheet, which `block`
names by its `title` in its `font`. Each writes nothing until `glyphs.json` passes every rule below,
and fails with the reason when it refuses.

```bash
./gradlew -q :resource-pack:createFont -Pfont=nordtal:signs -Pconstant=FONT_SIGNS -Pabout='Shop signs'
./gradlew -q :resource-pack:editFont -Pfont=nordtal:signs -Pconstant=FONT_SHOP_SIGNS
./gradlew -q :resource-pack:editGlyphBlock -Pfont=nordtal:bossbar -Pblock=Arrows -Ptitle='Bearing arrows' -Pstatus=keep
./gradlew -q :resource-pack:addSheetCells -Pfont=nordtal:bossbar -Pblock=Arrows -Pconstants=ARROW_UP,ARROW_DOWN
./gradlew -q :resource-pack:deleteGlyphBlock -Pfont=nordtal:bossbar -Pblock=Arrows
./gradlew -q :resource-pack:deleteFont -Pfont=nordtal:signs
```

| task               | options                                                         | prints                                                       |
| ------------------ | --------------------------------------------------------------- | ------------------------------------------------------------ |
| `createFont`       | `font`, `constant`; `about`                                     | the absolute path of `glyphs.json`                           |
| `editFont`         | `font`; `constant`, `about`                                     | the absolute path of `glyphs.json`                           |
| `editGlyphBlock`   | `font`, `block`; `title`, `status`, `height`, `ascent`, `about` | the absolute path of `glyphs.json`                           |
| `addSheetCells`    | `font`, `block`, `constants`                                    | the absolute paths of `glyphs.json` and the sheet's PNG      |
| `deleteGlyphBlock` | `font`, `block`                                                 | the absolute paths of `glyphs.json` and each file it removed |
| `deleteFont`       | `font`                                                          | the absolute paths of `glyphs.json` and each file it removed |

- A new font comes after every other font, without blocks, and shares the numbering of every font
  without `numbering`. Its id has a namespace other than `minecraft`, and it refuses at `namespace`, `path` or `constant` as the
  tasks under _New textures_ do. `rows`, `pitch`, `numbering`, `spaces` and `lists` of a font are
  changed in `glyphs.json` only.
- An edit sets each option it is given, and an empty `status` or `about` removes it. A font's new
  constant is refused while code uses the old one, and `minecraft:default` has none. A glyph without
  its own `height` or `ascent` takes the block's new one.
- New cells follow the last cell of the sheet, one per constant, and the PNG grows by one cell width per
  new cell on the right, in transparent columns. Nordtal Designer adds cells only to the sheet open in its
  editor, and widens its `.aseprite` source too.
- A delete removes the block or font with every glyph, and the PNG under `src/` and the `.aseprite` under
  `art/` of each texture that no other glyph, sheet or item model draws. A list that loses its last
  glyph goes too. It refuses while code uses a constant, a name or such a list, as a glyph's delete
  does, and a font's delete also while code uses its constant or holds its id in quotes, outside
  `glyphs.json` and Markdown. `minecraft:default` and a sheet of ordinary characters stay, since code
  writes them as plain text.

## Deleting textures and items

Two tasks delete what no glyph names: a texture, such as a vanilla override, and an item. Each deletes
all or nothing, and fails with the reason when it refuses.

```bash
./gradlew -q :resource-pack:deleteTexture -Ptexture=minecraft:block/stone.png
./gradlew -q :resource-pack:deleteItem -Pitem=nordtal:wand
```

| task            | options   | prints                                    |
| --------------- | --------- | ----------------------------------------- |
| `deleteTexture` | `texture` | the absolute path of each file it removed |
| `deleteItem`    | `item`    | the absolute path of each file it removed |

- A texture goes with its `.png.mcmeta` and its `.aseprite` under `art/`. It is refused while
  `glyphs.json` or a JSON file under `src/assets/` names it, and the refusal lists each place as
  `path:line`. An override of a texture of the client jar is never refused, since the jar's shows again.
- An item goes with each model that it draws and each parent of those in the pack that nothing else
  draws: no other item, no block state and no model that stays. A texture of those models goes with its
  sources when nothing else names it. Files in `minecraft` stay, since the client jar may draw them,
  and so do the item's texts.
- An item outside `minecraft` is refused while a file that git tracks or would add, outside
  `src/assets/`, names it, and the refusal lists each place as `path:line`. A use is its id as a word,
  or `Key.key` or `new NamespacedKey` with its namespace and path. A document or a test is no use.
- A deleted item is one draft with each model and texture that went with it: the draft of its texture when that
  went too, else of its own file.
- A texture or an item that the pack lacks is refused at `texture` or `item`.

## Texts

`editText` writes one key of the language files under `src/assets/`. A file keeps its other keys,
and a file that ends up empty is deleted only when the last commit lacks it too.

```bash
./gradlew -q :resource-pack:editText -Pkey=menu.title -Planguage=de_de -Pvalue=Pause
./gradlew -q :resource-pack:editText -Pkey=menu.title -Pdelete=true
```

| task       | options                                     | prints                                  |
| ---------- | ------------------------------------------- | --------------------------------------- |
| `editText` | `key`; `language`, `value`, `delete` (true) | the absolute path of each file it wrote |

- A key is words of letters, digits, `_` and `-` with dots between them. An edit sets the key in the
  file of `language` that holds it, or in `src/assets/minecraft/lang/<language>.json` when none does.
  An empty `value` sets the key to an empty text.
- `-Pdelete=true` removes the key from every language file, and refuses a key that none holds.

## Sounds

A sound event is an entry of a namespace's `src/assets/<namespace>/sounds.json`, and its own sounds are
Ogg Vorbis files under that namespace's `sounds/`. The tasks below write both, and each prints the
absolute path of every file it wrote or deleted.

```bash
./gradlew -q :resource-pack:createSound -Psound=nordtal:ui.click -Pfile=/path/to/click.ogg
./gradlew -q :resource-pack:overrideSound -Psound=minecraft:block.anvil.land -Preplace=false -Pfile=/path/to/clang.ogg
./gradlew -q :resource-pack:editSound -Psound=nordtal:ui.click -Pat=0 -Pfields='{"volume":0.5,"pitch":null}'
./gradlew -q :resource-pack:subtitleSound -Psound=nordtal:ui.click -Planguage=en_us -Pvalue=Click
./gradlew -q :resource-pack:playSound -Psound=nordtal:ui.click -Pat=0
```

| task            | options                                                                                  | prints                        |
| --------------- | ---------------------------------------------------------------------------------------- | ----------------------------- |
| `createSound`   | `sound`, `file`                                                                          | each file it wrote            |
| `overrideSound` | `sound`; `replace` (true by default), `file`                                             | each file it wrote            |
| `editSound`     | `sound`; one of `file`, `name`, `remove` (true) with `at`, `fields` with `at`, `replace` | each file it wrote or deleted |
| `subtitleSound` | `sound`; `language`, `value`, `delete` (true)                                            | each file it wrote or deleted |
| `deleteSound`   | `sound`                                                                                  | each file it wrote or deleted |
| `playSound`     | `sound`; `at`                                                                            | the WAV it wrote              |

- Only Ogg Vorbis files are taken: Minecraft plays no other format, and Audacity exports one with
  _Export Audio_, format _Ogg Vorbis_. `checkPack` flags an `.ogg` under `src/assets/` that is none.
- `createSound` refuses the namespace `minecraft`, an event the pack has, and an event that is not
  words of `a` to `z`, `0` to `9`, `_` and `-` with dots between them. It copies the file to
  `sounds/<event, dots as slashes>.ogg`.
- `overrideSound` overrides a `minecraft:` event of the season's client. With `replace`, the event
  plays the pack's sounds alone: it starts with vanilla's subtitle and a copy of each of vanilla's
  `.ogg` files, at `sounds/nordtal/<vanilla's path>.ogg`, or with the `file` alone where one is given.
  A sound it cannot download refuses the override, which then writes nothing. Without `replace`, the
  `file` plays beside vanilla's sounds. Its own files go under `sounds/nordtal/` of `minecraft`, so
  they never take the place of a vanilla file that other events play.
- `editSound` changes one thing: `file` adds an Ogg Vorbis file as a sound, `name` adds a sound
  Minecraft or the pack holds, `remove` removes the sound `at` and its file where no other event names
  it, `fields`, a JSON object, sets the fields of the sound `at`, and a null clears one. `replace` is
  for `minecraft:` events only. `volume` and `pitch` are numbers above 0, `weight` and
  `attenuation_distance` whole numbers from 1, `stream` and `preload` booleans, and `type` is `file`
  or `event`. The client caps the volume at 1 and holds the pitch between 0.5 and 2.
- A subtitle is a key of the language files, and its text belongs to the event's draft. The key is
  `subtitles.<namespace>.<event>` unless the event names one that the pack's language files hold, so
  a text never changes a vanilla key that other events share. The client shows vanilla's subtitle
  for a `minecraft:` event that keeps vanilla's sounds, so `subtitleSound` refuses one, and
  `-Preplace=false` drops the event's subtitle and each copy of vanilla's sounds that still equals
  vanilla's file.
- `deleteSound` deletes the event, each file only it names and its subtitle text where no other
  event names that.
- `playSound` decodes the sound `at`, or the first, to `build/sounds/` as a WAV at its volume and
  pitch, for the operating system's own player. It takes the pack's event, else vanilla's, and the
  pack's file, else vanilla's.

## Drafts and publishing

A draft is one change of the clone that is in no pull request yet. A texture's draft holds its PNG,
its `.png.mcmeta`, its `.aseprite` source, the item models and definitions that draw it, and its
entries in `glyphs.json`. The fields of a font or a block in `glyphs.json` are a draft of their own,
and so is every other changed file. A text of a language file is part of its owner's draft, and a
text of its own is a draft of its own, so a draft holds keys rather than whole language files. A
draft's `id` is its texture's id, `font <font>`, `block <font> <range or title>`, `text <key>`, `sound <event>` or
the file's path. Its `state` is `added`, `changed` or `deleted`, and `review`
is the number of the pull request that holds an older version of it. A draft that equals its version
in a pull request or on `origin/main` is not listed.

A draft has a conflict when `origin/main` changed it too since its base. The base is the commit
where the clone meets `origin/main`, or the commit that `resolveConflict` recorded. A file conflicts
when both sides changed it. An entry in `glyphs.json` merges field by field: a field that one side
changed takes that side's value, and a field that both changed each their own way conflicts. An
entry that one side deleted and the other changed makes `glyphs.json` a file of the conflict.

```bash
./gradlew -q :resource-pack:discardDraft -Pdraft=nordtal:badges/star.png
./gradlew -q :resource-pack:readConflict -Pdraft=nordtal:badges/star.png
./gradlew -q :resource-pack:resolveConflict -Pdraft=nordtal:badges/star.png -Pkeep=main -Pfields='{"about":"clone"}'
./gradlew -q :resource-pack:publishDrafts -Pdrafts=nordtal:badges/star.png -Ptitle='feat(resource-pack): the star is white' -Pgh=gh
./gradlew -q :resource-pack:syncReviews -Pgh=gh
```

| task              | options                                | prints                                                                                              |
| ----------------- | -------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `discardDraft`    | `draft`                                | the absolute path of each file it wrote or deleted                                                  |
| `readConflict`    | `draft`                                | the absolute path of main's version of each file of the conflict                                    |
| `resolveConflict` | `draft`; `keep`, `fields`              | the absolute path of each file it wrote or deleted                                                  |
| `publishDrafts`   | `drafts` (a comma list), `title`; `gh` | the address of the pull request                                                                     |
| `syncReviews`     | `gh`                                   | `merged`, `closed`, `reviewed` or `unanswered` and the number per pull request, `moved`, `restored` |

- `discardDraft` restores the draft's files and entries to the last commit, or to its pull request
  when one holds it, and forgets the draft's base. A publish makes the commit of `origin/main` it
  started from the base of each draft it publishes, so a resolved conflict stays resolved.
- `readConflict` writes main's version of each file of the conflict under `build/conflict/`, at the
  file's path in the pack, after it removed what an earlier read left there.
- `resolveConflict` ends a conflict. `keep` is `clone` or `main`, the version of every file of the
  draft, and it is required when files conflict. `fields` is a JSON object that names `clone` or
  `main` for each conflicting field. The entry takes the merged fields, and `origin/main` becomes the
  draft's base, in `nordtal-designer/bases.json` of the clone's git directory. With `main`, the
  clone's version of each file goes to `build/conflict/yours/` first, where it stays until `:resource-pack:clean` removes it. A glyph of a texture is the
  same glyph when main moved its code point, so the code point is one of its fields.
- `publishDrafts` fetches `origin`, gives new glyphs their final code points as `assignCodePoints`
  does against `origin/main`, and copies only the chosen drafts into a work tree of `origin/main`. A
  chosen draft with a conflict is refused. Each entry keeps the fields that only main changed.
  `:resource-pack:check` runs there, so a draft that is not chosen cannot block the publish, and a
  chosen draft that needs one that is not chosen fails. The work tree commits them with the message
  `title`. The branch is `designer/` and the title's description
  in lowercase words. The commit runs `.githooks/commit-msg`, carries the name and address of the
  account that `gh` is signed in to, and is pushed to `origin`. `gh pr create` opens the pull request
  against `main`, with one line per draft as its body; when it fails, the publish deletes the pushed
  branch again. A failed publish leaves the code points it moved in the clone. A draft that a pull
  request holds already gets a further commit on that pull request's branch. Drafts of two pull
  requests are refused together.
- `syncReviews` fetches `origin` and prints `moved` when `origin/main` moved. A failed fetch fails the
  sync and says that `origin` could not be fetched. It asks `gh` for the
  state, the review decision, the checks and the reviews of every pull request that `publishDrafts`
  opened, and for its line comments, and prints `reviewed` when one of them changed, or `unanswered`
  when `gh` failed for it, which keeps the pull request for the next sync. After a merge
  it fast-forwards the clone to `origin/main` and puts every other change back on top: a file takes
  the clone's version, and `glyphs.json` merges the clone's entries with main's field by field. A
  draft with a conflict keeps its base, so the conflict stays. A change of the
  merged pull request that equals the commit pushed last takes main's version, so a commit a reviewer
  added before the merge stays. A pull request closed without a merge makes its changes drafts again.
- The pull keeps the clone's changes in a stash named `nordtal-designer-pull`. A pull that fails puts
  them back, and one that stopped halfway is put back by the next `syncReviews`, which prints
  `restored`. Edits made since go back on top. Edits that clash stay in the stash
  `nordtal-designer-edits` beside the pull's, and every sync is refused until both are applied by
  hand.
- The clone's git directory holds the record of the open pull requests, as
  `nordtal-designer/reviews.json`, with the commit pushed last for each and what the last sync read.
  `checks` is `failure`, `pending`, `success` or null. A comment's `body` is its text without markup
  and folded details, cut to 280 characters.

## Texture metadata

Two tasks write the `.png.mcmeta` of a texture, which `texture` names by its id, such as
`minecraft:block/lava_still.png`. Each keeps every other member of the file, writes it with four spaces
of indent, and removes a file left empty.

```bash
./gradlew -q :resource-pack:animateTexture -Ptexture=minecraft:block/lava_still.png -Pframes=100,100,250
./gradlew -q :resource-pack:scaleTexture -Ptexture=minecraft:gui/sprites/widget/button.png -Pscaling=nine_slice -Pwidth=200 -Pheight=20 -Pborder=3
```

| task             | options                                                                   | prints                                     |
| ---------------- | ------------------------------------------------------------------------- | ------------------------------------------ |
| `animateTexture` | `texture`, `frames`; `from`                                               | its notices, the absolute path of the file |
| `scaleTexture`   | `texture`, `scaling`; `width`, `height`, `border`, `stretchInner` (false) | the absolute path of the file              |

- `frames` is the duration of each frame in milliseconds, and the PNG is a column of the frames from the
  top. The `animation` gets one `frametime` in ticks of 50 milliseconds when every frame lasts as long,
  and otherwise `frames` with the `time` of each; a frame shorter than a tick lasts one. A frame that is
  not square sets `height`. `interpolate` stays, and one frame removes the animation.
- `width` stays while it equals the PNG's width, and the task prints `dropped width` when it removes it.
- `from` is the frame count of the PNG before the change. The order in the animation's `frames` stays while
  `from` equals the new count, and each entry that plays a frame with a changed duration gets the new time.
  Without `from` or with another count, the task removes the order and prints `changed frames`. An order that
  plays a frame the PNG lacks is removed with `dropped frames`.
- A reorder of the frames that keeps their count is not detected: the kept order plays the frames at their new indices.
- The task prints `unplayed` and the index of each frame whose new duration no entry of a kept order plays.
- `scaling` is `stretch`, `tile` or `nine_slice`, and only a texture under `textures/gui/sprites/` takes
  one. `stretch` removes `gui.scaling`. `tile` and `nine_slice` need `width` and `height`, and
  `nine_slice` a `border` of one number for every side or four for left, top, right and bottom. As in
  Minecraft, left and right together stay below `width`, and top and bottom below `height`.

## Item definitions

Six tasks read, write and edit item definitions under `assets/<namespace>/items/`, for Nordtal items
and overrides of vanilla items, edit the texture keys of a model, and take a model to Blockbench and
back into one of them. Each
reads the client jar of the season's Minecraft version, which _Vanilla overrides_ describes, for what the pack
does not hold, and opens it only then. Paths are absolute. A written file has four spaces of indent
and one member per line, like the pack's other files.

```bash
./gradlew -q :resource-pack:readItem -Pitem=minecraft:compass
./gradlew -q :resource-pack:writeItem -Pitem=nordtal:lamp -Pdefinition=/tmp/lamp.json
./gradlew -q :resource-pack:editItem -Pitem=nordtal:lamp -Pat=model -Padd=cases '-Pfields={"when":"gui"}'
./gradlew -q :resource-pack:importBlockbench -Pmodel=/tmp/lamp.bbmodel -Pid=nordtal:item/lamp -Pitem=nordtal:lamp -Pat=model
./gradlew -q :resource-pack:editModel -Pmodel=nordtal:item/station_rail -Pkey=bar -Ptexture=nordtal:block/station_rail_bar
./gradlew -q :resource-pack:exportBlockbench -Pmodel=nordtal:item/station_rail
```

| task               | options                                                                           | prints                                                                                              |
| ------------------ | --------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `readItem`         | `item`                                                                            | one line of JSON: the definition, where it comes from, its problems, `types`, `models` and `states` |
| `writeItem`        | `item`, `definition`                                                              | the absolute path of the definition it wrote                                                        |
| `editItem`         | `item`, `at`, `fields`, `add`, `remove`                                           | the absolute path of the definition it wrote                                                        |
| `importBlockbench` | `model`, `id`, `item`, `at`                                                       | the absolute path of the model, of each texture and of the definition it wrote, and of each removal |
| `editModel`        | `model`, `key`; `texture`, `to`, `delete` (true), `add` (true), `width`, `height` | the absolute path of the model and of a texture it wrote                                            |
| `exportBlockbench` | `model`; `to`                                                                     | the absolute path of the project it wrote                                                           |

The line of `readItem` holds `item`, `source` (`pack`, or `vanilla` for a definition only the jar
has), `file` (the path in the pack, or null), `definition`, `problems` (a list of strings, empty when
the definition passes) and `types`, every model type, property, tint source and special model that
the rules below know, with the fields of each, for an editor to offer. A field is `name`, `kind` and
`required`. Its kind is a value (`id`, `text`, `number`, `boolean`, `json`), a nested object
(`model`, `special`, `property`) or a list (`models`, `cases`, `entries`, `tints`). `types.kinds` maps
each value kind to the JSON type it holds, each list kind to the kind of its elements and each typed
kind to its table of types. `models` lists each model that the definition draws with its textures and
their parents' textures, and `pack`, true when the pack holds the model's file. A texture's `own` is
true when the model sets the key itself, false when a parent sets it. A texture whose PNG the pack holds has the fields of an override in the pack
model (`texture`, `mcmeta`, `animation`, `frames`), any other only its `id`. A texture is a draft
when its id is one of the pack model's `drafts`.
`states` lists the states a `/give` shows, each as `label` and `give`, the item with its components.
A state sets the custom model data, the damage, a component, a block state, a trim or the charged
projectiles for each case of a select, condition or range of the definition. A state of `damaged`,
`broken` or a range of `damage` also sets `max_stack_size` to 1. A condition on `has_component` has a
state of its true case only for `damage`, `charged_projectiles`, `dyed_color` and `lodestone_tracker`.
Its false case removes the component with `!component`, unless `ignore_default` is true. The plain
item comes last, except under a select or range without a fallback and under such a removal. A list
holds at most 64 states.

`editItem` changes the object at `at`, a path of keys and list indices such as `model/cases/0`.
`fields` is a JSON object whose members it sets, and a member that is null is removed. With `add`, it
appends a new object with those fields to the list of that name instead, or sets it as the model of
that name, such as `fallback`, which must be absent. `-Premove=true` removes the object at `at`. A new
type or property drops every field that it does not allow or gives another kind. A model, a list or a
property that the object requires and lacks starts as `minecraft:empty`, an empty list or the first
property of its type. A value of another kind is refused with `refused` and the field's name. When a
required value is still missing, such as `block_state_property` after a new property, it prints
`needs`, the field and its kind on a line for each, and fails. The result is checked and written like
`writeItem`'s, and a vanilla item that the pack lacks is read from the jar and written as an override.
Without `fields`, `add` and `remove`, it writes the definition unchanged, which starts such an override.

- The rules follow the codecs of the client jar: every model type (`empty`, `model`, `composite`,
  `condition`, `select`, `range_dispatch`, `special` and `bundle/selected_item`), each property of
  `condition`, `select` and `range_dispatch`, each tint source and each special model, with the
  fields each requires and allows. A field that none of them knows is refused, except one whose name
  starts with `_`, such as `_comment`.
- A definition is written only when it passes, and when each model it draws, each parent of those
  and each of their textures is in the pack or the jar. A texture may also be a sprite that an
  atlas generates, such as a paletted permutation of an armor trim.
- `checkPack` holds every definition in the pack to the same rules and references, without the jar:
  a reference into `minecraft` passes there, and `writeItem` is what checks it against the jar.
- An import reads a `.bbmodel` project of the format Java Block/Item, with its textures embedded,
  and converts it as Blockbench's export would: cubes with their rotation, faces, textures and
  `display`. An element that is not exported is left out, and its light is kept within 0 to 15. A
  mesh, an element outside -16 to 32, a face without UV and a texture whose name no texture path can
  hold are refused, as are two textures that would be the same file. It also reads a model that
  Blockbench exported as JSON, whose textures lie beside it as PNG files.
- An import writes the model at `id` under `models/` and sets the object at `at` of `item` to a
  `minecraft:model` that draws it. Each texture goes, with its `.mcmeta`, into the folder of the model
  under `textures/` under its name in lower case, and its reference is rewritten to the namespace of
  `id`. A reference that the pack or the jar resolves, such as `block/stone`, stays. A file that the
  pack or the jar holds already is refused, so an import never hides a vanilla model or texture.
- An import onto the object at `at` that draws the model `id` already writes that model again. A
  texture the project holds with the same pixels stays the id it came from, in the pack or the jar.
  A texture of the model's own, one in the pack outside `minecraft` that nothing else of the pack
  names, is written over in place when it changed, its `.mcmeta` stays, and its source under `art/`
  goes, since it holds the old pixels. One that the project no longer names goes with its `.mcmeta`
  and source. Any other changed texture is a new file, as above. The model keeps the layout of its
  file: keys in their order, each object on the lines it had and each number as it was spelled, so
  only the lines of what changed differ.
- A model of the pack that the item drew at `at` before goes when nothing of the pack draws it any
  more, neither an item, a model's `parent` nor a block state. Each of its textures goes with it,
  with its `.mcmeta` and its source under `art/`, when no other JSON of the pack and no glyph of
  `glyphs.json` names it. What something else uses stays, and so does every file in `minecraft`,
  which the client jar may draw. The new model, its textures, the definition and these removals
  are written all or none.
- `editModel` changes the texture `key` of the pack's model `model`: `texture` sets it to a texture
  id, where `block/stone` reads as `minecraft:block/stone`, or to `#other`, another key of the model.
  `to` renames it in each face and key that draws it, and `-Pdelete=true` removes it. With
  `-Padd=true` a key the model or a parent sets already is refused. A key is
  letters, digits and `_`. A texture outside `minecraft` that neither the pack nor the jar holds is
  written as a transparent PNG of `width` by `height`, 16 by 16 by default, with the model in one run.
  A model only the jar holds, a key that a parent sets for a rename or a removal, a key that a face
  still draws for a removal, two keys that name each other and a missing texture in `minecraft` are
  refused with `refused` and the field's name.
- `exportBlockbench` writes the model `model` of the pack or the jar to `to`, by default
  `build/blockbench/<name>.bbmodel`, as a project of the format Java Block/Item. It holds the
  elements of the model or of its nearest parent that has some, and each texture that a key resolves
  to, embedded once with its folder and namespace, so that `importBlockbench` reads it back as the
  same model. A model without elements, a texture without a PNG, two textures of one file name and a
  face whose key names no texture are refused.
- Every item definition of the client jar passes these rules and is written back unchanged, keys in
  their order, which a test checks against the jar. Without the jar in the cache and without the
  network, that test is skipped.

## glyphs.json

`fonts` lists one entry per font, in the order that `Glyphs` follows. The `about` of a font, block,
glyph or list becomes the doc comment of its constant.

| level | key                          | meaning                                                                                                       |
| ----- | ---------------------------- | ------------------------------------------------------------------------------------------------------------- |
| font  | `font`                       | the font id, such as `nordtal:board`; with `{row}` in it, one font per row                                    |
| font  | `rows`, `pitch`              | the number of row fonts that `{row}` writes, counted from 0, and the pixels between one row and the next      |
| font  | `shifts`                     | with `{shift}` in the font id, one font per number, each drawing every bitmap that many pixels lower          |
| font  | `letters`                    | adds vanilla's letter sheets from `include/default.json`, lowered with the font, after the spaces             |
| font  | `silent`                     | maps every vanilla letter to an empty advance of 0, so a text in the font takes no room and draws nothing     |
| font  | `references`                 | font ids whose providers come first, written as `reference` providers                                         |
| font  | `constant`                   | the Java constant of the font id, or of the list of row ids                                                   |
| font  | `numbering`                  | `"own"` lets the code points of the font repeat those of another font                                         |
| font  | `spaces`                     | the `space` provider: each entry has a `code`, an `advance` in pixels and an optional `constant`              |
| font  | `blocks`, `lists`            | the code points in ascending order; Java lists (`constant`, `about`) that a glyph joins with `list`           |
| block | `range`, `title`             | a range such as `FE000-FE00F`, and the name of the block in `Glyphs` and in every message about it            |
| block | `texture`                    | makes the block one sheet whose glyphs are the cells of one row, left to right                                |
| block | `chars`                      | replaces `range` and `glyphs`: rows of ordinary characters, one per sheet row; `\u0000` is no cell            |
| block | `height`, `ascent`, `status` | the default of every glyph in the block; a sheet needs both `height` and `ascent`                             |
| glyph | `code`, `constant`           | the code point in capital hex and its constant in `Glyphs`; a glyph without a constant is reached by its list |
| glyph | `name`                       | its `<glyph:name>`, lowercase and hyphenated; every glyph of `minecraft:default` has one and no other does    |
| glyph | `texture`                    | `nordtal:<path>.png` under `src/assets/nordtal/textures`; the cells of a sheet take the texture of the block  |
| glyph | `height`, `ascent`           | the values of the bitmap provider, unless the block sets them                                                 |
| glyph | `status`                     | `keep` is shipping art, `final candidate` can ship, `placeholder` is for testing; unset is unjudged           |

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
- A font id and a texture are resource locations by the rule under _New textures_, and a texture ends in `.png`.

## The fonts

`U+FE001` is a different glyph in `minecraft:default` than in `nordtal:bossbar`. A component without
a font draws `minecraft:default`, so every component with a `nordtal:` glyph names its font.
`:architecture` lets only `BossBarLine` name a boss bar. Every code point is a surrogate pair in
UTF-16: `Glyphs` writes `cp(0xFE004)`, and no message bundle holds one (`TabListTest`). A glyph reaches
a bundle as a `{parameter}` or as `<glyph:name>`.

- **`nordtal:board`:** a row draws its left edge, advances by the configured width
  (`boards[].width` in the `boards` group of `smp`, 32 to 240 px), draws the right edge and goes back.
  Only the client knows the width of a text line.
- **`nordtal:bossbar`:** its spaces are a `space` provider. The low digits of a code point are its
  advance in decimal, negative advances included, so a line can draw over itself. A HUD line is one
  rounded pill per piece of information. The HUD now draws in the UI kit's fonts, and nothing draws this
  font any more. No advance may be allocated onto an assigned character.
- **`nordtal:gui`:** menu panels, drawn from the inventory title. A title holds a bitmap as large as
  the window on a high `ascent`, over the emptied `generic_54.png`. A title needs **-8** to reach the left edge of
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
provider at **+3**. The sheet has capitals only. `MenuFont` in `:pack-rendering` folds lower case onto
them, except `ß`, and maps unknown characters to `?`, because a missing-glyph box is six pixels wide.
`O`, `0` and `8` are three pixels wide and drawn apart. `MenuFontTest` checks their distances:

| glyph | rows                  | distinguishing feature                             |
| ----- | --------------------- | -------------------------------------------------- |
| `O`   | `.#. #.# #.# #.# .#.` | round, tapered top and bottom, like `C G Q`        |
| `0`   | `### #.# #.# #.# ###` | square, flat top and bottom, like `1 2 3 5 7`      |
| `8`   | `### #.# .#. #.# ###` | square and waisted: two loops joined in the middle |

### The ui fonts

The ui fonts draw the chest menus, the dialogs, the tooltips and the pills. `eu.nordtal.season.packrendering.ui`
composes them, and nothing else names them.

- **`nordtal:ui_space`:** moves of -1 to -256 and +1 to +256 pixels. Every other ui font references
  it, and `minecraft:default` does too, so a move joins the run of the piece before it.
- **`nordtal:ui_text_{shift}`:** vanilla's letters `shift` pixels below the text row, with the 7 × 7
  symbols of `ui/kit/symbols.png`. Each font references `minecraft:include/space`, so a space is 4
  wide as in vanilla text and not the 3 of `nordtal:gui`. The build measures the letters into
  `nordtal/ui/letter-advances.properties`, which `Letters` reads.
- **`nordtal:ui_art_{shift}`:** the plates in three looks, the slot frame, the field frame, the
  primary button, the progress bar and the flat label pill with a 1 px rim, which the HUD and
  every panel in the world draw. Shifts 11 + 18r put a piece's top on content row r's cell top in a chest window.
- **`nordtal:ui_panel`:** the chest panel for 1 to 6 rows, with the player's slot frames only
  (`UI_CHEST_PANELS`) or with every content slot (`UI_CHEST_PANELS_PLAIN`, for placed containers).
- **`nordtal:ui_dialog_{shift}`:** the dialog panels, one list per panel size, in tiles of at most
  256 columns. The shift is the panel's top: 49 over a first plate line, 50 over a first text line, 43 over a
  frame 2 px above its line and 64 for the wheel.
- **`nordtal:ui_tooltip`:** the covers that hide a native tooltip's box, one sheet per box height.
  `minecraft:default` holds one more cover sheet for the language file's tooltip, which can name no font.
- **`nordtal:ui_silent`:** every vanilla letter at advance 0, for a dialog body the narrator reads
  but the screen does not show.

The tooltip style `nordtal:panel` is the pair of sprites `nordtal/textures/gui/sprites/tooltip/panel_background.png`
and `panel_frame.png`, nine-sliced by their `.mcmeta`.

#### Plates of any width

A plate is its left edge, runs of fill and its right edge. Each sheet of a look holds the two edges
and fill runs of 1, 2, 4 to 256 columns. A bitmap advances its ink width + 1, so `Pen` follows each
piece with a move of -1. The fill is two columns narrower than the plate, written greedily from 256
down to 1, so any width from 2 up takes one run per 256 columns and at most 8 more. A label stands on the plate in a
text font 5 pixels lower than the plate's art.

#### The chest panels

`drawChestPanels` draws the twelve chest panel textures into `build/generated/chest-panels` on every
build, from vanilla's `generic_54.png` in the client jar, and `assemblePack` copies them into the
pack. They are never committed. `ChestPanelGeometry` in `build-logic` holds where the panel sits
against vanilla's window (window y 0 to 113 + 18 rows), so moving it is a change to one constant
there. The header's row is `ChestTitle.CAPS_Y`, which must be a shift that `nordtal:ui_text_{shift}` has.

## Vanilla overrides

| file                                                           | effect                                                                       |
| -------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `minecraft/textures/gui/sprites/boss_bar/white_background.png` | makes the vanilla boss bar frame invisible, so only the composed HUD shows   |
| `minecraft/textures/gui/sprites/boss_bar/white_progress.png`   | makes the progress fill invisible                                            |
| `minecraft/lang/en_us.json`                                    | sets `menu.returnToGame`, `menu.game` (the logo glyph) and `menu.disconnect` |
| `minecraft/lang/de_de.json`                                    | sets the same three keys in German                                           |
| `minecraft/textures/gui/container/generic_54.png`              | empties the chest window, so the title draws the whole panel                 |
| `minecraft/textures/gui/sprites/dialog/warning_button*.png`    | empties the dialog's warning button in its three states                      |
| `minecraft/textures/gui/sprites/widget/checkbox*.png`          | the four checkbox states at 17 px with the tick centred                      |
| `minecraft/lang/en_*.json`, `de_*.json`                        | `menu.custom_screen_info.tooltip`, the Nordtal menu's tooltip, in 8 files    |

A vanilla override starts as a copy from the client jar of the season's Minecraft version, the version
of `paper` in the catalog. It is the jar that Fabric Loom downloads for `:bridge-mod` once, checks
against the SHA-1 of Mojang's version manifest and keeps in `~/.gradle/caches/fabric-loom`, which
`:bridge-mod` hands to these tasks. It is never part of the pack. Each task prints the absolute path
of its result, one file per line.

```bash
./gradlew -q :resource-pack:listVanillaAssets       # build/vanilla-assets.json: textures (id, width, height, mcmeta), items, sounds (id, subtitle, sounds)
./gradlew -q :resource-pack:copyVanillaTexture -Ptexture=minecraft:block/stone.png
```

Minecraft's sounds are not in the client jar but in the asset index of the version. `:bridge-mod`
hands its id, URL and SHA-1 from the version manifest to these tasks, which download the index and
each `.ogg` once into `~/.gradle/caches/nordtal/assets` and check each against its SHA-1. Without a
connection `listVanillaAssets` lists no sounds and says why in `soundsProblem`.

`copyVanillaTexture` writes the PNG and its `.mcmeta` to `src/assets/minecraft/textures` and refuses
a texture that exists there already.

The language files are the only place where the client language decides what a player sees.
`en_us.json` and `de_de.json` set the three pause-menu strings. All eight set
`menu.custom_screen_info.tooltip`: a cover from `minecraft:default` that hides the native tooltip's
box and draws the panel style, then the title and the text, wrapped at 158 into two rows.
`menu.game` and the cover name glyphs, so a language file is the one place besides `glyphs.json`
that writes a code point by hand. `ResourcePackTest` parses the language files against the written
`minecraft:default`.
