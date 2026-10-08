import type { ConfigEntry } from "@/lib/api"
import { gameKey, isGameReference } from "@/lib/references"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import { blankSection, sectionsOf, withValue } from "@/components/steward/repeatable-cards"

/**
 * The milestones group as its editor reads it: the keys this layout places, and nothing it means.
 *
 * Labels, controls, choices and references still come from the schema; a key the layout does not place is edited
 * in the objective's sheet or the milestone's fields like any other.
 */
export const KEY = {
  milestones: "milestones",
  id: "key",
  type: "type",
  objectives: "objectives",
  target: "target",
  auraBudget: "aura-budget",
  spinBudget: "spin-budget",
} as const

/** The milestone fields drawn as small marks in a collapsed row, each by its schema label. */
export const MARKED = ["unlocks", "border-diameter", "admin-unlocked"] as const

/** An objective's budgets, which a milestone and the whole track show as sums. */
export const BUDGETS = [KEY.auraBudget, KEY.spinBudget] as const

export type Budget = (typeof BUDGETS)[number]

/** Every budget summed over the objectives of `milestones`. */
export function budgetSums(milestones: SectionValues[]): Record<Budget, number> {
  const sums: Record<Budget, number> = {
    [KEY.auraBudget]: 0,
    [KEY.spinBudget]: 0,
  }
  for (const objective of milestones.flatMap(objectivesOf)) {
    for (const key of BUDGETS) {
      const value = Number(text(objective, key))
      if (Number.isFinite(value)) sums[key] += value
    }
  }
  return sums
}

/** The schema of the track: the milestone's fields and an objective's, with the track's own entry. */
export type TrackSchema = {
  entry: ConfigEntry
  milestone: ConfigEntry[]
  objective: ConfigEntry[]
}

export function trackSchema(entries: ConfigEntry[]): TrackSchema | null {
  const entry = entries.find((candidate) => candidate.key === KEY.milestones && candidate.kind === "SECTIONS")
  const milestone = entry?.template ?? []
  const objective = milestone.find((field) => field.key === KEY.objectives)?.template ?? []
  return entry && milestone.length > 0 && objective.length > 0 ? { entry, milestone, objective } : null
}

export function text(section: SectionValues | undefined, key: string): string {
  const value = section?.[key]
  return typeof value === "string" ? value : ""
}

export function strings(section: SectionValues | undefined, key: string): string[] {
  const value = section?.[key]
  return Array.isArray(value) && value.every((item) => typeof item === "string") ? value : []
}

export function objectivesOf(section: SectionValues | undefined): SectionValues[] {
  return sectionsOf(section?.[KEY.objectives])
}

/** `list` with the element at `from` moved to `to`, or unchanged where `to` is outside. */
export function moved<T>(list: T[], from: number, to: number): T[] {
  if (to < 0 || to >= list.length || from === to) return list
  const out = [...list]
  const [item] = out.splice(from, 1)
  out.splice(to, 0, item)
  return out
}

/**
 * `section` with every value that names something in the game in its namespaced form, at every depth.
 *
 * The game reads `OAK_LOG` and `minecraft:oak_log` alike; a track that is saved writes only the second.
 */
export function namespaced(fields: ConfigEntry[], section: SectionValues): SectionValues {
  const out: SectionValues = { ...section }
  for (const field of fields) {
    const value = section[field.key]
    if (field.kind === "SECTIONS" && field.template) {
      const template = field.template
      out[field.key] = sectionsOf(value).map((nested) => namespaced(template, nested))
    } else if (field.refers && isGameReference(field.refers)) {
      if (typeof value === "string") out[field.key] = gameKey(value)
      else if (Array.isArray(value) && value.every((item) => typeof item === "string"))
        out[field.key] = value.map(gameKey)
    }
  }
  return out
}

/** The track's edits, each answering the whole list in its namespaced form, which is what the draft holds. */
export function trackEdits(track: SectionValues[], schema: TrackSchema, onChange: (track: SectionValues[]) => void) {
  const save = (next: SectionValues[]) => onChange(next.map((milestone) => namespaced(schema.milestone, milestone)))
  const replace = (index: number, next: SectionValues) => save(track.map((m, at) => (at === index ? next : m)))
  return {
    setField: (index: number, key: string, value: string | string[]) =>
      replace(index, withValue(schema.milestone, track[index], key, value)),
    move: (index: number, to: number) => save(moved(track, index, to)),
    remove: (index: number) => save(track.filter((_, at) => at !== index)),
    add: () => save([...track, blankSection(schema.milestone)]),
    setObjective: (index: number, at: number, next: SectionValues) => {
      const objectives = objectivesOf(track[index]).map((o, i) => (i === at ? next : o))
      replace(index, { ...track[index], [KEY.objectives]: objectives })
    },
    addObjective: (index: number) => {
      const objectives = [...objectivesOf(track[index]), blankSection(schema.objective)]
      replace(index, { ...track[index], [KEY.objectives]: objectives })
      return objectives.length - 1
    },
    removeObjective: (index: number, at: number) => {
      const objectives = objectivesOf(track[index]).filter((_, i) => i !== at)
      replace(index, { ...track[index], [KEY.objectives]: objectives })
    },
  }
}

export type TrackEdits = ReturnType<typeof trackEdits>
