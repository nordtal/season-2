import { cn } from "cn"

import type { ImageState, Service } from "@/lib/api"
import { dateTime } from "@/lib/format"
import { Badge } from "@/components/ui/badge"
import { Skeleton } from "@/components/ui/skeleton"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import type { ReactNode } from "react"

/**
 * Status badges in the theme's three status colours, wrapping shadcn's `Badge`.
 *
 * Every badge also says a word, so colour is never the only carrier.
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
  tipContent,
  className,
}: {
  tone: Tone
  children: ReactNode
  tipContent?: ReactNode
  className?: string
}) {
  const badge = (
    <Badge
      variant="outline"
      /** `max-w-full` and `truncate`, so a long badge in a table card ends in an ellipsis, not a silent cut. */
      className={cn("max-w-full truncate font-medium", TONES[tone], className)}
    >
      {children}
    </Badge>
  )
  if (!tipContent) return badge
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        {/* `max-w-full` here too, since this span is the grid item in a table card. */}
        <span tabIndex={0} className="inline-flex max-w-full rounded-full focus-visible:outline-none">
          {badge}
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs p-2">{tipContent}</TooltipContent>
    </Tooltip>
  )
}

/** The three fields {@link serviceTone} and {@link ServiceState} need. */
export type ServiceHealth = Pick<Service, "state" | "health" | "hold">

/** One service reduced to a tone, shared by {@link ServiceState} and {@link HealthDot} so they cannot drift apart. */
export function serviceTone(service: ServiceHealth): Tone {
  /** A deliberately stopped service is `idle`, not down. */
  if (held(service)) return "idle"
  if (service.state !== "running") return "down"
  if (service.health === "unhealthy") return "down"
  if (service.health === "starting") return "warn"
  return "ok"
}

/** Whether this service is stopped and meant to be; a hold on a running container says nothing. */
export function held(service: ServiceHealth): boolean {
  return service.hold !== undefined && service.state !== "running"
}

/** Docker's container state, with health folded in where there is one. */
export function ServiceState({
  state,
  health,
  hold,
}: {
  state: string
  health?: string
  /** The hold, if the caller has one; a stopped service with one reads as held. */
  hold?: Service["hold"]
}) {
  if (state !== "running") {
    if (hold) {
      /** One badge: Docker's own word moves to the title rather than a second, red badge. */
      return (
        <StatusBadge
          tone="idle"
          tipContent={
            `Held down since ${dateTime(hold.since)}.` +
            ` No update and no restart starts it again. Docker reports the state "${state}".`
          }
        >
          held down
        </StatusBadge>
      )
    }
    return (
      <StatusBadge tone="down" tipContent={`Docker reports the state "${state}".`}>
        {STATES[state] ?? state}
      </StatusBadge>
    )
  }
  const tone = serviceTone({ state, health })
  if (tone === "down") {
    return (
      <StatusBadge tone="down" tipContent="The container is running, but its healthcheck is failing.">
        unhealthy
      </StatusBadge>
    )
  }
  if (tone === "warn") {
    return (
      <StatusBadge tone="warn" tipContent="The healthcheck has not reached a verdict yet.">
        starting
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone="ok" tipContent={health ? `Healthcheck: ${health}.` : "Running. No healthcheck."}>
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

const DOT_TONE: Record<Tone, string> = {
  ok: "bg-success",
  warn: "bg-warning",
  down: "bg-destructive",
  /** A held service: a bordered dot, differing from the other three in shape, not only colour. */
  idle: "border border-muted-foreground/60 bg-muted-foreground/30",
}

const DOT_WORD: Record<Tone, string> = {
  ok: "healthy",
  warn: "starting",
  down: "unhealthy",
  idle: "held down",
}

/**
 * The health of one service as a dot, for the sidebar and the network view.
 *
 * `quiet` hides the fine state; an undefined `service` draws a neutral dot, never the fine one.
 */
export function HealthDot({
  service,
  className,
  quiet = true,
}: {
  service?: ServiceHealth
  className?: string
  /** Whether a healthy service draws nothing at all: `true` for the sidebar, `false` for the picture. */
  quiet?: boolean
}) {
  if (!service) {
    /** A dot still waiting shimmers, like every other loading surface. */
    return (
      <Skeleton
        role="img"
        aria-label="Not read yet."
        title="Not read yet."
        className={cn("size-2 shrink-0 rounded-full", className)}
      />
    )
  }

  const tone = serviceTone(service)
  if (tone === "ok" && quiet) return null

  return (
    <span
      role="img"
      aria-label={DOT_WORD[tone]}
      title={DOT_WORD[tone]}
      className={cn("size-2 shrink-0 rounded-full", DOT_TONE[tone], className)}
    />
  )
}

/**
 * One artefact's resolve status, in four answers kept apart.
 *
 * "could not ask" must never look like "up to date", since a silent source looks like one with no change.
 */
export function AvailableBadge({ status }: { status: string }) {
  switch (status) {
    case "UP_TO_DATE":
      return (
        <StatusBadge tone="ok" tipContent="What is installed is what the source says is newest.">
          up to date
        </StatusBadge>
      )
    case "OUTDATED":
      return (
        <StatusBadge tone="warn" tipContent="A newer file exists. A run would install it.">
          outdated
        </StatusBadge>
      )
    case "MISSING":
      return (
        <StatusBadge
          tone="warn"
          tipContent={
            "Nothing with this file name is installed. On a fresh volume that is normal; on a" +
            " running server it is either new, or a publisher who renamed the jar."
          }
        >
          not installed
        </StatusBadge>
      )
    case "UNSUPPORTED":
      return (
        <StatusBadge
          tone="idle"
          tipContent={
            "The source answered and has no build of this for the Minecraft version the network" +
            " runs. Nothing is installed and nothing failed. It stays on this list, so the day a" +
            " build appears the next run picks it up."
          }
        >
          unsupported
        </StatusBadge>
      )
    case "MOUNT_MISSING":
      return (
        <StatusBadge tone="down" tipContent="The volume is not mounted in steward, so nothing can be said about it.">
          no volume
        </StatusBadge>
      )
    default:
      return (
        <StatusBadge
          tone="down"
          tipContent="The source could not be asked. This is not the same as nothing having changed."
        >
          could not ask
        </StatusBadge>
      )
  }
}

/** Image drift: UNKNOWN is never silent or green, and LOCAL, being ahead of the registry, is neutral. */
export function DriftBadge({ drift, image }: { drift: ImageState; image?: string; digests?: string[] }) {
  switch (drift) {
    case "UP_TO_DATE":
      return (
        <StatusBadge tone="ok" tipContent="The running image carries the digest the registry names.">
          up to date
        </StatusBadge>
      )
    case "OUTDATED":
      return (
        <StatusBadge tone="warn" tipContent="Out of date: the next update makes it again.">
          outdated
        </StatusBadge>
      )
    case "LOCAL":
      return (
        <StatusBadge
          tone="idle"
          tipContent={
            <div className="flex w-full min-w-0 flex-col sm:w-auto sm:min-w-64">
              <span className="text-xs font-medium font-heading text-muted-foreground">Image</span>
              <span className="truncate text-sm">{image}</span>
            </div>
          }
        >
          local build
        </StatusBadge>
      )
    default:
      return (
        <StatusBadge
          tone="idle"
          tipContent={
            "Not compared - either the registry did not answer, or this container's exact image" +
            " is no longer on file locally (its tag was rebuilt without recreating it). That is" +
            ' not "up to date".'
          }
        >
          unchecked
        </StatusBadge>
      )
  }
}

/** The status of a run, as its row in steward's inbox carries it. */
export function RunStatus({ status }: { status: string }) {
  const tone: Tone = status === "DONE" ? "ok" : status === "FAILED" ? "down" : status === "RUNNING" ? "warn" : "idle"
  return <StatusBadge tone={tone}>{RUN_STATUS[status] ?? status}</StatusBadge>
}

/** The outcome of a run in {@link RunStatus}'s words, for text such as the command palette's search. */
export const RUN_STATUS: Record<string, string> = {
  PENDING: "waiting",
  RUNNING: "running",
  DONE: "done",
  FAILED: "failed",
  CANCELLED: "cancelled",
}

/** What kind of run it was. */
export const RUN_KIND: Record<string, string> = {
  UPDATE: "Update",
  BACKUP: "Backup",
  RESTART: "Restart",
  DOWN: "Take down",
  START: "Start",
  RECREATE: "Recreate",
  DEPLOY: "Deploy",
  RESTORE: "Restore",
  REMOVE_PLUGIN: "Remove plugin",
}
