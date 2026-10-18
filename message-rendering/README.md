# message-rendering

Renders messages as Adventure components, for Paper and Velocity code only. `MessageRenderer` is the
Minecraft target of the message core in `:messages`.

`MessageRenderer` writes the text's own MiniMessage. It inserts every value as a component of its
kind, never as characters in the markup, so nothing a player typed can open a tag. The items of a
list and the arguments of a game line are components of their own kinds too. A tone tag takes its
colour from `ToneColours`, which parses the `colours` group. `<action:name>` becomes a click on the
command the code bound to that name.

- `Names` draws a player's name. It draws the bare name by default, or the composition a server hands
  in.
- `NameCards` supplies the card a name shows on hover. The renderer draws a card for the same reader
  without cards of its own, so a card cannot contain a card. A player the process holds nothing of
  has no card.
- The style `plain` uses neither `Names` nor `NameCards`. `bare()` returns a renderer with neither,
  for a console.
- `GameLines` turns a line the game wrote, such as a death message, into a `GameContent` value. It
  renders as a translatable component, which the client translates into its own language.
- `Tones` paints the tone of a reply. `FeedbackSounds` turns a feedback into a sound.
- `Lines` fits a rendered text to a place the game draws line by line. `Lines.tooltip` makes one
  line per line of the texts of an item, and the later lines of the name open the lore. A title, a
  subtitle, the action bar and a menu title hold one line, so `Lines.joined` draws each line break
  there as a space.

`<glyph:name>` is resolved through `GlyphNames`, found with `ServiceLoader`. `:pack-rendering`
supplies the one implementation. Without it every name stays as written.
