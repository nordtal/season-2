import { WarningIcon } from "@phosphor-icons/react"

import type {
  ConfigChanges,
  ConfigEntry,
  EditableRawConfigDocument,
  GuildList,
  ParsedConfigDocument,
  RawConfigDocument,
} from "@/lib/api"
import { languageName } from "@/lib/language-names"
import { ListControl, ScalarControl } from "@/components/steward/config-controls"
import { RawConfigEditor } from "@/components/steward/raw-config-editor"
import { type SectionValues, RepeatableCards, sectionsFromEntry } from "@/components/steward/repeatable-cards"
import { Badge } from "@/components/ui/badge"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

/** Re-exported for `snowflake-picker.test.tsx`; it lives in `config-controls.tsx` to avoid an import cycle. */
export { discordId } from "@/components/steward/config-controls"

/** The marker for a key the file has but the schema does not mention. */
export function NotInSchemaBadge() {
  return (
    <Badge variant="outline" className="shrink-0 gap-1 text-muted-foreground">
      not in schema
    </Badge>
  )
}

/**
 * The marker for a key an environment variable answers, where saving changes the file but not the service.
 *
 * The field stays editable, so the file can be prepared for when the variable is gone.
 */
export function EnvironmentOverriddenBadge() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="rounded-full focus-visible:outline-none">
          <Badge variant="outline" className="shrink-0 gap-1 border-warning/30 bg-warning/12 text-warning">
            <WarningIcon className="size-3" aria-hidden />
            env override
          </Badge>
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">
        An environment variable overrides this. Saving changes the file, not the running service, until that variable is
        removed.
      </TooltipContent>
    </Tooltip>
  )
}

/** Whether `document` carries the `revision` steward sends on every raw document. */
function isEditable(document: RawConfigDocument): document is EditableRawConfigDocument {
  return "revision" in document && typeof document.revision === "string"
}

/** A file that is not valid YAML, shown as it stands on disk and handed to `RawConfigEditor` for editing. */
export function RawConfigView({
  file,
  document,
  origin,
}: {
  file: string
  document: RawConfigDocument
  origin?: "nordtal" | "third-party"
}) {
  if (!isEditable(document)) {
    throw new Error(`${file}: steward answered a raw document with no revision`)
  }
  return <RawConfigEditor file={file} origin={origin} document={document} />
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

/** Only the keys that differ from the file, so a one word change stays a one line diff. */
export function changed(document: ParsedConfigDocument, draft: Draft): ConfigChanges {
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
    /** A card list is sent whole under its parent path, since a removed card leaves no key to differ. */
    if (entry.kind === "SECTIONS") {
      if (isSectionValuesArray(value) && JSON.stringify(value) !== JSON.stringify(sectionsFromEntry(entry))) {
        changes[entry.path] = value
      }
      continue
    }
    /** A secret has no value to compare, so any typed value is a change, an empty one emptying it. */
    if (entry.secret || value !== (entry.value ?? "")) {
      changes[entry.path] = value
    }
  }
  return changes
}

/**
 * Titles `languages` cards by their tag's language name, the one `SECTIONS` entry with a natural title.
 *
 * A blank tag falls back to the plain index.
 */
function sectionTitleFor(entry: ConfigEntry): ((section: SectionValues, index: number) => string) | undefined {
  if (entry.path !== "languages") return undefined
  return (section, index) => {
    const tag = typeof section.tag === "string" ? section.tag.trim() : ""
    return tag ? languageName(tag) : `Entry ${index + 1}`
  }
}

/** Which control an entry gets: cards for `SECTIONS`, list rows for `LIST`, else `ScalarControl`. */
export function Control({
  entry,
  draft,
  disabled,
  roles,
  channels,
  onChange,
}: {
  entry: ConfigEntry
  draft: Draft
  disabled: boolean
  roles: GuildList | undefined
  channels: GuildList | undefined
  onChange: (value: string | string[] | SectionValues[]) => void
}) {
  if (entry.kind === "LIST") {
    const raw = draft[entry.path]
    const items = (isStringArray(raw) ? raw : undefined) ?? entry.items ?? []
    return <ListControl id={entry.path} items={items} disabled={disabled} onChange={onChange} />
  }

  if (entry.kind === "SECTIONS") {
    const raw = draft[entry.path]
    const value = (isSectionValuesArray(raw) ? raw : undefined) ?? sectionsFromEntry(entry)
    return (
      <RepeatableCards
        entry={entry}
        value={value}
        disabled={disabled}
        roles={roles}
        channels={channels}
        onChange={onChange}
        sectionTitle={sectionTitleFor(entry)}
      />
    )
  }

  const rawTyped = draft[entry.path]
  const typed = typeof rawTyped === "string" ? rawTyped : undefined
  const value = typed ?? entry.value ?? ""

  return (
    <ScalarControl
      id={entry.path}
      entry={entry}
      value={value}
      edited={typed !== undefined}
      disabled={disabled}
      roles={roles}
      channels={channels}
      onChange={onChange}
    />
  )
}
