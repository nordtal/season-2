# smp

`smp` is the season's Paper plugin for the SMP. It holds the farm world, the Nether, the End, the milestone track, aura, the wheel, duels and graves.

## Features

Each of the packages `duel`, `grave`, `navigate`, `npc`, `progress`, `protect`, `travel`, `welcome` and `wheel` is a feature. A feature uses no class of another feature, and `:architecture` fails the build when it does.

The features share the packages `milestone`, `state` and `aura`. The root package and `command` may use the classes of every feature.

A feature reads a player's identity from `Identities` in paper-common.

## Ports

A feature that needs something from another feature uses an interface in `port`. The feature that owns the thing implements the interface, and `SmpStart` passes it on.

## Database

Each feature has its own DAO. A DAO is a JDBI SqlObject created with `onDemand` from the plugin's one `Jdbi`. It runs off the main thread only.

A DAO belongs to a feature, not to a table. `SmpPlugin` writes the track's rows, and `progress` changes them.

## Transactions

A write of several statements is one transaction. These are a credit with its completion, aura and spins, finishing an objective by hand, an unlock with the activation of the next milestone, and the rows of a reload.

An `onDemand` DAO uses the handle that `inTransaction` holds open on the same thread. A DAO or a `PrizeSource` therefore joins the transaction without receiving a handle.

A failed payout credits no delivery, and the hand-in returns the items.

The milestone check and the announcements run after the commit. A payout writes its players in one fixed order, which prevents deadlocks.
