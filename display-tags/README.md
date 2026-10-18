# display-tags

`display-tags` draws the name tag of every player as a packet-only text display. smp starts it with
its `nametags` settings group. `DisplayTags` runs it, and other code uses the tags through
`NameTagManager` and the `NameTag*Event`s. smp writes the text of a tag, and the settings shape
only its look.

PacketEvents must be installed on the server. `display-tags` uses TAB if it is present.

`display-tags` is a fork of [DisplayTags](https://github.com/imskeptical/DisplayTags) (MIT). The
[NOTICE](../NOTICE) of the repository carries its notice.
