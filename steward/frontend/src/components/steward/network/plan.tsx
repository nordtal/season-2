import type { Arrangement } from "./place"

/**
 * The one arrangement of the network view, in lanes that `place.tsx` resolves against any width.
 *
 * The middle lane's cards each get a row no side card uses, since at `minWidth` the lanes overlap.
 */
export const PLAN: Arrangement = {
  /** The panel's width at a 1024px viewport; narrower panels scale the picture down slightly. */
  minWidth: 372,
  /** Past this the three lanes sit so far apart that the arrows outgrow the cards, so the picture is centred. */
  maxWidth: 720,
  height: 956,
  junction: { lane: 0.5, y: 520 },
  spots: [
    { id: "players", lane: 0.5, y: 40 },

    { id: "caddy", lane: 0, y: 170 },
    { id: "proxy", lane: 1, y: 170 },

    { id: "steward", lane: 0, y: 300 },

    { id: "postgres", lane: 0.5, y: 430 },

    { id: "discord-bot", lane: 0.5, y: 888 },
  ],
  groups: [
    /** The Paper servers, which only `proxy` routes to; its frame runs 562..818. */
    { id: "paper", members: ["smp", "hunger-games", "limbo"], lane: 1, y: 690 },
    /** What only `steward` calls; its frame runs 722..818, ending on the Paper group's bottom line. */
    { id: "steward-ops", members: ["steward-deployer"], lane: 0, y: 770 },
  ],
}
