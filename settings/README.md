# settings

Where every process's settings come from, and the groups every process shares. A process holds a
`Settings` and loads one `Setting` per group; `FileSettings` reads each group from a commented YAML
file (jcore's config system) and lets the environment override any value, `<PREFIX>_<GROUP>_<KEY>`.
Only this module knows the source is a file, so moving the settings into the database changes this
module and none of its callers.

- **Shared groups**: `DatabaseSpec` (`database.yml`) and `ColoursSpec` (`colours.yml`, mapped onto
  the message tones by `Colours`). `DatabasePool` opens the pool a Minecraft process uses.
- **Checks**: a group is refused as a whole by its `Check`; `Checks` holds the rules every group
  shares. A refusal names the file and the key.
- **Overrides**: beside every file, `EnvOverrideFile` names the keys the environment overrides, so
  Steward can say that editing them there changes nothing.

jcore and HikariCP are `compileOnly`: every consumer already carries them in the version it needs.
