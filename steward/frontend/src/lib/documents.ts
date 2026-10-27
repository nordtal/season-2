import type { Document, DocumentDeclaration, DocumentPart, DocumentView, MessageText, SharedPartView } from "@/lib/api"
import { ENGLISH, type Language } from "@/lib/message-text"

/** One card of the Translations page: a document, a text of a bundle, or a shared part. */
export type PageCard =
  | { kind: "document"; view: DocumentView }
  | { kind: "text"; text: MessageText }
  | { kind: "shared"; shared: SharedPartView }

/** A text of a document: one source per language, or the versions of which one shows at random. */
export type DocumentText = Record<string, string | string[]>

/** Returns the document as the bot draws it: the admin's, else the packaged one. */
export function documentOf(view: DocumentView): Document {
  return view.written ?? view.packaged
}

/** The id a shared part is opened by, apart from every document's and text's. */
export function sharedPartId(name: string): string {
  return `shared-part/${name}`
}

export function documentId(declaration: DocumentDeclaration): string {
  return `${declaration.namespace}/${declaration.name}`
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

/** The service a document's order follows: the first of its services in the filter's order. */
function firstService(view: DocumentView): string {
  return view.services.toSorted()[0] ?? ""
}

/**
 * Returns the documents of `service`, or all of them, then each of `texts` as a card of its own. Within an area a
 * document several servers share comes first, then each server's own in the order of the service filter.
 */
export function pageCards(
  texts: readonly MessageText[],
  views: readonly DocumentView[],
  service?: string,
): Exclude<PageCard, { kind: "shared" }>[] {
  const shown = views.filter((view) => service === undefined || view.services.includes(service))
  const areas = [...new Set(shown.map((view) => view.declaration.area))]
  return [
    ...shown
      .toSorted(
        (a, b) =>
          areas.indexOf(a.declaration.area) - areas.indexOf(b.declaration.area) ||
          Number(b.services.length > 1) - Number(a.services.length > 1) ||
          firstService(a).localeCompare(firstService(b)),
      )
      .map((view) => ({ kind: "document" as const, view })),
    ...texts.map((text) => ({ kind: "text" as const, text })),
  ]
}
