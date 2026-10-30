import { cn } from "@/lib/utils"

import type { ImageState, LocalBuild, Service } from "@/lib/api"
import { choice, t } from "@/lib/texts"
import { Badge } from "@/components/ui/badge"
import { Skeleton } from "@/components/ui/skeleton"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import type { ComponentProps, ReactElement, ReactNode } from "react"

/** Every badge also shows a word, so colour is never the only signal. */

export type Tone = "ok" | "warn" | "down" | "idle"

export const TONES: Record<Tone, string> = {
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
      /**
       * `max-w-full` and `truncate` make a long badge in a table card end in an ellipsis and not in a silent cut.
       */
      className={cn("max-w-full truncate font-medium", TONES[tone], className)}
    >
      {children}
    </Badge>
  )
  if (!tipContent) return badge
  return (
    /** `max-w-full` is set here too, because this span is the grid item in a table card. */
    <Tip content={tipContent}>{badge}</Tip>
  )
}

/** The trigger is a focusable span, so a mark that is not a control still answers the keyboard. */
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

/**
 * The tooltip of an element that is the trigger itself: a control, or a mark that should not take the focus.
 *
 * The tree stays the same whether the tip is there or not and whether the control is disabled, so a field whose tip
 * comes with its first keystroke keeps its focus. A disabled control answers the pointer again, so its tip, mostly the
 * reason it is disabled, shows on hover; it takes no focus, as before.
 *
 * What an outer `asChild` trigger hands it goes on to the element, so a popover's trigger may wrap it.
 */
export function Hint({
  tip,
  children,
  style,
  ...props
}: { tip?: ReactNode; children: ReactElement<{ disabled?: boolean }> } & Omit<
  ComponentProps<typeof TooltipTrigger>,
  "asChild" | "children"
>) {
  const shown = Boolean(tip)
  return (
    <Tooltip>
      <TooltipTrigger
        asChild
        {...props}
        style={shown && children.props.disabled ? { ...style, pointerEvents: "auto" } : style}
      >
        {children}
      </TooltipTrigger>
      {shown ? <TooltipContent className="max-w-xs">{tip}</TooltipContent> : null}
    </Tooltip>
  )
}

export type ServiceHealth = Pick<Service, "state" | "health" | "hold" | "alert"> &
  Partial<Pick<Service, "oneShot" | "lastRun">>

/**
 * Red is the alert rule's word alone. A stop that the rule raises nothing about is intended, as for a standby or a hold, so it is `idle`. A one-shot that exited cleanly did its job, so it is `ok`.
 */
export function serviceTone(service: ServiceHealth): Tone {
  if (service.alert === "down") return "down"
  if (service.state !== "running") return completed(service) ? "ok" : "idle"
  if (service.health === "starting") return "warn"
  return "ok"
}

export function completed(service: ServiceHealth): boolean {
  return service.oneShot === true && service.state !== "running" && service.lastRun?.exitCode === 0
}

/** A hold on a running container says nothing. */
export function held(service: ServiceHealth): boolean {
  return service.hold !== undefined && service.state !== "running"
}

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
    /** One badge. Docker's own word moves to the title and does not become a second, red badge. */
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
  /**
   * An intended stop gets a bordered dot, which differs from the other three in shape and not only in colour.
   */
  idle: "border border-muted-foreground/60 bg-muted-foreground/30",
}

const DOT_STATE: Record<Tone, string> = {
  ok: "healthy",
  warn: "starting",
  down: "unhealthy",
  idle: "held",
}

/**
 * `quiet` hides the fine state. An undefined `service` draws a neutral dot and never the fine one.
 */
export function HealthDot({
  service,
  className,
  quiet = true,
  tip = false,
}: {
  service?: ServiceHealth
  className?: string
  quiet?: boolean
  /** Shows the state's word as a tooltip, where no tooltip around the dot names it already. */
  tip?: boolean
}) {
  if (!service) {
    const unknown = (
      <Skeleton
        role="img"
        aria-label={t("steward.service.not-read")}
        className={cn("size-2 shrink-0 rounded-full", className)}
      />
    )
    return tip ? <Hint tip={t("steward.service.not-read")}>{unknown}</Hint> : unknown
  }

  const tone = serviceTone(service)
  if (tone === "ok" && quiet) return null
  const word = t("steward.service.state", {
    state: completed(service) ? "completed" : tone === "idle" && !held(service) ? "standby" : DOT_STATE[tone],
  })

  const dot = (
    <span role="img" aria-label={word} className={cn("size-2 shrink-0 rounded-full", DOT_TONE[tone], className)} />
  )
  return tip ? <Hint tip={word}>{dot}</Hint> : dot
}

/**
 * The tone of each resolve status. A status that Steward does not know yet is neutral and never green.
 */
const AVAILABLE_TONE: Record<string, Tone> = {
  UP_TO_DATE: "ok",
  OUTDATED: "warn",
  MISSING: "warn",
  UNSUPPORTED: "idle",
  NOT_IN_RELEASE: "idle",
  UNRESOLVED: "down",
  MOUNT_MISSING: "down",
}

/**
 * "could not ask" never looks like "up to date", because a silent source looks like one with no change.
 * "not installed" is normal on a fresh volume. On a running server it means a new file or a renamed jar.
 * "unsupported" means that the source has no build for the network's Minecraft version. Nothing failed, and the artefact stays listed so the next run picks up a build when one appears.
 */
export function AvailableBadge({ status }: { status: string }) {
  return (
    <StatusBadge tone={AVAILABLE_TONE[status] ?? "idle"}>
      {t("steward.artifact.status", { status: choice(status) })}
    </StatusBadge>
  )
}

const DRIFT_TONE: Record<string, Tone> = { UP_TO_DATE: "ok", OUTDATED: "warn" }

/**
 * Image drift: UNKNOWN is never silent or green. LOCAL is ahead of the registry and is neutral.
 */
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
    <StatusBadge tone={DRIFT_TONE[shown] ?? "idle"} tipContent={driftTip(drift, image, localBuild)}>
      {t("steward.image.drift", { drift: choice(shown) })}
    </StatusBadge>
  )
}

/**
 * A local build is one made on this host and in no release. An update that would replace it names it and asks first. It returns nothing when the image is not known.
 */
export function driftTip(drift: ImageState, image?: string, localBuild?: LocalBuild): ReactNode {
  const shown = shownDrift(drift, localBuild)
  const jars = localBuild?.jars ?? []
  const local = shown === "LOCAL"
  const name = local ? (drift === "LOCAL" || localBuild?.image ? (localBuild?.image ?? image) : undefined) : image
  const unchecked = !local && shown !== "UP_TO_DATE" && shown !== "OUTDATED"
  if (!name && jars.length === 0 && !unchecked) return null
  return (
    <div className="flex w-full min-w-0 flex-col gap-1 sm:w-auto sm:min-w-64">
      {name ? (
        <>
          <span className="text-xs font-medium font-heading text-muted-foreground">{t("steward.image.label")}</span>
          <span className="truncate text-sm">{name}</span>
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
      {unchecked ? <span className="text-xs text-muted-foreground">{t("steward.image.unchecked-tip")}</span> : null}
    </div>
  )
}

export function RunStatus({ status }: { status: string }) {
  const tone: Tone = status === "DONE" ? "ok" : status === "FAILED" ? "down" : status === "RUNNING" ? "warn" : "idle"
  return <StatusBadge tone={tone}>{runStatus(status)}</StatusBadge>
}

export function runStatus(status: string): string {
  return t("run.status", { status: choice(status) })
}

export function runKind(kind: string): string {
  return t("run.kind", { kind: choice(kind) })
}
