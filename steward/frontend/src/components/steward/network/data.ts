import type { Service } from "@/lib/api"
import { useServices } from "@/lib/queries"

import { INGRESS, type NodeId } from "./topology"

/**
 * `/api/services` arranged per box, from the one query that the sidebar and the pages already share.
 */
export type Network = {
  query: ReturnType<typeof useServices>
  service: (id: NodeId) => Service | undefined
  /** The count to draw on a box, or `undefined` when nobody has reported one. That is not zero. */
  players: (id: NodeId) => number | undefined
}

export function useNetwork(): Network {
  const query = useServices()
  const rows = query.data?.services ?? []
  const byName = new Map(rows.map((row) => [row.service, row]))

  return {
    query,
    service: (id) => (id === INGRESS ? undefined : byName.get(id)),
    players: (id) =>
      /**
       * The entry box shows the proxy's own total, because summing the servers would count the lobby twice.
       */
      id === INGRESS ? byName.get("proxy")?.players : byName.get(id)?.players,
  }
}
