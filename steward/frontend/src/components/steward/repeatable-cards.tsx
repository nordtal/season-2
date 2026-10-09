import { PlusIcon, TrashIcon, WarningCircleIcon } from "@phosphor-icons/react"
import { useMemo, useState } from "react"

import type { ConfigEntry } from "@/lib/api"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { ListControl, ScalarControl, explanationOf, isRequiredChannel } from "@/components/steward/config-controls"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { t } from "@/lib/texts"

/** One section's fields as a card edits them, a list of sections holding the same record one level down. */
export type SectionValues = {
  [key: string]: string | string[] | SectionValues[]
}

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

/** One field's value in a brand new section: what its template holds, which is the schema's default. */
function blankValue(field: ConfigEntry): string | string[] | SectionValues[] {
  if (field.kind === "SECTIONS") return []
  if (field.kind === "LIST") return field.items ?? []
  return field.value ?? ""
}

/** A brand new section with every key `template` names, each at its default. */
export function blankSection(template: ConfigEntry[]): SectionValues {
  const values: SectionValues = {}
  for (const field of template) values[field.key] = blankValue(field)
  return values
}

/** Whether a field applies to `section`: always, unless its schema names the sibling values it is for. */
export function appliesTo(field: ConfigEntry, section: SectionValues): boolean {
  return !field.appliesWhen || field.appliesWhen.values.includes(textOf(section, field.appliesWhen.key))
}

/**
 * `section` with `key` set to `value`, and every field that hangs on that key back at its default where it changed:
 * one that no longer applies, and one whose reference depends on it, such as the subjects of a statistic.
 */
export function withValue(
  template: ConfigEntry[],
  section: SectionValues,
  key: string,
  value: string | string[] | SectionValues[],
): SectionValues {
  const next: SectionValues = { ...section, [key]: value }
  for (const field of template) {
    const leftBehind = field.appliesWhen?.key === key && appliesTo(field, section) && !appliesTo(field, next)
    const repointed = field.refers?.dependsOn === key && textOf(section, key) !== textOf(next, key)
    if (leftBehind || repointed) next[field.key] = blankValue(field)
  }
  return next
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
export function sectionsOf(value: string | string[] | SectionValues[] | undefined): SectionValues[] {
  return Array.isArray(value) && value.every((item) => typeof item === "object" && item !== null) ? value : []
}

/** The entries of a `SECTIONS` value as cards, each drawn from `template`, appended and removed one at a time. */
export function RepeatableCards({
  entry,
  value,
  disabled,
  onChange,
  sectionTitle,
  within,
}: {
  entry: ConfigEntry
  value: SectionValues[]
  disabled: boolean
  onChange: (value: SectionValues[]) => void
  /** What to call a card instead of "Entry N"; the caller decides, since the schema knows no titles. */
  sectionTitle?: (section: SectionValues, index: number) => string
  /** Set for a list inside another card, whose entries are divided by a rule; it names the add button. */
  within?: string
}) {
  const template = entry.template ?? NO_TEMPLATE

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
        <p className="text-sm text-muted-foreground">{t("steward.settings.raw-list")}</p>
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
      {value.length === 0 ? <p className="text-sm text-muted-foreground">{t("steward.settings.no-entries")}</p> : null}
      {value.map((section, index) => {
        const missing = requiredChannelFields.filter((field) => !textOf(section, field.key).trim())
        const isProtected = index === protectedIndex
        const title = titleOf(section, index)
        const replace = (key: string, next: string | string[] | SectionValues[]) =>
          onChange(value.map((s, at) => (at === index ? withValue(template, s, key, next) : s)))
        const body = (
          <>
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-medium text-muted-foreground">{title}</span>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                disabled={disabled || isProtected}
                title={isProtected ? (listExplanation ?? t("steward.settings.cannot-remove")) : undefined}
                aria-label={
                  isProtected
                    ? t("steward.settings.entry-cannot-remove", {
                        index: index + 1,
                      })
                    : within
                      ? t("steward.settings.remove", { what: title })
                      : t("steward.settings.remove-entry", { index: index + 1 })
                }
                onClick={() => setPendingRemoval(index)}
              >
                <TrashIcon aria-hidden />
              </Button>
            </div>
            {missing.length > 0 ? (
              <p className="flex items-start gap-1.5 text-sm text-amber-600 dark:text-amber-500">
                <WarningCircleIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
                <span>
                  {t("steward.settings.incomplete", {
                    missing: missing.map((field) => field.label).join(", "),
                  })}
                </span>
              </p>
            ) : null}
            {template
              .filter((field) => appliesTo(field, section))
              .map((field) => {
                const id = `${entry.path}.${index}.${field.key}`
                const explanation = explanationOf(field)
                const fieldValue = section[field.key]
                let control
                if (field.kind === "LIST") {
                  control = (
                    <ListControl
                      id={id}
                      entry={field}
                      items={stringsOf(fieldValue)}
                      sibling={field.refers?.dependsOn ? textOf(section, field.refers.dependsOn) : undefined}
                      disabled={disabled}
                      onChange={(next) => replace(field.key, next)}
                    />
                  )
                } else if (field.kind === "SECTIONS") {
                  /** The file's copy of this nested list; a card added in this draft has none yet. */
                  const own = entry.sections?.[index]?.find((f) => f.key === field.key)
                  control = (
                    <RepeatableCards
                      entry={{
                        ...field,
                        ...own,
                        path: id,
                        template: field.template,
                      }}
                      value={sectionsOf(fieldValue)}
                      disabled={disabled}
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
                      sibling={field.refers?.dependsOn ? textOf(section, field.refers.dependsOn) : undefined}
                      disabled={disabled}
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
          aria-label={within ? t("steward.settings.add-to", { what: within }) : undefined}
          onClick={() => onChange([...value, blankSection(template)])}
        >
          <PlusIcon aria-hidden />
          {t("steward.settings.add-entry")}
        </Button>
      </div>

      {/* Removing only changes the draft; nothing is written to the file until Save. */}
      <AskThenAct
        open={pendingRemoval !== null}
        onOpenChange={(open) => open || setPendingRemoval(null)}
        title={
          pendingRemoval !== null
            ? t("steward.settings.remove-ask", {
                title: titleOf(value[pendingRemoval], pendingRemoval),
              })
            : t("steward.settings.remove-entry-ask")
        }
        description={listExplanation ? <span className="whitespace-pre-wrap">{listExplanation}</span> : undefined}
        cancel={t("steward.settings.keep-it")}
        action={t("steward.settings.remove-it")}
        act={() => {
          if (pendingRemoval !== null) onChange(value.filter((_, at) => at !== pendingRemoval))
        }}
      />
    </div>
  )
}
