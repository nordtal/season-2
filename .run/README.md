# `.run/`: the run configurations

JetBrains IDEs read the run configurations in this directory. None of them needs bash.

The 30 `dev:` configurations each run one `dev` command without extra arguments. In a terminal,
`./gradlew -q :dev:run --args="<command>"` runs the same command, and `dev help` lists them all.
`RunConfigurationsTest` fails when a configuration names a command that `dev` lacks.

| folder                      | content                                           |
| --------------------------- | ------------------------------------------------- |
| `dev: stack`                | `init`, `up`, `ui`, `ps`, `stop`, `down`, `help`  |
| `dev: build and deploy`     | `deploy`, its four per-server variants and `pack` |
| `dev: logs`                 | all services and seven single services            |
| `dev: console and database` | `console` per server, `mc` and `psql`             |
| `dev: reset (destructive)`  | `reset` per server                                |

The Run window shows typed secrets of interactive commands. Every configuration starts in the
repository root, so the relative paths in `deploy/dev.env` resolve.

The `resource pack` folder installs the pack into a Minecraft client, as
[`resource-pack/README.md`](../resource-pack/README.md) describes. The `tests:` configurations are Gradle
tasks, so their results appear in the Run window's test tree. The deploy script suites skip
themselves without bash 4 on the PATH, and CI always runs them.
