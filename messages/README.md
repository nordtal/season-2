# messages

The message system without Adventure, so the bot and Steward can load it: bundles and the
admins' overrides over them (`Messages`), a message chosen and filled (`MessageRef`), the specs that declare
every key (`spec`), the contexts a message can name (`context`), and the refusal model (`Refusal`,
`Refused`) that an inbox, a command and an HTTP answer share.

A process hands its `MessageEnvironment` (which service it is, which season) to its bundles once
at startup with `Messages.within`; nothing holds it in a static.

Rendering to Adventure components lives in `:message-rendering`, glyphs in `:pack-rendering`. The plain
target is `Messages.format`, which the bot, Steward and every log line use; there is one parser
(`MessageText`) and one validator (`MessageCheck`) behind both.

## Writing a text

A value is written `{role}` or `{role.attr}`, optionally with its kind and a style: `{left, duration, short}`.
`{n, plural, one {# day} other {# days}}` and `{open, select, true {open} other {closed}}` are ICU's, and nothing
else of MessageFormat is. The kinds are a closed set, each rendered once per target:

| kind | Java type | styles (the first is the default) |
|---|---|---|
| `text` | a `CharSequence` | |
| `number` | any `Number` | grouped, `plain`; at most two fraction digits |
| `duration` | `Duration` | `long` (its two largest units), `short` (`2h 5m`), `clock` (`2:05:00`) |
| `instant` | `Instant` | `datetime`, `date`, `time`, in the reader's zone |
| `money` | `Money` | |
| `list` | a `List` | `and`, `or` |
| `name` | `DisplayName` | with the process's name card, `plain` without |
| `mention` | `Mention` | |
| `item` | `GameContent` | |
| `glyph` | `Glyph` | |
| `choice` | `Boolean` or an enum | what `select` chooses on; an enum constant reads in kebab case |

A Minecraft text is MiniMessage. A packaged text paints only with tones, `<good>`, `<bad>`, `<warn>`, `<muted>`,
`<accent>`, `<brand>`, `<emphasis>`, `<faint>` and `<neutral>`, which each process's `colours` group maps to hex; an
admin's override may also name colours and gradients. A value may stand in a hover text and, quoted, in a click's
target, and in no other tag argument. `<glyph:name>` draws a glyph of the pack and `<action:name>…</action>` a
click whose command the code binds, so a text places a button without knowing its command.

## Values and contexts

A message declares its values in its spec (`@Arg`), and a value's kind follows its Java type. A context
(`MessageContext`) is a record annotated `@ContextType` in the module that owns it: each component is an attribute
with a kind and a static example, and a component that is itself a context is expanded one level deep, as
`{winner.team.name}`. `Contexts` is the registry, so a new game mode brings its own types without a change to
Steward. Every message also has `server`, `season`, `network` and `viewer`, and every player role has `self`,
true for the reader it is about.

A reader is a `Viewer`: a language, a zone and, in game, the player. An instant is shown in the reader's zone and
in the network's where they chose none. A value that is missing shows its kind's replacement word from the
`values` bundle (`someone`, `jemand`) and logs a warning; `{x}` is never printed. `GameContent` is the game's
own line: a translatable component the client reads in its language, English everywhere else.

## The check

`MessageSpecCheck` holds every spec against its bundles on the build's `messageSchema` task, in the packaged
mode, and Steward runs the same `MessageCheck` on an admin's override, where what is an error in a release is
a warning. It refuses an undeclared value, a kind or style a value does not have, an unclosed or unknown tag, a
colour in a packaged text, a value in a tag argument that takes none, and a text longer than where it is shown;
a text that never shows a value it is given is an error in a bundle and a warning in an override.

## Packaged texts and overrides

A bundle is packaged in the jar as `messages/<bundle>/<language>.properties`, read in one place
(`PackagedTexts`). A key may hold several texts, one chosen at random each time: `key=` is the
first, `key[1]=`, `key[2]=` the next, without a gap.

An admin's change is never a file. It is rows of `message_override`, one per bundle, key, language
and variant, which Steward writes and every process reads: at start and on the signal hub's
`nordtal_messages`, through the same `SignalHub.watch` that re-reads the settings. A Paper plugin
therefore reads them off the main thread, and shows the packaged texts until the hub has connected.
An override's texts replace the packaged ones of that language as a set, and it records the hash
of the packaged texts it replaced, so a release that changes them can be noticed. A row for a key
the bundle does not declare is left out.

A renamed key names its former names with `@Formerly` (`key` or `bundle/key`), which the schema
carries into the jar; an override stored under a former name follows the key, and one under the
current name wins.
