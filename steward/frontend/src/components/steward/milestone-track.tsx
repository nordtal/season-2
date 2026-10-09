import { CheckIcon, FlagBannerIcon } from "@phosphor-icons/react"
import { useState } from "react"
import { cn } from "cn"

import type { ConfigDocument, ConfigEntry, SmpMilestone, SmpObjective, SmpTrack } from "@/lib/api"
import { date } from "@/lib/format"
import { useConfig, useSmpTrack } from "@/lib/queries"
import { QueryState, SkeletonText } from "@/components/steward/query-state"
import { SectionSummary } from "@/components/steward/section-summary"
import { ActionDialog, keyName } from "@/components/steward/game-actions"
import type { Ask } from "@/components/steward/game-actions"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "@/components/ui/popover"
import { Progress } from "@/components/ui/progress"
import { t } from "@/lib/texts"

/** The track's definition, read through the configuration API for its order and what each step asks for. */
export const TRACK_FILE = "smp/milestones"

/** A task's progress and the settings that define it. */
export type TrackTask = SmpObjective & { fields: ConfigEntry[] }

/** A step's progress and the settings that define it. */
export type TrackStep = Omit<SmpMilestone, "objectives"> & { fields: ConfigEntry[]; objectives: TrackTask[] }

/**
 * The database's progress in the file's order, each step with its settings beside it.
 *
 * Nothing here knows what a milestone holds: the file's list of sections is the track, a section's `key` joins it
 * to its row as the settings cards title it, and its own list of sections holds the tasks. A section the database
 * holds no row for is not on the track yet. Without the file the database's order stands; a row the file no longer
 * declares goes last.
 */
export function trackSteps(progress: SmpTrack, definition?: ConfigDocument): TrackStep[] {
  const rows = new Map(progress.milestones.map((milestone) => [milestone.key, milestone]))
  const steps: TrackStep[] = []
  for (const [key, section] of titled(definition?.entries ?? [])) {
    const row = rows.get(key)
    if (row === undefined) continue
    rows.delete(key)
    steps.push({ ...row, fields: details(section, row), objectives: tasks(row.objectives, section) })
  }
  for (const row of rows.values()) steps.push({ ...row, fields: [], objectives: tasks(row.objectives, []) })
  return steps
}

function tasks(progress: SmpObjective[], section: ConfigEntry[]): TrackTask[] {
  const declared = titled(section)
  const ordered = [...declared.keys()]
  const at = (key: string) => (ordered.includes(key) ? ordered.indexOf(key) : ordered.length)
  return progress
    .toSorted((a, b) => at(a.key) - at(b.key))
    .map((row) => ({ ...row, fields: details(declared.get(row.key) ?? [], row) }))
}

/** The first list of sections among `entries`, each by its `key`. */
function titled(entries: ConfigEntry[]): Map<string, ConfigEntry[]> {
  const sections = entries.find((entry) => entry.kind === "SECTIONS")?.sections ?? []
  const out = new Map<string, ConfigEntry[]>()
  for (const section of sections) {
    const key = section.find((field) => field.key === "key")?.value
    if (key) out.set(key, section)
  }
  return out
}

/** A section's settings less what its progress row already says, so nothing is said twice. */
function details(section: ConfigEntry[], row: object): ConfigEntry[] {
  return section.filter((field) => !Object.hasOwn(row, field.key))
}

function share(task: TrackTask): number {
  if (task.completed) return 100
  return task.target > 0 ? Math.min(100, Math.floor((task.amount * 100) / task.target)) : 0
}

/** The SMP's milestone track, every step at once: across on a wide screen, down on a phone. */
export function SmpActions() {
  const track = useSmpTrack()
  const definition = useConfig(TRACK_FILE)
  const [ask, setAsk] = useState<Ask | null>(null)
  const steps = track.data ? trackSteps(track.data, definition.data) : undefined
  const done = steps?.filter((step) => step.state === "UNLOCKED").length

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <FlagBannerIcon className="size-4 text-muted-foreground" aria-hidden />
          {t("steward.game.track")}
        </CardTitle>
        {steps ? (
          <CardAction className="text-sm tabular-nums text-muted-foreground">
            {done} / {steps.length}
          </CardAction>
        ) : null}
      </CardHeader>
      <CardContent>
        <QueryState
          query={track}
          isEmpty={(data: SmpTrack) => data.milestones.length === 0}
          empty={{ title: t("steward.game.no-track") }}
        >
          {(data) =>
            data && steps ? (
              <ol aria-label={t("steward.game.milestones")} className="flex flex-col lg:flex-row">
                {steps.map((step, index) => (
                  <Step key={step.key} step={step} last={index === steps.length - 1} onAsk={setAsk} />
                ))}
              </ol>
            ) : (
              <SkeletonText width="long" />
            )
          }
        </QueryState>
      </CardContent>
      <ActionDialog ask={ask} onClose={() => setAsk(null)} refresh="smp-track" />
    </Card>
  )
}

function Step({ step, last, onAsk }: { step: TrackStep; last: boolean; onAsk: (ask: Ask) => void }) {
  const name = keyName(step.key)
  const active = step.state === "ACTIVE"
  const unlock = () =>
    onAsk({
      path: "/api/smp/milestone",
      body: { key: step.key },
      title: t("steward.game.unlock-ask", { name }),
      description: t("steward.game.unlock-note"),
      confirm: t("steward.game.unlock"),
    })

  return (
    <li
      aria-label={name}
      aria-current={active ? "step" : undefined}
      className={cn("flex min-w-0 gap-3 lg:flex-1 lg:flex-col lg:gap-2", active && "lg:flex-[1.5]")}
    >
      <div className="flex flex-col items-center lg:flex-row" aria-hidden>
        <Node state={step.state} />
        {last ? null : (
          <span
            className={cn(
              "w-px flex-1 lg:mx-1 lg:h-px lg:w-auto",
              step.state === "UNLOCKED" ? "bg-primary" : "bg-border",
            )}
          />
        )}
      </div>
      <div className={cn("flex min-w-0 flex-1 flex-col gap-1.5 lg:pr-3", last ? "" : "pb-4 lg:pb-0")}>
        <div className="flex min-h-6 items-start justify-between gap-2">
          <StepPopover step={step} name={name} onUnlock={unlock} />
          {active ? (
            <Button type="button" variant="outline" size="xs" onClick={unlock}>
              {t("steward.game.unlock")}
            </Button>
          ) : null}
        </div>
        {/* A finished step's tasks are all done; on a phone they stay behind its popover. */}
        {step.objectives.length > 0 ? (
          <ul className={cn("grid grid-cols-2 gap-1 lg:grid-cols-1", step.state === "UNLOCKED" && "max-lg:hidden")}>
            {step.objectives.map((task) => (
              <li key={task.key} className="min-w-0">
                <TaskPopover task={task} active={active} onAsk={onAsk} />
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </li>
  )
}

function Node({ state }: { state: SmpMilestone["state"] }) {
  if (state === "UNLOCKED") {
    return (
      <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground">
        <CheckIcon className="size-3" weight="bold" />
      </span>
    )
  }
  if (state === "ACTIVE") {
    return (
      <span className="flex size-5 shrink-0 items-center justify-center rounded-full ring-2 ring-primary ring-inset">
        <span className="size-2 rounded-full bg-primary" />
      </span>
    )
  }
  return <span className="size-5 shrink-0 rounded-full border border-border bg-muted" />
}

function StepPopover({ step, name, onUnlock }: { step: TrackStep; name: string; onUnlock: () => void }) {
  const [open, setOpen] = useState(false)
  const line = step.state === "UNLOCKED" ? date(step.unlocked) : undefined
  const finished = step.objectives.filter((task) => task.completed).length

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="flex min-w-0 flex-col items-start justify-center rounded-sm text-left outline-none pointer-coarse:min-h-control pointer-coarse:min-w-control focus-visible:ring-2 focus-visible:ring-ring/50"
        >
          <span
            className={cn(
              "max-w-full truncate text-sm font-medium",
              step.state === "LOCKED" && "text-muted-foreground",
            )}
          >
            {name}
          </span>
          {line ? <span className="text-xs tabular-nums text-muted-foreground">{line}</span> : null}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" collisionPadding={16} className="w-72">
        <PopoverHeader>
          <PopoverTitle>{name}</PopoverTitle>
          <PopoverDescription>
            {step.state === "UNLOCKED"
              ? t("steward.game.unlocked", { at: date(step.unlocked) })
              : step.state === "ACTIVE"
                ? t("steward.game.active")
                : t("steward.game.locked")}
          </PopoverDescription>
        </PopoverHeader>
        <SectionSummary fields={step.fields} />
        <p className="text-xs tabular-nums text-muted-foreground">
          {t("steward.game.tasks", { finished, total: step.objectives.length })}
        </p>
        {step.state === "ACTIVE" ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => {
              setOpen(false)
              onUnlock()
            }}
          >
            Unlock
          </Button>
        ) : null}
      </PopoverContent>
    </Popover>
  )
}

function TaskPopover({ task, active, onAsk }: { task: TrackTask; active: boolean; onAsk: (ask: Ask) => void }) {
  const [open, setOpen] = useState(false)
  const name = keyName(task.key)
  const value = share(task)
  const working = active && !task.completed
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={name}
          className={cn(
            "flex w-full min-w-0 flex-col justify-center gap-1 rounded-md bg-muted/50 px-2 py-1.5 text-left text-xs outline-none pointer-coarse:min-h-control hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring/50",
            !active && !task.completed && "text-muted-foreground",
          )}
        >
          <span className="flex min-w-0 items-center justify-between gap-1">
            <span className="truncate">{name}</span>
            {task.completed ? (
              <CheckIcon className="size-3 shrink-0 text-primary" weight="bold" aria-hidden />
            ) : working ? (
              <span className="shrink-0 tabular-nums text-muted-foreground">{value} %</span>
            ) : null}
          </span>
          {working ? <Progress value={value} className="h-0.5" aria-hidden /> : null}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" collisionPadding={16} className="w-72">
        <PopoverHeader>
          <PopoverTitle>{name}</PopoverTitle>
          <PopoverDescription className="font-mono text-xs">{task.type}</PopoverDescription>
        </PopoverHeader>
        <div className="flex flex-col gap-1">
          <Progress value={value} aria-label={t("steward.game.progress", { name })} />
          <div className="flex justify-between text-xs tabular-nums text-muted-foreground">
            <span>
              {task.amount.toLocaleString("en")} / {task.target.toLocaleString("en")}
            </span>
            <span>{task.completed ? t("steward.game.done-at", { at: date(task.completedAt) }) : `${value} %`}</span>
          </div>
        </div>
        <SectionSummary fields={task.fields} />
        {working ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => {
              setOpen(false)
              onAsk({
                path: "/api/smp/objective",
                body: { key: task.key },
                title: t("steward.game.complete-ask", { name }),
                description: t("steward.game.complete-note", { amount: task.amount, target: task.target }),
                confirm: t("steward.game.complete"),
              })
            }}
          >
            {t("steward.game.complete")}
          </Button>
        ) : null}
      </PopoverContent>
    </Popover>
  )
}
