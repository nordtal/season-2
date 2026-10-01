import { CheckIcon, FlagBannerIcon } from "@phosphor-icons/react"
import { useState } from "react"
import { cn } from "cn"

import type { ConfigDocument, ConfigEntry, SmpMilestone, SmpObjective, SmpTrack } from "@/lib/api"
import { date } from "@/lib/format"
import { useConfig, useSmpTrack } from "@/lib/queries"
import { QueryState, SkeletonText } from "@/components/steward/query-state"
import { ActionDialog, keyName } from "@/components/steward/game-actions"
import type { Ask } from "@/components/steward/game-actions"
import { Badge } from "@/components/ui/badge"
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

/** The track's definition, read through the configuration API for its order and what each step asks for. */
export const TRACK_FILE = "smp/milestones"

export type TrackTask = SmpObjective & {
  role?: string
  items: string[]
  statistic?: string
  subjects: string[]
  advancement?: string
}

export type TrackStep = Omit<SmpMilestone, "objectives"> & {
  unlocks?: string
  border?: number
  pot?: number
  adminUnlocked?: boolean
  objectives: TrackTask[]
}

const TYPE: Record<SmpObjective["type"], string> = {
  HAND_IN: "Hand-in",
  STATISTIC: "Statistic",
  ADVANCEMENT: "Advancement",
}

/**
 * The database's progress in the file's order, with the file's details on each step.
 *
 * Without the file the database's order stands; a row the file no longer declares goes last.
 */
export function trackSteps(progress: SmpTrack, definition?: ConfigDocument): TrackStep[] {
  const declared = definition ? sectionsOf(definition.entries, "milestones") : []
  const rows = new Map(progress.milestones.map((milestone) => [milestone.key, milestone]))
  const steps: TrackStep[] = []
  for (const section of declared) {
    const key = text(section, "key")
    if (!key) continue
    const row = rows.get(key)
    rows.delete(key)
    steps.push({
      key,
      state: row?.state ?? "LOCKED",
      unlocked: row?.unlocked,
      unlocks: text(section, "unlocks"),
      border: number(section, "border-diameter"),
      pot: number(section, "objective-pot"),
      adminUnlocked: text(section, "admin-unlocked") === "true",
      objectives: tasks(row?.objectives ?? [], sectionsOf(section, "objectives")),
    })
  }
  for (const row of rows.values()) steps.push({ ...row, objectives: tasks(row.objectives, []) })
  return steps
}

function tasks(progress: SmpObjective[], declared: ConfigEntry[][]): TrackTask[] {
  const rows = new Map(progress.map((objective) => [objective.key, objective]))
  const out: TrackTask[] = []
  for (const section of declared) {
    const key = text(section, "key")
    if (!key) continue
    const row = rows.get(key)
    rows.delete(key)
    out.push({
      key,
      type: row?.type ?? objectiveType(text(section, "type")),
      amount: row?.amount ?? 0,
      target: row?.target ?? number(section, "target") ?? 0,
      completed: row?.completed ?? false,
      completedAt: row?.completedAt,
      role: text(section, "role"),
      items: list(section, "items"),
      statistic: text(section, "statistic"),
      subjects: list(section, "subjects"),
      advancement: text(section, "advancement"),
    })
  }
  for (const row of rows.values()) out.push({ ...row, items: [], subjects: [] })
  return out
}

function objectiveType(value: string | undefined): SmpObjective["type"] {
  return value === "STATISTIC" || value === "ADVANCEMENT" ? value : "HAND_IN"
}

function sectionsOf(entries: ConfigEntry[], key: string): ConfigEntry[][] {
  return entries.find((entry) => entry.key === key)?.sections ?? []
}

function text(entries: ConfigEntry[], key: string): string | undefined {
  return entries.find((entry) => entry.key === key)?.value || undefined
}

function number(entries: ConfigEntry[], key: string): number | undefined {
  const value = Number(text(entries, key))
  return Number.isFinite(value) ? value : undefined
}

function list(entries: ConfigEntry[], key: string): string[] {
  return entries.find((entry) => entry.key === key)?.items ?? []
}

/** `OAK_LOG` and `minecraft:story/form_obsidian` as words. */
function gameName(id: string): string {
  return keyName(
    id
      .slice(id.lastIndexOf("/") + 1)
      .replace(/^minecraft:/, "")
      .toLowerCase(),
  )
}

function share(task: TrackTask): number {
  if (task.completed) return 100
  return task.target > 0 ? Math.min(100, Math.floor((task.amount * 100) / task.target)) : 0
}

function unlockLine(step: TrackStep): string | undefined {
  switch (step.unlocks) {
    case "BORDER":
      return step.border ? `Border ${step.border.toLocaleString("en")}` : "Border"
    case "NETHER":
      return "Nether"
    case "END":
      return "End"
    default:
      return undefined
  }
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
          Milestone track
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
          empty={{ title: "The SMP has not written its track yet." }}
        >
          {(data) =>
            data && steps ? (
              <ol aria-label="Milestones" className="flex flex-col lg:flex-row">
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
      title: `Unlock ${name}?`,
      description: "The track moves on and aura is paid out to everybody who qualified. There is no way back.",
      confirm: "Unlock",
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
              Unlock
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
  const unlocks = unlockLine(step)
  /** "Nether" under "Nether" says nothing twice. */
  const line = step.state === "UNLOCKED" ? date(step.unlocked) : unlocks === name ? undefined : unlocks
  const finished = step.objectives.filter((task) => task.completed).length

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="flex min-w-0 flex-col items-start rounded-sm text-left outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
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
      <PopoverContent align="start" collisionPadding={16} className="w-64">
        <PopoverHeader>
          <PopoverTitle>{name}</PopoverTitle>
          <PopoverDescription>
            {step.state === "UNLOCKED"
              ? `Unlocked ${date(step.unlocked)}`
              : step.state === "ACTIVE"
                ? "Active"
                : "Locked"}
          </PopoverDescription>
        </PopoverHeader>
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs">
          <dt className="text-muted-foreground">Unlocks</dt>
          <dd>{unlockLine(step) ?? "Nothing"}</dd>
          {step.pot !== undefined ? (
            <>
              <dt className="text-muted-foreground">Aura pot</dt>
              <dd className="tabular-nums">{step.pot.toLocaleString("en")}</dd>
            </>
          ) : null}
          <dt className="text-muted-foreground">Tasks</dt>
          <dd className="tabular-nums">
            {step.objectives.length === 0 ? "None" : `${finished} of ${step.objectives.length}`}
          </dd>
          {step.adminUnlocked ? (
            <>
              <dt className="text-muted-foreground">Opens</dt>
              <dd>By an admin</dd>
            </>
          ) : null}
        </dl>
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
  const what =
    task.type === "HAND_IN"
      ? task.items.map(gameName)
      : task.type === "STATISTIC"
        ? task.subjects.map(gameName)
        : task.advancement
          ? [gameName(task.advancement)]
          : []

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={name}
          className={cn(
            "flex w-full min-w-0 flex-col gap-1 rounded-md bg-muted/50 px-2 py-1.5 text-left text-xs outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring/50",
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
      <PopoverContent align="start" collisionPadding={16} className="w-64">
        <PopoverHeader>
          <PopoverTitle>{name}</PopoverTitle>
          <div className="flex flex-wrap gap-1">
            <Badge variant="secondary">{TYPE[task.type] ?? task.type}</Badge>
            {task.role ? <Badge variant="outline">{keyName(task.role)}</Badge> : null}
          </div>
        </PopoverHeader>
        <div className="flex flex-col gap-1">
          <Progress value={value} aria-label={`${name} progress`} />
          <div className="flex justify-between text-xs tabular-nums text-muted-foreground">
            <span>
              {task.amount.toLocaleString("en")} / {task.target.toLocaleString("en")}
            </span>
            <span>{task.completed ? `Done ${date(task.completedAt)}` : `${value} %`}</span>
          </div>
        </div>
        {what.length > 0 ? (
          <p className="line-clamp-3 text-xs text-muted-foreground">
            {task.type === "STATISTIC" && task.statistic ? `${gameName(task.statistic)}: ` : null}
            {what.join(", ")}
          </p>
        ) : null}
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
                title: `Complete ${name}?`,
                description: `It closes at ${task.amount.toLocaleString("en")} of ${task.target.toLocaleString("en")} and pays out that share of its aura. There is no way back.`,
                confirm: "Complete",
              })
            }}
          >
            Complete
          </Button>
        ) : null}
      </PopoverContent>
    </Popover>
  )
}
