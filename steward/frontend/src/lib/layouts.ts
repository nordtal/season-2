import type { Layout, LayoutView, MessageText } from "@/lib/api"
import { sentenceOf } from "@/lib/words"

/**
 * One card of the Translations page: a declared layout, or a text that no layout shows, as a layout of one part.
 */
export type PageLayout = { kind: "layout"; view: LayoutView } | { kind: "text"; text: MessageText }

/** Returns the layout as the bot draws it: the stored rebuild, else the packaged one. */
export function layoutOf(view: LayoutView): Layout {
  return view.rebuild ?? view.entry.layout
}

export function layoutId(layout: Layout): string {
  return `${layout.bundle}/${layout.name}`
}

export function layoutName(layout: Layout): string {
  return sentenceOf(layout.name.split("-")) || layout.name
}

/** Returns the first layout that shows the text `id`, a `bundle/key`. */
export function holderOf(views: readonly LayoutView[], id: string): LayoutView | undefined {
  return views.find((view) => view.entry.shows.includes(id))
}

/**
 * Returns the layouts that show one of `texts`, then each of `texts` that none shows as a layout of its own.
 */
export function pageLayouts(texts: readonly MessageText[], views: readonly LayoutView[]): PageLayout[] {
  const listed = new Set(texts.map((text) => `${text.entry.bundle}/${text.entry.key}`))
  const shown = new Set(views.flatMap((view) => view.entry.shows))
  return [
    ...views
      .filter((view) => view.entry.shows.some((id) => listed.has(id)))
      .map((view): PageLayout => ({ kind: "layout", view })),
    ...texts
      .filter((text) => !shown.has(`${text.entry.bundle}/${text.entry.key}`))
      .map((text): PageLayout => ({ kind: "text", text })),
  ]
}
