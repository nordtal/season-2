/**
 * The five names `eu.nordtal.s2.common.SeasonPhase` knows, and what each one means - in one place.
 *
 * steward/90: this used to be two lists that agreed with neither each other nor the backend.
 * `pages/season.tsx` carried the five real names; `pages/overview.tsx` carried a second list with
 * two invented phases (`EVENT`, `ENDED`) and three of the five real ones missing, so a phase that
 * only the second list did not know reached the screen as the bare enum constant -
 * `PRE_LAUNCH`, versals and an underscore, in an interface that otherwise speaks in sentences.
 *
 * There is now exactly one list, and both pages read it. `season-phases.test.ts` reads
 * `SeasonPhase.java` itself and holds this list against it in both directions: every real name has
 * a label here, and - the direction that actually would have caught the defect - nothing here names
 * a phase the backend does not have.
 */
export type SeasonPhaseName = "PRE_LAUNCH" | "PRE_EVENT" | "START_EVENT" | "SMP" | "MAINTENANCE"

export interface SeasonPhaseInfo {
  name: SeasonPhaseName
  /** A sentence fragment fit for a page title or a tile - never the constant itself. */
  label: string
  /** The admission rule, in the words of `SeasonPhase` in `:common`. */
  who: string
  /** Where a player lands, or "—" while nobody may join at all. */
  where: string
}

export const SEASON_PHASES: SeasonPhaseInfo[] = [
  {
    name: "PRE_LAUNCH",
    label: "Before launch",
    who: "Admins only. Everybody else sees a countdown to the launch date.",
    where: "—",
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

/**
 * The sentence for a phase value read off the wire.
 *
 * Falls back to the raw value rather than throwing - a value the backend sends and this list does
 * not yet know about must still render as *something*, and `season-phases.test.ts` is what keeps
 * that fallback from ever actually being needed, not a runtime check here.
 */
export function seasonPhaseLabel(phase: string): string {
  return SEASON_PHASES.find((candidate) => candidate.name === phase)?.label ?? phase
}
