import { Field, type Plan } from "./place"

/**
 * Draft **h** - the delta.
 *
 * Two branches run down opposite edges and the database lane runs between them, empty of cards for
 * its whole height, gathering every write into one trunk that ends in `postgres` at the mouth. The
 * shape is the claim: the picture has a middle, and the middle belongs to the lines.
 *
 * <h2>What it answers of Till's seven</h2>
 * The cards are all the same size and smaller (`place.ts`), the lines are curves, and the space
 * between two cards that a line has to cross is 70px rather than the 10px a grid gap was giving it.
 * The arrangement is not a grid in the sense that matters: no card is in a cell, the rows carry
 * unrelated things at the same height on purpose, and the empty lane down the middle is a decision
 * rather than a leftover.
 *
 * <h2>The three siblings, and the detour</h2>
 * `network-control` starts a player on one of three servers and `steward-ui` talks to two services.
 * Stacked in one lane, the far sibling's line would run straight through the near one; each such
 * edge therefore carries a `bow` that takes it out into the lane and back. That is where the
 * arrangement is doing work a grid cannot: the detour has somewhere to go.
 *
 * <h2>On a phone</h2>
 * The same picture, narrower: two lanes of the same order, the lane between them 60px wide,
 * everything 130px further apart vertically because the cards are the only things that can move.
 * It is 880px tall and scrolls, which a topology map may - what it must not do is scroll sideways,
 * and it does not.
 */

const wide: Plan["wide"] = {
  width: 600,
  height: 762,
  junction: { x: 300, y: 646 },
  spots: [
    // The entrance and the one service nothing routes through sit on the top row, clear of the
    // lane: `discord-bot` has no traffic edge at all, so anywhere it stands says something, and
    // beside the entrance says "it is on this network and in nobody's path", which is true.
    { id: "players", x: 92, y: 44 },
    { id: "discord-bot", x: 508, y: 44 },

    { id: "caddy", x: 92, y: 182 },
    { id: "network-control", x: 508, y: 182 },

    { id: "steward-ui", x: 92, y: 320 },
    { id: "smp", x: 508, y: 320 },

    { id: "steward-worker", x: 92, y: 458 },
    { id: "hunger-games", x: 508, y: 458 },

    { id: "steward-deployer", x: 92, y: 596 },
    { id: "limbo", x: 508, y: 596 },

    { id: "postgres", x: 300, y: 700 },
  ],
  bows: {
    "steward-ui-steward-deployer": 150,
    "network-control-hunger-games": -150,
    "network-control-limbo": -200,
  },
}

const narrow: Plan["narrow"] = {
  width: 352,
  height: 880,
  junction: { x: 176, y: 752 },
  spots: [
    { id: "players", x: 74, y: 40 },
    { id: "discord-bot", x: 278, y: 40 },

    { id: "caddy", x: 74, y: 170 },
    { id: "network-control", x: 278, y: 170 },

    { id: "steward-ui", x: 74, y: 300 },
    { id: "smp", x: 278, y: 300 },

    { id: "steward-worker", x: 74, y: 430 },
    { id: "hunger-games", x: 278, y: 430 },

    { id: "steward-deployer", x: 74, y: 560 },
    { id: "limbo", x: 278, y: 560 },

    { id: "postgres", x: 176, y: 812 },
  ],
  // Measured, not chosen. With a 76px card the detour from `network-control` past `smp` and
  // `hunger-games` down to `limbo` only clears both of them between -195 and -180; at the -140 this
  // file carried first, the curve cut the corner of `smp`. The two lanes moved 4px further apart
  // (74 and 278 rather than 78 and 268) to buy that window at all - at the old spacing every
  // negative value failed and the only clear detours went off the right edge of the canvas.
  bows: {
    "steward-ui-steward-deployer": 150,
    "network-control-hunger-games": -125,
    "network-control-limbo": -188,
  },
}

export const DELTA: Plan = { wide, narrow }

export function DeltaDraft() {
  return <Field plan={DELTA} id="h" />
}
