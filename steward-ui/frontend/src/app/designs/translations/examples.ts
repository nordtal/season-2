import type { MessageArg, MessageExamples } from "@/lib/api"

/**
 * The value a placeholder is previewed with. A typed one (`winner.name`, type `player`) reads the
 * server's example for that type and property - real data, the admin first; an untyped one falls
 * back to a fixed word that reads like what the code passes, and failing that to its own name.
 */
export function exampleOf(name: string, args: MessageArg[], examples: MessageExamples | undefined): string {
  const arg = args.find((candidate) => candidate.name === name)
  if (arg?.type) {
    const property = name.includes(".") ? name.slice(name.indexOf(".") + 1) : "name"
    const value = examples?.[arg.type]?.[property]
    if (value) return value
    return TYPED_FALLBACK[arg.type] ?? name
  }
  const bare = name.replace(/^_/, "")
  if (bare === "sender") return examples?.player?.name ?? TYPED_FALLBACK.player
  return UNTYPED_FALLBACK[bare] ?? (NUMBERS.test(bare) ? "3" : bare)
}

const TYPED_FALLBACK: Record<string, string> = {
  player: "Steve",
  "discord-member": "@Steve",
  team: "Nordlichter",
  milestone: "frontier",
  service: "smp",
  season: "2",
}

const UNTYPED_FALLBACK: Record<string, string> = {
  message: "Good morning!",
  invite: "discord.gg/nordtal",
  separator: "|",
  percent: "50",
  reason: "The server is being updated.",
  what: "the network",
  command: "/spawn",
  language: "English",
  total: "12.00 EUR",
  price: "4.00 EUR",
  version: "0.9.5",
  from: "0.9.4",
  to: "0.9.5",
}

const NUMBERS =
  /count|online|max|days|hours|minutes|seconds|amount|number|players|participants|rank|place|size|left|kills|level|points|aura/i
