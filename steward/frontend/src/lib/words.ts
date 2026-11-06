import { t } from "@/lib/texts"

/**
 * Abbreviations that stay upper case, the same list as the spec module's `SettingLabels.of`.
 */
const ACRONYMS = new Set([
  "api",
  "db",
  "gui",
  "http",
  "https",
  "id",
  "ip",
  "json",
  "jvm",
  "motd",
  "mspt",
  "npc",
  "pvp",
  "smp",
  "sql",
  "tps",
  "ttl",
  "ui",
  "url",
  "uri",
  "uuid",
  "xp",
])

/** A brand with one lower-case letter in front, such as `bStats`, keeps its case. */
const BRAND = /^[a-z][A-Z][a-z]+$/

/**
 * Returns already split parts as one name in sentence case. A change of case also splits words.
 */
export function sentenceOf(parts: string[], keepBrands = false): string {
  const words: string[] = []
  let brandFirst = false
  for (const part of parts) {
    if (!part) continue
    if (keepBrands && BRAND.test(part)) {
      if (words.length === 0) brandFirst = true
      words.push(part)
      continue
    }
    const allCaps = part === part.toUpperCase()
    const pieces = allCaps ? [part] : part.split(/(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])/)
    for (const piece of pieces) {
      if (!piece) continue
      const lower = piece.toLowerCase()
      const shouted = !allCaps && piece.length > 1 && piece === piece.toUpperCase() && /[A-Z]/.test(piece)
      words.push(ACRONYMS.has(lower) || shouted ? lower.toUpperCase() : lower)
    }
  }
  if (words.length === 0) return ""
  const joined = words.join(" ")
  return brandFirst ? joined : joined.charAt(0).toUpperCase() + joined.slice(1)
}

/** Short words that title case keeps lower case after the first word, as in "Deaths and Graves". */
const MINOR_WORDS = new Set(["a", "an", "and", "at", "by", "for", "in", "of", "on", "or", "the", "to"])

/** Returns "smp config" as "SMP Config", and "deaths and graves" as "Deaths and Graves". */
function capitalCase(sentence: string): string {
  return sentence
    .split(" ")
    .map((word, index) =>
      BRAND.test(word) || (index > 0 && MINOR_WORDS.has(word)) ? word : word.charAt(0).toUpperCase() + word.slice(1),
    )
    .join(" ")
}

/**
 * Drops a word that only repeats the one before it, so `voicechat/voicechat-server` is one name.
 */
function withoutRepeats(sentence: string): string {
  return sentence
    .split(" ")
    .filter((word, index, words) => index === 0 || word.toLowerCase() !== words[index - 1].toLowerCase())
    .join(" ")
}

/** The name a service goes by where a person reads it, from the bundle. */
export function serviceTitle(service: string): string {
  return t("steward.service.name", { service })
}

export function fileTitle(path: string): string {
  const withoutExtension = path.replace(/\.[a-z0-9]+$/i, "")
  const sentence = sentenceOf(withoutExtension.split(/[-_./]+/), true)
  return sentence ? capitalCase(withoutRepeats(sentence)) : path
}
