import type { MessageEntry, MessageSurface } from "@/lib/api"

/**
 * Where the Translations page lists a text, and the pills and previews that say where it appears.
 */

export type Places = Record<string, MessageSurface>

/**
 * A group of the Translations page: a surface where a text appears, or the building blocks that every other text is made of.
 */
export type TextGroup = MessageSurface | "VALUES"

export const TEXT_GROUPS: readonly TextGroup[] = ["GAME", "DISCORD", "STEWARD", "VALUES"]

/**
 * The bundles of the words other texts are built from, `Messages.VALUES` and `Messages.TERMS` on the server.
 */
const BUILDING_BLOCKS = new Set(["values", "terms"])

/** Returns whether a text is a building block, a word other texts put in. */
export function isBuildingBlock(entry: MessageEntry): boolean {
  return BUILDING_BLOCKS.has(entry.bundle)
}

/**
 * Returns the group a text is listed in. Building blocks have their own. Every other text goes by its first place.
 */
export function groupOf(entry: MessageEntry, places: Places): TextGroup {
  if (isBuildingBlock(entry)) return "VALUES"
  const first = entry.shown[0]
  const surface = first === undefined ? undefined : places[first]
  if (surface) return surface
  return entry.format === "DISCORD_MARKDOWN" ? "DISCORD" : entry.format === "PLAIN" ? "STEWARD" : "GAME"
}

export type Pill = { kind: "place"; place: string } | { kind: "surface"; surface: MessageSurface }

/**
 * Returns a text's pills in the order of its places. A surface the text fills in full is one pill.
 */
export function pillsOf(entry: MessageEntry, places: Places): Pill[] {
  const shown = new Set(entry.shown)
  const filled = new Set<MessageSurface>()
  for (const surface of new Set(Object.values(places))) {
    const all = Object.keys(places).filter((place) => places[place] === surface)
    if (all.length > 1 && all.every((place) => shown.has(place))) filled.add(surface)
  }
  const pills: Pill[] = []
  const drawn = new Set<MessageSurface>()
  for (const place of entry.shown) {
    const surface = places[place]
    if (surface === undefined || !filled.has(surface)) {
      pills.push({ kind: "place", place })
    } else if (!drawn.has(surface)) {
      drawn.add(surface)
      pills.push({ kind: "surface", surface })
    }
  }
  return pills
}

/**
 * Returns the places a text is previewed as, one preview each. `undefined` is the neutral one, which a building block has alone because it only appears inside another text.
 */
export function previewPlacesOf(entry: MessageEntry): Array<string | undefined> {
  return isBuildingBlock(entry) || entry.shown.length === 0 ? [undefined] : entry.shown
}
