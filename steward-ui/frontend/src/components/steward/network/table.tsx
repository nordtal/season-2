import { UsersIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"

import { dateTime } from "@/lib/format"
import { HealthDot, held } from "@/components/steward/status"
import { SkeletonText } from "@/components/ui/skeleton"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

import { useNetwork } from "./data"
import { DriftMark, NodeToolbar } from "./node"
import { SECTIONS, imageTag } from "./topology"

/**
 * The network, on a phone: one row per service, and no drawing at all.
 *
 * <h2>Why a table and not a smaller picture</h2>
 * Till, 2026-09-19, translated: on the mobile view the whole thing sadly has to become a table
 * again - every service node, with the information it carries in the network plan, drawn as one
 * row. The word he used for "sadly" is the honest part of it. A topology drawing is
 * two-dimensional and a phone is one column wide; the thing that was actually shipped before this
 * ticket was a second hand-written arrangement, 352px across and 940px tall, which is a drawing
 * that has stopped being one - ten cards in three columns with the lines between them squeezed into
 * whatever was left. A list of ten rows says less and says all of it.
 *
 * <h2>What a row carries, and what it cannot</h2>
 * Exactly the card's four facts, in the card's own order: the identifier, the health dot, the image
 * drift mark with the running tag, and the player count for the four services that have one. The
 * toolbar comes across whole - `NodeToolbar` is imported from `node.tsx` rather than rebuilt, so
 * "open" and "recreate" are the same two buttons doing the same two things.
 *
 * What it cannot carry is the one thing the drawing is *for*: which edge goes where. Till decided
 * that outright - **no "connected to" column**. A column of service names beside a column of
 * service names is a table pretending to be a graph, and it would be read as neither. The wiring
 * becomes the **order** instead: `SECTIONS` in `topology.ts` is the plan's own grouping written
 * down as five headings, so a reader still learns that the three Paper servers belong together and
 * that everything above them is the way in.
 *
 * <h2>No frame, and no separators between the facts</h2>
 * Two standing rules of this interface, both of which a table is the usual place to break. Bordered
 * things do not nest, so the rows sit flat on the page under the panel's own heading rather than
 * inside a `Card`; and nothing between two facts on a row is a written character - the space
 * between them is the separator, the way it is on the card this row is a flattening of.
 */
export function NetworkTable() {
  const network = useNetwork()

  return (
    <div className="flex flex-col gap-4">
      {SECTIONS.map((section) => (
        <div key={section.id} className="flex flex-col">
          <h3 className="mb-1 text-xs font-medium tracking-wide text-muted-foreground">{section.title}</h3>
          {section.members.map((id) => {
            const service = network.service(id)
            const players = network.players(id)
            return (
              <div
                // Read by `network.test.tsx` the same way a card is, so the one assertion that
                // matters most here - that every service in `navigation.ts` is on the page and
                // none has quietly fallen out of a section - is the same assertion either way.
                key={id}
                data-row={id}
                className="flex min-w-0 items-center gap-2 border-b border-border/60 py-1 last:border-b-0"
              >
                {service ? (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <span tabIndex={0} className="flex shrink-0 rounded-full">
                        <HealthDot service={service} quiet={false} />
                      </span>
                    </TooltipTrigger>
                    {/* steward/134: for a held service Docker's own sentence is true and beside
                        the point - "Exited (0) 5 minutes ago" is what a crash says too. The row
                        that makes it a decision is `service_hold`, so that is what this says. */}
                    <TooltipContent>
                      {held(service)
                        ? `Held down since ${dateTime(service.hold!.since)}${
                            service.hold!.by ? ` by ${service.hold!.by}` : ""
                          }.`
                        : (service.status ?? service.state)}
                    </TooltipContent>
                  </Tooltip>
                ) : (
                  <HealthDot />
                )}

                <Link
                  to="/services/$name"
                  params={{ name: id }}
                  className="min-w-0 flex-1 truncate text-sm font-medium underline-offset-4 hover:text-primary hover:underline"
                >
                  {id}
                </Link>

                {players === undefined ? null : (
                  <span
                    // Same word and same reason as on the card: the icon is what a reader sees,
                    // this is what a screen reader and a test see.
                    title="players"
                    className="flex shrink-0 items-center gap-1 text-[0.6875rem] text-muted-foreground"
                  >
                    <UsersIcon className="size-3" aria-hidden />
                    <span className="tnum">{players}</span>
                  </span>
                )}

                <DriftMark drift={service?.drift ?? "UNKNOWN"} />
                {service ? (
                  <span className="max-w-24 shrink truncate text-[0.6875rem] text-muted-foreground tnum">
                    {imageTag(service.image)}
                  </span>
                ) : (
                  <SkeletonText className="w-16 shrink-0" width="short" />
                )}

                {/* Fixed width, because `RecreateButton` declines to draw itself for
                    `steward-deployer` - the service that would be recreating itself - and a
                    toolbar that is one button narrower on one row pulls that row's tag out of
                    line with the other nine. On a card that never showed, because each card is
                    its own box; in a list it is the only ragged edge there is. */}
                <div className="flex w-[3.125rem] shrink-0 justify-end">
                  <NodeToolbar id={id} />
                </div>
              </div>
            )
          })}
        </div>
      ))}
    </div>
  )
}
