import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"
import type { ReactNode, RefObject } from "react"

import { EDGES, type Edge, type EdgeKind, type NodeId } from "./topology"

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
 * to `/home/dev/ui-shots/tool/preview.mjs`, which is where it can be seen.
 *
 * <h2>What changed for steward/81's second round</h2>
 * The first three drafts drew every line at `strokeOpacity` 0.4 (traffic) and 0.18 (data) in
 * `currentColor` - on the near-black background that is barely more than nothing, which is the
 * single biggest reason the orchestrator's screenshots called the drafts unreadable. Lines are now
 * drawn in two named colours rather than one faint one - `--primary` for the path a request takes,
 * `--muted-foreground` for the bookkeeping line to the database - at opacities a screenshot can
 * actually show, with rounded caps and joins throughout. Every corner in {@link orthogonal} is
 * rounded by {@link roundedPath} rather than a hard right angle, because Till asked for exactly
 * that - the lines would be better off rounded.
 */

export type Box = { x: number; y: number; width: number; height: number }
export type Point = { x: number; y: number }

export type Geometry = { boxes: Record<string, Box>; width: number; height: number }

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

export const centre = (box: Box): Point => ({ x: box.x + box.width / 2, y: box.y + box.height / 2 })

function dist(a: Point, b: Point): number {
  return Math.hypot(b.x - a.x, b.y - a.y)
}

/** The point `distance` px from `from`, on the straight line toward `to`. */
function towards(from: Point, to: Point, distance: number): Point {
  const d = dist(from, to)
  if (d === 0) return from
  const t = distance / d
  return { x: from.x + (to.x - from.x) * t, y: from.y + (to.y - from.y) * t }
}

/**
 * A polyline with every interior corner replaced by a rounded one.
 *
 * The technique is the same one a rounded rectangle uses: stop short of the corner by `radius`,
 * curve through it, and carry on. `radius` is clamped to half of whichever neighbouring segment is
 * shorter, so a corner on a short stub never overshoots past its own ends and folds back on itself.
 */
export function roundedPath(points: Point[], radius: number): string {
  if (points.length < 2) return ""
  let d = `M ${points[0].x} ${points[0].y}`
  for (let i = 1; i < points.length - 1; i++) {
    const prev = points[i - 1]
    const curr = points[i]
    const next = points[i + 1]
    const r = Math.min(radius, dist(prev, curr) / 2, dist(curr, next) / 2)
    const a = towards(curr, prev, r)
    const b = towards(curr, next, r)
    d += ` L ${a.x} ${a.y} Q ${curr.x} ${curr.y} ${b.x} ${b.y}`
  }
  const last = points[points.length - 1]
  d += ` L ${last.x} ${last.y}`
  return d
}

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

/**
 * Two straight segments and one rounded corner, never a diagonal.
 *
 * Chooses the same axis {@link curve} does - horizontal first when the boxes are further apart in
 * x, vertical first otherwise - so a layout that stacks on a phone keeps the same routing rule. The
 * one turn sits exactly halfway between the two boxes on the axis that changes, which is what keeps
 * parallel edges between the same two columns from overlapping.
 */
export function orthogonal(from: Box, to: Box, radius = 8): string {
  const a = centre(from)
  const b = centre(to)
  const dx = b.x - a.x
  const dy = b.y - a.y

  let points: Point[]
  if (Math.abs(dx) >= Math.abs(dy)) {
    const way = Math.sign(dx) || 1
    const start = { x: way > 0 ? from.x + from.width : from.x, y: a.y }
    const end = { x: way > 0 ? to.x : to.x + to.width, y: b.y }
    const midX = (start.x + end.x) / 2
    points = [start, { x: midX, y: start.y }, { x: midX, y: end.y }, end]
  } else {
    const way = Math.sign(dy) || 1
    const start = { x: a.x, y: way > 0 ? from.y + from.height : from.y }
    const end = { x: b.x, y: way > 0 ? to.y : to.y + to.height }
    const midY = (start.y + end.y) / 2
    points = [start, { x: start.x, y: midY }, { x: end.x, y: midY }, end]
  }
  return roundedPath(points, radius)
}

/**
 * Every source bundled into one rail, routed through a lane that no box ever stands in.
 *
 * <h2>Why a straight drop is not enough</h2>
 * `postgres` sits under several rows, not one, and the seven services that write to it are spread
 * across all of them. A straight vertical line from a source in an upper row would cut across
 * whatever the layout has placed directly beneath it before it ever reached the row `postgres` is
 * actually in - which is the exact defect the ring draft shipped in the first round. Exiting
 * sideways into a lane that is never given to a box, and only turning down once inside it, is what
 * keeps that from happening regardless of how many rows sit between a source and the sink.
 *
 * <h2>The lane is the caller's to keep empty</h2>
 * This function trusts `laneX`; it is the caller's job to reserve that column - a `padding-right`
 * on every row above the sink and none on the sink's own row is how `d` and `f` do it, so the sink
 * spans the full width while nothing else ever reaches into the last few pixels of it.
 *
 * <h2>The rounded kink Till asked for</h2>
 * Each stub is drawn as if it kept travelling a little further in the sink's direction once it
 * reaches the lane, so {@link roundedPath} has a real corner to round rather than a bare T-junction
 * - which is what makes the branch look like it is joining the rail instead of merely touching it.
 */
export function railToSink(
  sources: readonly Box[],
  sink: Box,
  laneX: number,
  radius = 10,
): { stubs: string[]; rail: string } {
  if (sources.length === 0) return { stubs: [], rail: "" }
  const sinkY = sink.y + sink.height / 2
  const stubs = sources.map((box) => {
    const y = box.y + box.height / 2
    const exit = { x: box.x + box.width, y }
    const bend = { x: laneX, y }
    // A short step further in the rail's own direction - toward the sink - so the join is a
    // rounded corner rather than a hard T where the stub meets the vertical line.
    const after = { x: laneX, y: y + Math.sign(sinkY - y || 1) * 14 }
    return roundedPath([exit, bend, after], radius)
  })
  const top = Math.min(...sources.map((box) => box.y + box.height / 2), sinkY)
  const bottom = Math.max(...sources.map((box) => box.y + box.height / 2), sinkY)
  return { stubs, rail: `M ${laneX} ${top} L ${laneX} ${bottom}` }
}

export type Routing = "curve" | "spoke" | "orthogonal"

const ROUTERS: Record<Routing, (from: Box, to: Box) => string> = { curve, spoke, orthogonal }

/**
 * What each edge kind is drawn in, and how thick.
 *
 * Colour carries the distinction now, not just opacity: `traffic` is the path a request takes and
 * is drawn in the interface's one accent colour, the same blue every primary action already uses.
 * `data` is bookkeeping - seven identical lines into the same box - and is drawn in the quiet
 * neutral the rest of the interface uses for a fact nobody needs to act on.
 */
export const EDGE_COLOR: Record<EdgeKind, string> = {
  traffic: "var(--primary)",
  data: "var(--muted-foreground)",
}

export const BASE_WIDTH: Record<EdgeKind, number> = { traffic: 2, data: 1.25 }

/** The resting opacity for every draft that is not steward/81's "focus" draft (`e`). */
const EDGE_OPACITY: Record<EdgeKind, number> = { traffic: 0.85, data: 0.55 }

/**
 * `e`'s resting opacity - deliberately quieter than {@link EDGE_OPACITY}, because that draft's
 * whole idea is that nothing competes for attention until a node is hovered, focused or tapped.
 */
const REST_OPACITY: Record<EdgeKind, number> = { traffic: 0.26, data: 0.14 }

/** What an edge touching the active node is boosted to, only when `focusable` is set. */
const FOCUS_OPACITY: Record<EdgeKind, number> = { traffic: 1, data: 0.85 }

/**
 * Every edge that has two measured boxes, drawn under the boxes.
 *
 * <h2>`focusable` and `active`</h2>
 * Draft `e` is the one place a resting line is meant to be quiet and an active one is meant to be
 * loud. `focusable` switches on that whole scheme (quiet baseline, full-strength edges touching
 * `active`, everything else dimmed further while something is active); every other draft leaves it
 * off and keeps the plain, always-legible styling.
 *
 * <h2>`children`</h2>
 * Extra `<path>` elements in the same coordinate space, drawn after the mapped edges - this is how
 * `d` layers its bundled database rail and `g` its collapsed channel on top of (or instead of) the
 * plain per-edge routing, without a second `<svg>` that would have to be kept in sync with this
 * one's size by hand.
 */
export function Wires({
  geometry,
  routing = "curve",
  arrows = false,
  edges = EDGES,
  id,
  active = null,
  focusable = false,
  children,
}: {
  geometry: Geometry
  routing?: Routing
  /** Arrowheads, which only a draft that claims a direction should pay for. */
  arrows?: boolean
  edges?: Edge[]
  /** Unique per draft, because an SVG marker is addressed by a document-wide id. */
  id: string
  /** The node whose edges should read as "on" right now. Ignored unless `focusable` is set. */
  active?: NodeId | null
  /** Switches on the quiet-baseline / bright-on-focus scheme `e` needs. See the class comment. */
  focusable?: boolean
  children?: ReactNode
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

  const touchesActive = (edge: Edge) =>
    active != null && (edge.from === active || edge.to === active)

  const opacityFor = (edge: Edge): number => {
    if (!focusable) return EDGE_OPACITY[edge.kind]
    if (touchesActive(edge)) return FOCUS_OPACITY[edge.kind]
    if (active != null) return REST_OPACITY[edge.kind] * 0.4
    return REST_OPACITY[edge.kind]
  }

  const widthFor = (edge: Edge): number => {
    const base = BASE_WIDTH[edge.kind]
    return focusable && touchesActive(edge) ? base + 0.75 : base
  }

  return (
    <svg
      className="pointer-events-none absolute inset-0 z-0"
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
            <path d="M 0 1 L 7 4 L 0 7 z" fill={EDGE_COLOR.traffic} fillOpacity="0.9" />
          </marker>
        </defs>
      ) : null}
      {drawn.map(({ edge, d }) => (
        <path
          key={`${edge.from}-${edge.to}`}
          d={d}
          fill="none"
          stroke={EDGE_COLOR[edge.kind]}
          strokeWidth={widthFor(edge)}
          strokeOpacity={opacityFor(edge)}
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeDasharray={edge.kind === "data" ? "2 6" : undefined}
          markerEnd={arrows && edge.kind === "traffic" ? `url(#${id}-arrow)` : undefined}
        />
      ))}
      {children}
    </svg>
  )
}
