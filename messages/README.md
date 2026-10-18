# messages

The `messages` module loads message bundles without Adventure, so the bot and Steward can use it.
`Messages` holds the bundles and the admins' overrides, `MessageRef` names a message with its values,
`spec` declares every key and `context` declares the contexts a message can name. `Refusal` and
`Refused` model a refusal for an inbox, a command and an HTTP answer. A process passes its
`MessageEnvironment` (service, season) to its bundles at startup with `Messages.within`.

```mermaid
flowchart LR
    text["a text<br/>MessageText, MessageCheck, ValueText"] --> prepared["Messages.Prepared<br/>one reader"]
    prepared --> plain["plain: Messages.format<br/>console, log, push"]
    prepared --> mc["Minecraft: MessageRenderer<br/>:message-rendering"]
    prepared --> discord["Discord: DiscordRenderer<br/>discord-bot"]
    prepared --> web["web: lib/texts.ts<br/>steward frontend"]
```

`MessageText` parses every text, `MessageCheck` validates it and `ValueText` formats its values for
every target. The browser walks the tree of `MessageText` and formats values as `ValueText` does.
The shared vectors hold both to the same result.

## Writing a text

A value is `{role}` or `{role.attr}`, optionally with a kind and a style: `{left, duration, short}`.
`{n, plural, one {# day} other {# days}}` and `{open, select, true {open} other {closed}}` are the
only MessageFormat syntax a text may use. The kinds are a closed set:

| kind       | styles (the first is the default)                                                               |
| ---------- | ----------------------------------------------------------------------------------------------- |
| `text`     |                                                                                                 |
| `number`   | grouped, `plain`, at most two fraction digits                                                   |
| `duration` | `long` (its two largest units), `short` (`2h 5m`), `clock` (`2:05:00`), `minutes` (`1d 6h 30m`) |
| `instant`  | `datetime`, `date`, `time`, `relative`, in the reader's zone                                    |
| `money`    | in the reader's language: `€3.00`, `3,00 €`                                                     |
| `list`     | `and`, `or`                                                                                     |
| `name`     | with the process's name card, `plain` without                                                   |
| `mention`  |                                                                                                 |
| `item`     |                                                                                                 |
| `glyph`    |                                                                                                 |
| `choice`   | what `select` chooses on; an enum constant reads in kebab case                                  |
| `message`  | another message, rendered for the same reader in the same target                                |

A Minecraft text is MiniMessage. A packaged text colours only with the tones of `Tone`, such as
`<good>`. The `colours` group of each process maps the tones to hex. An admin's override may also
name colours and gradients. A value may appear in a hover text and, quoted, in a click's target, and
in no other tag argument. `<glyph:name>` draws a glyph of the pack. `<action:name>…</action>` is a
click whose command the code binds.

## Values and contexts

A message declares its values in its spec with `@Arg`, and the Java type of a value gives its kind.
A context (`MessageContext`) is a record annotated `@ContextType` in the module that owns it, and
`Contexts` is the registry. Every message also has the roles `server`, `season`, `network` and
`viewer`, and every player role has `self`.

- An instant shows in the reader's zone, else in the network's. In Discord it is Discord's own
  timestamp, and `relative` is the countdown that Discord keeps current.
- A missing value shows the replacement word of its kind from the `values` bundle (`someone`,
  `jemand`) and logs a warning. `{x}` is never printed. The build refuses a spec parameter without a
  kind.
- `GameContent` is a translatable component that the client reads in its language. Other targets
  show English.
- A `message` value is a phrase written once and placed in many texts. It is never rendered into a
  string ahead of time, because only the target knows the reader's language and how to draw the
  tones. `@Takes` names the keys it may take: `key`, `bundle/key`, or `bundle/*` for any key of that
  bundle, which needs an `@Example`. Without an `@Example`, the first key is previewed.

## A message as data

`MessageJson` writes a message as `{"key", "args"}` with each value as `{"kind", "value"}`. A row
that stores a message stores this form, and a target renders it when somebody reads it.

The browser reads message syntax only in Steward's translation editor (`lib/message-tree.ts`), which
follows `MessageText` line by line. It walks the `NodeJson` trees of `MessageJson.texts`.
`WebTargetVectorsTest`, `message-tree.test.ts` and `texts.test.ts` hold both sides to the vectors in
`src/test/resources/web-target.json`.

## Where a text is shown

`@Shown` names every place where a key appears (`Display`). It sits on the spec, a section or the
key, and the nearest one wins. A place has a surface (`Display.Surface`: the game, Discord or
Steward). The surface groups the key on Steward's Translations page and decides how Steward previews
the place. The length limit of a key is the strictest limit of its places (`Display.strictest`).

## Discord layouts

A Discord message is a `Layout` (`spec.layout`). The module whose code fills the layout declares it
once as a `static final` field and names the class in its build with
`messageSpec { layoutClasses.add(...) }`. A layout has a name in kebab case, a bundle, an area, a
surface and a tree of Components V2 parts.

The code fills an `Element`, which the tree places once by its id. A modal (`Layout.modal`) is a
layout of a title and one field per row. Its texts are `DISCORD_MODAL` places, and only its texts
change. A button or select that is filled with nothing is left out. So is a row that holds nothing
else, and so is a text block that holds only headings and empty data lists.

The `messageSchema` task writes `messages/<bundle>/layouts.json` beside the schema. It lists each
layout with the values its texts may name and every key the layout shows. A data list's line and a
select's option line may also name the values of each item. The task refuses a layout that places an
element wrongly, places a key where its `@Shown` does not list the place, or asks Discord for
something it cannot draw.

steward-agent ships the layouts with the bundle (`MessageBundle.layouts`). A key shown in two or more
layouts is a building block of them (`LayoutSchema.buildingBlocks`).

An editor can rebuild a layout. The rebuild keeps the layout's identity and the elements the code
fills. It may move them, place fewer keys, add a line of its own text per language (`Line.added`) and
add link buttons (`Element.link`). `LayoutCheck.rebuildProblems` compares the rebuild with the
packaged layout and keeps its `LayoutSchema.hash`. An image is `attachment://`, `https://` or
`upload://<id>`, an image that an admin uploaded in Steward and that the bot sends as an attachment.
Steward stores rebuilds in the database (`LayoutStore`), and the bot reads them again on
`nordtal_messages`.

`LayoutMerge` keeps a rebuild when a release changes the layout:

- An element or key that the release removed is removed from the rebuild.
- A section or block per language whose element was removed keeps what it held.
- A new element is placed at the end, and new buttons go in rows.
- An added text that names a value the layout no longer has shows the replacement word of that value.
- A rebuild that still does not fit is replaced by the packaged layout.

Every change is an `Adaptation`, which Steward lists.

## The check

The `messageSchema` task of the build runs `MessageSpecCheck`, which holds every spec against its
bundles in the packaged mode. Steward runs the same `MessageCheck` on an admin's override, where an
error of a release is a warning. Each problem is a message of the English-only `check` bundle
(`CheckMessages`), so an admin can read and change it in the editor. The build and a log show it in
the packaged English (`MessageCheck.english`). `GET /api/message-check` returns the problems of a
text for a key.

- The check refuses an undeclared value, an unknown tag, a colour in a packaged text and a text that
  is longer than the strictest limit of its places.
- It refuses a word that `TermMessages.SYNONYMS` lists for a game term.
- The build refuses a key that is shown nowhere.
- A text that never shows a value it is given is an error in a bundle and a warning in an override.
  So is a line break in a text that is shown only where the game draws one line (`Display.oneLine`),
  and so is a link whose visible text is its own address.

## Packaged texts and overrides

`PackagedTexts` reads a bundle from `messages/<bundle>/<language>.properties` in the jar. A key may
hold several texts, and one is chosen at random each time: `key=`, then `key[1]=`, `key[2]=` without
a gap.

An admin's change is rows of `message_override`, one per bundle, key, language and variant. Steward
writes them. Every process reads them at start and on the signal hub's `nordtal_messages`. A Paper
plugin reads them off the main thread and shows the packaged texts until the hub has connected.

- The texts of an override replace the packaged texts of that language as a set. The override keeps
  the packaged texts it replaced and their hash.
- A process sets an override aside when its hash no longer matches the jar or the validator refuses
  it. The hash no longer matches when the admin wrote the override over a text that this release
  changed. The packaged text shows until an admin saves the override again. Every process logs the
  override, and Steward lists them at `GET /api/message-fallbacks`.
- `@Formerly` names the former names of a renamed key (`key` or `bundle/key`), and the schema copies
  them into the jar. An override under a former name follows the key. An override under the current
  name wins.
