import { useMemo } from "react"
import { Plus, Trash2 } from "lucide-react"

import type { ConfigEntry, GuildList } from "@/lib/api"
import { ScalarControl, explanationOf } from "@/components/steward/config-controls"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"

/**
 * One repeatable card, drawn from a `SECTIONS` entry (steward/57).
 *
 * `languages` in `discord-bot/access.yml` (steward/49) is the case this was built for: a YAML
 * sequence of mappings, each carrying the same handful of fields. **This is only the frontend half
 * of the mechanism** - `SECTIONS` does not exist on the worker today; see the long comment on
 * `ConfigEntry.kind` in `lib/api.ts` for exactly what `steward-worker`'s `ConfigFiles` would have
 * to grow before a real file can drive this component. Everything below works against the shape
 * that finding proposes, checked with fixtures rather than a live file.
 *
 * A card holds no field the schema did not put in `template` - not a title, not an index beyond the
 * plain "Entry N" every card gets regardless of its content, because a generic mechanism has no way
 * to know which field of an arbitrary section would make a good title.
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
}: {
  entry: ConfigEntry
  value: SectionValues[]
  disabled: boolean
  roles: GuildList | undefined
  channels: GuildList | undefined
  onChange: (value: SectionValues[]) => void
}) {
  const template = entry.template ?? []
  // What a field started from, per section - the only thing `ScalarControl`'s secret placeholder
  // needs `edited` for, and there is no schema-shaped default to fall back to beside "the file's
  // own copy", the same way the top-level form uses the document rather than a hardcoded default.
  const original = useMemo(() => sectionsFromEntry(entry), [entry])

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
      {value.map((section, index) => (
        <Card key={index} className="gap-3 py-4">
          <CardContent className="flex flex-col gap-3 px-4">
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-medium text-muted-foreground">Entry {index + 1}</span>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                disabled={disabled}
                aria-label={`Remove entry ${index + 1}`}
                onClick={() => onChange(value.filter((_, at) => at !== index))}
              >
                <Trash2 aria-hidden />
              </Button>
            </div>
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
      ))}
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
    </div>
  )
}
