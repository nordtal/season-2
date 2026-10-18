import type { Service } from "@/lib/api"
import { useServices } from "@/lib/queries"

import { INGRESS, type NodeId } from "./topology"

export type Network = {
  query: ReturnType<typeof useServices>
  service: (id: NodeId) => Service | undefined
  /** `undefined` is not zero: nobody has reported one. */
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
