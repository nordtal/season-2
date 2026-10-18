import type { MessageArg, MessageExamples } from "@/lib/api"
import { ENGLISH, type Language } from "@/lib/message-text"

export type Fill = (name: string) => string

/**
 * The first that exists wins: the live example for its type, then a nested message's example in the language or in English, then its schema's example, then its name.
 */
export function exampleOf(
  name: string,
  args: MessageArg[],
  examples: MessageExamples | undefined,
  language?: Language,
): string {
  const arg = args.find((candidate) => candidate.name === name)
  if (arg?.type) {
    const live = examples?.[arg.type]?.[name.slice(name.indexOf(".") + 1)]
    if (live) return live
  }
  const words = language === undefined ? undefined : (arg?.exampleWords[language] ?? arg?.exampleWords[ENGLISH])
  return words ?? arg?.example ?? name
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
