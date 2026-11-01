import type { ConfigEntry } from "@/lib/api"
import type { SectionValues } from "@/components/steward/repeatable-cards"

/** The list whose entries hold every one of `keys`, or none when the jar sends another shape. */
export function listEntry(entries: ConfigEntry[], keys: readonly string[]): ConfigEntry | undefined {
  return entries.find((entry) => holds(entry, keys))
}

export function holds(entry: ConfigEntry, keys: readonly string[]): boolean {
  return entry.kind === "SECTIONS" && keys.every((key) => entry.template?.some((candidate) => candidate.key === key))
}

export function text(section: SectionValues, key: string): string {
  const value = section[key]
  return typeof value === "string" ? value : ""
}

/** A field that holds no number counts as nothing. */
export function count(section: SectionValues, key: string): number {
  const value = Number(text(section, key))
  return Number.isFinite(value) ? value : 0
}

export function sum(sections: SectionValues[], key: string): number {
  return sections.reduce((total, section) => total + count(section, key), 0)
}

/** A chance below one percent keeps two significant digits, so a rare prize does not read as nothing. */
export function chance(weight: number, total: number): string {
  if (total <= 0 || weight <= 0) return "0%"
  const percent = (weight / total) * 100
  return `${percent >= 1 ? percent.toFixed(1) : percent.toPrecision(2)}%`
}

export function field(entry: ConfigEntry, key: string): ConfigEntry | undefined {
  return entry.template?.find((candidate) => candidate.key === key)
}

/** The box keys of a platform or a region, in the order a row shows them. */
export const BOX = {
  min: ["min-x", "min-y", "min-z"],
  max: ["max-x", "max-y", "max-z"],
} as const

export const BOX_KEYS = ["world", ...BOX.min, ...BOX.max]

/** An area's dialog: corner 1 and corner 2 as X and Z, then the height as Y from and Y to. */
export const AREA = {
  corners: [
    ["min-x", "min-z"],
    ["max-x", "max-z"],
  ],
  height: ["min-y", "max-y"],
  keys: ["min-x", "min-z", "max-x", "max-z", "min-y", "max-y"],
} as const

/** An area between two corner blocks, or a single block. */
export type PlaceShape = "area" | "block"

export const BLOCK_KEYS = ["world", "x", "y", "z"] as const

/** The shape of a setting whose fields are `keys`, or none when it is not a place in a world. */
export function placeShape(keys: readonly string[]): PlaceShape | undefined {
  const holdsAll = (wanted: readonly string[]) => wanted.every((key) => keys.includes(key))
  const holdsOnly = (allowed: readonly string[]) => keys.every((key) => allowed.includes(key))
  if (holdsAll(BOX_KEYS) && holdsOnly(BOX_KEYS)) return "area"
  // A facing may come with a block; anything else, such as a board's kind, makes it more than a place.
  if (holdsAll(BLOCK_KEYS) && holdsOnly([...BLOCK_KEYS, "yaw", "pitch"])) return "block"
  return undefined
}
