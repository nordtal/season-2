/** The five phases of `eu.nordtal.s2.common.SeasonPhase`; `season-phases.test.ts` holds this list against it. */
export type SeasonPhaseName = "PRE_LAUNCH" | "PRE_EVENT" | "START_EVENT" | "SMP" | "MAINTENANCE"

export interface SeasonPhaseInfo {
  name: SeasonPhaseName
  /** A sentence fragment for a page title or a tile, never the constant itself. */
  label: string
  /** The admission rule, in the words of `SeasonPhase` in `:common`. */
  who: string
  /** Where a player lands, or an em dash while nobody may join at all. */
  where: string
}

export const SEASON_PHASES: SeasonPhaseInfo[] = [
  {
    name: "PRE_LAUNCH",
    label: "Before launch",
    who: "Admins only. Everybody else sees a countdown to the launch date.",
    where: "\u2014",
  },
  {
    name: "PRE_EVENT",
    label: "Before the event",
    who: "Every linked, unbanned Discord member. No contribution period is needed.",
    where: "hunger-games",
  },
  {
    name: "START_EVENT",
    label: "Event start",
    who: "Same as before the event.",
    where: "hunger-games",
  },
  {
    name: "SMP",
    label: "Season running",
    who: "Linked, unbanned - and the only phase that also requires current access.",
    where: "smp",
  },
  {
    name: "MAINTENANCE",
    label: "Maintenance",
    who: "Everybody reaches the network and stays in limbo; admins are not redirected.",
    where: "limbo",
  },
]

/** The sentence for a phase value off the wire, or the raw value when this list does not know it. */
export function seasonPhaseLabel(phase: string): string {
  return SEASON_PHASES.find((candidate) => candidate.name === phase)?.label ?? phase
}
