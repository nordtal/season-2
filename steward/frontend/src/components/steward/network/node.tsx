import { ArrowSquareOutIcon, ArrowUpIcon, QuestionIcon, UsersIcon, WrenchIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { cn } from "cn"

import type { Service } from "@/lib/api"
import { bytes, percent, since } from "@/lib/format"
import { HealthDot } from "@/components/steward/status"
import { RecreateButton } from "@/components/steward/recreate"
import { buttonVariants } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { SkeletonText } from "@/components/ui/skeleton"

import { INGRESS, imageTag, type NodeId } from "./topology"

/**
 * One card of the network picture, the same size in every arrangement, sized by `--node-w` and `--node-h`.
 *
 * Three lines: identifier and health dot, drift mark with tag and player count, then the toolbar.
 */

/** The words for the drift states; `UP_TO_DATE` draws none. */
const DRIFT_WORDS: Record<string, string> = {
  OUTDATED: "out of date",
  LOCAL: "built on this host, ahead of the registry",
  UP_TO_DATE: "the same image the registry has",
  UNKNOWN: "never compared against the registry",
}

/**
 * Image drift as a mark: silent when up to date, warning when OUTDATED, neutral for LOCAL and UNKNOWN.
 *
 * Exported for `table.tsx`, so the phone's rows draw the same mark.
 */
export function DriftMark({ drift }: { drift: string }) {
  switch (drift) {
    case "OUTDATED":
      return <ArrowUpIcon className="size-3 shrink-0 text-warning" role="img" aria-label="out of date" />
    case "LOCAL":
      return <WrenchIcon className="size-3 shrink-0 text-muted-foreground" role="img" aria-label="built on this host" />
    case "UP_TO_DATE":
      return null
    default:
      return (
        <QuestionIcon className="size-3 shrink-0 text-muted-foreground" role="img" aria-label="image not compared" />
      )
  }
}

/** Resources and runtime, on the dot; exported for `network.test.tsx`, which cannot hover. */
export function Vitals({ service }: { service: Service }) {
  return (
    /** A stopped node says "not running" instead of a row of dashes. */
    <span className="flex flex-col gap-0.5">
      <span>{service.startedAt ? `up ${since(service.startedAt)}` : "not running"}</span>
      {service.cpuPercent == null ? null : <span>cpu {percent(service.cpuPercent)}</span>}
      {service.memoryBytes == null ? null : (
        <span>
          memory {bytes(service.memoryBytes)}
          {service.memoryLimitBytes ? ` of ${bytes(service.memoryLimitBytes)}` : ""}
        </span>
      )}
      {service.unreadable ? <span className="text-warning">{service.unreadable}</span> : null}
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
            aria-label={`open ${id}`}
            className={cn(buttonVariants({ variant: "ghost", size: "icon-xs" }))}
          >
            <ArrowSquareOutIcon aria-hidden />
          </Link>
        </TooltipTrigger>
        <TooltipContent>open {id}</TooltipContent>
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
            <TooltipContent>{service?.status ?? service?.state ?? "not read yet"}</TooltipContent>
          </Tooltip>
        )}

        {/* Only `players` draws its count here, having no second line. */}
        {ingress && players !== undefined ? (
          <span
            /** The icon carries the word on screen; this carries it to a screen reader and a test. */
            title="players"
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
              <DriftMark drift={service?.drift ?? "UNKNOWN"} />
              {/* Only the tag has to be fetched, so only it waits. */}
              {service ? (
                <span className="min-w-0 flex-1 truncate tnum">{imageTag(service.image)}</span>
              ) : (
                <SkeletonText className="min-w-0 flex-1" width="long" />
              )}

              {players === undefined ? null : (
                <span
                  /** The same word for a screen reader and a test, as on `players` above. */
                  title="players"
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
              <span className="tnum">{service?.image ?? "no image reported"}</span>
              <span>{DRIFT_WORDS[service?.drift ?? "UNKNOWN"] ?? DRIFT_WORDS.UNKNOWN}</span>
            </span>
          </TooltipContent>
        </Tooltip>
      )}

      {/* `players` gets no toolbar, since it is not a container. */}
      {ingress ? null : <NodeToolbar id={id} />}
    </div>
  )
}
