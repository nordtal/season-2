import { Link } from "@tanstack/react-router"
import { ArrowUp, ArrowUpRight, CircleHelp, Users, Wrench } from "lucide-react"
import { cn } from "cn"

import type { Service } from "@/lib/api"
import { bytes, percent, since } from "@/lib/format"
import { HealthDot } from "@/components/steward/status"
import { RecreateButton } from "@/components/steward/recreate"
import { buttonVariants } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

import { INGRESS, imageTag, type NodeId } from "./topology"

/**
 * A tooltip that reads as a tooltip on this theme, not as a stray white rectangle.
 *
 * `TooltipContent`'s own default is `bg-foreground text-background`, which on a light theme is a
 * dark chip - on this one it is the reverse, because `--foreground` is the near-white text colour
 * and `--background` is the near-black page. Till's word for the result: it should not be white.
 * `--popover` is the token this theme already keeps for "a surface that floats over the page", so
 * it is what every tooltip drawn in the network view asks for instead, here and in the toolbar.
 */
const DARK_TOOLTIP = "border border-border bg-popover text-popover-foreground"

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
 * The identifier left and the health dot right on the first line; underneath, the running tag with
 * the image-drift mark in front of it, and the player count pinned to the right of that same line
 * for the four Minecraft services that carry one. Everything else - memory, CPU, uptime - is in the
 * tooltip on the dot, which is where the ticket put it: anything that does not fit on a box goes
 * there, and never into a line of text beside it.
 *
 * <h2>Why the count is not on the first line</h2>
 * It used to sit there, beside the identifier and the dot, and at 390px that is exactly what broke:
 * the orchestrator's measurement of 2026-09-17 found `network-control` and `hunger-games` losing
 * their own name to an ellipsis - `network-co…` and `hunger-ga…` - on the two-column grid every
 * draft falls back to at that width, because the identifier is `min-w-0 flex-1 truncate` and the
 * player count sat on the same line as `shrink-0`, so it was the identifier, the first of the
 * ticket's four facts, that gave way first. `d` and `f` make it worse again with a `pr-8` rail
 * margin reserved for the database bus, narrowing every box further - which is why `network-control`
 * fits in draft `a`'s plain grid at that width but not in either of those. The second line has a
 * dash and a short tag on it and nothing else, so it has the room the first line does not, and this
 * moves the count there **unconditionally** rather than behind a breakpoint: a width-keyed toggle
 * would have to know each draft's own column count to reason about a box's actual width, since the
 * same component sits inside `grid-cols-2`, `-3`, `-4` and a rail margin across the seven drafts, and
 * getting that wrong the same way `a` "worked" only by accident is the exact failure this is fixing.
 * Always-second-line is one fewer variable, for the cost of the count sitting one line lower even
 * where 1440px had room to spare - which changes nothing that was being read differently there
 * before, so 1440px stays as it was.
 *
 * <h2>Two shapes, and neither of them may collapse the layout</h2>
 * Six of the ten services have no player count, and `players` is then **absent rather than zero**
 * (steward/86). On the four services that draw a second line, the count sits in a flex row that is
 * pinned right after the tag, so a node without one is the same row with one fewer item in it - the
 * tag does not move, and no placeholder is drawn to keep it still. `players` (the `INGRESS` box) has
 * no second line at all, so it keeps its own count on the first line beside the dot exactly as
 * before - that box's identifier is short enough that the failure above never applied to it, and
 * moving its count would need inventing a second line this one box does not otherwise have.
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

/**
 * What a node can actually do, not what a toolbar usually has room for.
 *
 * Till asked for at least three jumps per node - to the service page, to its configuration, to its
 * logs or an action. `navigation.ts` and the router name exactly one destination per service,
 * `/services/$name`: log window, console and configuration are three sections of that one page,
 * not three routes, and none of them carries an id a link could jump to. Three buttons to one
 * address would be three names for the same jump, which is worse than one - so this draws the two
 * things that are real: **open**, the same destination the identifier above already links to, made
 * into an explicit target of its own for a toolbar that is supposed to look like one; and
 * **recreate**, `RecreateButton` as already built for the service page, wired to the same
 * `steward-deployer` job it already uses there. It already declines to draw itself for
 * `steward-deployer`. Nothing else here is a button that does nothing: what is missing is written
 * up in the ticket rather than faked as a third link to the page the first one already opens.
 */
function NodeToolbar({ id }: { id: Exclude<NodeId, typeof INGRESS> }) {
  return (
    <div className="flex flex-wrap items-center gap-1.5 border-t border-border/60 pt-1.5">
      <Tooltip>
        <TooltipTrigger asChild>
          <Link
            to="/services/$name"
            params={{ name: id }}
            aria-label={`open ${id}`}
            className={cn(buttonVariants({ variant: "ghost", size: "icon-xs" }))}
          >
            <ArrowUpRight aria-hidden />
          </Link>
        </TooltipTrigger>
        <TooltipContent className={DARK_TOOLTIP}>open</TooltipContent>
      </Tooltip>

      <RecreateButton service={id} size="sm" />
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

        {/* Only `players` (`INGRESS`) draws its count here - it has no second line to put it on
            instead. Every real service with a count draws it below, next to the drift mark and
            the running tag, which is where the room at 390px actually is (see the class comment). */}
        {ingress && players !== undefined ? (
          <span
            // The only word on the box that is not data, and it is not drawn: the icon carries it
            // on screen and this carries it to a screen reader and to a test.
            title="players"
            className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground"
          >
            <Users className="size-3" aria-hidden />
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
            <TooltipContent className={cn("max-w-xs", DARK_TOOLTIP)}>
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
          <span className="min-w-0 flex-1 truncate tnum">{imageTag(service?.image)}</span>

          {players === undefined ? null : (
            <span
              // Same word, same reason as the one on `players` above: the icon is what a reader
              // sees, this is what a screen reader and a test see.
              title="players"
              className="flex shrink-0 items-center gap-1"
            >
              <Users className="size-3" aria-hidden />
              <span className="tnum">{players}</span>
            </span>
          )}
        </div>
      )}

      {/* `players` gets no toolbar: it is not a container (see the class comment on `ServiceNode`),
          has no route of its own in `navigation.ts`, and `RecreateButton` has nothing to recreate.
          A toolbar with zero real buttons is worse than none - Till's own rule for this ticket. */}
      {ingress ? null : <NodeToolbar id={id} />}
    </div>
  )
}
