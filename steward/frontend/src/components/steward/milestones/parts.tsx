import {
  ArrowDownIcon,
  ArrowUpIcon,
  CircleDashedIcon,
  CoinsIcon,
  PinwheelIcon,
  TrashIcon,
  UserGearIcon,
} from "@phosphor-icons/react"
import type { Icon } from "@phosphor-icons/react"
import { useState } from "react"

import type { ConfigEntry } from "@/lib/api"
import { isColour } from "@/lib/references"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { ListControl, ScalarControl } from "@/components/steward/config-controls"
import { ReferenceMarks } from "@/components/steward/reference-picker"
import type { SectionValues } from "@/components/steward/repeatable-cards"
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

/** The milestone marks drawn as an icon before their number; the rest show their value. */
const MARK_ICON: Partial<Record<(typeof MARKED)[number], Icon>> = {
  "border-diameter": CircleDashedIcon,
  "admin-unlocked": UserGearIcon,
}

const BUDGET_ICON: Record<Budget, Icon> = {
  "aura-budget": CoinsIcon,
  "spin-budget": PinwheelIcon,
}

/** Budget sums, each by its icon and titled by the objective field's schema label; a zero sum is left out. */
export function BudgetMarks({ sums, schema }: { sums: Record<Budget, number>; schema: TrackSchema }) {
  return BUDGETS.filter((key) => sums[key] > 0).map((key) => {
    const label = schema.objective.find((field) => field.key === key)?.label ?? key
    const MarkIcon = BUDGET_ICON[key]
    return (
      <span key={key} title={label} className="inline-flex items-center gap-1 tabular-nums">
        <MarkIcon aria-label={label} className="size-3.5" />
        {sums[key].toLocaleString("en")}
      </span>
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

/** A section's fields under their schema labels, two to a row where they are small. */
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
  return (
    <div
      className={cn(
        // A select keeps to its cell here, where the form elsewhere gives it a minimum width.
        "grid grid-cols-2 gap-x-3 gap-y-3 [&_[data-slot=select-trigger]]:w-full [&_[data-slot=select-trigger]]:min-w-0",
        className,
      )}
    >
      {fields.map((field) => {
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
 * A collapsed milestone's settings as small marks: a choice as its value, a number by its icon, a flag when on,
 * and the sums of its objectives' budgets.
 */
export function MilestoneMarks({ milestone, schema }: { milestone: SectionValues; schema: TrackSchema }) {
  const marks = MARKED.flatMap((key) => {
    const field = schema.milestone.find((candidate) => candidate.key === key)
    const value = text(milestone, key).trim()
    if (!field || value === "" || value === "0" || value === "false") return []
    return [{ key, field, value }]
  })
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1 text-xs text-muted-foreground">
      {marks.map(({ key, field, value }) => {
        const MarkIcon = MARK_ICON[key]
        if (field.type === "BOOLEAN") {
          return MarkIcon ? <MarkIcon key={key} aria-label={field.label} className="size-3.5" /> : null
        }
        if (MarkIcon) {
          return (
            <span key={key} title={field.label} className="inline-flex items-center gap-1 tabular-nums">
              <MarkIcon aria-label={field.label} className="size-3.5" />
              {Number(value).toLocaleString("en")}
            </span>
          )
        }
        return (
          <span key={key} title={field.label} className="font-mono text-[11px] tracking-tight text-foreground/80">
            {value}
          </span>
        )
      })}
      <BudgetMarks sums={budgetSums([milestone])} schema={schema} />
    </span>
  )
}

/** What an objective names, as icons: the first reference it holds a value in. */
export function ObjectiveMarks({
  objective,
  schema,
  max = 3,
  counted = true,
}: {
  objective: SectionValues
  schema: TrackSchema
  max?: number
  counted?: boolean
}) {
  const references = schema.objective.filter((field) => field.refers && !isColour(field.refers))
  const filled = references
    .map((field) => ({
      field,
      values: field.kind === "LIST" ? strings(objective, field.key) : [text(objective, field.key)],
    }))
    .map(({ field, values }) => ({ field, values: values.filter((value) => value.trim() !== "") }))
    .filter(({ values }) => values.length > 0)
  /** A subject says more than the statistic it is counted by, so the last filled reference stands for it. */
  const shown = filled.at(-1)
  if (!shown?.field.refers) return <span className="size-5 shrink-0 rounded-sm bg-muted" aria-hidden />
  return (
    <ReferenceMarks
      reference={shown.field.refers}
      values={shown.values}
      sibling={siblingOf(shown.field, objective)}
      max={max}
      counted={counted}
    />
  )
}

/** An objective as one line: what it names, its ID and its target; the whole line opens it. */
export function ObjectiveRow({
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
  return (
    <button
      type="button"
      onClick={onOpen}
      className={cn(
        "flex min-h-10 w-full min-w-0 items-center gap-3 rounded-md px-2 py-1.5 text-left text-sm outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring/50",
        className,
      )}
    >
      <span className="flex w-20 shrink-0 items-center overflow-hidden">
        <ObjectiveMarks objective={objective} schema={schema} max={2} />
      </span>
      <span className={cn("min-w-0 flex-1 truncate", id === "" && "text-muted-foreground")}>{id || "unnamed"}</span>
      <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
        {Number.isFinite(target) ? target.toLocaleString("en") : ""}
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
                onChange={(key, value) => onChange({ ...objective, [key]: value })}
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
