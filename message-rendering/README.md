# message-rendering

Messages as Adventure components, for Paper and Velocity code only. `MessageRenderer` is the
Minecraft target of the one message core in `:messages`: it writes the text's own MiniMessage and
inserts every value as a component of its kind, never as characters spliced into the markup, so
nothing a player typed can open a tag. A tone tag is painted from the process's palette
(`ToneColours`, parsed from its `colours` group), and `<action:name>` becomes a click on the command
the code bound to that name.

`Names` is how a process draws a player's name: the bare name by default, a server's own
composition with its hover card where it hands one in; the style `plain` bypasses it. `GameLines`
turns a line the game wrote, such as a death message, into a `GameContent` value, which renders as a
translatable component the client reads in its own language. `Tones` paints a reply's tone and
`FeedbackSounds` turns a feedback into a sound.

`<glyph:name>` is resolved through `GlyphNames`, found with `ServiceLoader`, so this module needs no
resource pack; `:pack-rendering` supplies the one implementation, and without it every name stays
as written.
