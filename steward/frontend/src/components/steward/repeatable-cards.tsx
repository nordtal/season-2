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

/** One section's fields as a card edits them, a list of sections holding the same record one level down. */
export type SectionValues = { [key: string]: string | string[] | SectionValues[] }

/** A stable identity for "no template", so a memo keyed on it does not recompute every render. */
const NO_TEMPLATE: ConfigEntry[] = []

function fieldsToValues(fields: ConfigEntry[]): SectionValues {
  const values: SectionValues = {}
  for (const field of fields) {
    if (field.kind === "LIST") values[field.key] = field.items ?? []
    else if (field.kind === "SECTIONS") values[field.key] = (field.sections ?? []).map(fieldsToValues)
    else values[field.key] = field.value ?? ""
  }
  return values
}

/** The document's own sections, as records, which a card's draft starts from. */
export function sectionsFromEntry(entry: ConfigEntry): SectionValues[] {
  return (entry.sections ?? []).map(fieldsToValues)
}

/** A brand new section with every key `template` names, empty. */
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

/** A LIST field's own value: strings, never the sections array a SECTIONS field would hold. */
function stringsOf(value: string | string[] | SectionValues[] | undefined): string[] {
  return Array.isArray(value) && value.every((item) => typeof item === "string") ? value : []
}

/** A SECTIONS field's own value: nested records, never the strings a LIST field would hold. */
function sectionsOf(value: string | string[] | SectionValues[] | undefined): SectionValues[] {
  return Array.isArray(value) && value.every((item) => typeof item === "object" && item !== null) ? value : []
}

/** The entries of a `SECTIONS` value as cards, each drawn from `template`, appended and removed one at a time. */
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
  /** What to call a card instead of "Entry N"; the caller decides, since the schema knows no titles. */
  sectionTitle?: (section: SectionValues, index: number) => string
  /** Set for a list inside another card, whose entries are divided by a rule; it names the add button. */
  within?: string
}) {
  const template = entry.template ?? NO_TEMPLATE
  /** The file's own copy of each section, which the secret placeholder compares against. */
  const original = useMemo(() => sectionsFromEntry(entry), [entry])

  /** The channel fields not marked optional, whose absence marks a card incomplete. */
  const requiredChannelFields = useMemo(() => template.filter((field) => isRequiredChannel(field)), [template])

  /** The index armed for removal, which only the dialog's confirmation splices out. */
  const [pendingRemoval, setPendingRemoval] = useState<number | null>(null)
  const listExplanation = explanationOf(entry)

  /** The entry the schema protects, greyed out here since steward refuses its removal too. */
  const protectedIndex = entry.protectedEntry
    ? value.findIndex((section) => textOf(section, entry.protectedEntry!.field) === entry.protectedEntry!.value)
    : -1

  /** A schema with no single shape for every entry sends no template, so the list is edited as raw text. */
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
            className="font-mono text-sm max-md:text-base"
          />
        ))}
      </div>
    )
  }

  /** A card's title: the caller's, else its own `key` field, else its position. */
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
                    items={stringsOf(fieldValue)}
                    disabled={disabled}
                    onChange={(next) => replace(field.key, next)}
                  />
                )
              } else if (field.kind === "SECTIONS") {
                /** The file's copy of this nested list; a card added in this draft has none yet. */
                const own = entry.sections?.[index]?.find((f) => f.key === field.key)
                control = (
                  <RepeatableCards
                    entry={{ ...field, ...own, path: id, template: field.template }}
                    value={sectionsOf(fieldValue)}
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
