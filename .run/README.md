# `.run/`: the run configurations

JetBrains IDEs read the run configurations in this directory. The `dev:` folders run one
`deploy/dev` subcommand each, with nothing added, so `deploy/dev help` stays the list; the `tests:`
folders are Gradle configurations, so results land in the test tree.

| folder                      | what is in it                                                   |
| --------------------------- | --------------------------------------------------------------- |
| `dev: stack`                | `init`, `up`, `ui`, `ps`, `stop`, `down`, `help`                |
| `dev: build and deploy`     | `deploy` and its four per-server variants, `pack`               |
| `dev: logs`                 | all services, then the seven worth having on a key of their own |
| `dev: console and database` | `console` per server, `mc`, `psql`                              |
| `dev: reset (destructive)`  | `reset` per server                                              |

## Settings

- **`INTERPRETER_PATH` is `/usr/bin/env bash`**, since macOS's `/bin/bash` is 3.2 and the scripts
  need bash 4.
- **`EXECUTE_IN_TERMINAL` is `true` everywhere**, since `init`, `psql`, `reset` and `console` need a
  keyboard and `ui` and `logs` need Ctrl-C.
- **`SCRIPT_WORKING_DIRECTORY` is `$PROJECT_DIR$`**, so the relative paths in `deploy/dev.env` read
  the same in the terminal.

There is one `mc` template rather than one configuration per console command, and none that wraps
two commands.
