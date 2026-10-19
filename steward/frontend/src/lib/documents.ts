import type { Document, DocumentDeclaration, DocumentPart, DocumentView, MessageText } from "@/lib/api"
import { ENGLISH, type Language } from "@/lib/message-text"
import { sentenceOf } from "@/lib/words"

/** One card of the Translations page: a Discord document, or a text of a bundle. */
export type PageCard = { kind: "document"; view: DocumentView } | { kind: "text"; text: MessageText }

/** A text of a document: one source per language, or the versions of which one shows at random. */
export type DocumentText = Record<string, string | string[]>

/** Returns the document as the bot draws it: the admin's, else the packaged one. */
export function documentOf(view: DocumentView): Document {
  return view.written ?? view.packaged
}

export function documentId(declaration: DocumentDeclaration): string {
  return `${declaration.namespace}/${declaration.name}`
}

export function documentName(declaration: DocumentDeclaration): string {
  return sentenceOf(declaration.name.split("-")) || declaration.name
}

/** Returns every text the document shows, each as its versions in every language. */
export function textsOf(document: Document): DocumentText[] {
  return document.parts.flatMap(textsOfPart)
}

function textsOfPart(part: DocumentPart): DocumentText[] {
  return [
    ...(part.lines ?? []).map((line) => line.text),
    ...[part.button, ...(part.items ?? [])].flatMap((item) =>
      item ? [item.label, item.placeholder, item.option].filter((text) => text !== undefined) : [],
    ),
    ...(part.parts ?? []).flatMap(textsOfPart),
  ]
}

/** Returns the languages the document's texts are written in, English first. */
export function languagesOf(document: Document): Language[] {
  const found = new Set(textsOf(document).flatMap((text) => Object.keys(text)))
  return [ENGLISH, ...[...found].filter((language) => language !== ENGLISH).toSorted()]
}

export function documentMatches(view: DocumentView, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return true
  return [
    documentName(view.declaration),
    view.declaration.name,
    view.declaration.area,
    ...textsOf(documentOf(view)).flatMap((text) => Object.values(text).flat()),
  ]
    .join("\n")
    .toLowerCase()
    .includes(needle)
}

/** Returns the documents of `service`, or all of them, then each of `texts` as a card of its own. */
export function pageCards(texts: readonly MessageText[], views: readonly DocumentView[], service?: string): PageCard[] {
  return [
    ...views
      .filter((view) => service === undefined || view.services.includes(service))
      .map((view): PageCard => ({ kind: "document", view })),
    ...texts.map((text): PageCard => ({ kind: "text", text })),
  ]
}
