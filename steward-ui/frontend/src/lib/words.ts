/**
 * The abbreviations a key or a file name in this stack actually uses, written upper-case. The same
 * list as the worker's `Labels.of` and jcore's `SettingLabels.of`, so a label reads the same
 * whichever of the three made it.
 */
const ACRONYMS = new Set([
  "api", "db", "gui", "http", "https", "id", "ip", "json", "jvm", "motd", "mspt", "pvp", "smp",
  "sql", "tps", "ttl", "ui", "url", "uri", "uuid", "xp",
])

/** A brand spelled with one lower-case letter in front, `bStats`, is one word and keeps its case. */
const BRAND = /^[a-z][A-Z][a-z]+$/

/**
 * Parts already split on their separators, as one sentence-case name: a change of case is a word
 * boundary too, a known acronym and a word written in capitals inside a mixed-case part stay
 * upper-case, and everything else is lower-cased.
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

/** "smp config" reads "SMP Config": every word capitalised, an acronym or a brand left as it is. */
function capitalCase(sentence: string): string {
  return sentence
    .split(" ")
    .map((word) => (BRAND.test(word) ? word : word.charAt(0).toUpperCase() + word.slice(1)))
    .join(" ")
}

/** Drops a word that only repeats the one before it: `voicechat/voicechat-server` is one name. */
function withoutRepeats(sentence: string): string {
  return sentence
    .split(" ")
    .filter((word, index, words) => index === 0 || word.toLowerCase() !== words[index - 1].toLowerCase())
    .join(" ")
}

/** The names the services go by where a person reads them - the plugins tab's names. */
const SERVICE_TITLES: Record<string, string> = {
  smp: "SMP",
  "hunger-games": "Hunger Games",
  limbo: "Limbo",
  proxy: "Proxy",
  "discord-bot": "Discord Bot",
  "steward-ui": "Steward UI",
  "steward-worker": "Steward Worker",
  "steward-deployer": "Steward Deployer",
  postgres: "Postgres",
  caddy: "Caddy",
}

export function serviceTitle(service: string): string {
  return SERVICE_TITLES[service] ?? capitalCase(sentenceOf(service.split(/[-_.\s]+/)) || service)
}

/**
 * A path under a service's volume as a name in Capital Case: the extension goes, the separators and
 * a change of case split words, and a word that repeats the one before it is dropped.
 */
export function fileTitle(path: string): string {
  const withoutExtension = path.replace(/\.[a-z0-9]+$/i, "")
  const sentence = sentenceOf(withoutExtension.split(/[-_./]+/), true)
  return sentence ? capitalCase(withoutRepeats(sentence)) : path
}

/**
 * How a config file is named in the list. A service's own file, and a Nordtal plugin's file inside
 * the service it belongs to, go by the file alone ("Milestones"); a file in another plugin's folder
 * is named after that plugin first ("Display Tags Config", "bStats Config", "Voicechat Server").
 */
export function configTitle(location: { service: string; name: string; plugin?: string | null }): string {
  const slash = location.name.indexOf("/")
  if (slash < 0) return fileTitle(location.name)
  const folder = location.name.slice(0, slash)
  const rest = location.name.slice(slash + 1)
  if (folder === location.service) return fileTitle(rest)
  const plugin = location.plugin ?? folder
  return fileTitle(`${plugin.replace(/ /g, "-")}/${rest}`)
}

/** A message bundle is named after the service or module it belongs to: "SMP Translations". */
export function translationsTitle(bundle: { service: string; module: string }): string {
  return `${serviceTitle(bundle.module || bundle.service)} Translations`
}
