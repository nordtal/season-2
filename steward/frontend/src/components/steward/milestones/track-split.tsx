import { CaretLeftIcon, CaretRightIcon, PlusIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { SectionValues } from "@/components/steward/repeatable-cards"
import {
  BUDGETS,
  KEY,
  type TrackEdits,
  type TrackSchema,
  budgetSums,
  objectivesOf,
  text,
} from "@/components/steward/milestones/model"
import {
  BudgetMarks,
  Fields,
  MilestoneActions,
  MilestoneMarks,
  ObjectiveCard,
  ObjectiveSheet,
  milestoneFields,
} from "@/components/steward/milestones/parts"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { t } from "@/lib/texts"

export type TrackProps = {
  id: string
  schema: TrackSchema
  track: SectionValues[]
  edits: TrackEdits
  disabled: boolean
}

type Open = { milestone: number; objective: number } | null

function useObjectiveSheet({ id, schema, track, edits, disabled }: TrackProps) {
  const [open, setOpen] = useState<Open>(null)
  const objective = open ? (objectivesOf(track[open.milestone])[open.objective] ?? null) : null
  const sheet = (
    <ObjectiveSheet
      id={open ? `${id}.${open.milestone}.${open.objective}` : id}
      objective={objective}
      schema={schema}
      disabled={disabled}
      onChange={(next) => open && edits.setObjective(open.milestone, open.objective, next)}
      onRemove={() => open && edits.removeObjective(open.milestone, open.objective)}
      onClose={() => setOpen(null)}
    />
  )
  const add = (milestone: number) => setOpen({ milestone, objective: edits.addObjective(milestone) })
  return {
    open: (milestone: number, at: number) => setOpen({ milestone, objective: at }),
    add,
    sheet,
  }
}

function nameOf(milestone: SectionValues): string {
  return text(milestone, KEY.id) || "unnamed"
}

function Step({ index, className }: { index: number; className?: string }) {
  return (
    <span
      aria-hidden
      className={cn(
        "flex size-6 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-medium tabular-nums",
        className,
      )}
    >
      {index + 1}
    </span>
  )
}

function MilestoneBody({
  props,
  index,
  onOpen,
  onAdd,
}: {
  props: TrackProps
  index: number
  onOpen: (objective: number) => void
  onAdd: () => void
}) {
  const { id, schema, track, disabled, edits } = props
  const milestone = track[index]
  const objectives = objectivesOf(milestone)
  return (
    <div className="flex flex-col gap-4">
      <Fields
        id={`${id}.${index}`}
        fields={milestoneFields(schema)}
        section={milestone}
        disabled={disabled}
        onChange={(key, value) => edits.setField(index, key, value)}
        className="@md:grid-cols-3 @3xl:grid-cols-5"
      />
      <div className="flex flex-col gap-2">
        <div className="grid grid-cols-1 gap-2 @lg:grid-cols-2 @4xl:grid-cols-3">
          {objectives.map((objective, at) => (
            <ObjectiveCard key={at} objective={objective} schema={schema} onOpen={() => onOpen(at)} />
          ))}
        </div>
        <div>
          <Button type="button" variant="ghost" size="sm" disabled={disabled} onClick={onAdd}>
            <PlusIcon aria-hidden />
            {t("steward.game.objective")}
          </Button>
        </div>
      </div>
    </div>
  )
}

function SeasonSums({ track, schema }: { track: SectionValues[]; schema: TrackSchema }) {
  const sums = budgetSums(track)
  if (BUDGETS.every((key) => sums[key] <= 0)) return null
  return (
    <p className="flex flex-wrap items-center gap-x-2.5 gap-y-1 px-3 text-xs text-muted-foreground">
      <span className="font-medium text-foreground/80">{t("steward.season.title")}</span>
      <BudgetMarks sums={sums} schema={schema} />
    </p>
  )
}

function AddMilestone({ disabled, onAdd }: { disabled: boolean; onAdd: () => void }) {
  return (
    <div>
      <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={onAdd}>
        <PlusIcon aria-hidden />
        {t("steward.game.milestone")}
      </Button>
    </div>
  )
}

/**
 * A wide screen keeps the list and opens the first milestone while none is chosen. A phone shows the list, then the chosen milestone with a way back.
 */
export function TrackSplit(props: TrackProps) {
  const { schema, track, edits, disabled } = props
  const [chosen, setChosen] = useState<number | null>(null)
  const sheet = useObjectiveSheet(props)
  const shown = chosen !== null && chosen < track.length ? chosen : null
  const open = shown ?? (track.length > 0 ? 0 : null)
  const name = open === null ? "" : nameOf(track[open])

  return (
    <div className="flex flex-col gap-3">
      <SeasonSums track={track} schema={schema} />
      <div className="lg:grid lg:grid-cols-[minmax(17rem,max-content)_minmax(0,1fr)] lg:gap-6">
        <div className={cn("flex flex-col gap-2 lg:border-r lg:pr-4", shown !== null && "max-lg:hidden")}>
          <ol aria-label={t("steward.game.milestones")} className="flex flex-col gap-0.5">
            {track.map((milestone, index) => {
              const on = open === index
              return (
                <li key={index} aria-label={nameOf(milestone)}>
                  <button
                    type="button"
                    aria-current={on ? "true" : undefined}
                    onClick={() => setChosen(index)}
                    className={cn(
                      "flex min-h-12 w-full min-w-0 items-start gap-3 rounded-md px-2 py-2 text-left outline-none hover:bg-muted/60 focus-visible:ring-2 focus-visible:ring-ring/50 [&>svg]:mt-1",
                      on && "lg:bg-muted/60",
                    )}
                  >
                    <Step index={index} className={cn(on && "lg:bg-primary lg:text-primary-foreground")} />
                    <span className="flex min-w-0 flex-1 flex-col gap-1">
                      <span className="truncate text-sm font-medium">{nameOf(milestone)}</span>
                      <MilestoneMarks milestone={milestone} schema={schema} />
                    </span>
                    <CaretRightIcon aria-hidden className="size-4 shrink-0 text-muted-foreground lg:hidden" />
                  </button>
                </li>
              )
            })}
          </ol>
          <AddMilestone
            disabled={disabled}
            onAdd={() => {
              edits.add()
              setChosen(track.length)
            }}
          />
        </div>
        {open === null ? null : (
          <section
            aria-label={name}
            className={cn("@container flex min-w-0 flex-col gap-4", shown === null && "max-lg:hidden")}
          >
            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={t("steward.game.all-milestones")}
                onClick={() => setChosen(null)}
                className="lg:hidden"
              >
                <CaretLeftIcon aria-hidden />
              </Button>
              <Step index={open} />
              <h3 className="min-w-0 flex-1 truncate text-base font-semibold">{name}</h3>
              <MilestoneActions
                name={name}
                index={open}
                count={track.length}
                disabled={disabled}
                onMove={(to) => {
                  edits.move(open, to)
                  setChosen(to)
                }}
                onRemove={() => {
                  edits.remove(open)
                  setChosen(null)
                }}
              />
            </div>
            <MilestoneBody
              props={props}
              index={open}
              onOpen={(at) => sheet.open(open, at)}
              onAdd={() => sheet.add(open)}
            />
          </section>
        )}
      </div>
      {sheet.sheet}
    </div>
  )
}
