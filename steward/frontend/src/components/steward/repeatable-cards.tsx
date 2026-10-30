import { Hint } from "@/components/steward/status"
import { PlusIcon, TrashIcon } from "@phosphor-icons/react"
import { useMemo, useState } from "react"

import type { ConfigEntry } from "@/lib/api"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { ListControl, ScalarControl, explanationOf, isRequiredChannel } from "@/components/steward/config-controls"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { FieldLabelIcon, TERM_ICON } from "@/components/steward/term-icons"
import { Textarea } from "@/components/ui/textarea"
import { t } from "@/lib/texts"
import { Empty } from "@/components/steward/query-state"

export type SectionValues = {
  [key: string]: string | string[] | SectionValues[]
}

/**
 * An empty list with a fixed identity for "no template", so a memo that depends on it does not recompute on every render.
 */
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

export function sectionsFromEntry(entry: ConfigEntry): SectionValues[] {
  return (entry.sections ?? []).map(fieldsToValues)
}

function blankValue(field: ConfigEntry): string | string[] | SectionValues[] {
  if (field.kind === "SECTIONS") return []
  if (field.kind === "LIST") return field.items ?? []
  return field.value ?? ""
}

export function blankSection(template: ConfigEntry[]): SectionValues {
  const values: SectionValues = {}
  for (const field of template) values[field.key] = blankValue(field)
  return values
}

export function appliesTo(field: ConfigEntry, section: SectionValues): boolean {
  return !field.appliesWhen || field.appliesWhen.values.includes(textOf(section, field.appliesWhen.key))
}

/**
 * Returns `section` with `key` set to `value`. Every field that depends on that key goes back to its default where it changed: a field that no longer applies, and a field whose reference depends on the key, such as the subjects of a statistic.
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

function textOf(section: SectionValues | undefined, key: string): string {
  const value = section?.[key]
  return typeof value === "string" ? value : ""
}

function stringsOf(value: string | string[] | SectionValues[] | undefined): string[] {
  return Array.isArray(value) && value.every((item) => typeof item === "string") ? value : []
}

/**
 * Whether the card at `index` shows `key`'s default because its stored copy lacks the field. It holds only while the
 * draft keeps that default, so a card moved by a removal is not marked by its neighbour's state.
 */
export function notSaved(entry: ConfigEntry, index: number, key: string, shown: string): boolean {
  const stored = entry.sections?.[index]?.find((field) => field.key === key)
  return stored !== undefined && !stored.filled && !stored.secret && (stored.value ?? "") === shown
}

export function sectionsOf(value: string | string[] | SectionValues[] | undefined): SectionValues[] {
  return Array.isArray(value) && value.every((item) => typeof item === "object" && item !== null) ? value : []
}

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
  /** The caller decides, because the schema has no titles. */
  sectionTitle?: (section: SectionValues, index: number) => string
  /**
   * Set for a list inside another card, whose entries are divided by a rule. It names the add button.
   */
  within?: string
}) {
  const template = entry.template ?? NO_TEMPLATE

  const requiredChannelFields = useMemo(() => template.filter((field) => isRequiredChannel(field)), [template])

  const [pendingRemoval, setPendingRemoval] = useState<number | null>(null)
  const listExplanation = explanationOf(entry)

  const protectedIndex = entry.protectedEntry
    ? value.findIndex((section) => textOf(section, entry.protectedEntry!.field) === entry.protectedEntry!.value)
    : -1

  /**
   * A schema without a single shape for every entry sends no template, so the list is edited as raw text.
   */
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

  const titleOf = (section: SectionValues, index: number) =>
    sectionTitle?.(section, index) ??
    (textOf(section, "key").trim() || t("steward.settings.entry", { index: index + 1 }))

  return (
    <div className="flex flex-col gap-3">
      {value.length === 0 ? <Empty title={t("steward.settings.no-entries")} /> : null}
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
              <Hint tip={isProtected ? (listExplanation ?? t("steward.settings.cannot-remove")) : undefined}>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  disabled={disabled || isProtected}
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
              </Hint>
            </div>
            {missing.length > 0 ? (
              <p className="flex items-start gap-1.5 text-sm text-warning">
                <TERM_ICON.warning aria-hidden className="mt-0.5 size-4 shrink-0" />
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
                  /**
                   * The file's copy of this nested list. A card added in this draft has none yet.
                   */
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
                    <Label htmlFor={id} className="gap-1.5 text-sm font-medium">
                      <FieldLabelIcon fieldKey={field.key} className="size-4 shrink-0 text-muted-foreground" />
                      {field.label}
                    </Label>
                    {explanation ? (
                      <p className="whitespace-pre-wrap text-xs text-muted-foreground">{explanation}</p>
                    ) : null}
                    {control}
                    {field.kind === "SCALAR" && notSaved(entry, index, field.key, textOf(section, field.key)) ? (
                      <p className="text-xs text-muted-foreground italic">{t("steward.settings.not-saved")}</p>
                    ) : null}
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

      {/* Removing only changes the draft. Nothing is written to the file until Save. */}
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
        destructive
        act={() => {
          if (pendingRemoval !== null) onChange(value.filter((_, at) => at !== pendingRemoval))
        }}
      />
    </div>
  )
}
