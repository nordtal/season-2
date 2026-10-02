import type { Language } from "@/lib/message-text"

export type Editor = "visual" | "source" | "segments"

export type TranslationsSearch = { bundle?: string; key?: string; editor?: Editor; lang?: Language }

/** The page's search, apart from the page, so the router reads it without loading the editors. */
export function translationsSearch(search: Record<string, unknown>): TranslationsSearch {
  const answer: TranslationsSearch = {}
  if (typeof search.bundle === "string" && search.bundle) answer.bundle = search.bundle
  if (typeof search.key === "string" && search.key) answer.key = search.key
  if (search.editor === "visual" || search.editor === "source" || search.editor === "segments")
    answer.editor = search.editor
  if (search.lang === "en" || search.lang === "de") answer.lang = search.lang
  return answer
}
