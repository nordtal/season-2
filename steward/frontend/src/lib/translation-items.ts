import type { Document, TranslationHit } from "@/lib/api"
import { versionsOf } from "@/lib/document-edit"
import { documentId, documentOf, languagesOf, sharedPartId, textsOf, type PageCard } from "@/lib/documents"
import { ENGLISH, overrideOf, packagedOf, type Language } from "@/lib/message-text"
import { messageName, textId, type TreeNode } from "@/lib/settings-tree"
import { TEXT_GROUPS, type TextGroup } from "@/lib/text-places"
import { choice, t } from "@/lib/texts"

/** One document or text as the Translations page lists it, with the branches between its section and itself. */
export type Item = { id: string; card: PageCard; name: string; trail: string[] }

/** The first level under an area: a document's area or a text's topic. The section of loose items has no label. */
export type Section = { id: string; label: string; items: Item[] }

export type Area = { id: TextGroup; label: string; sections: Section[]; count: number }

/** Returns the id an item is opened by: a document's `discord/<name>` or a text's `bundle/key`. */
export function cardId(card: PageCard): string {
  if (card.kind === "shared") return sharedPartId(card.shared.name)
  return card.kind === "document" ? documentId(card.view.declaration) : textId(card.text.entry)
}

/** Returns the id of the item a search hit opens: the server names a shared part by its name alone. */
export function hitItemId(hit: TranslationHit): string {
  return hit.kind === "SHARED_PART" ? sharedPartId(hit.id) : hit.id
}

export function itemName(card: PageCard): string {
  if (card.kind === "shared") return card.shared.name
  return card.kind === "document" ? card.view.declaration.title : messageName(card.text.entry)
}

function itemsBelow(nodes: TreeNode<PageCard>[], trail: string[]): Item[] {
  return nodes.flatMap((node): Item[] => {
    if (node.kind === "branch") return itemsBelow(node.children, [...trail, ...node.labels])
    const name = itemName(node.value)
    // A branch named like the item it holds says nothing more.
    const kept = trail.at(-1)?.toLowerCase() === name.toLowerCase() ? trail.slice(0, -1) : trail
    return [{ id: cardId(node.value), card: node.value, name, trail: kept }]
  })
}

function isGroup(id: string): id is TextGroup {
  return TEXT_GROUPS.some((group) => group === id)
}

/**
 * Returns the page's tree as areas, each with its sections. A section is named by its first branch, and the branches
 * below it go into each item's trail. A section that holds one item named like itself joins the loose items, which come first.
 */
export function areasOf(nodes: TreeNode<PageCard>[]): Area[] {
  return nodes.flatMap((node) => {
    if (node.kind !== "branch" || !isGroup(node.id)) return []
    const id = node.id
    const sections: Section[] = []
    const loose: Item[] = []
    for (const child of node.children) {
      if (child.kind === "leaf") {
        loose.push(...itemsBelow([child], []))
        continue
      }
      const label = child.labels[0]
      const items = itemsBelow(child.children, child.labels.slice(1))
      if (items.length === 1 && items[0].trail.length === 0 && items[0].name.toLowerCase() === label.toLowerCase()) {
        loose.push(items[0])
      } else sections.push({ id: child.id, label, items })
    }
    if (loose.length > 0) sections.unshift({ id: `${id}:loose`, label: "", items: loose })
    return [
      {
        id,
        label: t("steward.texts.group", { group: choice(id) }),
        sections,
        count: sections.reduce((sum, section) => sum + section.items.length, 0),
      },
    ]
  })
}

/** Returns whether an item has no text of its own in `language`, which English never lacks. */
export function untranslated(card: PageCard, language: Language, languagesOfBundle: (bundle: string) => Language[]) {
  if (language === ENGLISH) return false
  if (card.kind === "document") return !languagesOf(documentOf(card.view)).includes(language)
  if (card.kind === "shared") return !languagesOf({ parts: [card.shared.part] }).includes(language)
  const entry = card.text.entry
  if (!languagesOfBundle(entry.bundle).includes(language)) return false
  return packagedOf(entry, language).length === 0 && overrideOf(entry, language) === undefined
}

/** Returns the source of a document's first text in `language` with words beyond a lone value, for a one-line row. */
export function documentWords(document: Document, language: Language): string {
  const written = textsOf(document).flatMap((text) => versionsOf(text, language).slice(0, 1))
  return (
    written.find((source) => /[\p{L}\p{N}]/u.test(readable(source).replaceAll(/\{[^}]*\}/g, ""))) ?? written[0] ?? ""
  )
}

/** Returns a source text for one line: each choice as its value's name, without marks of emphasis or breaks. */
export function readable(source: string): string {
  let out = ""
  for (let at = 0; at < source.length; at++) {
    const opened = /^\{\s*([\w.]+)\s*,\s*(?:plural|select|selectordinal)\s*,/.exec(source.slice(at))
    if (!opened) {
      out += source[at]
      continue
    }
    let depth = 0
    let end = at
    for (; end < source.length; end++) {
      if (source[end] === "{") depth++
      else if (source[end] === "}" && --depth === 0) break
    }
    out += `{${opened[1]}}`
    at = end
  }
  return out
    .replaceAll(/<\/?[a-z][\w-]*(?::[^>]*)?>|\*\*|__|~~/g, "")
    .replaceAll(/\s+/g, " ")
    .trim()
}
