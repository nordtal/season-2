import { ArrowSquareOutIcon, QuestionIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Link } from "@tanstack/react-router"
import { cn } from "cn"

import type { ImageState, LocalBuild, Service } from "@/lib/api"
import { bytes, percent } from "@/lib/format"
import { HealthDot, Tip, driftTip, shownDrift } from "@/components/steward/status"
import { RecreateButton } from "@/components/steward/recreate"
import { buttonVariants } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { SkeletonText } from "@/components/ui/skeleton"

import { INGRESS, imageTag, type NodeId } from "./topology"
import { choice, since, t } from "@/lib/texts"

/**
 * One card of the network picture. It has the same size in every arrangement, set by `--node-w` and `--node-h`.
 *
 * It has three lines: identifier and health dot, drift mark with tag and player count, then the toolbar.
 */

/**
 * Image drift as a mark: silent when up to date, a warning when OUTDATED, neutral for LOCAL and UNKNOWN.
 *
 * `table.tsx` imports it, so the phone's rows draw the same mark.
 */
export function DriftMark({
  drift,
  image,
  localBuild,
}: {
  drift: ImageState
  image?: string
  localBuild?: LocalBuild
}) {
  const shown = shownDrift(drift, localBuild)
  if (shown === "UP_TO_DATE") return null

  const jars = localBuild?.jars ?? []
  const word = t("steward.image.drift", {
    drift: shown === "OUTDATED" ? "outdated" : shown === "LOCAL" ? "local" : "unknown",
  })
  const Icon = shown === "OUTDATED" ? TERM_ICON.update : shown === "LOCAL" ? TERM_ICON.localBuild : QuestionIcon
  const mark = (
    <Icon
      className={cn("size-3 shrink-0", shown === "OUTDATED" ? "text-warning" : "text-muted-foreground")}
      role="img"
      aria-label={shown === "LOCAL" && jars.length > 0 ? `${word}: ${jars.join(", ")}` : word}
    />
  )
  const tip = driftTip(drift, image, localBuild)
  if (!tip) return mark
  return (
    /** Padded to a finger's size and pulled back, so the line does not move. */
    <Tip content={tip} className="-m-2 p-2">
      {mark}
    </Tip>
  )
}

/**
 * Resources and runtime, on the dot. `network.test.tsx` imports it, because the test cannot hover.
 */
export function Vitals({ service }: { service: Service }) {
  return (
    /** A stopped node says that it is not running and shows no row of dashes. */
    <span className="flex flex-col gap-0.5">
      <span>
        {service.startedAt
          ? t("steward.network.up", { since: since(service.startedAt) })
          : t("steward.network.not-running")}
      </span>
      {service.cpuPercent == null ? null : (
        <span>{t("steward.network.cpu", { percent: percent(service.cpuPercent) })}</span>
      )}
      {service.memoryBytes == null ? null : (
        <span>
          {service.memoryLimitBytes
            ? t("steward.network.memory-of", {
                used: bytes(service.memoryBytes),
                limit: bytes(service.memoryLimitBytes),
              })
            : t("steward.network.memory", { used: bytes(service.memoryBytes) })}
        </span>
      )}
    </span>
  )
}

/**
 * What a node can do: open its service page and recreate it.
 *
 * The buttons are ghost buttons with icons only, so ten toolbars do not compete with the lines. `table.tsx` imports the toolbar.
 */
export function NodeToolbar({ id }: { id: Exclude<NodeId, typeof INGRESS> }) {
  return (
    <div className="mt-auto flex items-center justify-end gap-0.5">
      <Tooltip>
        <TooltipTrigger asChild>
          <Link
            to="/services/$name"
            params={{ name: id }}
            aria-label={t("steward.network.open", { service: id })}
            className={cn(buttonVariants({ variant: "ghost", size: "icon-xs" }))}
          >
            <ArrowSquareOutIcon aria-hidden />
          </Link>
        </TooltipTrigger>
        <TooltipContent>{t("steward.network.open", { service: id })}</TooltipContent>
      </Tooltip>

      <RecreateButton service={id} variant="ghost" compact />
    </div>
  )
}

export function ServiceNode({
  id,
  service,
  players,
  className,
}: {
  id: NodeId
  /** Absent until `/api/services` answers, and for a card that is not a container. */
  service?: Service
  /** The count to draw, or nothing. A zero never stands for "not reported". */
  players?: number
  className?: string
}) {
  const ingress = id === INGRESS

  return (
    <div
      /**
       * `useNodeBoxes` reads this attribute back, because the lines are drawn from measured positions.
       */
      data-node={id}
      className={cn(
        "relative z-10 flex h-(--node-h) w-(--node-w) flex-col gap-0.5 rounded-md border bg-card px-2.5 py-1.5",
        /**
         * The people card is drawn as an opening: dashed, without a dot, because it has no health.
         */
        ingress ? "border-dashed border-border bg-background" : "border-border",
        className,
      )}
    >
      <div className="flex min-w-0 items-center gap-1.5">
        {ingress ? (
          <span className="min-w-0 flex-1 truncate text-xs font-medium text-muted-foreground">{id}</span>
        ) : (
          <Tooltip>
            <TooltipTrigger asChild>
              <Link
                to="/services/$name"
                params={{ name: id }}
                className="min-w-0 flex-1 truncate text-xs font-medium underline-offset-4 hover:text-primary hover:underline"
              >
                {id}
              </Link>
            </TooltipTrigger>
            {/* Docker's own status sentence, or the state word until it answers. */}
            <TooltipContent>{service?.status ?? service?.state ?? t("steward.service.not-read")}</TooltipContent>
          </Tooltip>
        )}

        {/* Only `players` draws its count here, because it has no second line. */}
        {ingress && players !== undefined ? (
          <span
            /**
             * The icon shows the word on screen. The title gives it to a screen reader and a test.
             */
            title={t("steward.network.players")}
            className="flex shrink-0 items-center gap-1 text-[0.6875rem] text-muted-foreground"
          >
            <TERM_ICON.people className="size-3" aria-hidden />
            <span className="tnum">{players}</span>
          </span>
        ) : null}

        {ingress ? null : service ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <span tabIndex={0} className="flex shrink-0 rounded-full">
                <HealthDot service={service} quiet={false} />
              </span>
            </TooltipTrigger>
            <TooltipContent className="max-w-xs">
              <Vitals service={service} />
            </TooltipContent>
          </Tooltip>
        ) : (
          <HealthDot />
        )}
      </div>

      {ingress ? null : (
        <Tooltip>
          <TooltipTrigger asChild>
            <div
              tabIndex={0}
              className="flex min-w-0 items-center gap-1 rounded-sm text-[0.6875rem] text-muted-foreground"
            >
              <DriftMark drift={service?.drift ?? "UNKNOWN"} image={service?.image} localBuild={service?.localBuild} />
              {/* Only the tag has to be fetched, so only the tag waits. */}
              {service ? (
                <span className="min-w-0 flex-1 truncate tnum">{imageTag(service.image)}</span>
              ) : (
                <SkeletonText className="min-w-0 flex-1" width="long" />
              )}

              {players === undefined ? null : (
                <span
                  /** The same title for a screen reader and a test, as on `players` above. */
                  title={t("steward.network.players")}
                  className="flex shrink-0 items-center gap-1"
                >
                  <TERM_ICON.people className="size-3" aria-hidden />
                  <span className="tnum">{players}</span>
                </span>
              )}
            </div>
          </TooltipTrigger>
          <TooltipContent className="max-w-xs">
            <span className="flex flex-col gap-0.5">
              <span className="tnum">{service?.image ?? t("steward.network.no-image")}</span>
              <span>{t("steward.image.drift", { drift: choice(service?.drift ?? "UNKNOWN") })}</span>
            </span>
          </TooltipContent>
        </Tooltip>
      )}

      {/* `players` has no toolbar, because it is not a container. */}
      {ingress ? null : <NodeToolbar id={id} />}
    </div>
  )
}
