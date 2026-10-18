import { TERM_ICON } from "@/components/steward/term-icons"
import { Link } from "@tanstack/react-router"

import { dateTime } from "@/lib/format"
import { HealthDot, held } from "@/components/steward/status"
import { SkeletonText } from "@/components/ui/skeleton"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

import { useNetwork } from "./data"
import { DriftMark, NodeToolbar } from "./node"
import { imageTag, type Section } from "./topology"
import { t } from "@/lib/texts"

/**
 * The network on a phone: one row per service with the card's facts, grouped by the sections of the topology.
 *
 * The grouping replaces the edges, because a "connected to" column would be neither table nor graph.
 */
export function NetworkTable({ sections }: { sections: readonly Section[] }) {
  const network = useNetwork()

  return (
    <div className="flex flex-col gap-4">
      {sections.map((section) => (
        <div key={section.id} className="flex flex-col">
          <h3 className="mb-1 text-xs font-medium tracking-wide text-muted-foreground">{section.title}</h3>
          {section.members.map((id) => {
            const service = network.service(id)
            const players = network.players(id)
            return (
              <div
                /** `network.test.tsx` reads it the same way as a card. */
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
                    {/* A held service shows `service_hold`, because Docker's exit sentence reads like a crash. */}
                    <TooltipContent>
                      {held(service)
                        ? t("steward.service.held-since", {
                            since: dateTime(service.hold!.since),
                            state: service.state,
                          })
                        : (service.status ?? service.state)}
                    </TooltipContent>
                  </Tooltip>
                ) : (
                  <HealthDot />
                )}

                <Link
                  to="/services/$name"
                  params={{ name: id }}
                  className="flex min-w-0 flex-1 items-center self-stretch text-sm font-medium underline-offset-4 pointer-coarse:min-h-control hover:text-primary hover:underline"
                >
                  <span className="truncate">{id}</span>
                </Link>

                {players === undefined ? null : (
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
                )}

                <DriftMark
                  drift={service?.drift ?? "UNKNOWN"}
                  image={service?.image}
                  localBuild={service?.localBuild}
                />
                {service ? (
                  <span className="w-16 shrink-0 truncate text-right text-[0.6875rem] text-muted-foreground tnum">
                    {imageTag(service.image)}
                  </span>
                ) : (
                  <SkeletonText className="w-16 shrink-0" width="short" />
                )}

                {/* Two buttons wide at the pointer's target size, because `RecreateButton` skips `steward-agent`. */}
                <div className="flex w-[3.125rem] shrink-0 justify-end pointer-coarse:w-[calc(2*var(--control-min-height)+0.125rem)]">
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
