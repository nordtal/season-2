# settings

Where every process's settings come from. A process holds a `Settings` and loads one `Setting` per
`Group`: its name, its jcore `@ConfigSpec`, its `Check`, whether it is taken while the process runs,
whether it is the network's, and any default of this process that differs from the spec's.

- **Source**: `DatabaseSettings` publishes each group's schema and defaults into `setting_group` at
  load and composes its values: the spec's defaults, this process's own defaults, the rows an admin
  stored in `setting_override`, the environment, then the check. Only an admin's changes are rows.
  `listen` re-reads on the signal hub; a live group's holder keeps one instance whose values change.
  `EnvironmentSettings` is the bootstrap: the database connection, from the environment alone.
- **Refusal**: a stored value the check refuses never stops a start. The group runs on its defaults,
  the reason is recorded on the group, and Steward shows it.
- **Secrets** (`@Secret`) come from the environment only and are never stored or imported.
- **Import**: `LegacyFiles` takes each group's YAML file left by the last installation once, every
  value that differs from the spec's default, as the host, and never over a row. `retireFiles`
  deletes the files afterwards.
- **References** (`@Refers`): a getter that names a game thing (an item, a statistic and the subject it
  counts, an advancement, a sound, a damage type and the like), a colour or a Discord role, channel or
  user says so, and `SpecJson.schema` puts it on the field as `refers`. Steward draws its picker from
  that and from nothing else, so it never guesses from a key's name or a value's look. A subject
  depends on its sibling `statistic`, whose registry it reads. Game values are namespaced keys
  (`minecraft:oak_log`); the plugin binds each when it loads and refuses one it does not know, while
  Steward stores whatever is picked or typed. The annotation lives here rather than in jcore's schema
  because it is the published schema that carries it, and that is `SpecJson`.
- **Shared groups**: `DatabaseSpec`, `ColoursSpec` (mapped onto the message tones by `Colours`) and
  `DistancesSpec`, whose defaults each Paper server sets for itself. `DatabasePool` opens the pool.
- **The network's groups** (`network/`), stored once under the service `network` and read by every
  process: `players` (the limit, which only the proxy enforces, and the command allowlist), taken while
  running; `season`, `language-and-time` (the default language, the languages and
  the default time zone) and `prices` (the tiers and the donation threshold, which the bot offers and steward
  books by), taken at the next start. `CommandAllowlist` is the parsed list, `NetworkSettings.tiers` the price
  list as `:database`'s `Tiers`.

jcore and HikariCP are `compileOnly`: every consumer already carries them in the version it needs.
