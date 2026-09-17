import { Link } from "@tanstack/react-router"
import { ArrowUp, CircleHelp, Users, Wrench } from "lucide-react"
import { cn } from "cn"

import type { Service } from "@/lib/api"
import { bytes, percent, since } from "@/lib/format"
import { HealthDot } from "@/components/steward/status"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

import { INGRESS, imageTag, type NodeId } from "./topology"

/**
 * One box, and the same box in all three drafts of steward/81.
 *
 * That is deliberate: Till is choosing a **shape**, so the thing inside the shape has to be
 * identical in every one of them, or the choice is about two variables at once.
 *
 * <h2>One frame, not three</h2>
 * Till's rule of 2026-09-17: bordered things do not nest. A node has the border, so nothing around
 * it may - the drafts sit flat on the page with a heading, the way `Panel` does, and never inside a
 * `Card`. The border stops here and is the only one in the picture.
 *
 * <h2>What is on it, in the order it is read</h2>
 * The identifier left, the player count and the health dot right, and one small line underneath
 * carrying the running tag with the image-drift mark in front of it. Everything else - memory, CPU,
 * uptime - is in the tooltip on the dot, which is where the ticket put it: anything that does not
 * fit on a box goes there, and never into a line of text beside it.
 *
 * <h2>Two shapes, and neither of them may collapse the layout</h2>
 * Six of the ten services have no player count, and `players` is then **absent rather than zero**
 * (steward/86). The count sits in a flex row that is pinned right by the dot, so a node without one
 * is the same box with one fewer item in it - the dot does not move, and no placeholder is drawn to
 * keep it still.
 */

/**
 * Image drift as a mark rather than a badge - four states, one of which is silence.
 *
 * `up to date` draws nothing, which is the same call `HealthDot` makes in the sidebar and the
 * Issues tile makes on the start page: ten little ticks in a picture of ten boxes say nothing that
 * their absence would not. The three that are **not** "current" all draw, and each draws
 * differently, because those are the three a reader has to tell apart:
 *
 * - `OUTDATED` is the only warning colour here. The registry has something newer, which is the
 *   thing the front page's `Behind` tile used to say and no longer does.
 * - `LOCAL` is neutral on purpose (steward/75): this host is **ahead** of the registry, and a red
 *   lamp for "you just built this" is a lamp people stop reading.
 * - `UNKNOWN` is neutral and never silent (A24): an image nobody compared is not a current image.
 */
function DriftMark({ drift }: { drift: string }) {
  switch (drift) {
    case "OUTDATED":
      return (
        <ArrowUp
          className="size-3 shrink-0 text-warning"
          role="img"
          aria-label="a newer image exists"
        />
      )
    case "LOCAL":
      return (
        <Wrench
          className="size-3 shrink-0 text-muted-foreground"
          role="img"
          aria-label="built on this host"
        />
      )
    case "UP_TO_DATE":
      return null
    default:
      return (
        <CircleHelp
          className="size-3 shrink-0 text-muted-foreground"
          role="img"
          aria-label="image not compared"
        />
      )
  }
}

/** Resources and runtime, on the dot. Three short lines, each a value with the word it needs. */
function Vitals({ service }: { service: Service }) {
  return (
    <span className="flex flex-col gap-0.5">
      <span>{service.startedAt ? `up ${since(service.startedAt)}` : "not running"}</span>
      <span>cpu {percent(service.cpuPercent)}</span>
      <span>
        memory {bytes(service.memoryBytes)}
        {service.memoryLimitBytes ? ` of ${bytes(service.memoryLimitBytes)}` : ""}
      </span>
      {service.unreadable ? <span className="text-warning">{service.unreadable}</span> : null}
    </span>
  )
}

export function ServiceNode({
  id,
  service,
  players,
  className,
}: {
  id: NodeId
  /** Absent while `/api/services` has not answered, or for a box that is not a container. */
  service?: Service
  /** The count to draw, or nothing at all - never a zero standing in for "not said". */
  players?: number
  className?: string
}) {
  const ingress = id === INGRESS

  return (
    <div
      // Read back by `useNodeBoxes` to find out where this box ended up. The lines are drawn from
      // the measured positions rather than from a coordinate table, which is the whole reason one
      // set of edges survives the layout turning ninety degrees on a phone.
      data-node={id}
      className={cn(
        "relative z-10 flex min-w-0 flex-col gap-1 rounded-md border bg-card px-2.5 py-2",
        // The one box that is people rather than a container says so by being drawn as an opening
        // rather than a thing: dashed, and with no dot, because it has no health to report.
        ingress ? "border-dashed border-border bg-background" : "border-border",
        className,
      )}
    >
      <div className="flex min-w-0 items-center gap-2">
        {ingress ? (
          <span className="min-w-0 flex-1 truncate text-sm font-medium text-muted-foreground">
            {id}
          </span>
        ) : (
          <Link
            to="/services/$name"
            params={{ name: id }}
            className="min-w-0 flex-1 truncate text-sm font-medium underline-offset-4 hover:text-primary hover:underline"
          >
            {id}
          </Link>
        )}

        {players === undefined ? null : (
          <span
            // The only word on the box that is not data, and it is not drawn: the icon carries it
            // on screen and this carries it to a screen reader and to a test.
            title="players"
            className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground"
          >
            <Users className="size-3" aria-hidden />
            <span className="tnum">{players}</span>
          </span>
        )}

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
        <div className="flex min-w-0 items-center gap-1 text-xs text-muted-foreground">
          <DriftMark drift={service?.drift ?? "UNKNOWN"} />
          <span className="min-w-0 truncate tnum">{imageTag(service?.image)}</span>
        </div>
      )}
    </div>
  )
}
