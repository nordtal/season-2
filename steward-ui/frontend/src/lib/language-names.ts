/**
 * Language names for the `languages` cards in `discord-bot/access.yml` (steward/61).
 *
 * **Hand-kept, not derived.** Nothing in the config schema carries a display name for a tag - only
 * the tag itself (`AccessSpec.LanguageSpec#tag`, a bare lower-case string) - so there is no field to
 * read this from. The two names below are sourced from the message bundles that actually exist in
 * the repository as of 2026-09-16: every module under `season-2` ships an `en.properties` and a
 * `de.properties` under its own `messages` directory, and nothing else - a repo-wide search for
 * every properties file under a `messages` directory names only those two tags. That is the honest
 * list, not a guess at a roster the project has not built: a third bundle earns a third line here,
 * in the same commit that adds it.
 *
 * A tag with no entry here falls back to the tag itself (`languageName("fr")` is `"fr"`) - an
 * unfamiliar two-letter code is still readable on a card, and a wrong guess at a name would not be.
 */
const LANGUAGE_NAMES: Record<string, string> = {
  en: "English",
  de: "Deutsch",
}

export function languageName(tag: string): string {
  const key = tag.trim().toLowerCase()
  return LANGUAGE_NAMES[key] ?? tag
}
