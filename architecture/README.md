# architecture

ArchUnit rules over the compiled classes of every checked module, run as JUnit tests on
`:architecture:check`. They hold the shape of the codebase and the wiring a unit test cannot reach
without a running server. The module has no production code.

| class                 | what it holds                                                                                                                                        |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ArchitectureTest`    | The closed dependency lists of the shared modules, no package cycles, the wall clock, the one scheduler, migrations, listed settings, the allowlist. |
| `ProcessRulesTest`    | How a process says it is alive, and what it may write about a player.                                                                                |
| `PlayerTextRulesTest` | What a player reads goes through the message system; the few exceptions say why.                                                                     |
| `FeedbackRulesTest`   | A call site picks a feedback category; one adapter per module names a sound or an effect.                                                            |
| `ServerRulesTest`     | Wiring inside the limbo, the Hunger Games and the bot.                                                                                               |
| `StewardRulesTest`    | The order of an update run's steps, and how a follow's heartbeat runs.                                                                               |
| `ProxyRulesTest`      | The proxy's routing, its countdown, the move at zero and the return from the standby.                                                                |
| `SmpRulesTest`        | The SMP's features kept apart, its transactions, the reload, duels, graves, the figure, the portal, the welcome, the payout at stop, the landings.   |

## Helpers

- **`Codebase.classes()`** imports the classes once for every rule class, and skips the calling test
  where the build does not set `conventions.enforced`. The checked modules are listed in
  `build.gradle.kts`; a module missing there is checked by nothing. A library class is imported as a
  stub that knows its name and nothing else (`resolveMissingDependenciesFromClassPath=false`).
- **`Wiring`** holds conditions on how a class is wired. A target is `Owner#member`, the owner by
  its simple name as the call site's static receiver type, a constructor as `<init>`, and a field
  read or write, an enum constant or a method reference counts as reaching it.
  - `callFrom`, `callInOrder` and `callOnOneLine` hold what one method reaches, in which order, or
    on one source line; `callOnceFrom` holds that it reaches a target from exactly one line.
  - `neverCallFrom` holds what a method does not reach, `callOnlyFrom` that no other code of the
    class or its nested classes reaches a target, and `reachInside` that the class reaches it at all.
  - `neverOnOneLine` and `alwaysOnOneLine` pair two kinds of access on a line, across a whole class.
  - `isListed` names classes and fails when one is gone, so a rename cannot leave a rule holding
    over nothing; `isOrIsNestedIn` takes lambdas and anonymous classes along.

## What a rule can hold

A claim about who calls what, which handler exists, what is caught, or the order of calls inside one
method is a rule here, never a test reading a source file as text. A text test breaks on a
reformatted line, and Gradle reruns it only when the file is declared as a test input.

What the bytecode does not carry stays out of a rule: a literal argument, a condition, a
local's name. The rule holds the call they guard, and a behaviour test holds the value wherever one
can be built without a server.

Order is the order of the first source line that reaches each target. An access inside a lambda
counts for the method declaring the lambda, at its own line, so a call inside a scheduled task is
held as the scheduling call and the target on one line, or as the target after it and reached once.
