# messages

The message system without Adventure, so the bot and Steward can load it: bundles and their
override layer (`Messages`), a message chosen and filled (`MessageRef`), the specs that declare
every key (`spec`), the contexts a message can name (`context`), and the refusal model (`Refusal`,
`Refused`) that an inbox, a command and an HTTP answer share.

A process hands its `MessageEnvironment` (which service it is, which season) to its bundles once
at startup with `Messages.within`; nothing holds it in a static.

Rendering to Adventure components lives in `:message-rendering`, glyphs in `:pack-rendering`.
