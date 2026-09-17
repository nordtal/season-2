import { useMemo, useRef } from "react"

import { useNetwork } from "./data"
import { LAYERS } from "./layers"
import { ServiceNode } from "./node"
import { DATABASE_CLIENTS, EDGES, type NodeId } from "./topology"
import { type Box, EDGE_COLOR, Wires, railToSink, useNodeBoxes } from "./wires"

/**
 * Draft **d** - a route map.
 *
 * Built for the second round of steward/81, after Till's own read of the first three drafts:
 * "die Verbindungen ... sind sehr schwer zu erkennen". This one answers with the transit-map
 * convention rather than a new arrangement - it draws {@link LAYERS}'s own bands, the same ones
 * `b` draws, but treats the wires the way a subway map treats its lines rather than the way a
 * flowchart does: no diagonal ever appears, every corner is rounded, and a line keeps one colour
 * for its whole length.
 *
 * <h2>Only two lines, not nine</h2>
 * Colour carries the two kinds directly rather than through opacity: the traffic path is drawn in
 * the interface's own accent blue, database traffic in a quiet neutral, and nothing else competes
 * for either. `b` needed four band labels to say what its lines could not; this draft's bet is that
 * two colours and no diagonals say it without them, so the bands are drawn without their names.
 *
 * <h2>One rail, not seven fans</h2>
 * `postgres` still receives seven lines - `hunger-games`, `smp`, `limbo`, `network-control`,
 * `steward-ui`, `steward-worker` and `discord-bot` all write to it, spread across three different
 * bands - so seven straight drops would still cut across whatever the layout put beneath them, in
 * exactly the way `c` cut through `hunger-games` and `network-control` in the first round. Each of
 * the seven exits into a lane along the right edge instead, one rounded corner per service
 * ({@link railToSink}), and travels down that empty lane to `postgres` as a single visible rail -
 * "eine dicke Linie statt sieben Fächern", which is the shape Till asked for by name.
 *
 * <h2>What the lane costs</h2>
 * The bands above `postgres` carry a fixed right margin (`pr-8`) that no box is ever placed in,
 * which is the only thing standing between this and the same defect `c` shipped: the lane is a
 * promise the layout has to keep, not something `railToSink` can verify from the boxes it is given.
 */

const TRAFFIC = EDGES.filter((edge) => edge.kind === "traffic")

const BANDS = LAYERS.filter((layer) => layer.label !== "data")

export function RouteMapDraft() {
  const host = useRef<HTMLDivElement>(null)
  const geometry = useNodeBoxes(host)
  const network = useNetwork()

  const bus = useMemo(() => {
    const sink = geometry.boxes["postgres"]
    if (!sink) return null
    const sources = DATABASE_CLIENTS.map((client) => geometry.boxes[client]).filter(
      (box): box is Box => Boolean(box),
    )
    if (sources.length === 0) return null
    // 16px inside the right edge of the `pr-8` margin the bands reserve below.
    return railToSink(sources, sink, geometry.width - 16)
  }, [geometry])

  const node = (id: NodeId) => (
    <ServiceNode key={id} id={id} service={network.service(id)} players={network.players(id)} />
  )

  return (
    <div ref={host} className="@container relative flex flex-col gap-7">
      <div className="flex flex-col gap-7 pr-8">
        {BANDS.map((band) => (
          <div key={band.label} className="grid grid-cols-2 gap-2.5 @md:grid-cols-3 @2xl:grid-cols-4">
            {band.members.map(node)}
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1">{node("postgres")}</div>

      <Wires id="d" geometry={geometry} routing="orthogonal" edges={TRAFFIC}>
        {bus ? (
          <>
            {bus.stubs.map((d, index) => (
              <path
                key={index}
                d={d}
                fill="none"
                stroke={EDGE_COLOR.data}
                strokeWidth={1.25}
                strokeOpacity={0.55}
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            ))}
            <path
              d={bus.rail}
              fill="none"
              stroke={EDGE_COLOR.data}
              strokeWidth={3}
              strokeOpacity={0.7}
              strokeLinecap="round"
            />
          </>
        ) : null}
      </Wires>
    </div>
  )
}
