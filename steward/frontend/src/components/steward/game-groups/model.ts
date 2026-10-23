import type { ConfigEntry } from "@/lib/api"
import type { SectionValues } from "@/components/steward/repeatable-cards"

/** The list whose entries hold every one of `keys`, or none when the jar sends another shape. */
export function listEntry(entries: ConfigEntry[], keys: string[]): ConfigEntry | undefined {
  return entries.find((entry) => holds(entry, keys))
}

export function holds(entry: ConfigEntry, keys: string[]): boolean {
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
