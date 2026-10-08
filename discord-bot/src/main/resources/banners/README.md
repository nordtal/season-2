# Banner images

Three placeholder PNGs, 2560x640, attached to the bot's managed messages as
`attachment://contribution.png`, `attachment://link.png` and `attachment://onboarding.png` (the welcome in the
onboarding channel).

To change a banner, replace the file and keep its name: `ManagedMessage` loads them from the
classpath by name. They are attachments rather than URLs because Discord media URLs expire.
