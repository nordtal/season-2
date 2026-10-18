import type {
  DocumentDeclaration,
  DocumentItem,
  DocumentLine,
  DocumentPart,
  DocumentPartType,
  DocumentSlot,
} from "@/lib/api"
import type { DocumentText } from "@/lib/documents"
import { ENGLISH } from "@/lib/message-text"

/** Where a part sits: its index at each depth of `parts`. */
export type PartPath = number[]

/** Returns `parts` with the part at `path` replaced by what `change` returns, or removed when it returns nothing. */
export function changePart(
  parts: readonly DocumentPart[],
  path: PartPath,
  change: (part: DocumentPart) => DocumentPart | undefined,
): DocumentPart[] {
  const [at, ...rest] = path
  return parts.flatMap((part, index) => {
    if (index !== at) return [part]
    if (rest.length > 0) return [{ ...part, parts: changePart(part.parts ?? [], rest, change) }]
    const changed = change(part)
    return changed ? [changed] : []
  })
}

/** Returns `parts` with the part at `path` moved one place up or down among its siblings. */
export function movePart(parts: readonly DocumentPart[], path: PartPath, by: -1 | 1): DocumentPart[] {
  const parent = path.slice(0, -1)
  const at = path[path.length - 1]
  const swap = (siblings: readonly DocumentPart[]) => moved(siblings, at, by)
  if (parent.length === 0) return swap(parts)
  return changePart(parts, parent, (part) => ({ ...part, parts: swap(part.parts ?? []) }))
}

export function moved<T>(list: readonly T[], at: number, by: -1 | 1): T[] {
  const to = at + by
  if (to < 0 || to >= list.length) return [...list]
  const next = [...list]
  ;[next[at], next[to]] = [next[to], next[at]]
  return next
}

/** Returns `parts` with `added` appended inside the part at `path`, or at the top for an empty path. */
export function addPart(parts: readonly DocumentPart[], path: PartPath, added: DocumentPart): DocumentPart[] {
  if (path.length === 0) return [...parts, added]
  return changePart(parts, path, (part) => ({ ...part, parts: [...(part.parts ?? []), added] }))
}

/** Returns every slot id the part places, its inner parts included. */
export function slotsOf(part: DocumentPart): string[] {
  return [
    ...(part.slot ? [part.slot] : []),
    ...(part.button?.slot ? [part.button.slot] : []),
    ...(part.items ?? []).flatMap((item) => (item.slot ? [item.slot] : [])),
    ...(part.lines ?? []).flatMap((line) => (line.list ? [line.list] : [])),
    ...(part.parts ?? []).flatMap(slotsOf),
  ]
}

/** Returns whether removing what places these slots would leave a required slot unplaced. */
export function holdsRequired(declaration: DocumentDeclaration, slots: readonly string[]): boolean {
  return slots.some((id) => declaration.slots.find((slot) => slot.id === id)?.required === true)
}

/** Returns the declared slots no part places, which the editor offers to add. */
export function unplacedSlots(declaration: DocumentDeclaration, parts: readonly DocumentPart[]): DocumentSlot[] {
  const placed = new Set(parts.flatMap(slotsOf))
  return declaration.slots.filter((slot) => !placed.has(slot.id))
}

function inEnglish(english: string): DocumentText {
  return { [ENGLISH]: english }
}

/** A new part of `type`, as the editor adds it before the admin fills it. */
export function newPart(type: Exclude<DocumentPartType, "LANGUAGES">): DocumentPart {
  switch (type) {
    case "CONTAINER":
      return { type, parts: [newPart("TEXT")] }
    case "TEXT":
      return { type, lines: [{ text: inEnglish("") }] }
    case "SECTION":
      return { type, parts: [newPart("TEXT")], thumbnail: "" }
    case "SEPARATOR":
      return { type }
    case "GALLERY":
      return { type, media: [""] }
    case "FILE":
      return { type, media: [""] }
    case "ROW":
      return { type, items: [newLink()] }
    default: {
      const exhaustive: never = type
      throw new Error(`unreachable part type: ${String(exhaustive)}`)
    }
  }
}

export function newLink(): DocumentItem {
  return { link: "https://", label: inEnglish("") }
}

/** The part that places a slot the code fills, labelled with the slot's id until the admin writes its words. */
export function slotPart(slot: DocumentSlot): DocumentPart {
  switch (slot.type) {
    case "BUTTON":
      return { type: "ROW", items: [{ slot: slot.id, label: inEnglish(slot.id) }] }
    case "SELECT":
      return {
        type: "ROW",
        items: [
          {
            slot: slot.id,
            placeholder: inEnglish(slot.id),
            ...(slot.values && Object.keys(slot.values).length > 0 ? { option: inEnglish("") } : {}),
          },
        ],
      }
    case "INPUT":
      return { type: "ROW", items: [{ slot: slot.id, label: inEnglish(slot.id) }] }
    case "LIST":
      return { type: "TEXT", lines: [{ list: slot.id, text: inEnglish("") }] }
    case "LANGUAGES":
      return { type: "LANGUAGES", slot: slot.id, parts: [newPart("TEXT")] }
    default: {
      const exhaustive: never = slot.type
      throw new Error(`unreachable slot type: ${String(exhaustive)}`)
    }
  }
}

/** A condition as the editor shows it: always, a value set or not set, or a value equal to a constant. */
export type When =
  | { mode: "ALWAYS" }
  | { mode: "SET" | "UNSET"; value: string }
  | { mode: "EQUALS"; value: string; constant: string }

/** Reads `when` as `Condition` writes it: `value`, `!value` or `value=constant`. */
export function readWhen(when: string | undefined): When {
  if (!when) return { mode: "ALWAYS" }
  if (when.startsWith("!")) return { mode: "UNSET", value: when.slice(1) }
  const equals = when.indexOf("=")
  if (equals >= 0) return { mode: "EQUALS", value: when.slice(0, equals), constant: when.slice(equals + 1) }
  return { mode: "SET", value: when }
}

export function writeWhen(when: When): string | undefined {
  switch (when.mode) {
    case "ALWAYS":
      return undefined
    case "SET":
      return when.value
    case "UNSET":
      return `!${when.value}`
    case "EQUALS":
      return `${when.value}=${when.constant}`
    default: {
      const exhaustive: never = when
      throw new Error(`unreachable condition: ${JSON.stringify(exhaustive)}`)
    }
  }
}

/** Returns the versions of a text in `language`, an empty one for none. */
export function versionsOf(text: DocumentText | undefined, language: string): string[] {
  const written = text?.[language]
  if (written === undefined) return [""]
  return typeof written === "string" ? [written] : written
}

/** Returns the text with the versions of `language` replaced, one version as a plain string. */
export function withVersions(text: DocumentText | undefined, language: string, versions: string[]): DocumentText {
  return { ...text, [language]: versions.length === 1 ? versions[0] : versions }
}

/** Inserts `{name}` into `source` at `at`, the end when the field has no caret. */
export function insertValue(source: string, name: string, at: number | null): string {
  const index = at ?? source.length
  return `${source.slice(0, index)}{${name}}${source.slice(index)}`
}

export function withLine(part: DocumentPart, index: number, line: DocumentLine | undefined): DocumentPart {
  const lines = [...(part.lines ?? [])]
  if (line) lines[index] = line
  else lines.splice(index, 1)
  return { ...part, lines }
}

export function withItem(part: DocumentPart, index: number, item: DocumentItem | undefined): DocumentPart {
  const items = [...(part.items ?? [])]
  if (item) items[index] = item
  else items.splice(index, 1)
  return { ...part, items }
}
