# Banner images

Two flat placeholder PNGs, 800x200. They are attached to the bot-maintained messages with
`attachment://contribution.png` and `attachment://link.png`.

**Swap the files, keep the names.** Nothing else has to change: `ManagedMessages` reads them off
the classpath by these names.

They are attachments and not URLs on purpose. Season 1's embeds pointed at
`media.discordapp.net` URLs carrying `ex=` / `is=` expiry parameters, and those links are dead now
— the embeds render as broken images in a channel nobody has re-posted since. An attachment lives
with the message.
