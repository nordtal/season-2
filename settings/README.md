# settings

The settings of every process. A process holds a `Settings` and loads one `Setting` per `Group`. A
`Group` holds its name, its `@ConfigSpec` from `:spec`, its `Check`, whether the process takes a
change while it runs, whether the group belongs to the network, and any default of this process
that differs from the spec's.

- `DatabaseSettings` publishes the schema and the defaults of each group into `setting_group` at
  load. It composes the values from the spec's defaults, the defaults of this process, the rows an
  admin stored in `setting_override`, the environment and the check, in that order. Only an admin's
  changes are rows, so a changed default reaches every deployment at its next start, except where a
  row overrides that path. `listen` re-reads on the signal hub.
- `EnvironmentSettings` reads the database connection from the environment alone.
- A stored value the check refuses never stops a start. The group runs on its defaults, the reason
  is recorded on the group, and Steward shows it.
- A `@Secret` value comes from the environment only and is never stored.
- `@Refers` marks a getter that names a game thing, a colour or a Discord role, channel or user.
  `SpecJson.schema` puts it on the field as `refers`, and Steward draws its picker from that alone.
  Game values are namespaced keys (`minecraft:oak_log`). The plugin refuses a key it does not know
  when it loads, and Steward stores whatever is picked or typed.
- `@AppliesWhen` shows a field only where a sibling holds one of the values it names.
  `@ChoiceNames` names the values of a closed choice by bundle keys of the plugin.
- `DatabaseSpec`, `ColoursSpec` and `DistancesSpec` are shared groups. Each Paper server sets the
  defaults of `DistancesSpec` for itself.
- The groups in `network/` are stored once under the service `network` and read by every process.
  `players` and `prestige` apply while a process runs. `season`, `language-and-time` and `prices`
  apply at the next start.

`:spec` is an `api` dependency. Gson, HikariCP and JDBI are `compileOnly`, since every consumer
already carries them in the version it needs.
