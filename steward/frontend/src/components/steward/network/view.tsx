import { useIsMobile } from "@/hooks/use-mobile"
import { useTopology } from "@/lib/queries"

import { allSpots, Field, place } from "./place"
import { PLAN } from "./plan"
import { NetworkTable } from "./table"
import { layoutFaults, topologyOf } from "./topology"
import { QueryState } from "@/components/steward/query-state"
import { t } from "@/lib/texts"

/** Every box `PLAN` places, at any width, since the lanes move and the names do not. */
const PLANNED = allSpots(place(PLAN, PLAN.minWidth)).map((spot) => spot.id)

/**
 * The start page's network picture, drawn from `PLAN` and `/api/topology` while each box's own values may still wait.
 *
 * Below 640px, per `useIsMobile` rather than a measured width, it is {@link NetworkTable}, and so it is wherever
 * the served services and `PLAN` disagree, since a hand drawing cannot place a service it has never seen.
 */
export function NetworkPanel() {
  const query = useTopology()
  const narrow = useIsMobile()

  return (
    <section className="flex min-w-0 flex-col gap-3">
      <h2 className="text-lg font-semibold text-foreground">{t("steward.network.title")}</h2>
      {/* The sidebar reads the same query at start, so the bars show on a cold load only. */}
      <QueryState
        query={query}
        rows={10}
        isEmpty={(answer) => answer.services.length === 0}
        empty={{
          title: t("steward.network.no-service"),
          note: t("steward.network.no-service-note"),
        }}
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
