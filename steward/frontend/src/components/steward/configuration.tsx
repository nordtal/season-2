import { TERM_ICON } from "@/components/steward/term-icons"

import type { ConfigChanges, ConfigEntry, ConfigDocument } from "@/lib/api"
import { languageName } from "@/lib/language-names"
import { ListControl, ScalarControl } from "@/components/steward/config-controls"
import { type SectionValues, RepeatableCards, sectionsFromEntry } from "@/components/steward/repeatable-cards"
import { Badge } from "@/components/ui/badge"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { t } from "@/lib/texts"

/** A saved value has no effect until the variable is removed. */
export function EnvironmentOverriddenBadge() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="rounded-full focus-visible:outline-none">
          <Badge variant="outline" className="shrink-0 gap-1 border-warning/30 bg-warning/12 text-warning">
            <TERM_ICON.warning className="size-3" aria-hidden />
            {t("steward.settings.env-override")}
          </Badge>
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">{t("steward.settings.env-override-tip")}</TooltipContent>
    </Tooltip>
  )
}

export type Draft = Record<string, string | string[] | SectionValues[]>

function isStringArray(value: Draft[string] | undefined): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === "string")
}

function isSectionValuesArray(value: Draft[string] | undefined): value is SectionValues[] {
  return (
    Array.isArray(value) && value.every((item) => typeof item === "object" && item !== null && !Array.isArray(item))
  )
}

export function changed(document: ConfigDocument, draft: Draft): ConfigChanges {
  const changes: ConfigChanges = {}
  for (const entry of document.entries) {
    const value = draft[entry.path]
    if (value === undefined) continue
    if (entry.kind === "LIST") {
      if (JSON.stringify(value) !== JSON.stringify(entry.items ?? [])) {
        changes[entry.path] = value
      }
      continue
    }
    /**
     * A card list is sent whole under its parent path, because a removed card leaves no key to compare.
     */
    if (entry.kind === "SECTIONS") {
      if (isSectionValuesArray(value) && JSON.stringify(value) !== JSON.stringify(sectionsFromEntry(entry))) {
        changes[entry.path] = value
      }
      continue
    }
    if (!entry.secret && value !== (entry.value ?? "")) {
      changes[entry.path] = value
    }
  }
  return changes
}

/**
 * It is the one `SECTIONS` entry with a natural title. A blank tag falls back to the plain index.
 */
function sectionTitleFor(entry: ConfigEntry): ((section: SectionValues, index: number) => string) | undefined {
  if (entry.path !== "languages") return undefined
  return (section, index) => {
    const tag = typeof section.tag === "string" ? section.tag.trim() : ""
    return tag ? languageName(tag) : t("steward.settings.entry", { index: index + 1 })
  }
}

export function Control({
  entry,
  draft,
  disabled,
  onChange,
}: {
  entry: ConfigEntry
  draft: Draft
  disabled: boolean
  onChange: (value: string | string[] | SectionValues[]) => void
}) {
  if (entry.kind === "LIST") {
    const raw = draft[entry.path]
    const items = (isStringArray(raw) ? raw : undefined) ?? entry.items ?? []
    return <ListControl id={entry.path} entry={entry} items={items} disabled={disabled} onChange={onChange} />
  }

  if (entry.kind === "SECTIONS") {
    const value = draftSections(draft, entry)
    return (
      <RepeatableCards
        entry={entry}
        value={value}
        disabled={disabled}
        onChange={onChange}
        sectionTitle={sectionTitleFor(entry)}
      />
    )
  }

  return (
    <ScalarControl
      id={entry.path}
      entry={entry}
      value={draftText(draft, entry)}
      disabled={disabled}
      onChange={onChange}
    />
  )
}

/** A card list as the draft holds it, or as stored. */
export function draftSections(draft: Draft, entry: ConfigEntry): SectionValues[] {
  const raw = draft[entry.path]
  return (isSectionValuesArray(raw) ? raw : undefined) ?? sectionsFromEntry(entry)
}

/** A single value as the draft holds it, or as stored. */
export function draftText(draft: Draft, entry: ConfigEntry): string {
  const raw = draft[entry.path]
  return typeof raw === "string" ? raw : (entry.value ?? "")
}
