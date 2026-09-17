import { useMemo, useRef, useState } from "react"

import { useNetwork } from "./data"
import { FLOW, FLOW_SINK } from "./flow"
import { ServiceNode } from "./node"
import { DATABASE_CLIENTS, EDGES, type NodeId } from "./topology"
import { type Box, EDGE_COLOR, Wires, orthogonal, useNodeBoxes } from "./wires"

/**
 * Draft **g** - a channel.
 *
 * The same rows `f` draws, and the opposite answer to the same seven database edges: rather than
 * bundling them into a rail that is always on screen, they are collapsed into a single labelled
 * channel that only opens once somebody asks for it. At rest the picture holds exactly one line -
 * the traffic path, in the accent colour, arrowheads and all - which is meant literally: with the
 * seven `data` edges folded away, that path is the only story left to read.
 *
 * <h2>Opening it</h2>
 * The channel is one real `<button>` sitting over its own line, labelled "7 services" - hovering it
 * fans the seven edges out with the ordinary curved routing every other draft uses, and clicking it
 * pins that open state so a tap does the same thing a hover does on a desk. Clicking again, or the
 * pointer leaving after an unpinned hover, folds it back.
 *
 * <h2>What opening it does not solve</h2>
 * The fan, once open, is exactly as uncoordinated as the very first round's fans were - it is drawn
 * with plain `curve` routing and can cross whatever sits between a source and `postgres`, same as
 * `a`'s data edges did. That trade is the point of this draft: the picture is honest at rest and
 * only pays the first round's cost for as long as somebody is actually asking to see it, rather
 * than paying it on every screen all the time.
 *
 * <h2>Why traffic and the fan are two separate `Wires`</h2>
 * The traffic path is drawn with `routing="orthogonal"`, same as `d` and `f` - not `curve`, which
 * was tried first and rejected after a screenshot showed why: `network-control` and `hunger-games`
 * sit one column apart, so the centre-to-centre distance that picks `curve`'s axis is large while
 * the actual gap between their facing edges is a few pixels, smaller than the 12px floor on its
 * bezier handle. The handle overshoots the gap and the curve loops back on itself before reaching
 * the target - a visible knot right next to the box, arrowhead included, and exactly what "follow a
 * line with a finger" is supposed to catch. `orthogonal`'s bend has no such floor, so it does not
 * happen there. The fan is kept on plain `curve` on purpose (see above), which is why it is its own
 * `Wires` rather than a routing prop on the one that draws traffic.
 */

const TRAFFIC = EDGES.filter((edge) => edge.kind === "traffic")
const DATA = EDGES.filter((edge) => edge.kind === "data")

export function ChannelDraft() {
  const host = useRef<HTMLDivElement>(null)
  const geometry = useNodeBoxes(host)
  const network = useNetwork()
  const [hovered, setHovered] = useState(false)
  const [pinned, setPinned] = useState(false)
  const expanded = hovered || pinned

  const channel = useMemo(() => {
    const sink = geometry.boxes[FLOW_SINK]
    const sources = DATABASE_CLIENTS.map((client) => geometry.boxes[client]).filter(
      (box): box is Box => Boolean(box),
    )
    if (!sink || sources.length === 0) return null
    const centroid: Box = {
      x: sources.reduce((sum, box) => sum + box.x + box.width / 2, 0) / sources.length,
      y: sources.reduce((sum, box) => sum + box.y + box.height / 2, 0) / sources.length,
      width: 0,
      height: 0,
    }
    return {
      d: orthogonal(centroid, sink),
      label: { x: centroid.x, y: (centroid.y + sink.y + sink.height / 2) / 2 },
    }
  }, [geometry])

  const node = (id: NodeId) => (
    <ServiceNode key={id} id={id} service={network.service(id)} players={network.players(id)} />
  )

  return (
    <div ref={host} className="@container relative flex flex-col gap-7">
      <div className="flex flex-col gap-7">
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

      <Wires id="g-traffic" geometry={geometry} routing="orthogonal" edges={TRAFFIC} arrows>
        {!expanded && channel ? (
          <path
            d={channel.d}
            fill="none"
            stroke={EDGE_COLOR.data}
            strokeWidth={3}
            strokeOpacity={0.6}
            strokeLinecap="round"
          />
        ) : null}
      </Wires>
      {expanded ? <Wires id="g-data" geometry={geometry} edges={DATA} /> : null}

      {channel ? (
        <button
          type="button"
          aria-expanded={expanded}
          aria-label={
            expanded
              ? "collapse the 7 database connections"
              : "show the 7 database connections"
          }
          className="pointer-events-auto absolute z-20 -translate-x-1/2 -translate-y-1/2 rounded-full border border-border bg-popover px-2 py-0.5 text-xs text-popover-foreground shadow-sm"
          style={{ left: channel.label.x, top: channel.label.y }}
          onMouseEnter={() => setHovered(true)}
          onMouseLeave={() => setHovered(false)}
          onFocus={() => setHovered(true)}
          onBlur={() => setHovered(false)}
          onClick={() => setPinned((value) => !value)}
        >
          7 services
        </button>
      ) : null}
    </div>
  )
}
