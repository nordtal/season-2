import { ArrowDownIcon, ArrowUpIcon, TrashIcon } from "@phosphor-icons/react"
import { type ReactNode, useState } from "react"

import type { ConfigEntry } from "@/lib/api"
import { useGameData } from "@/lib/queries"
import { isColour, namesNothing } from "@/lib/references"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { ChoiceIcon, ListControl, ScalarControl, choiceName } from "@/components/steward/config-controls"
import { ReferenceMarks } from "@/components/steward/reference-picker"
import { FieldLabelIcon, fieldIcon } from "@/components/steward/term-icons"
import { type SectionValues, appliesTo, withValue } from "@/components/steward/repeatable-cards"
import {
  BUDGETS,
  type Budget,
  KEY,
  MARKED,
  type TrackSchema,
  budgetSums,
  strings,
  text,
} from "@/components/steward/milestones/model"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { cn } from "@/lib/utils"
import { t } from "@/lib/texts"

export function Pill({ title, className, children }: { title?: string; className?: string; children: ReactNode }) {
  return (
    <span
      title={title}
      className={cn(
        "inline-flex h-6 max-w-full min-w-0 shrink-0 items-center gap-1 rounded-full bg-muted px-2 text-xs text-foreground/80 tabular-nums",
        className,
      )}
    >
      {children}
    </span>
  )
}

function labelOf(fields: ConfigEntry[], key: string): string {
  return fields.find((field) => field.key === key)?.label ?? key
}

/**
 * A pill gets its label as title only where it stands for a milestone or the season, so a card's own sums are not mistaken for them.
 */
export function BudgetMarks({
  sums,
  schema,
  titled = true,
  className,
}: {
  sums: Record<Budget, number>
  schema: TrackSchema
  titled?: boolean
  className?: string
}) {
  return BUDGETS.filter((key) => sums[key] > 0).map((key) => {
    const label = labelOf(schema.objective, key)
    const icons = { Mark: fieldIcon(key) }
    return (
      <Pill key={key} title={titled ? label : undefined} className={className}>
        {icons.Mark ? <icons.Mark aria-label={label} className="size-3.5 shrink-0" /> : null}
        {sums[key].toLocaleString("en")}
      </Pill>
    )
  })
}

function siblingOf(field: ConfigEntry, section: SectionValues): string | undefined {
  return field.refers?.dependsOn ? text(section, field.refers.dependsOn) : undefined
}

export function FieldControl({
  id,
  field,
  section,
  disabled,
  onChange,
}: {
  id: string
  field: ConfigEntry
  section: SectionValues
  disabled: boolean
  onChange: (value: string | string[]) => void
}) {
  return field.kind === "LIST" ? (
    <ListControl
      id={id}
      entry={field}
      items={strings(section, field.key)}
      sibling={siblingOf(field, section)}
      disabled={disabled}
      onChange={onChange}
    />
  ) : (
    <ScalarControl
      id={id}
      entry={field}
      value={text(section, field.key)}
      sibling={siblingOf(field, section)}
      disabled={disabled}
      onChange={onChange}
    />
  )
}

function isWide(field: ConfigEntry): boolean {
  return field.kind === "LIST" || (field.refers !== undefined && !isColour(field.refers))
}

/**
 * Returns the fields that apply to a section, without a reference that has nothing to name, such as the subjects of a statistic kept per nothing.
 */
function useShownFields(fields: ConfigEntry[], section: SectionValues): ConfigEntry[] {
  const game = useGameData(fields.some((field) => field.refers?.to === "SUBJECT"))
  return fields.filter(
    (field) =>
      appliesTo(field, section) && !(field.refers && namesNothing(field.refers, game.data, siblingOf(field, section))),
  )
}

export function Fields({
  id,
  fields,
  section,
  disabled,
  onChange,
  className,
}: {
  id: string
  fields: ConfigEntry[]
  section: SectionValues
  disabled: boolean
  onChange: (key: string, value: string | string[]) => void
  className?: string
}) {
  const shown = useShownFields(fields, section)
  return (
    <div
      className={cn(
        // A select keeps to its cell here. Elsewhere the form gives it a minimum width.
        "grid grid-cols-2 gap-x-3 gap-y-3 [&_[data-slot=select-trigger]]:w-full [&_[data-slot=select-trigger]]:min-w-0",
        className,
      )}
    >
      {shown.map((field) => {
        const fieldId = `${id}.${field.key}`
        return (
          <div key={field.key} className={cn("flex min-w-0 flex-col gap-1", isWide(field) && "col-span-2")}>
            <Label htmlFor={fieldId} className="text-xs font-medium text-muted-foreground">
              <FieldLabelIcon fieldKey={field.key} />
              {field.label}
            </Label>
            <FieldControl
              id={fieldId}
              field={field}
              section={section}
              disabled={disabled}
              onChange={(value) => onChange(field.key, value)}
            />
          </div>
        )
      })}
    </div>
  )
}

export function milestoneFields(schema: TrackSchema): ConfigEntry[] {
  return schema.milestone.filter((field) => field.key !== KEY.objectives)
}

/**
 * A field that does not apply, such as a diameter without a border, is left out. A number that applies only for a choice sits in that choice's pill.
 */
export function MilestoneMarks({ milestone, schema }: { milestone: SectionValues; schema: TrackSchema }) {
  const marks = MARKED.flatMap((key) => {
    const field = schema.milestone.find((candidate) => candidate.key === key)
    const value = text(milestone, key).trim()
    if (!field || !appliesTo(field, milestone) || value === "" || value === "0" || value === "false") return []
    return [{ key, field, value }]
  })
  const joined = (choice: string) => marks.filter(({ field }) => field.appliesWhen?.key === choice && !field.choices)
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-1 lg:flex-nowrap">
      {marks.map(({ key, field, value }) => {
        if (field.choices) {
          return (
            <Pill key={key} className="pl-1">
              <ChoiceIcon choices={field.choices} value={value} size={18} />
              <span className="truncate">{choiceName(field.choices, value)}</span>
              {joined(key).map((mark) => (
                <span key={mark.key}>{Number(mark.value).toLocaleString("en")}</span>
              ))}
            </Pill>
          )
        }
        if (marks.some((mark) => mark.field.choices && mark.key === field.appliesWhen?.key)) return null
        const icons = { Mark: fieldIcon(key) }
        const mark = icons.Mark ? <icons.Mark aria-label={field.label} className="size-3.5 shrink-0" /> : field.label
        if (field.type === "BOOLEAN") {
          return (
            <Pill key={key} title={field.label}>
              {mark}
            </Pill>
          )
        }
        return (
          <Pill key={key} title={field.label}>
            {mark}
            {Number(value).toLocaleString("en")}
          </Pill>
        )
      })}
      <BudgetMarks sums={budgetSums([milestone])} schema={schema} />
    </span>
  )
}

function TypeIcon({ objective, schema, size }: { objective: SectionValues; schema: TrackSchema; size: number }) {
  const choices = schema.objective.find((field) => field.key === KEY.type)?.choices
  const type = text(objective, KEY.type)
  const game = useGameData(choices?.icons !== undefined)
  if (!choices || choices.icons?.[type] === undefined || game.data?.icons === undefined) return null
  return <ChoiceIcon choices={choices} value={type} size={size} />
}

/**
 * A statistic shows by its name alone, because none of its icons would say which. A field that another field depends on comes after it, so subjects come before the statistic that counts them.
 */
export function ObjectiveMarks({
  objective,
  schema,
  max = 3,
}: {
  objective: SectionValues
  schema: TrackSchema
  max?: number
}) {
  const named = schema.objective
    .filter((field) => field.refers && !isColour(field.refers) && appliesTo(field, objective))
    .map((field) => ({
      field,
      values: (field.kind === "LIST" ? strings(objective, field.key) : [text(objective, field.key)]).filter(
        (value) => value.trim() !== "",
      ),
    }))
    .filter(({ values }) => values.length > 0)
  const dependedOn = new Set(named.flatMap(({ field }) => field.refers?.dependsOn ?? []))
  return named
    .toSorted((a, b) => Number(dependedOn.has(a.field.key)) - Number(dependedOn.has(b.field.key)))
    .map(({ field, values }) =>
      field.refers ? (
        <ReferenceMarks
          key={field.key}
          reference={field.refers}
          values={values}
          sibling={siblingOf(field, objective)}
          max={max}
          named={field.kind !== "LIST"}
        />
      ) : null,
    )
}

export function ObjectiveCard({
  objective,
  schema,
  onOpen,
  className,
}: {
  objective: SectionValues
  schema: TrackSchema
  onOpen: () => void
  className?: string
}) {
  const id = text(objective, KEY.id)
  const target = Number(text(objective, KEY.target))
  const choices = schema.objective.find((field) => field.key === KEY.type)?.choices
  const type = text(objective, KEY.type)
  return (
    <button
      type="button"
      onClick={onOpen}
      className={cn(
        "flex w-full min-w-0 flex-col gap-2 rounded-lg bg-muted/60 p-2.5 text-left text-sm outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring/50",
        className,
      )}
    >
      <span className="flex min-w-0 items-center gap-2">
        <TypeIcon objective={objective} schema={schema} size={20} />
        {choices && type !== "" ? <span className="shrink-0 font-medium">{choiceName(choices, type)}</span> : null}
        <span className={cn("min-w-0 flex-1 truncate text-muted-foreground", id === "" && "italic")}>
          {id || "unnamed"}
        </span>
        <span className="flex shrink-0 items-center gap-1">
          <BudgetMarks
            sums={budgetSums([{ [KEY.objectives]: [objective] }])}
            schema={schema}
            titled={false}
            className="bg-background/70"
          />
        </span>
      </span>
      <span className="flex min-h-6 min-w-0 items-center gap-2 overflow-hidden whitespace-nowrap">
        {Number.isFinite(target) && target > 0 ? (
          <span className="shrink-0 text-xs font-medium tabular-nums">{target.toLocaleString("en")}</span>
        ) : null}
        <ObjectiveMarks objective={objective} schema={schema} max={6} />
      </span>
    </button>
  )
}

export function ObjectiveSheet({
  id,
  objective,
  schema,
  disabled,
  onChange,
  onRemove,
  onClose,
}: {
  id: string
  objective: SectionValues | null
  schema: TrackSchema
  disabled: boolean
  onChange: (next: SectionValues) => void
  onRemove: () => void
  onClose: () => void
}) {
  const [asking, setAsking] = useState(false)
  const name = objective ? text(objective, KEY.id) || t("steward.game.objective") : ""
  return (
    <>
      <ResponsiveDialog open={objective !== null} onOpenChange={(open) => open || onClose()}>
        <ResponsiveDialogContent className="sm:max-w-lg">
          <ResponsiveDialogHeader>
            <ResponsiveDialogTitle>{name}</ResponsiveDialogTitle>
          </ResponsiveDialogHeader>
          {objective ? (
            <div className="-mx-1 max-h-list overflow-y-auto px-1">
              <Fields
                id={id}
                fields={schema.objective}
                section={objective}
                disabled={disabled}
                onChange={(key, value) => onChange(withValue(schema.objective, objective, key, value))}
              />
            </div>
          ) : null}
          <ResponsiveDialogFooter>
            <Button
              type="button"
              variant="destructive"
              className="sm:mr-auto"
              disabled={disabled}
              onClick={() => setAsking(true)}
            >
              <TrashIcon aria-hidden />
              {t("steward.form.remove")}
            </Button>
            <Button type="button" onClick={onClose}>
              {t("steward.form.done")}
            </Button>
          </ResponsiveDialogFooter>
        </ResponsiveDialogContent>
      </ResponsiveDialog>
      <AskThenAct
        open={asking}
        onOpenChange={setAsking}
        title={t("steward.game.remove-ask", { name })}
        cancel={t("steward.game.keep")}
        action={t("steward.game.remove-it")}
        destructive
        act={() => {
          onRemove()
          onClose()
        }}
      />
    </>
  )
}

export function MilestoneActions({
  name,
  index,
  count,
  disabled,
  onMove,
  onRemove,
}: {
  name: string
  index: number
  count: number
  disabled: boolean
  onMove: (to: number) => void
  onRemove: () => void
}) {
  return (
    <span className="flex shrink-0 items-center">
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        aria-label={t("steward.game.move-up", { name })}
        disabled={disabled || index === 0}
        onClick={() => onMove(index - 1)}
      >
        <ArrowUpIcon aria-hidden />
      </Button>
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        aria-label={t("steward.game.move-down", { name })}
        disabled={disabled || index === count - 1}
        onClick={() => onMove(index + 1)}
      >
        <ArrowDownIcon aria-hidden />
      </Button>
      <AskThenAct
        trigger={
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label={t("steward.settings.remove", { what: name })}
            disabled={disabled}
          >
            <TrashIcon aria-hidden />
          </Button>
        }
        title={t("steward.game.remove-ask", { name })}
        cancel={t("steward.game.keep")}
        action={t("steward.game.remove-it")}
        act={onRemove}
      />
    </span>
  )
}
