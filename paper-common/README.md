# paper-common

`paper-common` holds what every Nordtal Paper plugin shares. `:architecture` refuses the platform
API that a facility wraps anywhere else.

| facility         | purpose                                                                            |
| ---------------- | ---------------------------------------------------------------------------------- |
| `NordtalPlugin`  | The start and stop of smp, hunger-games and limbo.                                 |
| `PaperScheduler` | A `Scheduler` for off-thread work, with `onMain`, `onMainAfter` and `onMainEvery`. |
| `hud()`          | The boss bar `HudLine`s of each player, rendered four times a second.              |
| `Menu`, `Menus`  | Chest inventory windows with a `MenuTitle`.                                        |
| `GameDataExport` | The server's registries as its row of `game_catalogue`.                            |
| `CommandTrees`   | The server's commands as its row of `command_tree`.                                |
| `GameKeys`       | The reading of a setting that names an item, block, statistic or entity.           |
| `WorldDistances` | The view and simulation distance of every world.                                   |
| `Identities`     | The session cache of `PlayerIdentity` from `:database`.                            |
| `Previews`       | An admin's text, shown to a player as the key's own text would be.                 |
| `renderer()`     | The message renderer for chat and replies.                                         |

## The plugin base

`NordtalPlugin` loads the `database`, `colours` and `distances` groups and the plugin's own settings.
It opens the pool and the one `SignalHub`, grants operator from the admin roster, filters the
commands a player sees and keeps the readiness marker beating. It stops the server when the start
fails.

A plugin adds settings, bundles and features in `prepare()`, `enable()` and `disable()`. It greets a
player in `languageKnown`, one tick after the join.

The base registers the plugin's root command (`/smp`, `/hg`) only if the plugin adds subcommands.
Admin subcommands answer the console only. The inbox runs the same actions. Both report through one
`Answer`: done, refused or failed. A done or failed answer, the help and the tab list's header are
documents (`DocumentSpec.game`), sent with `reply`. A refusal stays a key, which `refuse` paints as a
warning.

## Facilities

- `PaperScheduler` refuses the work of a disabled plugin with a `RejectedExecutionException`.
  `mainThread()` is the main thread as a `Scheduler`.
- A `Menu` title cannot be redrawn, so a page turn opens a next menu. `click` answers with a
  `MenuClick`: a sound, then a next menu or a close. `closed` runs on every close. `stopped` runs
  when the plugin stops, before `disable()`, because Paper disconnects players after the plugins stop.
- `GameDataExport` runs on the first tick after the start. A failed write costs the pickers of
  Steward this server's entries.
- `CommandTrees` reads the dispatcher on the main thread and writes the tree off it, only when the
  tree changed. It reads at the start, after every reload and one tick after a plugin is enabled or
  disabled.
- `GameKeys` accepts a namespaced key (`minecraft:` is the default) or a bare upper-case Bukkit name,
  and no legacy material.
- `WorldDistances` applies at the start, on every reload and to a world loaded later. A `distances`
  value of 0 takes `distanceDefaults()` of the plugin, or else the world keeps its value. Paper
  accepts 2 to 32. `WorldDistances` clamps any other value and logs it.
- `Identities` reads an identity at pre-login and holds it until the player leaves. Every hub signal
  reads all held identities again in one query, and `whenChanged` receives each one that differs.
- `Previews` shows a title, subtitle or action bar if the key is one, and a chat line otherwise. It
  reads a key of a bundle that the plugin does not load as MiniMessage.
- `renderer()` draws names as the plugin passes them to `composeNames` in `enable()`. A text writes
  the tone around a name as `plain`. The console reads names bare.
