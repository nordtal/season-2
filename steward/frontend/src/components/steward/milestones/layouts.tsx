import { CaretLeftIcon, CaretRightIcon, PlusIcon } from "@phosphor-icons/react"
import { type ReactNode, useState } from "react"

import type { SectionValues } from "@/components/steward/repeatable-cards"
import { KEY, type TrackEdits, type TrackSchema, objectivesOf, text } from "@/components/steward/milestones/model"
import {
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

/** What every layout of the track is handed: the schema, the track as the draft holds it, and its edits. */
export type LayoutProps = {
  id: string
  schema: TrackSchema
  track: SectionValues[]
  edits: TrackEdits
  disabled: boolean
}

/** The layouts the milestones editor can draw, by the name a proposal goes by. */
export const LAYOUTS = {
  rows: RowsLayout,
  split: SplitLayout,
  grid: GridLayout,
} as const

export type LayoutName = keyof typeof LAYOUTS

type Open = { milestone: number; objective: number } | null

/** The one objective sheet a layout opens, and the way to open it. */
function useObjectiveSheet({ id, schema, track, edits, disabled }: LayoutProps) {
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

/** A milestone's fields and its objectives, as a layout opens it. */
function MilestoneBody({
  props,
  index,
  onOpen,
  onAdd,
  fieldsClassName,
  objectivesClassName,
}: {
  props: LayoutProps
  index: number
  onOpen: (objective: number) => void
  onAdd: () => void
  fieldsClassName?: string
  objectivesClassName?: string
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
      <div className="flex flex-col gap-1">
        <div className={cn("grid grid-cols-1 gap-x-4", objectivesClassName)}>
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
 * A: one line per milestone, opened in place. Closed, a step shows its number, ID, marks and one icon per objective;
 * open, its fields two to a row and its objectives one line each, every objective edited in its own sheet.
 */
export function RowsLayout(props: LayoutProps) {
  const { schema, track, edits, disabled } = props
  const [opened, setOpened] = useState<number | null>(null)
  const sheet = useObjectiveSheet(props)
  return (
    <div className="flex flex-col gap-3">
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
                    fieldsClassName="sm:grid-cols-3 lg:grid-cols-5"
                    objectivesClassName="lg:grid-cols-2"
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
export function SplitLayout(props: LayoutProps) {
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
          const name = nameOf(milestone)
          const on = wide === index
          return (
            <li key={index}>
              <button
                type="button"
                aria-current={on ? "true" : undefined}
                onClick={() => setChosen(index)}
                className={cn(
                  "flex min-h-12 w-full min-w-0 items-center gap-3 rounded-md px-2 py-2 text-left outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring/50",
                  on && "lg:bg-muted",
                )}
              >
                <Step index={index} className={cn(on && "lg:bg-primary lg:text-primary-foreground")} />
                <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span className="truncate text-sm font-medium">{name}</span>
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
          fieldsClassName="xl:grid-cols-5"
        />
      </section>
    )
  }

  return (
    <div>
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
      <div className="grid grid-cols-[15rem_minmax(0,1fr)] gap-6 max-lg:hidden">
        <div className="border-r pr-4">{list}</div>
        {wide === null ? null : detail(wide, null)}
      </div>
      {sheet.sheet}
    </div>
  )
}

/**
 * C: every milestone open at once, each a flat block: its fields on one line on a wide screen, its objectives in
 * two columns beneath. Nothing is hidden; the track is read by scrolling.
 */
export function GridLayout(props: LayoutProps) {
  const { track, edits, disabled } = props
  const sheet = useObjectiveSheet(props)
  return (
    <div className="flex flex-col gap-3">
      <ol aria-label={t("steward.game.milestones")} className="flex flex-col gap-3">
        {track.map((milestone, index) => {
          const name = nameOf(milestone)
          return (
            <li key={index} aria-label={name} className="flex flex-col gap-3 rounded-lg border bg-card p-3">
              <div className="flex items-center gap-2">
                <Step index={index} />
                <span className="min-w-0 flex-1 truncate text-sm font-semibold">{name}</span>
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
                fieldsClassName="sm:grid-cols-3 lg:grid-cols-5"
                objectivesClassName="lg:grid-cols-2"
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
