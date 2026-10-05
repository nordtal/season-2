# paper-common

What every Nordtal Paper plugin shares, and nothing a single plugin owns. Each facility below is the
one way to do its thing; `:architecture` refuses the platform API it wraps anywhere else.

| facility         | what it is                                                                                                |
| ---------------- | --------------------------------------------------------------------------------------------------------- |
| `NordtalPlugin`  | one start and stop for smp, hunger-games and limbo                                                        |
| `PaperScheduler` | `PaperScheduler.of(plugin)`: off-thread work as a `Scheduler`, and `onMain`, `onMainAfter`, `onMainEvery` |
| `hud()`          | boss bar `HudLine`s per player on one render clock, four times a second                                   |
| `Menu`, `Menus`  | chest-inventory windows framed by `MenuTitle`; one listener for sounds, clicks and closing                |
| `GameDataExport` | the server's registries as its row of `game_catalogue`                                                    |
| `CommandTrees`   | every command the server takes as its row of `command_tree`                                               |
| `GameKeys`       | the one reading of a setting that names an item, block, statistic or entity                               |
| `WorldDistances` | view and simulation distance of every world                                                               |
| `Identities`     | the session cache of who everybody is, `PlayerIdentity` from `:database`                                  |
| `Previews`       | an admin's text shown to their player, rendered like the key's own text                                   |
| `renderer()`     | the one message renderer: names, cards, tones and sounds for chat and replies                             |

## The plugin base

`NordtalPlugin` loads the `database`, `colours` and `distances` groups and the plugin's own settings,
opens the pool and the one `SignalHub`, holds who everybody is, grants operator from the admin roster,
gates the commands a player sees, keeps the readiness marker beating and stops the server when a
start fails. A plugin contributes settings, bundles and features through `prepare()`, `enable()` and
`disable()`, and greets a player through `languageKnown`, one tick after join. The base registers the
plugin's root command (`/smp`, `/hg`) only when the plugin adds subcommands; admin subcommands answer
the console only, and the inbox answers the same actions, both through one `Answer` (done, refused or failed).

## Facilities

- **Scheduler.** A disabled plugin's work is refused with a `RejectedExecutionException`;
  `mainThread()` is the main thread as a `Scheduler`.
- **HUD.** A line answers what it says to one player now and nothing hides its bar. Slower surfaces
  ride the same clock with `every` (smp's boards, every five seconds); the clock starts after
  `enable()` and stops first at disable.
- **Menus.** A title cannot be redrawn, so a page turn is a next menu. A click on the menu's own slots
  goes to `click`, which answers with a `MenuClick`: a sound, then a next menu or a close. `closed`
  runs on every close and `stopped` at the plugin's stop, before `disable()`, since Paper disconnects
  players only after the plugins stopped.
- **Game data.** On the first tick after every start `GameDataExport` reads items, blocks, entity
  types, advancements, statistics, enchantments, biomes, effects, sounds and damage types, each with
  its translation key and English, for Steward's pickers. A failed write costs the pickers this server's entries.
- **Command tree.** `CommandTrees` takes the game's dispatcher from the commands event at start and
  after every reload and reads it again a tick after a plugin is enabled or disabled; it reads on the
  main thread and writes off it, only when the tree changed. The proxy publishes its own the same way.
- **Game keys.** A namespaced key with `minecraft:` assumed, or a bare upper-case Bukkit name; never a
  legacy material. Every plugin binds its settings through it at load.
- **World distances.** Applied at start, on every reload and for a world loaded later. A `distances`
  value left at 0 comes from the plugin's `distanceDefaults()` (smp: 32 and 10), else the world keeps
  the value it had. Paper accepts 2 to 32; a value outside is clamped and logged.
- **Identities.** Read at pre-login and held until the player leaves. Every hub signal reads all held
  identities again in one query, and `whenChanged` hears each one that differs (smp redraws a nametag
  after an aura booking or an admin flag). A language chosen mid-session arrives within the minute.
- **Previews.** A title, subtitle or action bar where the key is one, else a chat line. A key of a
  bundle this plugin does not load is read as MiniMessage; tones use the colours the preview carries.
- **Chat and replies.** Five system lines and a reply in the player's language with tone and sound,
  all through `renderer()`. Names are drawn the way the plugin hands to `composeNames` in `enable()`
  (smp: flag, prestige colour and crest; hunger-games: flag and a grey name) with the card of a player
  held in `Identities` on it. A text that wants the tone around a name writes it `plain`; the console reads names bare.
