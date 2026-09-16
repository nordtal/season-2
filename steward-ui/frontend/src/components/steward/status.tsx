import { cn } from "cn"

import { Badge } from "@/components/ui/badge"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

/**
 * Status, in the three colours this theme allows.
 *
 * shadcn's `Badge` has `default`, `secondary`, `destructive`, `outline`, `ghost`, `link` - action
 * colours, not status colours. `--success`, `--warning` and `--destructive` are defined in
 * `index.css` precisely so that "good", "careful" and "broken" are three values and not thirty, so
 * this wraps the official component and supplies the tint. One place, not a class chain per table.
 *
 * **Colour is never the only carrier.** Every badge here says a word as well, because a red dot and
 * a green dot are the same dot to a red-green colour-blind reader - and because a screenshot in a
 * chat has no legend.
 */

export type Tone = "ok" | "warn" | "down" | "idle"

const TONES: Record<Tone, string> = {
  ok: "border-success/30 bg-success/12 text-success",
  warn: "border-warning/30 bg-warning/12 text-warning",
  down: "border-destructive/30 bg-destructive/12 text-destructive",
  idle: "border-border bg-secondary text-muted-foreground",
}

export function StatusBadge({
  tone,
  children,
  title,
  className,
}: {
  tone: Tone
  children: React.ReactNode
  title?: string
  className?: string
}) {
  const badge = (
    <Badge variant="outline" className={cn("font-medium", TONES[tone], className)}>
      {children}
    </Badge>
  )
  if (!title) return badge
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="rounded-full focus-visible:outline-none">
          {badge}
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">{title}</TooltipContent>
    </Tooltip>
  )
}

/** Docker's container state, with health folded in where there is one. */
export function ServiceState({ state, health }: { state: string; health?: string }) {
  if (state !== "running") {
    return (
      <StatusBadge tone="down" title={`Docker reports the state "${state}".`}>
        {STATES[state] ?? state}
      </StatusBadge>
    )
  }
  if (health === "unhealthy") {
    return (
      <StatusBadge tone="down" title="The container is running, but its healthcheck is failing.">
        unhealthy
      </StatusBadge>
    )
  }
  if (health === "starting") {
    return (
      <StatusBadge tone="warn" title="The healthcheck has not reached a verdict yet.">
        starting
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone="ok" title={health ? `Healthcheck: ${health}.` : "Running. No healthcheck."}>
      running
    </StatusBadge>
  )
}

const STATES: Record<string, string> = {
  created: "created",
  restarting: "restarting",
  removing: "removing",
  paused: "paused",
  exited: "exited",
  dead: "dead",
}

/**
 * Image drift.
 *
 * `UNKNOWN` is deliberately not silent and deliberately not green: an image nobody compared is not
 * a current image, and treating it as one is how four releases shipped unnoticed (A24).
 */
export function DriftBadge({ drift }: { drift: string }) {
  switch (drift) {
    case "UP_TO_DATE":
      return (
        <StatusBadge tone="ok" title="The running image carries the digest the registry names.">
          up to date
        </StatusBadge>
      )
    case "OUTDATED":
      return (
        <StatusBadge tone="warn" title="The registry has a newer image than this container.">
          outdated
        </StatusBadge>
      )
    default:
      return (
        <StatusBadge
          tone="idle"
          title={
            'Not compared - either the image carries no registry digest (built here, published' +
            ' nowhere), or the registry did not answer. That is not "up to date".'
          }
        >
          unchecked
        </StatusBadge>
      )
  }
}

/** The status of a run, as the `update_request` row carries it. */
export function RunStatus({ status }: { status: string }) {
  const tone: Tone =
    status === "DONE"
      ? "ok"
      : status === "FAILED"
        ? "down"
        : status === "RUNNING"
          ? "warn"
          : "idle"
  return <StatusBadge tone={tone}>{RUN_STATUS[status] ?? status}</StatusBadge>
}

/** The outcome of a run, in the same words {@link RunStatus} draws - exported for anything that
 * needs the word rather than the badge, such as the command palette's search text. */
export const RUN_STATUS: Record<string, string> = {
  PENDING: "waiting",
  RUNNING: "running",
  DONE: "done",
  FAILED: "failed",
  CANCELLED: "cancelled",
}

/** What kind of run it was. Not a status - a noun. */
export const RUN_KIND: Record<string, string> = {
  UPDATE: "Update",
  BACKUP: "Backup",
  RESTART: "Restart",
  // Two kinds nothing in this interface asks for, but old rows carry them and a table that printed
  // the enum name for them would look broken rather than historical.
  REPORT: "Report",
  APPLY: "Apply",
}
