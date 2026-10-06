import { cn } from "cn"

import type { ImageState, LocalBuild, Service } from "@/lib/api"
import { choice, t } from "@/lib/texts"
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
    /** `max-w-full` here too, since this span is the grid item in a table card. */
    <Tip content={tipContent}>{badge}</Tip>
  )
}

/**
 * Wraps a small mark or badge in the one tip of this interface, which opens on hover, focus and a tap.
 *
 * The trigger is a focusable span, so a mark that is not a control still answers the keyboard.
 */
export function Tip({ content, children, className }: { content: ReactNode; children: ReactNode; className?: string }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className={cn("inline-flex max-w-full rounded-full focus-visible:outline-none", className)}>
          {children}
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs p-2">{content}</TooltipContent>
    </Tooltip>
  )
}

/** The fields {@link serviceTone} and {@link ServiceState} need; `alert` is the alert rule's verdict on the row. */
export type ServiceHealth = Pick<Service, "state" | "health" | "hold" | "alert"> &
  Partial<Pick<Service, "oneShot" | "lastRun">>

/**
 * One service reduced to a tone, shared by {@link ServiceState} and {@link HealthDot} so they cannot drift apart.
 *
 * Red is the alert rule's word alone; a stop it raises nothing about is meant, a standby or a hold, so `idle`.
 * A one-shot that exited cleanly did its job, so `ok`.
 */
export function serviceTone(service: ServiceHealth): Tone {
  if (service.alert === "down") return "down"
  if (service.state !== "running") return completed(service) ? "ok" : "idle"
  if (service.health === "starting") return "warn"
  return "ok"
}

/** Whether this is a one-shot whose last run exited with code 0. */
export function completed(service: ServiceHealth): boolean {
  return service.oneShot === true && service.state !== "running" && service.lastRun?.exitCode === 0
}

/** Whether this service is stopped and meant to be; a hold on a running container says nothing. */
export function held(service: ServiceHealth): boolean {
  return service.hold !== undefined && service.state !== "running"
}

/** Docker's container state, with health folded in where there is one. */
export function ServiceState({ service }: { service: ServiceHealth }) {
  const { state, health, hold, lastRun } = service
  const tone = serviceTone(service)
  if (state !== "running" && service.oneShot && lastRun) {
    return (
      <StatusBadge
        tone={tone}
        tipContent={t("steward.service.exited", { code: lastRun.exitCode, at: lastRun.finishedAt })}
      >
        {t("steward.service.state", { state: tone === "ok" ? "completed" : "failed" })}
      </StatusBadge>
    )
  }
  if (state !== "running") {
    if (tone === "down") {
      return (
        <StatusBadge tone="down" tipContent={t("steward.service.docker-state", { state })}>
          {t("steward.service.state", { state })}
        </StatusBadge>
      )
    }
    /** One badge: Docker's own word moves to the title rather than a second, red badge. */
    return (
      <StatusBadge
        tone="idle"
        tipContent={
          hold
            ? t("steward.service.held-since", { since: hold.since, state })
            : t("steward.service.docker-state", { state })
        }
      >
        {t("steward.service.state", { state: hold ? "held" : "standby" })}
      </StatusBadge>
    )
  }
  if (tone === "down") {
    return (
      <StatusBadge tone="down" tipContent={t("steward.service.unhealthy")}>
        {t("steward.service.state", { state: "unhealthy" })}
      </StatusBadge>
    )
  }
  if (tone === "warn") {
    return (
      <StatusBadge tone="warn" tipContent={t("steward.service.starting")}>
        {t("steward.service.state", { state: "starting" })}
      </StatusBadge>
    )
  }
  return (
    <StatusBadge
      tone="ok"
      tipContent={health ? t("steward.service.health", { health }) : t("steward.service.no-health")}
    >
      {t("steward.service.state", { state: "running" })}
    </StatusBadge>
  )
}

const DOT_TONE: Record<Tone, string> = {
  ok: "bg-success",
  warn: "bg-warning",
  down: "bg-destructive",
  /** A meant stop: a bordered dot, differing from the other three in shape, not only colour. */
  idle: "border border-muted-foreground/60 bg-muted-foreground/30",
}

/** The state word each tone says, which `steward.service.state` turns into the reader's word. */
const DOT_STATE: Record<Tone, string> = {
  ok: "healthy",
  warn: "starting",
  down: "unhealthy",
  idle: "held",
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
        aria-label={t("steward.service.not-read")}
        title={t("steward.service.not-read")}
        className={cn("size-2 shrink-0 rounded-full", className)}
      />
    )
  }

  const tone = serviceTone(service)
  if (tone === "ok" && quiet) return null
  const word = t("steward.service.state", {
    state: completed(service) ? "completed" : tone === "idle" && !held(service) ? "standby" : DOT_STATE[tone],
  })

  return (
    <span
      role="img"
      aria-label={word}
      title={word}
      className={cn("size-2 shrink-0 rounded-full", DOT_TONE[tone], className)}
    />
  )
}

/** The tone of each resolve status; anything else could not be asked, which is never green. */
const AVAILABLE_TONE: Record<string, Tone> = {
  UP_TO_DATE: "ok",
  OUTDATED: "warn",
  MISSING: "warn",
  UNSUPPORTED: "idle",
}

/**
 * One artefact's resolve status, in four answers kept apart.
 *
 * "could not ask" must never look like "up to date", since a silent source looks like one with no change.
 */
export function AvailableBadge({ status }: { status: string }) {
  return (
    <StatusBadge
      tone={AVAILABLE_TONE[status] ?? "down"}
      tipContent={t("steward.artifact.status-tip", { status: choice(status) })}
    >
      {t("steward.artifact.status", { status: choice(status) })}
    </StatusBadge>
  )
}

const DRIFT_TONE: Record<string, Tone> = { UP_TO_DATE: "ok", OUTDATED: "warn" }

/** Image drift: UNKNOWN is never silent or green, and LOCAL, being ahead of the registry, is neutral. */
/** The drift a service is drawn with: anything built on the host reads as a local build, whatever its image says. */
export function shownDrift(drift: ImageState, localBuild?: LocalBuild): ImageState {
  return localBuild ? "LOCAL" : drift
}

export function DriftBadge({
  drift,
  image,
  localBuild,
}: {
  drift: ImageState
  image?: string
  localBuild?: LocalBuild
}) {
  const shown = shownDrift(drift, localBuild)
  return (
    <StatusBadge
      tone={DRIFT_TONE[shown] ?? "idle"}
      tipContent={<DriftTip drift={drift} image={image} localBuild={localBuild} />}
    >
      {t("steward.image.drift", { drift: choice(shown) })}
    </StatusBadge>
  )
}

/** What a drift badge or mark explains: the image and jars of a local build, or what the drift word means. */
export function DriftTip({ drift, image, localBuild }: { drift: ImageState; image?: string; localBuild?: LocalBuild }) {
  if (shownDrift(drift, localBuild) !== "LOCAL") return <>{t("steward.image.drift-tip", { drift: choice(drift) })}</>
  const jars = localBuild?.jars ?? []
  return (
    <div className="flex w-full min-w-0 flex-col gap-1 sm:w-auto sm:min-w-64">
      {drift === "LOCAL" || localBuild?.image ? (
        <>
          <span className="text-xs font-medium font-heading text-muted-foreground">{t("steward.image.label")}</span>
          <span className="truncate text-sm">{localBuild?.image ?? image}</span>
        </>
      ) : null}
      {jars.length > 0 ? (
        <>
          <span className="text-xs font-medium font-heading text-muted-foreground">
            {t("steward.image.local-jars")}
          </span>
          {jars.map((jar) => (
            <span key={jar} className="truncate text-sm">
              {jar}
            </span>
          ))}
        </>
      ) : null}
      <span className="text-xs text-muted-foreground">{t("steward.image.local-tip")}</span>
    </div>
  )
}

/** The status of a run, as its row in steward's inbox carries it. */
export function RunStatus({ status }: { status: string }) {
  const tone: Tone = status === "DONE" ? "ok" : status === "FAILED" ? "down" : status === "RUNNING" ? "warn" : "idle"
  return <StatusBadge tone={tone}>{runStatus(status)}</StatusBadge>
}

/** The outcome of a run in {@link RunStatus}'s words, for text such as the command palette's search. */
export function runStatus(status: string): string {
  return t("run.status", { status: choice(status) })
}

/** What kind of run it was. */
export function runKind(kind: string): string {
  return t("run.kind", { kind: choice(kind) })
}
