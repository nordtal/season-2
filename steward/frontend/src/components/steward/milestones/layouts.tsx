import { CaretLeftIcon, CaretRightIcon, PlusIcon } from "@phosphor-icons/react"
import { type ReactNode, useState } from "react"

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
  ObjectivePill,
  ObjectiveSheet,
  milestoneFields,
} from "@/components/steward/milestones/parts"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { t } from "@/lib/texts"

/**
 * The track's layouts, A drawn in Settings until one of the three is picked on the design page.
 *
 * Every layout draws the same parts: a closed milestone as pills, an objective as a card, and its fields in a sheet.
 */

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
  return {
    open: (milestone: number, at: number) => setOpen({ milestone, objective: at }),
    add,
    sheet,
  }
}

function nameOf(milestone: SectionValues): string {
  return text(milestone, KEY.id) || "unnamed"
}

/** The step's number on the track, which is its order in the season. */
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

/** One pill per objective, what a closed milestone shows of them. */
function ObjectiveStrip({ milestone, schema }: { milestone: SectionValues; schema: TrackSchema }) {
  const objectives = objectivesOf(milestone)
  if (objectives.length === 0) return null
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-1">
      {objectives.map((objective, at) => (
        <ObjectivePill key={at} objective={objective} schema={schema} />
      ))}
    </span>
  )
}

/** A closed milestone's name, its settings and its objectives, wrapping onto a second line where they must. */
function MilestoneSummary({ milestone, schema }: { milestone: SectionValues; schema: TrackSchema }) {
  return (
    <span className="flex min-w-0 flex-1 flex-col gap-1.5">
      <span className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
        <span className="truncate text-sm font-medium">{nameOf(milestone)}</span>
        <MilestoneMarks milestone={milestone} schema={schema} />
      </span>
      <ObjectiveStrip milestone={milestone} schema={schema} />
    </span>
  )
}

/** A milestone's fields and its objectives, as an open row shows them. */
function MilestoneBody({
  props,
  index,
  onOpen,
  onAdd,
  fieldsClassName = "sm:grid-cols-3 lg:grid-cols-5",
}: {
  props: TrackProps
  index: number
  onOpen: (objective: number) => void
  onAdd: () => void
  fieldsClassName?: string
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
        className={fieldsClassName}
      />
      <div className="flex flex-col gap-2">
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-3">
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
 * A: one line per milestone, opened in place. Closed, a step shows its number, ID, pills and one pill per objective;
 * open, its fields in a grid and its objectives as cards, every objective edited in its own sheet.
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
                className="flex min-h-12 w-full min-w-0 items-start gap-3 px-3 py-2.5 text-left outline-none hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring/50 [&>svg]:mt-1"
              >
                <Step index={index} />
                <MilestoneSummary milestone={milestone} schema={schema} />
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

/**
 * B: the track as a list beside the chosen milestone. On a phone the list and the milestone take turns, with a way
 * back; on a wide screen the list stays.
 */
export function TrackSplit(props: TrackProps) {
  const { schema, track, edits, disabled } = props
  const [chosen, setChosen] = useState<number | null>(null)
  const sheet = useObjectiveSheet(props)
  const shown = chosen !== null && chosen < track.length ? chosen : null
  /** A wide screen always shows a milestone, the first until one is chosen. */
  const wide = shown ?? (track.length > 0 ? 0 : null)

  const list = (
    <div className="flex flex-col gap-2">
      <ol aria-label={t("steward.game.milestones")} className="flex flex-col gap-0.5">
        {track.map((milestone, index) => {
          const on = wide === index
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
  )

  const detail = (index: number, back: ReactNode) => {
    const name = nameOf(track[index])
    return (
      <section aria-label={name} className="flex min-w-0 flex-col gap-4">
        <div className="flex items-center gap-2">
          {back}
          <Step index={index} />
          <h3 className="min-w-0 flex-1 truncate text-base font-semibold">{name}</h3>
          <MilestoneActions
            name={name}
            index={index}
            count={track.length}
            disabled={disabled}
            onMove={(to) => {
              edits.move(index, to)
              setChosen(to)
            }}
            onRemove={() => {
              edits.remove(index)
              setChosen(null)
            }}
          />
        </div>
        <MilestoneBody
          props={props}
          index={index}
          onOpen={(at) => sheet.open(index, at)}
          onAdd={() => sheet.add(index)}
          fieldsClassName="sm:grid-cols-3 xl:grid-cols-5"
        />
      </section>
    )
  }

  return (
    <div className="flex flex-col gap-3">
      <SeasonSums track={track} schema={schema} />
      <div className="lg:hidden">
        {shown === null
          ? list
          : detail(
              shown,
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={t("steward.game.all-milestones")}
                onClick={() => setChosen(null)}
              >
                <CaretLeftIcon aria-hidden />
              </Button>,
            )}
      </div>
      <div className="grid grid-cols-[17rem_minmax(0,1fr)] gap-6 max-lg:hidden">
        <div className="border-r pr-4">{list}</div>
        {wide === null ? null : detail(wide, null)}
      </div>
      {sheet.sheet}
    </div>
  )
}

/**
 * C: every milestone open at once, each a flat block: its fields on one line on a wide screen, its objectives as
 * cards beneath. Nothing is hidden; the track is read by scrolling.
 */
export function TrackGrid(props: TrackProps) {
  const { schema, track, edits, disabled } = props
  const sheet = useObjectiveSheet(props)
  return (
    <div className="flex flex-col gap-3">
      <SeasonSums track={track} schema={schema} />
      <ol aria-label={t("steward.game.milestones")} className="flex flex-col gap-3">
        {track.map((milestone, index) => {
          const name = nameOf(milestone)
          return (
            <li key={index} aria-label={name} className="flex flex-col gap-3 rounded-lg border bg-card p-3">
              <div className="flex items-start gap-2">
                <Step index={index} />
                <span className="flex min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-1">
                  <span className="truncate text-sm font-semibold">{name}</span>
                  <span className="flex flex-wrap items-center gap-1">
                    <BudgetMarks sums={budgetSums([milestone])} schema={schema} />
                  </span>
                </span>
                <MilestoneActions
                  name={name}
                  index={index}
                  count={track.length}
                  disabled={disabled}
                  onMove={(to) => edits.move(index, to)}
                  onRemove={() => edits.remove(index)}
                />
              </div>
              <MilestoneBody
                props={props}
                index={index}
                onOpen={(at) => sheet.open(index, at)}
                onAdd={() => sheet.add(index)}
              />
            </li>
          )
        })}
      </ol>
      <AddMilestone disabled={disabled} onAdd={edits.add} />
      {sheet.sheet}
    </div>
  )
}

/** The layouts by the letter the design page shows them under. */
export const LAYOUTS = { A: TrackRows, B: TrackSplit, C: TrackGrid } as const

export type LayoutName = keyof typeof LAYOUTS
