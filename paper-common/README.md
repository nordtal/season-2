# paper-common

What every Nordtal Paper plugin shares, and nothing a single plugin owns.

- **The plugin base**, `NordtalPlugin`: one start and one stop for smp, hunger-games and limbo. It
  loads `database.yml`, `colours.yml`, `distances.yml` and the plugin's own settings, opens the pool and the one
  `SignalHub`, holds who everybody is (`Identities`), grants operator from the admin roster, gates
  the commands a player sees, keeps the readiness marker beating and stops the server when a start
  fails. A plugin contributes its settings, bundles and features through `prepare()`, `enable()` and
  `disable()`, and reacts to a player's language through `languageKnown`.
- **World distances**: `WorldDistances` sets every world's view and simulation distance through Paper's
  `World` at start, on every reload and for a world loaded later. A value `distances.yml` leaves at 0
  comes from the plugin's `distanceDefaults()` (smp: 32 and 10), and where that is unset too, the world
  keeps, or gets back, the value it had when the base first saw it. Paper accepts 2 to 32; a value
  outside is clamped and named in the log.
- **Identities**: read once at pre-login in one query and held until the player leaves; a plugin's
  own per-player data (smp's aura) is an `Identities.Part` that is loaded and dropped with it. The
  language every message is rendered in comes from here.
- **Commands**: the base registers the plugin's root (`/smp`, `/hg`, `/limbo`) with `reload`; a plugin adds its own
  subcommands. Admin subcommands answer the console only, and the inbox answers the same actions, both through one
  `Answer` (done, refused or failed) that the console reads in English and Steward as text or a refusal.
- **Chat and replies**: the five system lines and a reply in the player's language with tone and sound.
