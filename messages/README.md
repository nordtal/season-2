# messages

The message system without Adventure, so the bot and Steward can load it: bundles and the
admins' overrides over them (`Messages`), a message chosen and filled (`MessageRef`), the specs that declare
every key (`spec`), the contexts a message can name (`context`), and the refusal model (`Refusal`,
`Refused`) that an inbox, a command and an HTTP answer share.

A process hands its `MessageEnvironment` (which service it is, which season) to its bundles once
at startup with `Messages.within`; nothing holds it in a static.

Rendering to Adventure components lives in `:message-rendering`, glyphs in `:pack-rendering`.

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
