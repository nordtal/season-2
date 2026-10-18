import type { Arrangement } from "./place"

/**
 * The one arrangement of the network view, in lanes that `place.tsx` resolves against any width.
 *
 * The cards of the middle lane each get a row that no side card uses, because at `minWidth` the lanes overlap.
 */
export const PLAN: Arrangement = {
  /** The panel's width at a 1024px viewport. Narrower panels scale the picture down slightly. */
  minWidth: 372,
  /**
   * Past this width the three lanes are so far apart that the arrows outgrow the cards, so the picture is centred.
   */
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
    /** The Paper servers, which only `proxy` routes to. Their frame runs from 562 to 818. */
    { id: "paper", members: ["smp", "hunger-games", "limbo"], lane: 1, y: 690 },
    /**
     * What only `steward` calls. Its frame runs from 642 to 818 and ends on the Paper group's bottom line.
     */
    { id: "steward-ops", members: ["steward-agent", "steward-bunq"], lane: 0, y: 730 },
  ],
}
