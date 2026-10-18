# discord-rendering

Draws documents and messages for Discord, for the bot and for Steward's previews. `DocumentRenderer`
draws a document as a Components V2 message, a modal, a plain message or a channel name within
Discord's limits. `DiscordRenderer` is the Discord target of the message core in `:messages`: it
escapes each value and writes times and members as Discord's own.

The bot hands the renderer its `StoredDocuments`, which hold the packaged documents and the ones
admins wrote over them. Steward builds a renderer of drafts only: `preview` fills a draft with the
declared examples and answers Discord's own JSON, which the frontend draws. A stored image stays as
its `upload://` address there, and the frontend shows it from Steward.
