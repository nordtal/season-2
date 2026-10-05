# `.run/`: the run configurations

JetBrains IDEs read the run configurations in this directory. None needs bash.

The `dev:` folders hold thirty configurations, each one `dev` command with nothing added, so
`dev help` stays the list and `./gradlew -q :dev:run --args="<command>"` is the terminal equivalent.
`RunConfigurationsTest` fails when one names a command the program lacks.

| folder                      | what is in it                                                   |
| --------------------------- | --------------------------------------------------------------- |
| `dev: stack`                | `init`, `up`, `ui`, `ps`, `stop`, `down`, `help`                |
| `dev: build and deploy`     | `deploy` and its four per-server variants, `pack`               |
| `dev: logs`                 | all services, then the seven worth having on a key of their own |
| `dev: console and database` | `console` per server, `mc`, `psql`                              |
| `dev: reset (destructive)`  | `reset` per server                                              |

Interactive commands read the Run window, where typed secrets are visible. The working directory is
the repository root, so the relative paths in `deploy/dev.env` resolve.

The `resource pack` folder installs the pack into a Minecraft client; `resource-pack/README.md`
explains it. The `tests:` folders are Gradle configurations, so results land in the test tree. The
deploy script suites are skipped without bash 4 on the PATH; CI always runs them.
