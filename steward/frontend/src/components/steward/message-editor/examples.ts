import type { MessageArg, MessageExamples, MessageTexts } from "@/lib/api"
import { type Language, wordsOf } from "@/lib/message-text"
import { useMessageExamples, useMessageSyntax, useMessageTexts } from "@/lib/queries"
import { branchesOf, type Format, parse, plainText } from "@/lib/rich-text"
import { valueText, type Words } from "@/lib/texts"
import { readable } from "@/lib/translation-items"

export type Fill = (name: string, style?: string) => string

/**
 * The first that exists wins: the live example for its type, then its schema's example read as its kind, then as written, then its name.
 */
export function exampleOf(name: string, args: MessageArg[], examples: MessageExamples | undefined): unknown {
  const arg = args.find((candidate) => candidate.name === name)
  return liveOf(arg, name, examples) ?? arg?.exampleValue ?? arg?.example ?? name
}

/** Returns each value's example by its name. */
export function valuesOf(args: MessageArg[], examples: MessageExamples | undefined): Record<string, unknown> {
  return Object.fromEntries(args.map((arg) => [arg.name, exampleOf(arg.name, args, examples)]))
}

/** Draws each value's example as the game and the bot render its kind, in `words` where they are known. */
export function fillOf(args: MessageArg[], examples: MessageExamples | undefined, words?: Words): Fill {
  return (name, style) => valueText(exampleOf(name, args, examples), style, words)
}

/** The words of each language and bundle, built once per texts query result when a preview first asks for them. */
const wordsCache = new WeakMap<MessageTexts, Map<string, Words>>()

/**
 * Returns the words a preview writes values and nested messages in, by its language and the bundle of the text it previews,
 * once Steward's texts have arrived. A document's preview names no bundle.
 */
export function useWords(): (language: Language, bundle?: string) => Words | undefined {
  const { data } = useMessageTexts()
  return (language, bundle) => {
    if (data === undefined) return undefined
    const known = wordsCache.get(data) ?? new Map<string, Words>()
    wordsCache.set(data, known)
    const key = `${bundle ?? ""}/${language}`
    let words = known.get(key)
    if (words === undefined) {
      const entries = data.texts.map((text) => text.entry)
      // A nested message is filled with its own examples, as the server's preview does; its own bundle's key wins.
      const examples: Record<string, Record<string, unknown>> = {}
      for (const entry of entries.toSorted((a, b) => Number(a.bundle === bundle) - Number(b.bundle === bundle))) {
        examples[entry.key] = valuesOf(entry.args, undefined)
      }
      words = { ...wordsOf(entries, language, bundle), examples }
      known.set(key, words)
    }
    return words
  }
}

/** Draws a source text in `format` that names `args` on one line, in `language`; a document's text names no bundle. */
export type DrawLine = (
  source: string,
  format: Format,
  args: MessageArg[],
  language: Language,
  bundle?: string,
) => string

/**
 * Returns how a row, a folded part or a search hit shows a source text: as its preview draws it, with each value's example,
 * each choice as the case its example picks and no marks, on one line. `tones` are the tags it reads, the syntax's by default.
 */
export function useDrawLine(tones?: Record<string, string>): DrawLine {
  const examples = useMessageExamples().data
  const syntax = useMessageSyntax().data
  const words = useWords()
  const tags = Object.keys(tones ?? syntax?.tones ?? {})
  return (source, format, args, language, bundle) => {
    const shownIn = words(language, bundle)
    const runs = parse(source, format, tags)
    const drawn = runs
      ? plainText(branchesOf(runs, format, tags, valuesOf(args, examples), shownIn), fillOf(args, examples, shownIn))
      : readable(source)
    return drawn.replaceAll(/\s+/g, " ").trim()
  }
}

/** The server renders each example itself, so it is sent as written: a nested message as its key. */
export function sentOf(args: MessageArg[], examples: MessageExamples | undefined): Fill {
  return (name) => {
    const arg = args.find((candidate) => candidate.name === name)
    return liveOf(arg, name, examples) ?? arg?.example ?? name
  }
}

function liveOf(arg: MessageArg | undefined, name: string, examples: MessageExamples | undefined): string | undefined {
  return arg?.type ? examples?.[arg.type]?.[name.slice(name.indexOf(".") + 1)] || undefined : undefined
}

export type PlaceholderGroup = { role: string | null; args: MessageArg[] }

/** The message's own values come before the ones every message has. */
export function placeholderGroups(args: MessageArg[]): PlaceholderGroup[] {
  const groups: PlaceholderGroup[] = []
  const values = args.filter((arg) => !arg.action)
  for (const arg of [...values.filter((value) => !value.global), ...values.filter((value) => value.global)]) {
    const dot = arg.name.indexOf(".")
    const role = dot < 0 ? null : arg.name.slice(0, dot)
    const last = groups.at(-1)
    if (role !== null && last?.role === role) last.args.push(arg)
    else groups.push({ role, args: [arg] })
  }
  return groups
}
