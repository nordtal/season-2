# `.run/` — the run configurations

IntelliJ (and other JetBrains IDEs) read run configurations from this directory automatically.
Thirty of them are one `deploy/dev` subcommand each, grouped into five folders in the Run dropdown:

| folder | what is in it |
| --- | --- |
| `dev: stack` | `init`, `up`, `ui`, `ps`, `stop`, `down`, `help` |
| `dev: build and deploy` | `deploy` and its four per-server variants, `pack` |
| `dev: logs` | all services, then the seven worth having on a key of their own |
| `dev: console and database` | `console` per server, `mc`, `psql` |
| `dev: reset (destructive)` | `reset` per server |

**They are a keystroke, not an abstraction.** Every one of them runs `deploy/dev <subcommand>` and
nothing else — no environment, no arguments the script does not define, no second copy of a
default. `deploy/dev help` stays the list, the script stays the thing that can be typed by hand,
and a change to what a subcommand does needs no change here.

The thirty-first, `steward-ui (Java, :8080)`, is the odd one out and has its own header.

## Three settings, and why they are what they are

**`INTERPRETER_PATH` is `/usr/bin/env` with `bash` as its option, and not `/bin/bash`.** On macOS
`/bin/bash` is 3.2.57 and always will be — it is the last GPLv2 release — and both `deploy/dev` and
`deploy/nordtal.sh` need the associative arrays bash 4 added. Going through `env` picks up whatever
is first on `PATH`, which is a current bash on any machine that has one. Where there is none, the
scripts now say so in a sentence naming `brew install bash`; before 2026-09-20 they failed with
`STEWARD_HOST: unbound variable`, which reads like a complaint about the environment file and is
not one.

**`EXECUTE_IN_TERMINAL` is `true` everywhere, including for the commands that only print.** Four of
these need a keyboard and would otherwise hang on their first question: `init` asks, `psql` is a
shell, `reset` makes you type the service name back, and `console` attaches to the real server
console over tmux. Three more need Ctrl-C to mean what the script documents: `ui` and the `logs`
variants. Making the quiet ones match costs nothing and means there is no rule to remember about
which button does what.

**`SCRIPT_WORKING_DIRECTORY` is `$PROJECT_DIR$`.** `deploy/dev` `cd`s to the repository root itself
and works from anywhere, so this is belt and braces — but it is also what makes the relative paths
in `deploy/dev.env` (`./deploy/servers/...`, `./deploy/pack`) read in the terminal the way they read
in the file.

## What is deliberately not here

- **No `mc <service> <command>` per command.** `dev mc smp list` is in the dropdown as a working
  template; anything else is editing `SCRIPT_OPTIONS` or copying the configuration. Guessing which
  console commands somebody wants on a hotkey is how a directory like this turns into a hundred
  files nobody reads.
- **No configuration that wraps two commands.** `stop` then `up` is two clicks, and a third
  configuration that did both would be a third thing to keep true.
- **Nothing for the standby services or `nordtal.sh`.** The standbys come up only during a
  rehearsed restart, and `nordtal.sh` is the production host's installer; neither is a local
  keystroke.
