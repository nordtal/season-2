import type { Plan } from "./place"

/**
 * The arrangement the network view is drawn from - draft **h**, chosen by Till on 2026-09-18 and
 * rebuilt to his six follow-up changes from the same message.
 *
 * It was one of two letters on a throwaway page until it was chosen; that page, the other letter and
 * the route that served them are deleted, which steward/81 said from the start would happen to
 * whichever drafts lost. This file is what is left of that round, and the letter is kept in the
 * sentence above only because every note in this ticket calls the arrangement by it.
 *
 * The shape stays what it was: two branches run down opposite edges and the database lane runs
 * between them. What changes is everything about how the branches are populated and where the
 * lane's own traffic sits, because Till's six points are all about *this* arrangement rather than
 * about the shared node, the curve or the colour - those were settled in the previous round.
 *
 * <h2>The six changes, and where each one lives below</h2>
 *
 * 1. **`players` moves to top centre**, at the same height it already had (`y` unchanged from the
 *    previous round) - "not between the services", so it stays above the `caddy`/`proxy`
 *    row rather than moving into the grid the row implies.
 * 2. **`discord-bot` moves to bottom centre, between the two groups** - it has no traffic edge at
 *    all (see `topology.ts`), so the middle of the bottom row is the one place that does not imply
 *    a relationship that is not there, the same reasoning the old top-right corner used before this
 *    round moved it.
 * 3. **`postgres` moves to the middle of the picture**, both ways - `keeps postgres in the middle
 *    third of the canvas` in `geometry.test.ts` holds it there. It used to sit at the very bottom;
 *    now the upper singles (`caddy`, `proxy`, `steward-ui`) sit above it and the two
 *    groups plus `discord-bot` sit below it, which is what "the middle of the action" means once
 *    two things happen on both sides of a card rather than one thing flowing into it.
 * 4. **The three Paper services, and separately `steward-worker`/`steward-deployer`, pack into one
 *    bordered group each** (`GroupSpec` in `place.tsx`) - `gap-1` (4px, `GROUP_GAP`) between
 *    members, one grey frame (`GROUP_PADDING` on every side) around the stack.
 * 5. **One arrow into each group, one line to postgres out of each group** - not because the edge
 *    list changed (it did not: `topology.ts` still lists `proxy -> smp`, `-> hunger-games`
 *    and `-> limbo` as three edges), but because `wires.tsx`'s `resolveEndpoint` folds a grouped
 *    member's end of an edge into its group's own frame, and three edges that resolve to the same
 *    pair draw once.
 * 6. **The alignment**: two singles and a group on the left (`caddy`, `steward-ui`,
 *    `steward-ops`), one single and a group on the right (`proxy`, `paper`) - and three
 *    things share the picture's bottom line: both groups and `discord-bot`. `caddy` and
 *    `proxy` share the top line the same way.
 *
 * <h2>Why the junction sits below `postgres`, not above it</h2>
 * The database fan used to gather above the sink, because the sink used to be the lowest thing on
 * the page. Now three of five sources - both groups and `discord-bot` - sit *below* `postgres`,
 * and only two (`proxy`, `steward-ui`) sit above it. Putting the junction above `postgres`
 * would force `discord-bot`'s foot, which shares `postgres`'s own x, to run in a dead straight
 * vertical line through the middle of the card to get there. Putting the junction **below**
 * `postgres` instead - `wide`'s sits at `(300, 450)`, 62px under the sink - means the three
 * below-postgres sources reach it without ever entering postgres's own row, and the trunk
 * (`bundle`'s `M junction.x junction.y L junction.x sink.y`) travels from the junction up through
 * the space below the card and then behind it, which paints as "arrives at the bottom edge" rather
 * than "cuts through the middle" because the card (`z-10`) sits over the SVG (`z-0`) for the part of
 * the line that would otherwise show through. The two above-postgres sources still pass near the
 * card on their way down to the junction, the same way a source has always been allowed to pass
 * near the sink it is heading for - `postgres` is excluded from every foot's own crossing check for
 * exactly that reason, in both this round and the one before it.
 *
 * <h2>No detours left to bow</h2>
 * The previous round needed `bows` because three separate edges left `proxy` stacked in
 * one lane, and the two further siblings ran straight through the nearer ones. Collapsing those
 * three edges into one - into the group, not into three lines that happen to overlap - removes the
 * siblings, and with them the reason `bows` existed on this draft. Both arrangements below carry no
 * `bows` at all, which is itself the evidence the collapsing did its job rather than something
 * hand-tuned away.
 *
 * <h2>On a phone</h2>
 * The columns move from 92/508 (416px apart) to 84/268 (184px apart), which is what buys two
 * 144px-wide columns inside a 352px canvas at all - but nothing shares a row across that gap: every
 * centre-lane card (`players`, `postgres`) sits at a height no side card occupies, so the columns
 * can overlap in x without ever overlapping in the picture. `postgres` in particular moved off the
 * height it would have shared with `steward-ui` at the wider spacing (both would have landed with
 * overlapping x *and* y) down to its own row between the singles and the groups.
 *
 * <h2>`discord-bot` does not fit in the sixth change's bottom row, and moves instead of squeezing</h2>
 * At `wide`, change six puts `discord-bot` between the two groups on their shared bottom line, and
 * there is room: 600px across two 164px-wide group frames and one 144px card. At 352px there is
 * not - the two group frames alone already span from x=2 to x=350, `GROUP_PADDING` having widened
 * each one past its members' own footprint, and nothing is left between them for a third box. Rather
 * than shrink a frame Till asked to be one size, `discord-bot` drops below both groups on `narrow`,
 * still centred, still the lowest card in the picture - a real second arrangement making a different
 * call, not the wide one measured wrong. `geometry.test.ts` holds this arrangement to that claim
 * rather than to the wide one's "same bottom line", which cannot hold here.
 *
 * The canvas is 940px tall and scrolls, which a topology map may; it is 352px wide and does not,
 * which it must not.
 */

const wide: Plan["wide"] = {
  width: 600,
  height: 700,
  junction: { x: 300, y: 450 },
  spots: [
    { id: "players", x: 300, y: 44 },

    { id: "caddy", x: 92, y: 182 },
    { id: "proxy", x: 508, y: 182 },

    { id: "steward-ui", x: 92, y: 320 },

    { id: "postgres", x: 300, y: 350 },

    { id: "discord-bot", x: 300, y: 622 },
  ],
  groups: [
    // The three Paper servers - `proxy` is the only thing that routes to any of them,
    // and the group is what turns three arrows into one.
    { id: "paper", members: ["smp", "hunger-games", "limbo"], x: 508, y: 532 },
    // `steward-ui` calls both of these; same collapse, same reasoning, one column over.
    { id: "steward-ops", members: ["steward-worker", "steward-deployer"], x: 92, y: 572 },
  ],
}

const narrow: Plan["narrow"] = {
  width: 352,
  height: 940,
  junction: { x: 176, y: 510 },
  spots: [
    { id: "players", x: 176, y: 40 },

    { id: "caddy", x: 84, y: 170 },
    { id: "proxy", x: 268, y: 170 },

    { id: "steward-ui", x: 84, y: 300 },

    // Moved well clear of `steward-ui`'s row - at the narrower 84/268 columns the two would share
    // both an x-range and a y-range at the old spacing, which is an overlap the wide arrangement
    // never has to worry about because its columns sit 416px apart instead of 184.
    { id: "postgres", x: 176, y: 430 },

    // Below both groups, not between them - see the class comment for why change six's bottom row
    // does not fit at this width. 30px clear of the groups' own bottom edge (800), which is enough
    // that `geometry.test.ts`'s general overlap check has margin rather than landing on a seam.
    { id: "discord-bot", x: 176, y: 868 },
  ],
  groups: [
    { id: "paper", members: ["smp", "hunger-games", "limbo"], x: 268, y: 672 },
    { id: "steward-ops", members: ["steward-worker", "steward-deployer"], x: 84, y: 712 },
  ],
}

export const PLAN: Plan = { wide, narrow }
