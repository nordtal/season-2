import { useIsMobile } from "@/hooks/use-mobile"
import { useTopology } from "@/lib/queries"

import { allSpots, Field, place } from "./place"
import { PLAN } from "./plan"
import { NetworkTable } from "./table"
import { layoutFaults, topologyOf } from "./topology"
import { QueryState } from "@/components/steward/query-state"
import { t } from "@/lib/texts"

/** Every box that `PLAN` places, at any width. The lanes move and the names do not. */
const PLANNED = allSpots(place(PLAN, PLAN.minWidth)).map((spot) => spot.id)

/**
 * The network picture of the start page, drawn from `PLAN` and `/api/topology`. The values of each box may still be loading.
 *
 * Below 640px it is {@link NetworkTable}. The check is `useIsMobile` and not a measured width. It is also the table wherever the served services and `PLAN` disagree, because a hand drawing cannot place a service it has never seen.
 */
export function NetworkPanel() {
  const query = useTopology()
  const narrow = useIsMobile()

  return (
    <section className="flex min-w-0 flex-col gap-3">
      <h2 className="text-lg font-semibold text-foreground">{t("steward.network.title")}</h2>
      {/* The sidebar reads the same query at start, so the placeholder bars show on a cold load only. */}
      <QueryState
        query={query}
        rows={10}
        isEmpty={(answer) => answer.services.length === 0}
        /** No container has a section label. */
        empty={{ title: t("steward.network.no-service") }}
      >
        {(map) => {
          const topology = topologyOf(map)
          const drawable = layoutFaults(PLANNED, topology.names).length === 0
          return narrow || !drawable ? (
            <NetworkTable sections={topology.sections} />
          ) : (
            <Field plan={PLAN} topology={topology} id="network" />
          )
        }}
      </QueryState>
    </section>
  )
}
