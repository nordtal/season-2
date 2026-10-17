# display-tags

Every player's name tag as a packet-only text display, which smp starts with its `nametags`
settings group. `DisplayTags` runs it; other code reaches the tags through `NameTagManager` and
the `NameTag*Event`s. PacketEvents must be installed on the server; TAB is used when present.
The text is whatever smp writes onto a tag; the settings only shape how it looks.

It is a fork of [DisplayTags](https://github.com/imskeptical/DisplayTags) (MIT), whose notice the
repository's [NOTICE](../NOTICE) carries.
