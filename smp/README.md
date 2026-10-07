# smp

The season's Paper plugin: Nordtal, the farm world, the Nether and the End, the milestone track, aura, the
wheel, duels and graves. This file holds how its features are cut apart; what each one does is in its code.

- **A feature is a package and knows no other feature.** `duel`, `grave`, `navigate`, `npc`, `progress`,
  `protect`, `travel`, `welcome` and `wheel` each own their listeners, menus and rows. What they share sits
  beside them: the track (`milestone`, read live through `state`'s `SeasonState`, which the signal hub keeps
  current), the aura book (`aura`), the surfaces, the settings and the worlds. The root package and
  `command` wire the features together and may name any of them. `:architecture` refuses a class of one
  feature reaching a class of another.
- **What one feature needs of another is a port** in `port`, implemented by the owner and handed over in
  `SmpStart`: `PrizeSource` (the grant of the extra spins an objective's spin budget pays), `Contributions`
  (progress's credit and a player's own share with its spins so far, which the NPC's hand-in uses) and `Arenas` (whether a death is a duel's, which graves ask). Who a player is comes from
  paper-common's `Identities`, never from a feature.
- **Each feature has its own DAO** (`TrackDao`, `ProgressDao`, `AuraDao`, `GraveDao`, `PlaceDao`,
  `SpinDao`, `WelcomeDao`), on-demand JDBI SqlObjects over the plugin's one `Jdbi`, called off the main
  thread only. A DAO is per feature, not per table: the track's rows are written by the track's lifecycle
  in `SmpPlugin` and moved by `progress`.
- **A write of several statements is one transaction**: a credit with the completion, the aura and the
  spins it pays; finishing an objective by hand; an unlock with the next milestone's activation; a reload's
  rows. JDBI hands an on-demand DAO the handle its thread holds open in `inTransaction`, so every DAO and
  the `PrizeSource` join without being passed a handle, which is what lets a port take part in a transaction
  it knows nothing of. A failed payout therefore leaves no delivery credited, and the hand-in gives the items
  back with nothing kept. The milestone check runs after the commit, so two objectives finished at once each
  see the other; the announcements wait for the commit too, and a payout books its players in one order so
  two in flight never deadlock.
