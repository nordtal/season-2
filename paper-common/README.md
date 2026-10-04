# paper-common

What every Nordtal Paper plugin shares, and nothing a single plugin owns.

- **The plugin base**, `NordtalPlugin`: one start and one stop for smp, hunger-games and limbo. It
  loads the `database` group, the `colours` group, the `distances` group and the plugin's own settings, opens the pool and the one
  `SignalHub`, holds who everybody is (`Identities`), grants operator from the admin roster, gates
  the commands a player sees, keeps the readiness marker beating and stops the server when a start
  fails. A plugin contributes its settings, bundles and features through `prepare()`, `enable()` and
  `disable()`, and greets a player through `languageKnown`, one tick after join once every join handler ran.
- **The game data**: on the first tick after every start, `GameDataExport` reads the server's
  registries (items, blocks, entity types, advancements without the recipe ones, statistics with the
  registry they count per entry of, enchantments, biomes, effects, sounds, damage types and their
  tags), each entry with its translation key and the server's English for it, and publishes them as
  this server's row of `game_catalogue`, which Steward's pickers draw from. A failed write costs the
  pickers this server's entries and nothing else.
- **Game keys**: `GameKeys` is the one reading of a setting that names an item, a block, a
  statistic or an entity: a namespaced key, with `minecraft:` assumed where none is written and a
  bare upper-case Bukkit name accepted the same way, never a legacy material. Every plugin binds its
  settings through it at load, so a value the catalogue offers is one the server takes.
- **World distances**: `WorldDistances` sets every world's view and simulation distance through Paper's
  `World` at start, on every reload and for a world loaded later. A value the `distances` group leaves at 0
  comes from the plugin's `distanceDefaults()` (smp: 32 and 10), and where that is unset too, the world
  keeps, or gets back, the value it had when the base first saw it. Paper accepts 2 to 32; a value
  outside is clamped and named in the log.
- **Identities**: the one session cache of who everybody is, `PlayerIdentity` from `:database` (the link, the
  name, the language and time zone, both flags, the aura and the play time), read at pre-login and held until the
  player leaves. Every signal of the hub, and so its minute-scale reconciliation, reads all held identities again in
  one query; `reread` is the only way a held value changes, and a watcher registered with `whenChanged` hears of
  each identity that differs, which is how smp redraws a nametag after an aura booking or an admin flag. A
  language chosen mid-session therefore arrives within the minute, not at the next login. The language and zone
  every message is rendered in come from here; an account that chose none reads the network's.
- **Commands**: the base registers the plugin's root (`/smp`, `/hg`), and only when the plugin adds subcommands to
  it; nothing is reloaded by hand, since a settings change reaches every process on the hub. Admin subcommands answer the console only, and the inbox answers the same actions, both through one
  `Answer` (done, refused or failed) that the console reads in English and Steward as text or a refusal.
- **Previews**: `Previews` shows an admin's player the text they are trying for a key, rendered by the same
  `renderer()` as the key's own text: a title, a subtitle or an action bar where the key is one, a chat line for
  every other place, since a menu or a sidebar would have to be opened to show it. A key of a bundle this plugin
  does not load is read as MiniMessage, the format of every in-game key. Its tones are painted in the colours the
  preview carries, those of the key's own service, so the proxy's key looks on the SMP as it does on the proxy.
- **Chat and replies**: the five system lines and a reply in the player's language with tone and sound.
  Everything goes through the base's one `renderer()`. It draws every name the way the plugin hands to
  `composeNames` in `enable()` (smp: the flag, the prestige colour and the crest; hunger-games: the flag and a
  grey name) and puts the card of a player held in `Identities` on it: role, crest and play time, as the
  network's `prestige` group reads now. A text that wants the tone around a name, such as the aura board's
  rows, writes it `plain`. The console reads names bare.
