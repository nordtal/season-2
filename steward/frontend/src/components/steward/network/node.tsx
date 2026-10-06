import { ArrowSquareOutIcon, ArrowUpIcon, QuestionIcon, UsersIcon, WrenchIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { cn } from "cn"

import type { ImageState, LocalBuild, Service } from "@/lib/api"
import { bytes, percent } from "@/lib/format"
import { DriftTip, HealthDot, Tip, shownDrift } from "@/components/steward/status"
import { RecreateButton } from "@/components/steward/recreate"
import { buttonVariants } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { SkeletonText } from "@/components/ui/skeleton"

import { INGRESS, imageTag, type NodeId } from "./topology"
import { choice, since, t } from "@/lib/texts"

/**
 * One card of the network picture, the same size in every arrangement, sized by `--node-w` and `--node-h`.
 *
 * Three lines: identifier and health dot, drift mark with tag and player count, then the toolbar.
 */

/**
 * Image drift as a mark: silent when up to date, warning when OUTDATED, neutral for LOCAL and UNKNOWN.
 *
 * Exported for `table.tsx`, so the phone's rows draw the same mark.
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
  const Icon = shown === "OUTDATED" ? ArrowUpIcon : shown === "LOCAL" ? WrenchIcon : QuestionIcon
  return (
    /** Padded out to a finger's size, and pulled back so the line does not move. */
    <Tip content={<DriftTip drift={drift} image={image} localBuild={localBuild} />} className="-m-2 p-2">
      <Icon
        className={cn("size-3 shrink-0", shown === "OUTDATED" ? "text-warning" : "text-muted-foreground")}
        role="img"
        aria-label={shown === "LOCAL" && jars.length > 0 ? `${word}: ${jars.join(", ")}` : word}
      />
    </Tip>
  )
}

/** Resources and runtime, on the dot; exported for `network.test.tsx`, which cannot hover. */
export function Vitals({ service }: { service: Service }) {
  return (
    /** A stopped node says it is not running instead of a row of dashes. */
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
 * What a node can do: open its service page, and recreate it.
 *
 * Ghost and icon-only, so ten toolbars do not compete with the lines; exported for `table.tsx`.
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
  /** Absent until `/api/services` answers, or for a card that is not a container. */
  service?: Service
  /** The count to draw, or nothing; never a zero standing in for "not said". */
  players?: number
  className?: string
}) {
  const ingress = id === INGRESS

  return (
    <div
      /** Read back by `useNodeBoxes`, since the lines are drawn from measured positions. */
      data-node={id}
      className={cn(
        "relative z-10 flex h-(--node-h) w-(--node-w) flex-col gap-0.5 rounded-md border bg-card px-2.5 py-1.5",
        /** The people card is drawn as an opening: dashed, with no dot, since it has no health. */
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

        {/* Only `players` draws its count here, having no second line. */}
        {ingress && players !== undefined ? (
          <span
            /** The icon carries the word on screen; this carries it to a screen reader and a test. */
            title={t("steward.network.players")}
            className="flex shrink-0 items-center gap-1 text-[0.6875rem] text-muted-foreground"
          >
            <UsersIcon className="size-3" aria-hidden />
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
              {/* Only the tag has to be fetched, so only it waits. */}
              {service ? (
                <span className="min-w-0 flex-1 truncate tnum">{imageTag(service.image)}</span>
              ) : (
                <SkeletonText className="min-w-0 flex-1" width="long" />
              )}

              {players === undefined ? null : (
                <span
                  /** The same word for a screen reader and a test, as on `players` above. */
                  title={t("steward.network.players")}
                  className="flex shrink-0 items-center gap-1"
                >
                  <UsersIcon className="size-3" aria-hidden />
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

      {/* `players` gets no toolbar, since it is not a container. */}
      {ingress ? null : <NodeToolbar id={id} />}
    </div>
  )
}
