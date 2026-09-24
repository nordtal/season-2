import { PlusIcon, TrashIcon, WarningIcon } from "@phosphor-icons/react"

import type {
  ConfigChanges,
  ConfigEntry,
  EditableRawConfigDocument,
  GuildList,
  ParsedConfigDocument,
  RawConfigDocument,
} from "@/lib/api"
import { languageName } from "@/lib/language-names"
import { ScalarControl } from "@/components/steward/config-controls"
import { RawConfigEditor } from "@/components/steward/raw-config-editor"
import {
  type SectionValues,
  RepeatableCards,
  sectionsFromEntry,
} from "@/components/steward/repeatable-cards"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"


/**
 * `discordId` used to be defined here and stayed exported under this name for
 * `snowflake-picker.test.tsx`, which imports it from this module. The implementation moved to
 * `config-controls.tsx` (steward/57) so `repeatable-cards.tsx` could use it too without importing
 * this file back - a card's own fields need the same "is this a role or a channel" heuristic as a
 * top-level key, and a cycle between the two files would follow from importing it the other way.
 */
export { discordId } from "@/components/steward/config-controls"

/** The marker for a key the file has but the schema does not mention (steward/50, steward/55). */
export function NotInSchemaBadge() {
  return (
    <Badge variant="outline" className="shrink-0 gap-1 text-muted-foreground">
      not in schema
    </Badge>
  )
}

/**
 * The marker for a key an environment variable currently answers (steward/76): saving it here
 * changes the file, never the running service, until whoever set the variable removes it.
 *
 * The field stays editable regardless - Till's decision, 2026-09-16, is to let the file be
 * prepared for the day the variable is gone, not to lock it - so this is a sign next to the field,
 * not a state on it.
 */
export function EnvironmentOverriddenBadge() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="rounded-full focus-visible:outline-none">
          <Badge
            variant="outline"
            className="shrink-0 gap-1 border-warning/30 bg-warning/12 text-warning"
          >
            <WarningIcon className="size-3" aria-hidden />
            env override
          </Badge>
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">
        An environment variable overrides this. Saving changes the file, not the running service,
        until that variable is removed.
      </TooltipContent>
    </Tooltip>
  )
}


/**
 * A file steward could not read as YAML, shown exactly as it stands on disk (steward/56) - and,
 * since steward/60, editable as the plain text it is when the mount underneath it allows a write
 * at all. There is nothing here this class parsed, so there is no form and no per-field save; the
 * whole file is one draft, checked for the syntax its own name implies only once the operator
 * saves it, and never refused for what that check finds. See `RawConfigEditor` for the rest of
 * this - format detection, highlighting, the save itself and its warnings all live there so this
 * function stays just the hand-off steward/56 left it as.
 */
export function RawConfigView({ file, document }: { file: string; document: RawConfigDocument }) {
  return (
    <RawConfigEditor
      file={file}
      // The worker sends `revision` on every raw document too, since steward/60 gave this shape a
      // save path of its own (`ConfigApi#rawDocument`). `RawConfigDocument` itself is left alone,
      // the same way `ParsedConfigDocument` is above: other work lands in this file the same
      // night, so the widened shape is its own type in api.ts rather than a change to this one.
      document={document as EditableRawConfigDocument}
    />
  )
}

export type Draft = Record<string, string | string[] | SectionValues[]>

/**
 * What the form would send: only the keys that actually differ from the file.
 *
 * Sending everything would be simpler and would rewrite every line of the file on every save,
 * which turns a one-word change into a diff nobody reads.
 */
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
    // A card's whole list of sections (steward/57) is sent the same way a plain LIST is: as one
    // value under the parent path, compared whole against the sections the file itself held - a
    // removed card is invisible in a diff of individual keys, since there is no key left to differ.
    if (entry.kind === "SECTIONS") {
      if (JSON.stringify(value) !== JSON.stringify(sectionsFromEntry(entry))) {
        changes[entry.path] = value as SectionValues[]
      }
      continue
    }
    // A secret has no value here to compare against, so any typed value is a change - including an
    // empty one, which empties it. The field says so out loud rather than doing it quietly.
    if (entry.secret || value !== (entry.value ?? "")) {
      changes[entry.path] = value
    }
  }
  return changes
}

/**
 * The one path-keyed exception to "a card has no title beyond its index" (steward/61).
 *
 * `languages` in `discord-bot/access.yml` is the only `SECTIONS` entry in the whole config tree
 * that has a natural title - the tag - and nothing in the schema marks a field as "the one that
 * names this entry" for `RepeatableCards` to find generically (`SchemaNode` carries field shapes,
 * never a role like that). Recognising it by key path here, instead of teaching the schema a new
 * concept for a case that occurs exactly once, is the ticket's own documented fallback. `tiers`,
 * the only other `SECTIONS` entry today, gets no title from here and keeps the plain "Entry N".
 *
 * The name itself comes from `languageName` (`lib/language-names.ts`), a hand-kept map - see that
 * file for where the two names in it come from. A blank tag (a freshly added, still-empty card)
 * falls back to the plain index rather than showing an empty string as a title.
 */
function sectionTitleFor(
  entry: ConfigEntry,
): ((section: SectionValues, index: number) => string) | undefined {
  if (entry.path !== "languages") return undefined
  return (section, index) => {
    const tag = section.tag?.trim()
    return tag ? languageName(tag) : `Entry ${index + 1}`
  }
}

/**
 * Which control an entry gets: the repeatable cards for a `SECTIONS` entry (steward/57), the plain
 * scalar list rows for a `LIST`, or a leaf's own scalar control - a secret, a schema's choices, a
 * Discord id, a boolean or plain text, in that order of precedence. The leaf branch is
 * `ScalarControl` in `config-controls.tsx`, shared with a field drawn inside a card, so the two
 * never drift apart over what a "choices" or a "boolean" looks like.
 */
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
    const items = (draft[entry.path] as string[] | undefined) ?? entry.items ?? []
    return <ListControl id={entry.path} items={items} disabled={disabled} onChange={onChange} />
  }

  if (entry.kind === "SECTIONS") {
    const value = (draft[entry.path] as SectionValues[] | undefined) ?? sectionsFromEntry(entry)
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

  const typed = draft[entry.path] as string | undefined
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

/**
 * A list, one row per entry.
 *
 * The whole list is sent on save rather than a single added entry: two browsers sending "add one"
 * both succeed and the result is neither of the two lists anybody was looking at. Sending the list
 * is only half of it - the two saves would still have overwritten each other, one silently - and
 * the other half is the `revision` every save carries, which makes the second one a 409.
 */
function ListControl({
  id,
  items,
  disabled,
  onChange,
}: {
  id: string
  items: string[]
  disabled: boolean
  onChange: (items: string[]) => void
}) {
  return (
    <div className="flex flex-col gap-2">
      {items.length === 0 ? (
        <p className="text-sm text-muted-foreground">Empty list.</p>
      ) : (
        items.map((item, index) => (
          <div key={index} className="flex items-center gap-2">
            <Input
              id={index === 0 ? id : undefined}
              disabled={disabled}
              value={item}
              spellCheck={false}
              className="font-mono text-sm"
              aria-label={`Entry ${index + 1}`}
              onChange={(event) =>
                onChange(items.map((old, at) => (at === index ? event.target.value : old)))
              }
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              disabled={disabled}
              aria-label={`Remove entry ${index + 1}`}
              onClick={() => onChange(items.filter((_, at) => at !== index))}
            >
              <TrashIcon aria-hidden />
            </Button>
          </div>
        ))
      )}
      <div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled}
          onClick={() => onChange([...items, ""])}
        >
          <PlusIcon aria-hidden />
          Add entry
        </Button>
      </div>
    </div>
  )
}
