import type { MessageArg, MessageExamples } from "@/lib/api"
import { ENGLISH, type Language } from "@/lib/message-text"

/** What a placeholder is drawn as in a preview: a value for its name. */
export type Fill = (name: string) => string

/**
 * The value a placeholder is previewed with: the live example the server read for its type, else, given a
 * `language`, the words of a nested message's example in it or in English, else its schema's example, else its name.
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

/** A message's placeholders as the menu offers them: a plain value alone, a role with its attributes under it. */
export type PlaceholderGroup = { role: string | null; args: MessageArg[] }

/** Groups the values of `args`, actions left out, the message's own before the ones every message has. */
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
