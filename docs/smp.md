# The SMP — not yet designed

Placeholder. The SMP is the phase season 2 spends most of its life in, and **its concept has not
been worked through yet**. Do not implement `smp-farm-world` from this file; it records only what
other decisions have already committed us to.

## What is already committed

- **Access is required from the `SMP` phase onward** — that is the whole point of the phase model
  ([season-phases.md](season-phases.md)) and of the access system
  ([access-system.md](access-system.md)).
- **The farm world is reset periodically.** During a reset **every** player — including AFK ones —
  is teleported into the [`limbo`](architecture.md#modules) waiting world: dark, empty, with a title
  telling them what they are waiting for, in their language. That is one of the reasons `limbo`
  exists as a general waiting room rather than a pack-install server.
- **Aura points exist.** The hunger games winner starts the SMP with extra aura points while
  everyone else starts at zero, plus one or two special items. Both must be settable through
  configuration or a command rather than compiled in. What aura points *are*, what they do and how
  they are earned is **undesigned**.
- Season 1's `nordtal-smp` is **archive and source material**, not something to port. Nothing is
  ever migrated between seasons.

## What has to be worked through

- The aura system in full: what it measures, how it is earned and lost, what it grants, where it is
  stored, whether it is visible to others.
- Farm world lifecycle: which worlds reset, how often, how it is announced, what is preserved, and
  how the reset interacts with the waiting room.
- Preserved areas, world border and map size, BlueMap or its successor.
- Whether the season 1 bossbar UI has an heir, and what it shows.
- Nametags: `papermc-display-tags` runs on this network and ships from its own repository — what it
  displays in season 2 is undecided, and the resource pack still carries season 1's
  settler/citizen/knight/lord role tags, which no longer exist.

Work this through with the grilling skill the same way the access system and the hunger games were,
and replace this file with the result.
