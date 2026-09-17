import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"
import type { RefObject } from "react"

import { EDGES, type Edge, type NodeId } from "./topology"

/**
 * The lines, and the reason this is the expensive half of steward/81.
 *
 * <h2>Measured, not tabulated</h2>
 * The obvious way to draw a graph is a table of coordinates and absolutely positioned boxes. It is
 * also the way that has to be written three times - once for the wide layout, once for the narrow
 * one, once more the day a box grows a second line - and the three copies drift. So the boxes are
 * laid out by ordinary CSS, each one marked `data-node="<id>"`, and this reads back where they
 * ended up. One edge list then survives a layout that turns ninety degrees on a phone, a font that
 * loads late and a player count that appears mid-render.
 *
 * <h2>Why it cannot loop</h2>
 * The measurement runs after **every** render, with no dependency list, which is the only way to
 * catch a box that changed size without anything above it re-rendering. It writes state only when
 * the numbers actually differ, so a render that measures the same thing twice stops there.
 *
 * <h2>What it does in a test</h2>
 * jsdom has no layout: every rectangle is zero. `Wires` draws nothing at all in that case rather
 * than a pile of zero-length paths, so a rendering test sees the boxes, the counts and the dots -
 * which is what a rendering test can honestly check - and the geometry is left to the browser and
 * to `/home/dev/ui-shots/tool/overflow.mjs`, which is where it can be seen.
 */

export type Box = { x: number; y: number; width: number; height: number }

type Geometry = { boxes: Record<string, Box>; width: number; height: number }

const NOTHING: Geometry = { boxes: {}, width: 0, height: 0 }

export function useNodeBoxes(host: RefObject<HTMLElement | null>): Geometry {
  const [geometry, setGeometry] = useState<Geometry>(NOTHING)
  const last = useRef("")

  const measure = useCallback(() => {
    const element = host.current
    if (!element) return
    const base = element.getBoundingClientRect()
    const boxes: Record<string, Box> = {}
    for (const node of element.querySelectorAll<HTMLElement>("[data-node]")) {
      const box = node.getBoundingClientRect()
      const id = node.dataset.node
      if (!id) continue
      boxes[id] = {
        x: box.left - base.left,
        y: box.top - base.top,
        width: box.width,
        height: box.height,
      }
    }
    const next: Geometry = { boxes, width: base.width, height: base.height }
    const serialised = JSON.stringify(next)
    if (serialised === last.current) return
    last.current = serialised
    setGeometry(next)
  }, [host])

  // No dependency list: a box can change size without this component rendering, and the guard
  // above is what makes running it every time free.
  useLayoutEffect(measure)

  useEffect(() => {
    const element = host.current
    if (!element || typeof ResizeObserver === "undefined") return
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    for (const node of element.querySelectorAll("[data-node]")) observer.observe(node)
    window.addEventListener("resize", measure)
    return () => {
      observer.disconnect()
      window.removeEventListener("resize", measure)
    }
  }, [host, measure])

  return geometry
}

const centre = (box: Box) => ({ x: box.x + box.width / 2, y: box.y + box.height / 2 })

/**
 * A curve from one box to the next, leaving whichever side actually faces the other one.
 *
 * The axis is chosen by which distance is larger, so the same edge is a horizontal curve in a
 * left-to-right layout and a vertical one once that layout has stacked on a phone. Nothing in the
 * drafts has to know which of the two it is.
 */
function curve(from: Box, to: Box): string {
  const a = centre(from)
  const b = centre(to)
  const dx = b.x - a.x
  const dy = b.y - a.y

  if (Math.abs(dx) >= Math.abs(dy)) {
    const way = Math.sign(dx) || 1
    const start = way > 0 ? from.x + from.width : from.x
    const end = way > 0 ? to.x : to.x + to.width
    const bend = Math.max(Math.abs(end - start) / 2, 12)
    return `M ${start} ${a.y} C ${start + way * bend} ${a.y}, ${end - way * bend} ${b.y}, ${end} ${b.y}`
  }

  const way = Math.sign(dy) || 1
  const start = way > 0 ? from.y + from.height : from.y
  const end = way > 0 ? to.y : to.y + to.height
  const bend = Math.max(Math.abs(end - start) / 2, 12)
  return `M ${a.x} ${start} C ${a.x} ${start + way * bend}, ${b.x} ${end - way * bend}, ${b.x} ${end}`
}

/**
 * Centre to centre, straight.
 *
 * The right answer for a radial arrangement, where a curve that leaves the "nearest side" of a box
 * sitting at four o'clock leaves it from the wrong corner. It works because the boxes are opaque
 * and sit above this layer: the line is hidden underneath them and appears to start at the edge.
 */
function spoke(from: Box, to: Box): string {
  const a = centre(from)
  const b = centre(to)
  return `M ${a.x} ${a.y} L ${b.x} ${b.y}`
}

export type Routing = "curve" | "spoke"

const ROUTERS: Record<Routing, (from: Box, to: Box) => string> = { curve, spoke }

/**
 * Every edge that has two measured boxes, drawn under them.
 *
 * `data` edges - the seven lines into `postgres` - are thinner, dashed and fainter than the traffic
 * path. All seven end in the same box, so at one weight they are most of the ink in the picture and
 * the route a player takes disappears into them.
 */
export function Wires({
  geometry,
  routing = "curve",
  arrows = false,
  edges = EDGES,
  id,
}: {
  geometry: Geometry
  routing?: Routing
  /** Arrowheads, which only a draft that claims a direction should pay for. */
  arrows?: boolean
  edges?: Edge[]
  /** Unique per draft, because an SVG marker is addressed by a document-wide id. */
  id: string
}) {
  if (geometry.width === 0 || geometry.height === 0) return null

  const route = ROUTERS[routing]
  const drawn: Array<{ edge: Edge; d: string }> = []
  for (const edge of edges) {
    const from = geometry.boxes[edge.from as NodeId]
    const to = geometry.boxes[edge.to as NodeId]
    if (!from || !to) continue
    drawn.push({ edge, d: route(from, to) })
  }

  return (
    <svg
      className="pointer-events-none absolute inset-0 z-0 text-foreground"
      width={geometry.width}
      height={geometry.height}
      viewBox={`0 0 ${geometry.width} ${geometry.height}`}
      aria-hidden
    >
      {arrows ? (
        <defs>
          <marker
            id={`${id}-arrow`}
            viewBox="0 0 8 8"
            refX="7"
            refY="4"
            markerWidth="5"
            markerHeight="5"
            orient="auto-start-reverse"
          >
            <path d="M 0 1 L 7 4 L 0 7 z" fill="currentColor" opacity="0.4" />
          </marker>
        </defs>
      ) : null}
      {drawn.map(({ edge, d }) => (
        <path
          key={`${edge.from}-${edge.to}`}
          d={d}
          fill="none"
          stroke="currentColor"
          strokeWidth={edge.kind === "traffic" ? 1.5 : 1}
          strokeOpacity={edge.kind === "traffic" ? 0.4 : 0.18}
          strokeDasharray={edge.kind === "data" ? "3 4" : undefined}
          markerEnd={arrows && edge.kind === "traffic" ? `url(#${id}-arrow)` : undefined}
        />
      ))}
    </svg>
  )
}
