import type { ConfigDocument, ConfigEntry } from "@/lib/api"

export const ACCESS_FILE = "discord-bot/access"

export const FALLBACK_LANGUAGES = ["en", "de"]

export type AnnouncementTargets = {
  languages: { tag: string; channel: string }[]
  /** The host environment sets `languages`, so the file's channels are not where a line lands. */
  overridden: boolean
}

function field(section: ConfigEntry[], key: string) {
  return section.find((candidate) => candidate.key === key)?.value?.trim() ?? ""
}

/**
 * It returns null when the file has no `languages` cards. The page then writes in {@link FALLBACK_LANGUAGES} with no channel.
 */
export function announcementTargets(document: ConfigDocument | undefined): AnnouncementTargets | null {
  if (!document) return null
  const entry = document.entries.find((candidate) => candidate.path === "languages")
  if (!entry || entry.kind !== "SECTIONS" || !entry.sections) return null
  const languages = entry.sections
    .map((section) => ({ tag: field(section, "tag"), channel: field(section, "announcement-channel") }))
    .filter((language) => language.tag !== "")
  if (languages.length === 0) return null
  return { languages, overridden: entry.environmentOverridden }
}
