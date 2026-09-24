import type { Arrangement } from "./place"

/**
 * The arrangement the network view is drawn from - one arrangement, for every width.
 *
 * It began as draft **h**, chosen by Till on 2026-09-18 out of a throwaway page of letters and
 * rebuilt to his six follow-up changes from the same message. steward/121 took away the one thing
 * that was still hand-written twice: there was a `wide` table of coordinates and a `narrow` one,
 * and the drawing jumped from the second to the first at 600px. Now there is this, in lanes, and
 * `place.tsx` resolves it against whatever width the panel has.
 *
 * <h2>Three lanes and a row per thing</h2>
 * Lane 0 is the left column, lane 1 the right, lane 0.5 the middle, and what those mean in pixels
 * is `place.tsx`'s one formula. The shape is unchanged from the letter Till picked: two branches
 * run down opposite edges and the database lane runs between them.
 *
 * `y` is absolute and deliberately not stretched - the vertical is not where a wider window's extra
 * room is, and a picture that grew taller as it grew wider would push the rest of the start page
 * down for nothing. The consequence is worth stating plainly because it is a real cost of having
 * one arrangement instead of two: the drawing is **956px tall at every width**, where the old
 * `wide` was 700 and only the old `narrow` was this tall. The height is set by the two group
 * frames and by `discord-bot` under them, and the one layout that was shorter - `discord-bot`
 * between the two frames on their shared bottom line - only fits from about 600px up, which is
 * exactly the reflow this ticket exists to remove.
 *
 * <h2>Why the middle lane's cards each get a row of their own</h2>
 * At `minWidth` the three lanes overlap: a 144px card in lane 0.5 and one in lane 0 share 36px of
 * x. That is fine and is the point of a narrow canvas - but only as long as nothing in the middle
 * lane is ever level with something in a side lane. So `players`, `postgres` and `discord-bot` each
 * sit at a height no side card occupies, and the gaps between the rows are what the database feet
 * use to cross the picture. `geometry.test.ts` runs the whole collision suite at several widths
 * rather than one, which is what turns that sentence into a check.
 *
 * <h2>The six changes of 2026-09-18, and where each one lives below</h2>
 *
 * 1. **`players` at top centre** - "not between the services", so it stays above the `caddy`/`proxy`
 *    row rather than moving into the grid that row implies.
 * 2. **`discord-bot` at bottom centre** - it has no traffic edge at all (see `topology.ts`), so the
 *    middle of the bottom row is the one place that does not imply a relationship that is not there.
 * 3. **`postgres` in the middle of the picture**, both ways - the upper singles (`caddy`, `proxy`,
 *    `steward-ui`) sit above it and the two groups plus `discord-bot` below it.
 * 4. **Two groups, packed and framed** - the three Paper services on the right, the two deploy
 *    services on the left.
 * 5. **`caddy` and `proxy` share a top edge**, which is the row the two branches start from.
 * 6. **Two singles and a group on the left, one single and a group on the right.**
 *
 * <h2>The database lane</h2>
 * The junction sits at y=520, in the 52px band between `postgres`'s bottom edge and the Paper
 * group's frame - a row no card occupies at any width. Every source drops or rises in its own lane
 * and flattens onto that row; `bundle` in `wires.tsx` is the shape and says why. Three of the five
 * resolved sources are *below* the junction and two are above it, which is why the junction is
 * under `postgres` rather than over it: two feet have to come round the sink either way, and two is
 * fewer than three.
 *
 * <h2>No detours left to bow</h2>
 * A previous round needed `bows` because three separate edges left `proxy` stacked in one lane and
 * the two further siblings ran straight through the nearer ones. Collapsing those three edges into
 * one - into the group, not into three lines that happen to overlap - removed the siblings, and
 * with them the reason `bows` existed here. The arrangement below carries none at all, which is
 * itself the evidence the collapsing did its job rather than something hand-tuned away.
 */
export const PLAN: Arrangement = {
  // 372px, measured rather than chosen: at a 1024px viewport the start page's two columns and the
  // sidebar are all present at once, and the panel holding this picture is exactly that wide there
  // (2026-09-20), and the table takes over below the 640px line. Since that line moved down from
  // 768px (2026-09-24) there is one narrower place: at 640px with the column open the panel is
  // about 368px, so the picture is scaled by 0.99 there - the safety net below, not a new size.
  //
  // It is a floor and not a design size. What sets it is the 32px corridor between the two group
  // frames, which `discord-bot`'s foot runs up; go much below this and the line has single-figure
  // clearance on each side and stops reading as passing *between* them. A browser that takes a few
  // pixels for a scrollbar lands just under it and the picture is scaled by 0.98, which is the
  // safety net doing its job rather than a mode being entered.
  minWidth: 372,
  // 720px, and it is a judgement rather than a measurement: at the 820px the panel gets on a 1920px
  // screen the three lanes are 328px apart, the two traffic arrows that cross the picture are
  // longer than anything they connect, and the middle is empty. 720 is where the arrows still read
  // as arrows. Past it the drawing is centred in the room it has instead of filling it.
  maxWidth: 720,
  height: 956,
  junction: { lane: 0.5, y: 520 },
  spots: [
    { id: "players", lane: 0.5, y: 40 },

    { id: "caddy", lane: 0, y: 170 },
    { id: "proxy", lane: 1, y: 170 },

    { id: "steward-ui", lane: 0, y: 300 },

    { id: "postgres", lane: 0.5, y: 430 },

    { id: "discord-bot", lane: 0.5, y: 888 },
  ],
  groups: [
    // The three Paper servers - `proxy` is the only thing that routes to any of them, and the group
    // is what turns three arrows into one. Its frame runs 562..818.
    { id: "paper", members: ["smp", "hunger-games", "limbo"], lane: 1, y: 690 },
    // `steward-ui` calls both of these; same collapse, same reasoning, one lane over. Its frame runs
    // 642..818, so the two groups finish on the same bottom line.
    { id: "steward-ops", members: ["steward-worker", "steward-deployer"], lane: 0, y: 730 },
  ],
}
