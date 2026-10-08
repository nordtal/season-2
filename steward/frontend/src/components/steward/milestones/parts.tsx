import {
  ArrowDownIcon,
  ArrowUpIcon,
  CircleDashedIcon,
  CoinsIcon,
  PinwheelIcon,
  TargetIcon,
  TrashIcon,
  UserGearIcon,
} from "@phosphor-icons/react"
import type { Icon } from "@phosphor-icons/react"
import { type ReactNode, useState } from "react"

import type { ConfigEntry } from "@/lib/api"
import { useGameData } from "@/lib/queries"
import { isColour, namesNothing } from "@/lib/references"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { ChoiceIcon, ListControl, ScalarControl, choiceName } from "@/components/steward/config-controls"
import { ReferenceMarks } from "@/components/steward/reference-picker"
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

/** The milestone fields drawn as an icon before their value; a choice shows its own icon and name. */
const MARK_ICON: Partial<Record<(typeof MARKED)[number], Icon>> = {
  "border-diameter": CircleDashedIcon,
  "admin-unlocked": UserGearIcon,
}

const BUDGET_ICON: Record<Budget, Icon> = {
  "aura-budget": CoinsIcon,
  "spin-budget": PinwheelIcon,
}

/** A small rounded mark holding one value, the way a closed row shows its settings. */
function Pill({ title, className, children }: { title?: string; className?: string; children: ReactNode }) {
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

/** A label from the schema, for the key the layout places. */
function labelOf(fields: ConfigEntry[], key: string): string {
  return fields.find((field) => field.key === key)?.label ?? key
}

/**
 * Budget sums as pills, each by its icon and named by the objective field's schema label; a zero sum is left out.
 *
 * A pill is titled by its label only where it stands for a milestone or the season, so a card's own sums are not
 * mistaken for them.
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
    const MarkIcon = BUDGET_ICON[key]
    return (
      <Pill key={key} title={titled ? label : undefined} className={className}>
        <MarkIcon aria-label={label} className="size-3.5 shrink-0" />
        {sums[key].toLocaleString("en")}
      </Pill>
    )
  })
}

/** A field's sibling value where its reference depends on one, such as the statistic a subject is counted by. */
function siblingOf(field: ConfigEntry, section: SectionValues): string | undefined {
  return field.refers?.dependsOn ? text(section, field.refers.dependsOn) : undefined
}

/** One field of a section, by the control its schema asks for. */
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

/** Whether a field takes a whole row: a list, or a reference whose picker shows a name. */
function isWide(field: ConfigEntry): boolean {
  return field.kind === "LIST" || (field.refers !== undefined && !isColour(field.refers))
}

/**
 * The fields a section asks for: those that apply to it, without a reference that has nothing to name, such as the
 * subjects of a statistic kept per nothing.
 */
function useShownFields(fields: ConfigEntry[], section: SectionValues): ConfigEntry[] {
  const game = useGameData(fields.some((field) => field.refers?.to === "SUBJECT"))
  return fields.filter(
    (field) =>
      appliesTo(field, section) && !(field.refers && namesNothing(field.refers, game.data, siblingOf(field, section))),
  )
}

/** A section's fields under their schema labels, two to a row where they are small; only those that apply. */
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
  /** The grid's columns, two by default. */
  className?: string
}) {
  const shown = useShownFields(fields, section)
  return (
    <div
      className={cn(
        // A select keeps to its cell here, where the form elsewhere gives it a minimum width.
        "grid grid-cols-2 gap-x-3 gap-y-3 [&_[data-slot=select-trigger]]:w-full [&_[data-slot=select-trigger]]:min-w-0",
        className,
      )}
    >
      {shown.map((field) => {
        const fieldId = `${id}.${field.key}`
        return (
          <div key={field.key} className={cn("flex min-w-0 flex-col gap-1", isWide(field) && "col-span-2")}>
            <Label htmlFor={fieldId} className="text-xs font-medium text-muted-foreground">
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

/** The milestone's own fields, without its objectives. */
export function milestoneFields(schema: TrackSchema): ConfigEntry[] {
  return schema.milestone.filter((field) => field.key !== KEY.objectives)
}

/**
 * A collapsed milestone's settings as pills: a choice by its icon and name, a number by its icon, a flag when on,
 * and the sums of its objectives' budgets. A field that does not apply, such as a diameter without a border, is
 * left out.
 */
export function MilestoneMarks({ milestone, schema }: { milestone: SectionValues; schema: TrackSchema }) {
  const marks = MARKED.flatMap((key) => {
    const field = schema.milestone.find((candidate) => candidate.key === key)
    const value = text(milestone, key).trim()
    if (!field || !appliesTo(field, milestone) || value === "" || value === "0" || value === "false") return []
    return [{ key, field, value }]
  })
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-1">
      {marks.map(({ key, field, value }) => {
        if (field.choices) {
          return (
            <Pill key={key} className="pl-1">
              <ChoiceIcon choices={field.choices} value={value} size={18} />
              <span className="truncate">{choiceName(field.choices, value)}</span>
            </Pill>
          )
        }
        const MarkIcon = MARK_ICON[key] ?? CircleDashedIcon
        if (field.type === "BOOLEAN") {
          return (
            <Pill key={key} title={field.label}>
              <MarkIcon aria-label={field.label} className="size-3.5" />
            </Pill>
          )
        }
        return (
          <Pill key={key} title={field.label}>
            <MarkIcon aria-label={field.label} className="size-3.5 shrink-0" />
            {Number(value).toLocaleString("en")}
          </Pill>
        )
      })}
      <BudgetMarks sums={budgetSums([milestone])} schema={schema} />
    </span>
  )
}

/** The objective's type by its icon, or by its name where the icon cannot be drawn. */
function TypeMark({ objective, schema, size }: { objective: SectionValues; schema: TrackSchema; size: number }) {
  const choices = schema.objective.find((field) => field.key === KEY.type)?.choices
  const type = text(objective, KEY.type)
  const game = useGameData(choices?.icons !== undefined)
  if (!choices || type === "") return null
  const name = choiceName(choices, type)
  if (choices.icons?.[type] === undefined || game.data?.icons === undefined) {
    return <span className="shrink-0 text-xs text-muted-foreground">{name}</span>
  }
  return (
    <span title={name} className="shrink-0">
      <ChoiceIcon choices={choices} value={type} size={size} />
    </span>
  )
}

/**
 * What an objective names, field by field among those that apply: an item or a subject by its icon, a statistic by
 * its name, since none of its icons would say which.
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
  return named.map(({ field, values }) =>
    field.refers ? (
      <ReferenceMarks
        key={field.key}
        reference={field.refers}
        values={values}
        sibling={siblingOf(field, objective)}
        max={max}
      />
    ) : null,
  )
}

/** An objective as one pill, titled by its ID: its type, then what it names. */
export function ObjectivePill({ objective, schema }: { objective: SectionValues; schema: TrackSchema }) {
  return (
    <Pill title={text(objective, KEY.id) || undefined} className="gap-1.5 pl-1">
      <TypeMark objective={objective} schema={schema} size={18} />
      <ObjectiveMarks objective={objective} schema={schema} max={2} />
    </Pill>
  )
}

/** An objective as a card of its own: type, ID and target, then what it names and its budgets; it opens the sheet. */
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
  const targetLabel = labelOf(schema.objective, KEY.target)
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
        <TypeMark objective={objective} schema={schema} size={20} />
        <span className={cn("min-w-0 flex-1 truncate font-medium", id === "" && "text-muted-foreground")}>
          {id || "unnamed"}
        </span>
        {Number.isFinite(target) && target > 0 ? (
          <span className="inline-flex shrink-0 items-center gap-1 text-xs tabular-nums text-muted-foreground">
            <TargetIcon aria-label={targetLabel} className="size-3.5" />
            {target.toLocaleString("en")}
          </span>
        ) : null}
      </span>
      <span className="flex min-h-6 min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <ObjectiveMarks objective={objective} schema={schema} max={6} />
        <span className="ml-auto flex shrink-0 items-center gap-1">
          <BudgetMarks
            sums={budgetSums([{ [KEY.objectives]: [objective] }])}
            schema={schema}
            titled={false}
            className="bg-background/70"
          />
        </span>
      </span>
    </button>
  )
}

/** One objective's every field in a sheet, a bottom sheet on a phone; its edits go straight into the draft. */
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
            <div className="-mx-1 max-h-[65svh] overflow-y-auto px-1 sm:max-h-[70vh]">
              <Fields
                id={id}
                fields={schema.objective}
                section={objective}
                disabled={disabled}
                onChange={(key, value) => onChange(withValue(schema.objective, objective, key, value))}
              />
            </div>
          ) : null}
          <ResponsiveDialogFooter className="flex-row justify-between gap-2">
            <Button type="button" variant="ghost" disabled={disabled} onClick={() => setAsking(true)}>
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
        act={() => {
          onRemove()
          onClose()
        }}
      />
    </>
  )
}

/** Move up, move down and remove for one milestone. */
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
