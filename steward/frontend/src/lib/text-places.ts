import type { MessageEntry, MessageSurface } from "@/lib/api"

/** Where the Texts page lists a text, and the pills and previews that say where it appears. */

/** Every place a text can be shown, in order, with where it is, as `/api/messages` names them. */
export type Places = Record<string, MessageSurface>

/** A group of the Texts page: where a text appears, or the building blocks every other text is made of. */
export type TextGroup = MessageSurface | "VALUES"

/** The groups in the order the page lists them. */
export const TEXT_GROUPS: readonly TextGroup[] = ["GAME", "DISCORD", "STEWARD", "VALUES"]

/** The bundle of the words other texts are built from, `Messages.VALUES` on the server. */
const VALUES = "values"

/** Whether a text is a building block, a word other texts put in, rather than a text of its own place. */
export function isBuildingBlock(entry: MessageEntry): boolean {
  return entry.bundle === VALUES
}

/** The group a text is listed in: the building blocks apart, every other by where its first place is. */
export function groupOf(entry: MessageEntry, places: Places): TextGroup {
  if (isBuildingBlock(entry)) return "VALUES"
  const first = entry.shown[0]
  const surface = first === undefined ? undefined : places[first]
  if (surface) return surface
  return entry.format === "DISCORD_MARKDOWN" ? "DISCORD" : entry.format === "PLAIN" ? "STEWARD" : "GAME"
}

/** One pill of a text: a place it is shown, or a whole surface when it is shown in every place there. */
export type Pill = { kind: "place"; place: string } | { kind: "surface"; surface: MessageSurface }

/** A text's pills in the order of its places, each surface it fills once instead of every one of its places. */
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
 * The places a text is previewed as, one preview each; `undefined` is the neutral one.
 *
 * A building block is one neutral preview, since it only ever appears inside another text.
 */
export function previewPlacesOf(entry: MessageEntry): Array<string | undefined> {
  return isBuildingBlock(entry) || entry.shown.length === 0 ? [undefined] : entry.shown
}
