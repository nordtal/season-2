# architecture

`:architecture:check` runs ArchUnit rules over the compiled classes of every checked module. The
rules hold the shape of the codebase and wiring that a unit test cannot reach without a running
server. The module has no production code.

| class                 | what it holds                                                                               |
| --------------------- | ------------------------------------------------------------------------------------------- |
| `ArchitectureTest`    | Dependencies of the shared modules, package cycles, clock, scheduler, migrations, settings. |
| `ProcessRulesTest`    | How a process reports that it is alive, and what it writes about a player.                  |
| `PlayerTextRulesTest` | Player texts go through the message system.                                                 |
| `FeedbackRulesTest`   | Feedback categories, and the sounds and effects of each module.                             |
| `ServerRulesTest`     | Wiring of the limbo, the Hunger Games and the bot.                                          |
| `StewardRulesTest`    | The order of an update run's steps, and a follow's heartbeat.                               |
| `ProxyRulesTest`      | The proxy's routing, countdown, move at zero and return from the standby.                   |
| `SmpRulesTest`        | The SMP's features, transactions, reload, duels, graves, portal, welcome and payout.        |

## Helpers

- `Codebase.classes()` imports the classes once for all rule classes. It skips the calling test
  where the build does not set `conventions.enforced`. A module is checked only if
  `build.gradle.kts` lists it. A library class is imported as a stub with its name only.
- `Wiring` holds the conditions on how a class is wired. A target is `Owner#member`. `Owner` is the
  simple name of the call site's static receiver type, and a constructor is `<init>`. A field read
  or write, an enum constant and a method reference each reach their target.
  - `callFrom`, `callInOrder` and `callOnOneLine` hold what one method reaches, in which order and
    on which source line. `callOnceFrom` holds that a method reaches a target from exactly one line.
  - `neverCallFrom` holds what a method does not reach. `callOnlyFrom` holds that no other code of
    the class or its nested classes reaches a target. `reachInside` holds that the class reaches it.
  - `neverOnOneLine` and `alwaysOnOneLine` pair two kinds of access on a line across a class.
  - `isListed` fails when a listed class is gone. `isOrIsNestedIn` includes lambdas and anonymous
    classes.

## Rules over bytecode

A claim about who calls what, which handler exists, what is caught or the order of calls inside a
method is a rule, not a test that reads a source file as text.

A rule cannot hold a literal argument, a condition or a local's name. It holds the call they guard.

The order of two targets is the order of the first source line that reaches each. An access inside
a lambda counts for the method that declares the lambda, at the lambda's line.
