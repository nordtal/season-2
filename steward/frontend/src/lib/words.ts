/**
 * Abbreviations that stay upper case, the same list as Steward's `Labels.of` and the spec module's `SettingLabels.of`.
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

/** Returns "smp config" as "SMP Config". */
function capitalCase(sentence: string): string {
  return sentence
    .split(" ")
    .map((word) => (BRAND.test(word) ? word : word.charAt(0).toUpperCase() + word.slice(1)))
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

/** The names that the services go by where a person reads them. */
const SERVICE_TITLES: Record<string, string> = {
  smp: "SMP",
  "hunger-games": "Hunger Games",
  limbo: "Limbo",
  proxy: "Proxy",
  "discord-bot": "Discord Bot",
  steward: "Steward",
  "steward-agent": "Steward Agent",
  "steward-bunq": "Steward Bunq",
  postgres: "Postgres",
  caddy: "Caddy",
}

export function serviceTitle(service: string): string {
  return SERVICE_TITLES[service] ?? capitalCase(sentenceOf(service.split(/[-_.\s]+/)) || service)
}

export function fileTitle(path: string): string {
  const withoutExtension = path.replace(/\.[a-z0-9]+$/i, "")
  const sentence = sentenceOf(withoutExtension.split(/[-_./]+/), true)
  return sentence ? capitalCase(withoutRepeats(sentence)) : path
}
