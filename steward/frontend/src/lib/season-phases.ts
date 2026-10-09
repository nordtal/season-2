/** The five phases of `eu.nordtal.season.common.SeasonPhase`; `season-phases.test.ts` holds this list against it. */
export type SeasonPhaseName = "PRE_LAUNCH" | "PRE_EVENT" | "START_EVENT" | "SMP" | "MAINTENANCE"

/** A phase and where a player lands in it; its name is `steward.season.phase`. */
export interface SeasonPhaseInfo {
  name: SeasonPhaseName
  /** Where a player lands, or an em dash while nobody may join at all. */
  where: string
}

export const SEASON_PHASES: SeasonPhaseInfo[] = [
  { name: "PRE_LAUNCH", where: "\u2014" },
  { name: "PRE_EVENT", where: "hunger-games" },
  { name: "START_EVENT", where: "hunger-games" },
  { name: "SMP", where: "smp" },
  { name: "MAINTENANCE", where: "limbo" },
]
