import type { Service } from "@/lib/api"
import { useServices } from "@/lib/queries"

import { INGRESS, type NodeId } from "./topology"

/**
 * `/api/services`, arranged the way a box needs it.
 *
 * One query for the whole picture - the same one the sidebar, the start page and the operations
 * page already share, so three drafts on one screen still ask the backend once.
 */
export type Network = {
  query: ReturnType<typeof useServices>
  service: (id: NodeId) => Service | undefined
  /**
   * The count to draw on a box, or `undefined`.
   *
   * `undefined` means **nobody has said**, and that is not zero (steward/86). Six services never
   * carry a count; the four that do lose it whenever proxy has stopped writing, and a
   * dashboard that drew that as an empty server would be lying about the one number a player would
   * notice. The box simply has one item fewer.
   */
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
      // The entry box carries the network's total, which is the number proxy reports
      // for itself - not a sum computed here. Adding the three servers up would double-count
      // anybody the proxy is holding in the lobby and would disagree with the proxy on purpose.
      id === INGRESS ? byName.get("proxy")?.players : byName.get(id)?.players,
  }
}
