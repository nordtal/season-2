# `.run/` — the run configurations

IntelliJ (and other JetBrains IDEs) read run configurations from this directory automatically. None
of them needs bash: they run the same on Windows, macOS and Linux.

Thirty-one of them are one `dev` command each, grouped into five folders in the Run dropdown:

| folder                      | what is in it                                                   |
| --------------------------- | --------------------------------------------------------------- |
| `dev: stack`                | `init`, `up`, `ui`, `ps`, `stop`, `down`, `help`                |
| `dev: build and deploy`     | `deploy` and its four per-server variants, `pack`               |
| `dev: logs`                 | all services, then the seven worth having on a key of their own |
| `dev: console and database` | `console` per server, `mc`, `psql`                              |
| `dev: reset (destructive)`  | `reset` per server                                              |

**They are a keystroke, not an abstraction.** Each is an Application configuration of
`eu.nordtal.s2.dev.Dev` in the `:dev` module with one command as its program arguments, and nothing
else: no environment, no second copy of a default. `dev help` stays the list, and
`./gradlew -q :dev:run --args="<command>"` is the same thing typed in a terminal.
`RunConfigurationsTest` in `:dev` fails when a configuration names a command the program does not
have.

**Everything that asks runs in the Run window.** `init` asks its questions there, `reset` wants the
service name typed back, `console` sends every typed line to the server and `psql` reads one
statement per line. A secret typed into `init` there is visible, because only a real terminal can
hide input. The working directory is the repository root, which is what makes the relative paths in
`deploy/dev.env` (`./deploy/servers/...`, `./deploy/pack`) mean what they say.

The `resource pack` folder is for whoever draws the pack, not for the stack: `pack: 1. choose
Minecraft instance` and `pack: 2. install into Minecraft` are Gradle configurations.
`resource-pack/README.md` says how they are used.

The `tests:` folders are Gradle configurations, so results land in the Run window's test tree.
`tests: deploy scripts` runs the suites of the scripts that stay bash because they run on the Linux
host and in the containers; without bash 4 on the PATH, as on Windows, they are skipped with a line
saying so, and CI always runs them.

## What is deliberately not here

- **No `mc <service> <command>` per command.** `dev mc smp list` is in the dropdown as a working
  template; anything else is editing the program arguments or copying the configuration. Guessing
  which console commands somebody wants on a hotkey is how a directory like this turns into a
  hundred files nobody reads.
- **No configuration that wraps two commands.** `stop` then `up` is two clicks, and a third
  configuration that did both would be a third thing to keep true.
- **Nothing for the standby services or `nordtal.sh`.** The standbys come up only during a
  rehearsed restart, and `nordtal.sh` is the production host's installer; neither is a local
  keystroke.
