import { useMemo, useState } from "react"
import { CircleAlert, Plus, Trash2 } from "lucide-react"

import type { ConfigEntry, GuildList } from "@/lib/api"
import {
  ScalarControl,
  explanationOf,
  isRequiredChannel,
} from "@/components/steward/config-controls"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
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

/** One section's fields, flattened to the plain `{key: value}` record a card edits. */
export type SectionValues = Record<string, string>

function fieldsToValues(fields: ConfigEntry[]): SectionValues {
  const values: SectionValues = {}
  for (const field of fields) values[field.key] = field.value ?? ""
  return values
}

/** The document's own sections, as a card's draft starts from - `entry.sections`, flattened. */
export function sectionsFromEntry(entry: ConfigEntry): SectionValues[] {
  return (entry.sections ?? []).map(fieldsToValues)
}

/** A brand new, empty section - every key `template` names, with an empty value. */
export function blankSection(template: ConfigEntry[]): SectionValues {
  return fieldsToValues(template)
}

export function RepeatableCards({
  entry,
  value,
  disabled,
  roles,
  channels,
  onChange,
  sectionTitle,
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
}) {
  const template = entry.template ?? []
  // What a field started from, per section - the only thing `ScalarControl`'s secret placeholder
  // needs `edited` for, and there is no schema-shaped default to fall back to beside "the file's
  // own copy", the same way the top-level form uses the document rather than a hardcoded default.
  const original = useMemo(() => sectionsFromEntry(entry), [entry])

  // Which of a section's fields are a channel the schema does not call optional (steward/61's
  // "missing a channel is visibly incomplete") - computed once per template, not per section, since
  // it only reads field shape and never a value.
  const requiredChannelFields = useMemo(
    () => template.filter((field) => isRequiredChannel(field)),
    [template],
  )

  // Removing is a two-step action: the trash icon arms it, and only the dialog's own confirmation
  // splices the draft. `null` is "nothing armed". This is the generic answer steward/49 and
  // steward/61 settled on in place of refusing to remove `en` outright - `SchemaNode` has no way to
  // mark one list entry as protected (steward/74), so the interface cannot enforce that, only make
  // sure a human reads the list's own reason not to before they do it. Nothing below reads a tag or
  // any other field value to decide whether to ask; every removal gets the same dialog, and what it
  // shows is `explanationOf(entry)` - whatever the schema already says about the whole list.
  const [pendingRemoval, setPendingRemoval] = useState<number | null>(null)
  const listExplanation = explanationOf(entry)

  // The ticket's own escape hatch: a schema that could not describe one shape for every entry sends
  // no template, and a card that pretended it had one would just be the field set of whichever
  // entry happened to come first, silently dropping the rest. Raw text, clearly marked, beats that.
  if (template.length === 0) {
    return (
      <div className="flex flex-col gap-2">
        <p className="text-sm text-muted-foreground">
          These entries are not uniform enough for one card each - no card fits, so this list stays
          raw text and is not editable here.
        </p>
        {value.map((section, index) => (
          <Textarea
            key={index}
            readOnly
            spellCheck={false}
            rows={Math.max(2, Object.keys(section).length)}
            value={Object.entries(section)
              .map(([key, val]) => `${key}: ${val}`)
              .join("\n")}
            className="font-mono text-sm"
          />
        ))}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-3">
      {value.length === 0 ? (
        <p className="text-sm text-muted-foreground">No entries yet.</p>
      ) : null}
      {value.map((section, index) => {
        const missing = requiredChannelFields.filter((field) => !(section[field.key] ?? "").trim())
        return (
        <Card key={index} className="gap-3 py-4">
          <CardContent className="flex flex-col gap-3 px-4">
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-medium text-muted-foreground">
                {sectionTitle ? sectionTitle(section, index) : `Entry ${index + 1}`}
              </span>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                disabled={disabled}
                aria-label={`Remove entry ${index + 1}`}
                onClick={() => setPendingRemoval(index)}
              >
                <Trash2 aria-hidden />
              </Button>
            </div>
            {missing.length > 0 ? (
              <p className="flex items-start gap-1.5 text-sm text-amber-600 dark:text-amber-500">
                <CircleAlert aria-hidden className="mt-0.5 size-4 shrink-0" />
                <span>
                  Incomplete - missing {missing.map((field) => field.label).join(", ")}.
                </span>
              </p>
            ) : null}
            {template.map((field) => {
              const id = `${entry.path}.${index}.${field.key}`
              const fieldValue = section[field.key] ?? ""
              const explanation = explanationOf(field)
              const edited = fieldValue !== (original[index]?.[field.key] ?? "")
              return (
                <div key={field.key} className="flex flex-col gap-1.5">
                  <Label htmlFor={id} className="text-sm font-medium">
                    {field.label}
                  </Label>
                  {explanation ? (
                    <p className="text-sm text-muted-foreground">{explanation}</p>
                  ) : null}
                  <ScalarControl
                    id={id}
                    entry={field}
                    value={fieldValue}
                    edited={edited}
                    disabled={disabled}
                    roles={roles}
                    channels={channels}
                    onChange={(next) =>
                      onChange(value.map((s, at) => (at === index ? { ...s, [field.key]: next } : s)))
                    }
                  />
                </div>
              )
            })}
          </CardContent>
        </Card>
        )
      })}
      <div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled}
          onClick={() => onChange([...value, blankSection(template)])}
        >
          <Plus aria-hidden />
          Add entry
        </Button>
      </div>

      <AlertDialog
        open={pendingRemoval !== null}
        onOpenChange={(open) => open || setPendingRemoval(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              Remove {pendingRemoval !== null && sectionTitle
                ? `"${sectionTitle(value[pendingRemoval], pendingRemoval)}"`
                : `entry ${(pendingRemoval ?? 0) + 1}`}?
            </AlertDialogTitle>
            <AlertDialogDescription className="whitespace-pre-wrap text-left">
              {listExplanation ??
                "This only changes the draft - nothing is written to the file until Save."}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep it</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (pendingRemoval !== null) {
                  onChange(value.filter((_, at) => at !== pendingRemoval))
                }
                setPendingRemoval(null)
              }}
            >
              Remove it
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
