import { PlusIcon, TrashIcon, WarningCircleIcon } from "@phosphor-icons/react"
import { useMemo, useState } from "react"

import type { ConfigEntry, GuildList } from "@/lib/api"
import { ListControl, ScalarControl, explanationOf, isRequiredChannel } from "@/components/steward/config-controls"
import {
  ResponsiveAlertDialog,
  ResponsiveAlertDialogAction,
  ResponsiveAlertDialogCancel,
  ResponsiveAlertDialogContent,
  ResponsiveAlertDialogDescription,
  ResponsiveAlertDialogFooter,
  ResponsiveAlertDialogHeader,
  ResponsiveAlertDialogTitle,
} from "@/components/ui/responsive-dialog"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"

/**
 * One repeatable card, drawn from a `SECTIONS` entry (steward/57).
 *
 * `languages` in `discord-bot/access.yml` (steward/49, steward/61) is the case this was built for:
 * a YAML sequence of mappings, each carrying the same handful of fields. `SECTIONS` now exists on
 * the worker too (steward/68, steward/71) - append and remove both round-trip through
 * `PUT /api/config/<file>` a byte-identical file plus or minus exactly one entry.
 *
 * A card holds no field the schema did not put in `template` - a generic mechanism has no way to
 * know which field of an arbitrary section would make a good title, so by default the only label is
 * the plain "Entry N" every card has always had. `sectionTitle` and the incompleteness check below
 * are the two places that generic promise runs out, and both are opt-in props the caller supplies
 * rather than anything this component infers from a section's own values - see each prop's own
 * comment for why.
 */

/**
 * One section's fields as the record a card edits: text for a value, a list of text for a list,
 * and for a list of sections - the objectives of a milestone - the same record one level down.
 */
export type SectionValues = { [key: string]: string | string[] | SectionValues[] }

function fieldsToValues(fields: ConfigEntry[]): SectionValues {
  const values: SectionValues = {}
  for (const field of fields) {
    if (field.kind === "LIST") values[field.key] = field.items ?? []
    else if (field.kind === "SECTIONS") values[field.key] = (field.sections ?? []).map(fieldsToValues)
    else values[field.key] = field.value ?? ""
  }
  return values
}

/** The document's own sections, as a card's draft starts from - `entry.sections`, as records. */
export function sectionsFromEntry(entry: ConfigEntry): SectionValues[] {
  return (entry.sections ?? []).map(fieldsToValues)
}

/** A brand new, empty section - every key `template` names, empty, lists included. */
export function blankSection(template: ConfigEntry[]): SectionValues {
  const values: SectionValues = {}
  for (const field of template) {
    values[field.key] = field.kind === "LIST" || field.kind === "SECTIONS" ? [] : (field.value ?? "")
  }
  return values
}

/** A field's text, or "" for a field that holds a list. */
function textOf(section: SectionValues | undefined, key: string): string {
  const value = section?.[key]
  return typeof value === "string" ? value : ""
}

export function RepeatableCards({
  entry,
  value,
  disabled,
  roles,
  channels,
  onChange,
  sectionTitle,
  within,
}: {
  entry: ConfigEntry
  value: SectionValues[]
  disabled: boolean
  roles: GuildList | undefined
  channels: GuildList | undefined
  onChange: (value: SectionValues[]) => void
  /**
   * What to call a card instead of "Entry N" (steward/61) - `configuration.tsx` is the only caller
   * that passes one, keyed on `entry.path === "languages"`, because a language's tag is not a
   * concept this component or the schema knows about. Absent for every other `SECTIONS` entry
   * (`tiers`, today), which keeps the plain index.
   */
  sectionTitle?: (section: SectionValues, index: number) => string
  /**
   * Set for a list drawn inside another card: its entries are divided by a rule instead of being
   * cards themselves, since a card inside a card is one frame too many. The value is the parent
   * card's title, which names this list's add button.
   */
  within?: string
}) {
  const template = entry.template ?? []
  // What a field started from, per section - the only thing `ScalarControl`'s secret placeholder
  // needs `edited` for, and there is no schema-shaped default to fall back to beside "the file's
  // own copy", the same way the top-level form uses the document rather than a hardcoded default.
  const original = useMemo(() => sectionsFromEntry(entry), [entry])

  // Which of a section's fields are a channel the schema does not call optional (steward/61's
  // "missing a channel is visibly incomplete") - computed once per template, not per section, since
  // it only reads field shape and never a value.
  const requiredChannelFields = useMemo(() => template.filter((field) => isRequiredChannel(field)), [template])

  // Removing is a two-step action: the trash icon arms it, and only the dialog's own confirmation
  // splices the draft. `null` is "nothing armed". Every removal still gets the same dialog, and
  // what it shows is `explanationOf(entry)` - whatever the schema already says about the whole
  // list - because the confirmation is about the list in general, not about the one entry below
  // that cannot actually be removed.
  const [pendingRemoval, setPendingRemoval] = useState<number | null>(null)
  const listExplanation = explanationOf(entry)

  // The one entry the schema names as protected (steward/74), if any is currently in the draft -
  // `en` in `languages`. The worker refuses this removal too (`ConfigFiles.removeSection`), so
  // this is a courtesy: greying the button out up front is a better answer than letting somebody
  // confirm a removal that only fails once the save reaches the worker.
  const protectedIndex = entry.protectedEntry
    ? value.findIndex((section) => textOf(section, entry.protectedEntry!.field) === entry.protectedEntry!.value)
    : -1

  // The ticket's own escape hatch: a schema that could not describe one shape for every entry sends
  // no template, and a card that pretended it had one would just be the field set of whichever
  // entry happened to come first, silently dropping the rest. Raw text, clearly marked, beats that.
  if (template.length === 0) {
    return (
      <div className="flex flex-col gap-2">
        <p className="text-sm text-muted-foreground">
          These entries are not uniform enough for one card each - no card fits, so this list stays raw text and is not
          editable here.
        </p>
        {value.map((section, index) => (
          <Textarea
            key={index}
            readOnly
            spellCheck={false}
            rows={Math.max(2, Object.keys(section).length)}
            value={Object.entries(section)
              .map(([key, val]) => `${key}: ${typeof val === "string" ? val : JSON.stringify(val)}`)
              .join("\n")}
            className="font-mono text-sm"
          />
        ))}
      </div>
    )
  }

  // A card is named by the caller if it asked to, else by its own `key` - the one field every
  // repeating structure here that has a natural name keeps it under (a milestone, an objective) -
  // and only then by its position.
  const titleOf = (section: SectionValues, index: number) =>
    sectionTitle?.(section, index) ?? (textOf(section, "key").trim() || `Entry ${index + 1}`)

  return (
    <div className="flex flex-col gap-3">
      {value.length === 0 ? <p className="text-sm text-muted-foreground">No entries yet.</p> : null}
      {value.map((section, index) => {
        const missing = requiredChannelFields.filter((field) => !textOf(section, field.key).trim())
        const isProtected = index === protectedIndex
        const title = titleOf(section, index)
        const replace = (key: string, next: string | string[] | SectionValues[]) =>
          onChange(value.map((s, at) => (at === index ? { ...s, [key]: next } : s)))
        const body = (
          <>
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-medium text-muted-foreground">{title}</span>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                disabled={disabled || isProtected}
                title={isProtected ? (listExplanation ?? "This entry cannot be removed.") : undefined}
                aria-label={
                  isProtected
                    ? `Entry ${index + 1} cannot be removed`
                    : within
                      ? `Remove ${title}`
                      : `Remove entry ${index + 1}`
                }
                onClick={() => setPendingRemoval(index)}
              >
                <TrashIcon aria-hidden />
              </Button>
            </div>
            {missing.length > 0 ? (
              <p className="flex items-start gap-1.5 text-sm text-amber-600 dark:text-amber-500">
                <WarningCircleIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
                <span>Incomplete - missing {missing.map((field) => field.label).join(", ")}.</span>
              </p>
            ) : null}
            {template.map((field) => {
              const id = `${entry.path}.${index}.${field.key}`
              const explanation = explanationOf(field)
              const fieldValue = section[field.key]
              let control
              if (field.kind === "LIST") {
                control = (
                  <ListControl
                    id={id}
                    items={Array.isArray(fieldValue) ? (fieldValue as string[]) : []}
                    disabled={disabled}
                    onChange={(next) => replace(field.key, next)}
                  />
                )
              } else if (field.kind === "SECTIONS") {
                // The file's own copy of this very list, so the nested cards know what "edited"
                // and "protected" mean for it; a card added in this draft has none yet.
                const own = entry.sections?.[index]?.find((f) => f.key === field.key)
                control = (
                  <RepeatableCards
                    entry={{ ...field, ...own, path: id, template: field.template }}
                    value={Array.isArray(fieldValue) ? (fieldValue as SectionValues[]) : []}
                    disabled={disabled}
                    roles={roles}
                    channels={channels}
                    onChange={(next) => replace(field.key, next)}
                    within={title}
                  />
                )
              } else {
                const text = textOf(section, field.key)
                control = (
                  <ScalarControl
                    id={id}
                    entry={field}
                    value={text}
                    edited={text !== textOf(original[index], field.key)}
                    disabled={disabled}
                    roles={roles}
                    channels={channels}
                    onChange={(next) => replace(field.key, next)}
                  />
                )
              }
              return (
                <div key={field.key} className="flex flex-col gap-1.5">
                  <Label htmlFor={id} className="text-sm font-medium">
                    {field.label}
                  </Label>
                  {explanation ? <p className="text-sm text-muted-foreground">{explanation}</p> : null}
                  {control}
                </div>
              )
            })}
          </>
        )
        return within ? (
          <div key={index} className="flex flex-col gap-3 border-t pt-3">
            {body}
          </div>
        ) : (
          <Card key={index} className="gap-3 py-4">
            <CardContent className="flex flex-col gap-3 px-4">{body}</CardContent>
          </Card>
        )
      })}
      <div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled}
          aria-label={within ? `Add to ${within}` : undefined}
          onClick={() => onChange([...value, blankSection(template)])}
        >
          <PlusIcon aria-hidden />
          Add entry
        </Button>
      </div>

      <ResponsiveAlertDialog open={pendingRemoval !== null} onOpenChange={(open) => open || setPendingRemoval(null)}>
        <ResponsiveAlertDialogContent>
          <ResponsiveAlertDialogHeader>
            <ResponsiveAlertDialogTitle>
              Remove {pendingRemoval !== null ? `"${titleOf(value[pendingRemoval], pendingRemoval)}"` : "entry"}?
            </ResponsiveAlertDialogTitle>
            <ResponsiveAlertDialogDescription className="whitespace-pre-wrap text-left">
              {listExplanation ?? "This only changes the draft - nothing is written to the file until Save."}
            </ResponsiveAlertDialogDescription>
          </ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogFooter>
            <ResponsiveAlertDialogCancel>Keep it</ResponsiveAlertDialogCancel>
            <ResponsiveAlertDialogAction
              onClick={() => {
                if (pendingRemoval !== null) {
                  onChange(value.filter((_, at) => at !== pendingRemoval))
                }
                setPendingRemoval(null)
              }}
            >
              Remove it
            </ResponsiveAlertDialogAction>
          </ResponsiveAlertDialogFooter>
        </ResponsiveAlertDialogContent>
      </ResponsiveAlertDialog>
    </div>
  )
}
