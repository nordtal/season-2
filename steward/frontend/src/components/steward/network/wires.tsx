import type { ReactNode } from "react"

import { EDGES, type Edge, type EdgeKind } from "./topology"

/**
 * The lines of the network picture; the cards are only where they end.
 *
 * Cards sit at planned points, so geometry is a pure function of the plan and a test can check overlaps.
 */

export type Box = { x: number; y: number; width: number; height: number }
export type Point = { x: number; y: number }

export type Geometry = {
  boxes: Record<string, Box>
  /** A group's own frame by group id; empty, never absent, when an arrangement draws no frames. */
  groups: Record<string, Box>
  /** Which group a member belongs to, if any. */
  memberOf: Record<string, string>
  width: number
  height: number
}

export const centre = (box: Box): Point => ({ x: box.x + box.width / 2, y: box.y + box.height / 2 })

/** Whether two cards share any area. */
export function overlaps(a: Box, b: Box): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
}

/** Whether a point lies inside a card, with `pad` of slack around it. */
export function inside(box: Box, point: Point, pad = 0): boolean {
  return (
    point.x > box.x - pad &&
    point.x < box.x + box.width + pad &&
    point.y > box.y - pad &&
    point.y < box.y + box.height + pad
  )
}

/**
 * A cubic Bézier from one card to the next, leaving the side that faces the other one.
 *
 * `bow` pushes the handles off the chord, and `standoff` keeps an arrowhead clear of the border.
 */
export function curve(from: Box, to: Box, bow = 0, standoff = 3): string {
  const a = centre(from)
  const b = centre(to)
  const dx = b.x - a.x
  const dy = b.y - a.y

  if (Math.abs(dx) >= Math.abs(dy)) {
    const way = Math.sign(dx) || 1
    const start = (way > 0 ? from.x + from.width : from.x) + way * standoff
    const end = (way > 0 ? to.x : to.x + to.width) - way * standoff
    const reach = Math.max(Math.abs(end - start) / 2, Math.abs(dy) * 0.4, 16)
    return `M ${start} ${a.y} C ${start + way * reach} ${a.y + bow}, ${end - way * reach} ${b.y + bow}, ${end} ${b.y}`
  }

  const way = Math.sign(dy) || 1
  const start = (way > 0 ? from.y + from.height : from.y) + way * standoff
  const end = (way > 0 ? to.y : to.y + to.height) - way * standoff
  const reach = Math.max(Math.abs(end - start) / 2, Math.abs(dx) * 0.4, 16)
  return `M ${a.x} ${start} C ${a.x + bow} ${start + way * reach}, ${b.x + bow} ${end - way * reach}, ${b.x} ${end}`
}

/**
 * Samples a path as a browser draws it, so a test can ask where a line goes.
 *
 * Understands absolute `M`, `L` and `C` only and returns nothing for anything else; `steps` is per segment.
 */
export function samplePath(d: string, steps = 40): Point[] {
  const tokens = d.match(/[MLC]|-?\d+(?:\.\d+)?/g)
  if (!tokens || tokens[0] !== "M") return []

  const points: Point[] = []
  let at: Point | undefined
  let i = 0
  const number = () => {
    const value = Number(tokens[i++])
    return Number.isFinite(value) ? value : NaN
  }

  while (i < tokens.length) {
    const command = tokens[i++]
    if (command === "M") {
      at = { x: number(), y: number() }
      points.push(at)
    } else if (command === "L") {
      if (!at) return []
      const to = { x: number(), y: number() }
      for (let step = 1; step <= steps; step++) {
        const t = step / steps
        points.push({ x: at.x + (to.x - at.x) * t, y: at.y + (to.y - at.y) * t })
      }
      at = to
    } else if (command === "C") {
      if (!at) return []
      const x1 = number(),
        y1 = number(),
        x2 = number(),
        y2 = number()
      const x3 = number(),
        y3 = number()
      for (let step = 1; step <= steps; step++) {
        const t = step / steps
        const u = 1 - t
        points.push({
          x: u * u * u * at.x + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
          y: u * u * u * at.y + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3,
        })
      }
      at = { x: x3, y: y3 }
    } else {
      return []
    }
  }
  return points.some((point) => Number.isNaN(point.x) || Number.isNaN(point.y)) ? [] : points
}

/** How far before the junction a foot lies flat on the shared line, in pixels, the same for every foot. */
const BUNDLE_FLAT = 48

/** How early a foot leaves its lane, as a fraction of its sideways distance, so the curve reads as a turn. */
const BUNDLE_BEND = 0.9
const BUNDLE_BEND_MAX = 96

/**
 * How far off a card's centre line a foot leaves, away from the junction.
 *
 * The centre line is where the card's traffic edge leaves, so a foot there would run under the arrow.
 */
const LANE_OFFSET = 28

/**
 * Bundles database lines into a fan: one foot per source onto a shared line, and one trunk into the sink.
 *
 * The junction must sit in a row no card occupies; `geometry.test.ts` checks that.
 */
export function bundle(sources: readonly Box[], sink: Box, junction: Point): { feet: string[]; trunk: string } {
  if (sources.length === 0) return { feet: [], trunk: "" }
  const feet = sources.map((box) => {
    const from = centre(box)
    /** Which of the source's horizontal edges faces the junction; a source is never level with it. */
    const down = junction.y > from.y
    const exitY = down ? box.y + box.height : box.y
    const way = down ? 1 : -1
    const towards = Math.sign(junction.x - from.x)
    /** The foot's lane, stepped LANE_OFFSET clear of the arrow leaving the same edge. */
    const lane = from.x - towards * LANE_OFFSET
    const drop = Math.abs(junction.y - exitY)
    const across = Math.abs(junction.x - lane)
    /** How far from the junction's row the turn begins. */
    const bend = Math.min(drop, across * BUNDLE_BEND, BUNDLE_BEND_MAX)
    /** Also capped by the offset, so a source in the junction's lane draws a straight line. */
    const flat = Math.min(BUNDLE_FLAT, across * 0.4)
    const turn = junction.y - way * bend
    return (
      `M ${lane} ${exitY} L ${lane} ${turn} ` +
      `C ${lane} ${junction.y}, ${junction.x - towards * flat} ${junction.y}, ` +
      `${junction.x} ${junction.y}`
    )
  })
  const nearEdge = junction.y > centre(sink).y ? sink.y + sink.height : sink.y
  return { feet, trunk: `M ${junction.x} ${junction.y} L ${junction.x} ${nearEdge}` }
}

/**
 * Where an edge ends: a member's own box, or its group's frame when it has one.
 *
 * So edges into several members of one group draw one arrow.
 */
function resolveEndpoint(id: string, geometry: Geometry): { key: string; box: Box } | undefined {
  const group = geometry.memberOf[id]
  if (group) {
    const box = geometry.groups[group]
    if (box) return { key: group, box }
  }
  const box = geometry.boxes[id]
  return box ? { key: id, box } : undefined
}

/** A set of ids resolved through `resolveEndpoint`, deduplicated by the resulting key. */
export function resolvedSources(ids: readonly string[], geometry: Geometry): Array<{ key: string; box: Box }> {
  const seen = new Set<string>()
  const resolved: Array<{ key: string; box: Box }> = []
  for (const id of ids) {
    const endpoint = resolveEndpoint(id, geometry)
    if (!endpoint || seen.has(endpoint.key)) continue
    seen.add(endpoint.key)
    resolved.push(endpoint)
  }
  return resolved
}

/** The boxes a set of ids draws from, grouped ids folded into their group's frame. */
export function collapseToGroups(ids: readonly string[], geometry: Geometry): Box[] {
  return resolvedSources(ids, geometry).map((entry) => entry.box)
}

/** What each edge kind is drawn in, and how thick: traffic in the accent colour, data in the neutral. */
export const EDGE_COLOR: Record<EdgeKind, string> = {
  traffic: "var(--primary)",
  data: "var(--muted-foreground)",
}

const WIDTH: Record<EdgeKind, number> = { traffic: 2, data: 1.25 }
const OPACITY: Record<EdgeKind, number> = { traffic: 0.9, data: 0.5 }

/**
 * Every edge between two planned cards, drawn under the cards.
 *
 * `children` are extra paths in the same coordinate space.
 */
export function Wires({
  geometry,
  edges = EDGES,
  bows = {},
  id,
  children,
}: {
  geometry: Geometry
  edges?: Edge[]
  /** Per-edge detours, keyed `from-to`. See {@link curve}. */
  bows?: Record<string, number>
  /** Unique per draft, because an SVG marker is addressed by a document-wide id. */
  id: string
  children?: ReactNode
}) {
  if (geometry.width === 0 || geometry.height === 0) return null

  /** Resolved and deduplicated by key, so edges that collapse onto the same groups draw once. */
  const drawn: Array<{ key: string; kind: EdgeKind; d: string }> = []
  const seen = new Set<string>()
  for (const edge of edges) {
    const from = resolveEndpoint(edge.from, geometry)
    const to = resolveEndpoint(edge.to, geometry)
    if (!from || !to) continue
    const key = `${from.key}-${to.key}`
    if (seen.has(key)) continue
    seen.add(key)
    drawn.push({ key, kind: edge.kind, d: curve(from.box, to.box, bows[key] ?? 0) })
  }

  return (
    <svg
      className="pointer-events-none absolute inset-0 z-0"
      width={geometry.width}
      height={geometry.height}
      viewBox={`0 0 ${geometry.width} ${geometry.height}`}
      aria-hidden
    >
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
      {drawn.map(({ key, kind, d }) => (
        <path
          key={key}
          data-edge={key}
          d={d}
          fill="none"
          stroke={EDGE_COLOR[kind]}
          strokeWidth={WIDTH[kind]}
          strokeOpacity={OPACITY[kind]}
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeDasharray={kind === "data" ? "2 6" : undefined}
          markerEnd={kind === "traffic" ? `url(#${id}-arrow)` : undefined}
        />
      ))}
      {children}
    </svg>
  )
}
