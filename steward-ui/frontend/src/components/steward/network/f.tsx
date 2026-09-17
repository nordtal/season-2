import { useMemo, useRef } from "react"

import { useNetwork } from "./data"
import { FLOW, FLOW_SINK } from "./flow"
import { ServiceNode } from "./node"
import { DATABASE_CLIENTS, EDGES, type NodeId } from "./topology"
import { type Box, EDGE_COLOR, Wires, railToSink, useNodeBoxes } from "./wires"

/**
 * Draft **f** - neighbourhood.
 *
 * The same four stages `a` draws left to right, turned into rows instead of columns and ordered so
 * that nothing a player's connection touches ever has to cross another line on its way through:
 * `players` above `caddy` and `network-control`, `caddy` above `steward-ui`, `network-control`
 * above the three servers it actually starts a player on. Every one of those is a straight, mostly
 * vertical hop between two rows that already sit next to each other - the proof this draft asks for
 * is that a finger can follow any traffic line from `players` to where it ends without lifting.
 *
 * <h2>What this draft does and does not promise</h2>
 * That claim is scoped to the **traffic** path on purpose. The seven `data` edges still end in one
 * box that only three of the nine services actually sit next to, so a straight drop from the other
 * six would cut across whatever the layout put beneath it - the same failure `railToSink` exists to
 * avoid, and this draft leans on it exactly as `d` does, in the same quiet colour, for exactly the
 * six edges that would otherwise cross something. It is not this draft's story - the ordering above
 * is - so it is drawn thinly rather than as the thick rail `d` makes of it.
 */

const TRAFFIC = EDGES.filter((edge) => edge.kind === "traffic")

export function NeighbourhoodDraft() {
  const host = useRef<HTMLDivElement>(null)
  const geometry = useNodeBoxes(host)
  const network = useNetwork()

  const bus = useMemo(() => {
    const sink = geometry.boxes[FLOW_SINK]
    if (!sink) return null
    const sources = DATABASE_CLIENTS.map((client) => geometry.boxes[client]).filter(
      (box): box is Box => Boolean(box),
    )
    if (sources.length === 0) return null
    return railToSink(sources, sink, geometry.width - 16)
  }, [geometry])

  const node = (id: NodeId) => (
    <ServiceNode key={id} id={id} service={network.service(id)} players={network.players(id)} />
  )

  return (
    <div ref={host} className="@container relative flex flex-col gap-7">
      <div className="flex flex-col gap-7 pr-8">
        {FLOW.map((stage, index) => (
          <div
            key={index}
            className={
              stage.length === 1 ? "grid grid-cols-1" : "grid grid-cols-2 gap-2.5 @md:grid-cols-4"
            }
          >
            {stage.map(node)}
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1">{node(FLOW_SINK)}</div>

      <Wires id="f" geometry={geometry} routing="orthogonal" edges={TRAFFIC} arrows>
        {bus
          ? bus.stubs.map((d, index) => (
              <path
                key={index}
                d={d}
                fill="none"
                stroke={EDGE_COLOR.data}
                strokeWidth={1}
                strokeOpacity={0.4}
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            ))
          : null}
        {bus ? (
          <path
            d={bus.rail}
            fill="none"
            stroke={EDGE_COLOR.data}
            strokeWidth={1.5}
            strokeOpacity={0.5}
            strokeLinecap="round"
          />
        ) : null}
      </Wires>
    </div>
  )
}
