import { Field, type Plan } from "./place"

/**
 * Draft **i** - the lens.
 *
 * The same two branches and the same central lane as `h`, bent. Each branch bows away from the
 * middle and back, so the two of them together enclose the lane in a shape rather than flanking it
 * with two columns - and the lane widens exactly where the traffic detours need room and narrows
 * where they do not.
 *
 * <h2>Why this is the second draft and not a variation on the first</h2>
 * `h` and `i` disagree about one thing, and it is the thing Till's note was about: whether an
 * arrangement should be regular. `h` keeps two straight lanes, which is honest and dull and makes
 * every card's position predictable; `i` gives up alignment entirely - no two rows start at the
 * same x, nothing is in a column - in exchange for a picture with a silhouette. Everything else is
 * identical by construction, so what is being chosen is exactly that.
 *
 * <h2>Where the entrance sits</h2>
 * `players` is at the top centre here rather than at the corner, above the point where the two
 * branches part. The picture then reads as one thing splitting rather than two things running in
 * parallel, which is the reading `h` gives up for its straight lanes.
 *
 * <h2>On a phone</h2>
 * The bow shrinks to what 352px has room for - the lens is a suggestion at that width rather than
 * a shape - and the order is unchanged. Nothing is dropped and nothing reflows.
 */

const wide: Plan["wide"] = {
  width: 600,
  height: 790,
  junction: { x: 300, y: 650 },
  spots: [
    { id: "players", x: 300, y: 44 },

    { id: "caddy", x: 130, y: 182 },
    { id: "network-control", x: 470, y: 182 },

    // The bow: the two middle rows sit 52px further out than the rows above and below them, which
    // is what turns two lanes into two arcs. The number is not free - it is how much room the
    // detour of the three-server fan needs on the inside without reaching the database lane.
    { id: "steward-ui", x: 78, y: 320 },
    { id: "smp", x: 522, y: 320 },

    { id: "steward-worker", x: 78, y: 458 },
    { id: "hunger-games", x: 522, y: 458 },

    { id: "steward-deployer", x: 130, y: 596 },
    { id: "limbo", x: 470, y: 596 },

    // `discord-bot` stands beside `postgres` rather than up with the entrance, and that is a
    // finding rather than a preference: put at the top of the right branch it could only reach the
    // lane by sweeping across `players`, which sits on the lane at the apex of the lens. Down here
    // its one line is two centimetres long - and the position says what is true of it, which is
    // that the database is its only relationship in this picture.
    { id: "discord-bot", x: 130, y: 700 },

    { id: "postgres", x: 300, y: 730 },
  ],
  bows: {
    "steward-ui-steward-deployer": 120,
    "network-control-hunger-games": -190,
    "network-control-limbo": -200,
  },
}

const narrow: Plan["narrow"] = {
  width: 352,
  height: 880,
  junction: { x: 173, y: 752 },
  spots: [
    // The entrance loses the centre at this width: 352px has room for two cards on a row and
    // `discord-bot` has to stand somewhere that is not the lane. The lens itself survives - it is
    // the two bowed middle rows, not where `players` sits.
    { id: "players", x: 78, y: 40 },
    { id: "discord-bot", x: 268, y: 40 },

    { id: "caddy", x: 92, y: 170 },
    { id: "network-control", x: 256, y: 170 },

    { id: "steward-ui", x: 74, y: 300 },
    { id: "smp", x: 276, y: 300 },

    { id: "steward-worker", x: 74, y: 430 },
    { id: "hunger-games", x: 276, y: 430 },

    { id: "steward-deployer", x: 92, y: 560 },
    { id: "limbo", x: 256, y: 560 },

    { id: "postgres", x: 173, y: 812 },
  ],
  bows: {
    "steward-ui-steward-deployer": 115,
    "network-control-hunger-games": -115,
    "network-control-limbo": -132,
  },
}

export const LENS: Plan = { wide, narrow }

export function LensDraft() {
  return <Field plan={LENS} id="i" />
}
