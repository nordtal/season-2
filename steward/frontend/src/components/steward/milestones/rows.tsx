import { CaretRightIcon, PlusIcon } from "@phosphor-icons/react"
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
  ObjectiveMarks,
  ObjectiveRow,
  ObjectiveSheet,
  milestoneFields,
} from "@/components/steward/milestones/parts"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { t } from "@/lib/texts"

/** What the track is drawn from: the schema, the track as the draft holds it, and its edits. */
export type TrackProps = {
  id: string
  schema: TrackSchema
  track: SectionValues[]
  edits: TrackEdits
  disabled: boolean
}

type Open = { milestone: number; objective: number } | null

/** The one objective sheet the track opens, and the way to open it. */
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
  return { open: (milestone: number, at: number) => setOpen({ milestone, objective: at }), add, sheet }
}

function nameOf(milestone: SectionValues): string {
  return text(milestone, KEY.id) || "unnamed"
}

/** The step's number on the track, which is its order in the season. */
function Step({ index }: { index: number }) {
  return (
    <span
      aria-hidden
      className="flex size-6 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-medium tabular-nums"
    >
      {index + 1}
    </span>
  )
}

/** One icon per objective, the strip a closed milestone shows. */
function ObjectiveStrip({ milestone, schema }: { milestone: SectionValues; schema: TrackSchema }) {
  const objectives = objectivesOf(milestone)
  if (objectives.length === 0) return null
  return (
    <span className="flex shrink-0 items-center gap-1" aria-label={`${objectives.length} objectives`}>
      {objectives.map((objective, at) => (
        <ObjectiveMarks key={at} objective={objective} schema={schema} max={1} counted={false} />
      ))}
    </span>
  )
}

/** A milestone's fields and its objectives, as an open row shows them. */
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
        className="sm:grid-cols-3 lg:grid-cols-5"
      />
      <div className="flex flex-col gap-1">
        <div className="grid grid-cols-1 gap-x-4 lg:grid-cols-2">
          {objectives.map((objective, at) => (
            <ObjectiveRow key={at} objective={objective} schema={schema} onOpen={() => onOpen(at)} />
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

/** The season's budgets: every objective's, summed over the whole track. */
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
 * One line per milestone, opened in place. Closed, a step shows its number, ID, marks and one icon per objective;
 * open, its fields in a grid and its objectives one line each, every objective edited in its own sheet.
 */
export function TrackRows(props: TrackProps) {
  const { schema, track, edits, disabled } = props
  const [opened, setOpened] = useState<number | null>(null)
  const sheet = useObjectiveSheet(props)
  return (
    <div className="flex flex-col gap-3">
      <SeasonSums track={track} schema={schema} />
      <ol aria-label={t("steward.game.milestones")} className="flex flex-col divide-y rounded-lg border bg-card">
        {track.map((milestone, index) => {
          const open = opened === index
          const name = nameOf(milestone)
          return (
            <li key={index} aria-label={name} className="flex flex-col">
              <button
                type="button"
                aria-expanded={open}
                onClick={() => setOpened(open ? null : index)}
                className="flex min-h-12 w-full min-w-0 items-center gap-3 px-3 py-2 text-left outline-none hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring/50"
              >
                <Step index={index} />
                <span className="flex min-w-0 flex-1 flex-col gap-0.5 sm:flex-row sm:items-center sm:gap-3">
                  <span className="truncate text-sm font-medium sm:w-36 sm:shrink-0">{name}</span>
                  <MilestoneMarks milestone={milestone} schema={schema} />
                </span>
                <span className="max-sm:hidden">
                  <ObjectiveStrip milestone={milestone} schema={schema} />
                </span>
                <CaretRightIcon
                  aria-hidden
                  className={cn("size-4 shrink-0 text-muted-foreground transition-transform", open && "rotate-90")}
                />
              </button>
              {open ? (
                <div className="flex flex-col gap-3 border-t bg-background/40 px-3 pt-2 pb-3">
                  <div className="flex justify-end">
                    <MilestoneActions
                      name={name}
                      index={index}
                      count={track.length}
                      disabled={disabled}
                      onMove={(to) => {
                        edits.move(index, to)
                        setOpened(to)
                      }}
                      onRemove={() => {
                        edits.remove(index)
                        setOpened(null)
                      }}
                    />
                  </div>
                  <MilestoneBody
                    props={props}
                    index={index}
                    onOpen={(at) => sheet.open(index, at)}
                    onAdd={() => sheet.add(index)}
                  />
                </div>
              ) : null}
            </li>
          )
        })}
      </ol>
      <AddMilestone
        disabled={disabled}
        onAdd={() => {
          edits.add()
          setOpened(track.length)
        }}
      />
      {sheet.sheet}
    </div>
  )
}
