# message-rendering

Messages as Adventure components, for Paper and Velocity code only: `MessageRenderer` escapes
every value and parses the bundle's MiniMessage, `Tones` paints a reply's tone, `FeedbackSounds`
turns a feedback into a sound.

`<glyph:name>` is resolved through `GlyphNames`, found with `ServiceLoader`, so this module needs no
resource pack; `:pack-rendering` supplies the one implementation, and without it every name stays
as written.
