import type { ConfigDocument, ConfigEntry } from "@/lib/api"

/** The file the bot reads its languages, and each language's announcement channel, from. */
export const ACCESS_FILE = "discord-bot/access.yml"

/** The languages an announcement is written in when the file cannot say. */
export const FALLBACK_LANGUAGES = ["en", "de"]

export type AnnouncementTargets = {
  /** Each language of the file, in file order, with its channel id - empty when it has none. */
  languages: { tag: string; channel: string }[]
  /**
   * The host environment sets `languages`, so the file is not what the bot runs with and its
   * channels are not shown as where a line will land.
   */
  overridden: boolean
}

/**
 * Where an announcement lands, per language, out of `discord-bot/access.yml`.
 *
 * Null when the file does not carry `languages` as cards - raw, or a worker too old to describe a
 * list of sections - and the page then writes in {@link FALLBACK_LANGUAGES} without naming a
 * channel. The bot's answer on each row still says whether it posted.
 */
export function announcementTargets(document: ConfigDocument | undefined): AnnouncementTargets | null {
  if (!document || document.raw) return null
  const entry = document.entries.find((candidate) => candidate.path === "languages")
  if (!entry || entry.kind !== "SECTIONS" || !entry.sections) return null
  const field = (section: ConfigEntry[], key: string) =>
    section.find((candidate) => candidate.key === key)?.value?.trim() ?? ""
  const languages = entry.sections
    .map((section) => ({ tag: field(section, "tag"), channel: field(section, "announcement-channel") }))
    .filter((language) => language.tag !== "")
  if (languages.length === 0) return null
  return { languages, overridden: entry.environmentOverridden === true }
}
