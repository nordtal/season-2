# DisplayTags 2.1.0

Configuration handling moved onto nordtal's own hardened copy of the Spec library, shipped in
`jcore`. Your `config.yml` keeps the same layout, the same keys and the same comments — nothing
needs editing.

## Breaking changes

- **A setting that does not exist now stops the plugin instead of being deleted.** If
  `config.yml` contains a key DisplayTags does not know — almost always a typo — the plugin
  refuses to enable, prints the key and suggests the one you probably meant, and **leaves your
  file exactly as you wrote it**. The rest of the server starts normally.

  Previously such a key was silently removed from your file the next time it was saved, so a typo
  cost you both the setting and the line, with nothing in the log.

## New

- **Every setting can be overridden by an environment variable**, named
  `NORDTAL_DISPLAYTAGS_<SETTING>` — for example `NORDTAL_DISPLAYTAGS_NAMETAG_ENABLED=false`. The
  environment wins over the file and is never written back into it. Useful for containers.
- **A backup is written before the file is ever rewritten**, as `config.yml.bak`. That happens
  when an update adds a setting or rewords a comment.

## Fixed

- **`config.yml` can no longer be left half-written.** It is now written to a temporary file and
  moved into place in one step, so a crash, a kill or a full disk during a save leaves the
  previous file intact instead of a truncated one.
- **`/displaytags reload` is safe against overlapping runs.** Two reloads at once can no longer
  read the file while it is being written.
- Whole numbers stay whole in the written file. This was already worked around in 2.0.0; the fix
  now lives in the config library, so it cannot be forgotten.

## Internal

- The direct `io.github.revxrsal:spec:1.5` dependency is replaced by
  `com.github.nordtal:jcore:3.0.0`, which vendors that same library and hardens it. Spec has not
  been updated since May 2025.
- Gson and SnakeYAML are no longer bundled — Paper already ships both, and the plugin uses the
  platform's copies. The shaded jar is unchanged in size.

# DisplayTags 2.0.0

A full rewrite of DisplayTags, now targeting the 26.2 generation of Minecraft. **Please read the breaking changes before updating.**

## Breaking changes

- **Requires Minecraft 26.2 and Java 25.** This build is compiled for Java 25 and will not load on an older server, no matter which `api-version` you set. If you are still on 1.21.x, stay on **DisplayTags 1.1.5**.
- **Requires PacketEvents 2.13.0 or newer.** Older PacketEvents builds do not support 26.2.
- **The configuration layout changed.** The `nametags` section became `nametag`, and `show-self` became `show-to-self`. You do **not** have to edit anything by hand: an existing v1 `config.yml` is migrated automatically the first time the server starts, and your original file is kept next to it as `config.yml.v1.bak`. Everything you had configured is carried over.
- **The plugin's Java package changed** from `me.itsskeptical.displaytags` to `eu.nordtal.displaytags`. This only matters if another plugin was reaching into DisplayTags' internals.

## New

- **PlaceholderAPI support.** Any PlaceholderAPI placeholder now works inside your name tag lines, alongside the built-in `{player}` and `{health}`.
- **Developer API.** A separate `:api` module exposes the name tag manager plus create, remove, spawn and despawn events, so other plugins can read and change name tags without touching internals.
- **Configurable offset.** The name tag's position relative to the player's head is now a setting (`display.offset`) instead of a fixed value.
- **Sneak transparency.** Name tags fade out while a player is sneaking. Tune it with `display.sneak-text-opacity`, or set it to `-1` to turn the effect off.
- `/displaytags config` now shows every setting, including the new ones.

## Fixed

- Your own name tag no longer disappears for everyone when `show-to-self` is turned off — the setting now only affects the player it belongs to.
- The configured `scale` is applied again; v1 read it from the wrong place and silently ignored it.
- Name tags follow teleports instead of staying behind at the old position.
- Name tags come back correctly after a respawn, a world change, or leaving spectator mode.
- A player who joins now gets their name tag immediately rather than after the next update tick.
- The vanilla name stays hidden for every viewer, and is restored properly when a name tag goes away. With TAB installed, DisplayTags leaves the vanilla name tags to TAB.
- `/displaytags reload` no longer leaves the old name tags behind.
- An invalid `config.yml` produces a clear error instead of breaking plugin startup.
- Whole numbers in the generated configuration stay whole numbers.
- Various crashes under concurrent access and during a failed startup.
