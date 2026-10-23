import { useMemo } from "react"

import type { MessageArg, MessageExamples } from "@/lib/api"
import { ENGLISH, type Language, wordsOf } from "@/lib/message-text"
import { useMessageTexts } from "@/lib/queries"
import { valueText, type Words } from "@/lib/texts"

export type Fill = (name: string, style?: string) => string

/**
 * The first that exists wins: the live example for its type, then a nested message's example in the language or in English, then its schema's example read as its kind, then as written, then its name.
 */
export function exampleOf(
  name: string,
  args: MessageArg[],
  examples: MessageExamples | undefined,
  language: Language,
): unknown {
  const arg = args.find((candidate) => candidate.name === name)
  return (
    liveOf(arg, name, examples) ??
    arg?.exampleWords[language] ??
    arg?.exampleWords[ENGLISH] ??
    arg?.exampleValue ??
    arg?.example ??
    name
  )
}

/** Draws each value's example as the game and the bot render its kind, in `words` where they are known. */
export function fillOf(
  args: MessageArg[],
  examples: MessageExamples | undefined,
  language: Language,
  words?: Words,
): Fill {
  return (name, style) => valueText(exampleOf(name, args, examples, language), style, words)
}

/** Returns the words a preview writes values in, by its language, once Steward's texts have arrived. */
export function useWords(): (language: Language) => Words | undefined {
  const { data } = useMessageTexts()
  const values = useMemo(
    () => data?.texts.map((text) => text.entry).filter((entry) => entry.bundle === "values"),
    [data],
  )
  const known = useMemo(
    () =>
      new Map(
        values === undefined ? [] : (data?.languages ?? []).map((language) => [language, wordsOf(values, language)]),
      ),
    [data, values],
  )
  return (language) => known.get(language) ?? (values === undefined ? undefined : wordsOf(values, language))
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
