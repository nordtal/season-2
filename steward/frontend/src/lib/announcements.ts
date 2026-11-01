import type { AnnouncementView, Document, DocumentPart, DocumentView } from "@/lib/api"
import type { AnnouncementTargets } from "@/lib/announcement-targets"
import { ENGLISH, type Language } from "@/lib/message-text"

/** The bot's document every announcement starts from and is drawn and checked as. */
export const TEMPLATE = { namespace: "discord", name: "announcement" }

export function templateOf(documents: DocumentView[] | undefined): DocumentView | undefined {
  return documents?.find(
    (view) => view.declaration.namespace === TEMPLATE.namespace && view.declaration.name === TEMPLATE.name,
  )
}

/** The template as the editor shows an announcement: its own document in place of the admins' one. */
export function viewFor(template: DocumentView, document: Document): DocumentView {
  return { ...template, packaged: document, written: undefined, outdated: false, adapted: [] }
}

/** The channel each language of a new announcement goes out in: the default announcement channel, where one is set. */
export function defaultChannels(targets: AnnouncementTargets | null): Record<string, string> {
  return Object.fromEntries(
    (targets?.languages ?? [])
      .filter((language) => language.channel !== "")
      .map((language) => [language.tag, language.channel]),
  )
}

export function channelsOf(announcement: AnnouncementView): Record<string, string> {
  return Object.fromEntries(announcement.targets.map((target) => [target.language, target.channel]))
}

function linesOf(parts: DocumentPart[] | undefined, language: Language, into: string[]) {
  for (const part of parts ?? []) {
    for (const line of part.lines ?? []) {
      const value = line.text[language] ?? line.text[ENGLISH]
      into.push(Array.isArray(value) ? value.join(" ") : (value ?? ""))
    }
    linesOf(part.parts, language, into)
  }
  return into
}

/** The first line of the document in `language`, without Markdown marks, or empty when it has no words. */
export function titleOf(document: Document, language: Language): string {
  const first = linesOf(document.parts, language, []).find((line) => line.trim() !== "") ?? ""
  return first.replaceAll(/[*_~`#>]/g, "").trim()
}

/** Whether any text of the document, in any language, holds `query`, ignoring case. */
export function matches(document: Document, query: string): boolean {
  const words = query.trim().toLowerCase()
  if (words === "") return true
  const all: string[] = []
  const walk = (parts: DocumentPart[] | undefined) => {
    for (const part of parts ?? []) {
      for (const line of part.lines ?? []) {
        for (const value of Object.values(line.text)) all.push(Array.isArray(value) ? value.join(" ") : value)
      }
      walk(part.parts)
    }
  }
  walk(document.parts)
  return all.some((line) => line.toLowerCase().includes(words))
}

/** The languages of a sent announcement the bot could not post, which a retry posts. */
export function missing(announcement: AnnouncementView): string[] {
  return announcement.targets.filter((target) => target.delivery === "NOT_POSTED").map((target) => target.language)
}

function sorted(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sorted)
  if (value === null || typeof value !== "object") return value
  return Object.fromEntries(
    Object.entries(value)
      .toSorted(([left], [right]) => left.localeCompare(right))
      .map(([key, entry]) => [key, sorted(entry)]),
  )
}

/** Whether two values hold the same, whatever order their keys come in, as the server sends a text's languages sorted. */
export function same(left: unknown, right: unknown): boolean {
  return JSON.stringify(sorted(left)) === JSON.stringify(sorted(right))
}
