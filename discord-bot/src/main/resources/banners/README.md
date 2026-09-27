# Banner images

Two placeholder PNGs, 800x200, attached to the bot's managed messages as
`attachment://contribution.png` and `attachment://link.png`.

To change a banner, replace the file and keep its name: `ManagedMessages` loads them from the
classpath by name. They are attachments rather than URLs because Discord media URLs expire.
