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
 * One node of the network picture, and the same node in every draft of steward/81.
 *
 * That is deliberate: Till is choosing an **arrangement**, so the thing being arranged has to be
 * identical in each of them, or the choice is about two variables at once.
 *
 * <h2>Every card is the same size, and the size is small</h2>
 * Till, 2026-09-17: every card the same size, `postgres` included, and all of them smaller.
 * Both halves are load-bearing and both are enforced here rather than left to each
 * draft: the card takes its width from `--node-w` and its height from `--node-h`, two variables the
 * placement canvas sets once, so `postgres` cannot be a bar across the picture (which is what it
 * was in drafts `a`, `d` and `f`) and `players` cannot be a stub. A topology picture whose boxes
 * differ in size says the big ones matter more; here none of them does. The cost is real and is
 * accepted: at 144px `steward-deployer` fills its line almost exactly, and anything longer would
 * truncate.
 *
 * <h2>What is on it, in the order it is read</h2>
 * Three lines, each of which earns its height:
 *
 * 1. the identifier, and the health dot right - the two facts a glance is for;
 * 2. the image-drift mark with the running tag, and the player count pinned right;
 * 3. the toolbar: open, and recreate.
 *
 * Everything else - memory, cpu, uptime, the full image reference - is in a tooltip, which is where
 * the ticket put it from the start: anything that does not fit on a card goes there and never into
 * a line of text beside it.
 *
 * <h2>Three tooltips, not one</h2>
 * The ticket's second round only had one, on the dot. Till, 2026-09-17: the update information
 * and the service name should carry tooltips as well. So there are three, and each answers
 * the question its own anchor raises rather than repeating the others:
 *
 * - **the dot** - resources and runtime, unchanged;
 * - **the tag line** - the full image reference and what the drift mark means, which is the line
 *   that truncates and the mark nobody can be expected to have learnt. Its trigger is the whole
 *   line and not the mark, because `UP_TO_DATE` draws no mark at all and is precisely the state
 *   somebody might want to confirm;
 * - **the identifier** - docker's own status sentence (`Up 3 hours (healthy)`, `Exited (0)`),
 *   which is a fact rather than a gloss on the name.
 *
 * `players` gets no tooltip on its label: it is not a service, has no status, and a tooltip
 * explaining what the word "players" means would be the explanatory text this interface does not
 * write.
 *
 * <h2>Why the count is not on the first line</h2>
 * It used to sit there, beside the identifier and the dot, and at 390px that is exactly what broke:
 * the orchestrator's measurement of 2026-09-17 found `proxy` and `hunger-games` losing
 * their own name to an ellipsis, because the identifier is `min-w-0 flex-1 truncate` and the count
 * sat on the same line as `shrink-0` - so the identifier, the first of the four facts the ticket
 * names, was what gave way first. It lives on the tag line instead, unconditionally rather than
 * behind a breakpoint, and a test holds it there. `players` (the `INGRESS` card) keeps its count on
 * the first line because it has no tag line to move it to, and its label is short enough that the
 * failure above never applied to it.
 *
 * <h2>One frame, not three</h2>
 * Till's standing rule: bordered things do not nest. The card carries the border, so nothing around
 * it may - the drafts sit flat on the page, never inside a `Card`, and the toolbar is separated by
 * space rather than by a second rule across the card.
 */

/** The three states drift draws, and the one it does not. */
const DRIFT_WORDS: Record<string, string> = {
  OUTDATED: "a newer image is in the registry",
  LOCAL: "built on this host, ahead of the registry",
  UP_TO_DATE: "the same image the registry has",
  UNKNOWN: "never compared against the registry",
}

/**
 * Image drift as a mark rather than a badge - four states, one of which is silence.
 *
 * `up to date` draws nothing, which is the same call `HealthDot` makes in the sidebar and the
 * Issues tile makes on the start page: ten little ticks in a picture of ten cards say nothing that
 * their absence would not. The three that are **not** current all draw, and each draws differently,
 * because those are the three a reader has to tell apart:
 *
 * - `OUTDATED` is the only warning colour here. The registry has something newer, which is the
 *   thing the front page's `Behind` tile used to say and no longer does.
 * - `LOCAL` is neutral on purpose (steward/75): this host is **ahead** of the registry, and a red
 *   lamp for "you just built this" is a lamp people stop reading.
 * - `UNKNOWN` is neutral and never silent: an image nobody compared is not a current image.
 *
 * The word for each of the four is in the tooltip on the line, not here: `aria-label` is what a
 * screen reader reads, and a sighted reader gets the same sentence by pointing at the line.
 *
 * Exported for `table.tsx`: below 640px the picture becomes a row per service (steward/121), and a
 * row carries the same four facts a card does. A second mark drawn from a second `switch` is the
 * kind of copy that stays right for exactly as long as nobody edits either one.
 */
export function DriftMark({ drift }: { drift: string }) {
  switch (drift) {
    case "OUTDATED":
      return <ArrowUpIcon className="size-3 shrink-0 text-warning" role="img" aria-label="a newer image exists" />
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

/**
 * Resources and runtime, on the dot. Three short lines, each a value with the word it needs.
 *
 * Exported for `network.test.tsx`: it is drawn inside a Radix tooltip, so the panel test cannot
 * reach it without driving a hover, and what is worth asserting here is what it decides to draw
 * rather than where the tooltip put it.
 */
export function Vitals({ service }: { service: Service }) {
  return (
    // NOTHING INCOMPLETE WHEN THERE IS NOTHING TO SHOW (steward/123). `cpuPercent` and
    // `memoryBytes` are absent for a container that is not running, and the formatters answer an
    // en dash - which reads as "no value" in a table column and as a rendering fault behind the
    // word "cpu". A stopped node says "not running" and then says nothing more.
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
 * What a node can actually do, not what a toolbar usually has room for.
 *
 * Till asked for at least three jumps per node - to the service page, to its configuration, to its
 * logs or an action. `navigation.ts` and the router name exactly one destination per service,
 * `/services/$name`: log window, console and configuration are three sections of that one page,
 * not three routes, and none of them carries an id a link could jump to. Three buttons to one
 * address would be three names for the same jump, which is worse than one - so this draws the two
 * things that are real: **open**, the destination the identifier above also links to, made into an
 * explicit target for a toolbar that is supposed to look like one; and **recreate**, the same
 * component and the same `steward-deployer` job the service page uses, which already declines to
 * draw itself for `steward-deployer`.
 *
 * Both are ghost and both are icon-only, which is the second half of Till's note of 2026-09-17: a
 * filled or outlined button on every one of ten cards is ten rectangles competing with the lines
 * that are the actual subject of the picture.
 *
 * Exported for `table.tsx`, and for the same reason `DriftMark` is: the phone's row is the card's
 * contents on one line, not a second design.
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
  /** Absent while `/api/services` has not answered, or for a card that is not a container. */
  service?: Service
  /** The count to draw, or nothing at all - never a zero standing in for "not said". */
  players?: number
  className?: string
}) {
  const ingress = id === INGRESS

  return (
    <div
      // Read back by `useNodeBoxes` to find out where this card ended up. The lines are drawn from
      // the measured positions rather than from a coordinate table, which is the whole reason one
      // set of edges survives the layout changing under it.
      data-node={id}
      className={cn(
        "relative z-10 flex h-(--node-h) w-(--node-w) flex-col gap-0.5 rounded-md border bg-card px-2.5 py-1.5",
        // The one card that is people rather than a container says so by being drawn as an opening
        // rather than a thing: dashed, and with no dot, because it has no health to report.
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
            {/* Docker's own sentence, not a description of the service. `status` is absent only
                while nothing has answered, and then the state word is all there is. */}
            <TooltipContent>{service?.status ?? service?.state ?? "not read yet"}</TooltipContent>
          </Tooltip>
        )}

        {/* Only `players` (`INGRESS`) draws its count here - it has no second line to put it on
            instead. Every real service with a count draws it below, next to the drift mark and the
            running tag, which is where the room at 390px actually is (see the class comment). */}
        {ingress && players !== undefined ? (
          <span
            // The only word on the card that is not data, and it is not drawn: the icon carries it
            // on screen and this carries it to a screen reader and to a test.
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
              {/* The name above comes from the plan and is drawn at once; the tag is the only
                  thing on this line that has to be fetched, so it is the only thing that waits. */}
              {service ? (
                <span className="min-w-0 flex-1 truncate tnum">{imageTag(service.image)}</span>
              ) : (
                <SkeletonText className="min-w-0 flex-1" width="long" />
              )}

              {players === undefined ? null : (
                <span
                  // Same word, same reason as the one on `players` above: the icon is what a reader
                  // sees, this is what a screen reader and a test see.
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

      {/* `players` gets no toolbar: it is not a container (see the class comment on `ServiceNode`),
          has no route of its own in `navigation.ts`, and `RecreateButton` has nothing to recreate.
          A toolbar with zero real buttons is worse than none - Till's own rule for this ticket. */}
      {ingress ? null : <NodeToolbar id={id} />}
    </div>
  )
}
