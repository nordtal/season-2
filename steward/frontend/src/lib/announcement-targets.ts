import type { ConfigDocument, ConfigEntry } from "@/lib/api"

/** The group the bot reads its languages, and each language's announcement channel, from. */
export const ACCESS_FILE = "discord-bot/access"

/** The languages an announcement is written in when the file cannot say. */
export const FALLBACK_LANGUAGES = ["en", "de"]

export type AnnouncementTargets = {
  /** Each language of the file, in file order, with its channel id, or empty for none. */
  languages: { tag: string; channel: string }[]
  /** True when the host environment sets `languages`, so the file's channels are not where a line lands. */
  overridden: boolean
}

function field(section: ConfigEntry[], key: string) {
  return section.find((candidate) => candidate.key === key)?.value?.trim() ?? ""
}

/**
 * Where an announcement lands per language, read from the bot's `access` group.
 *
 * Null when the file has no `languages` cards; the page then writes in {@link FALLBACK_LANGUAGES} with no channel.
 */
export function announcementTargets(document: ConfigDocument | undefined): AnnouncementTargets | null {
  if (!document) return null
  const entry = document.entries.find((candidate) => candidate.path === "languages")
  if (!entry || entry.kind !== "SECTIONS" || !entry.sections) return null
  const languages = entry.sections
    .map((section) => ({ tag: field(section, "tag"), channel: field(section, "announcement-channel") }))
    .filter((language) => language.tag !== "")
  if (languages.length === 0) return null
  return { languages, overridden: entry.environmentOverridden === true }
}
