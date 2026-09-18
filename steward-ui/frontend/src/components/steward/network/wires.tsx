import type { ReactNode } from "react"

import { EDGES, type Edge, type EdgeKind } from "./topology"

/**
 * The lines, which are the picture - the cards are only where the lines end.
 *
 * <h2>Planned, not measured</h2>
 * The first two rounds of steward/81 laid the cards out with CSS grids and read their positions
 * back out of the DOM, because a grid decides where a box lands and only the browser knows. That
 * bought layouts that reflow for free and cost the one thing the drawing needed: nothing could be
 * checked without a browser, jsdom saw every rectangle as zero, and a line that ran through the
 * middle of a card was only ever found by looking at a screenshot.
 *
 * Till's note of 2026-09-17 - the grid arrangement is boring, be more creative - removes the
 * reason to keep it. A card is now placed at a point a draft names
 * (see `place.tsx`), so every rectangle in the picture is known before anything renders, geometry
 * is a pure function of the plan, and a test can ask whether two cards overlap or whether a line
 * crosses a card it has no business in. That is the trade: hand-placed arrangements in exchange for
 * an arrangement that can be reasoned about.
 *
 * <h2>Two kinds of line, told apart by colour and by shape</h2>
 * `traffic` is the path a request takes and is drawn in the interface's one accent colour, solid,
 * with an arrowhead. `data` is bookkeeping - seven services writing to one database - drawn in the
 * quiet neutral, dashed, thinner, and **merged**: Till asked for lines to be brought together where
 * that makes sense, and seven separate strokes converging on one card from seven directions is the
 * clearest case there is. See {@link bundle}.
 *
 * <h2>Curved, because Till chose curved</h2>
 * He picked the sweeping, rounded lines of draft `b` over the rest. Every line here is a cubic
 * leaving the side of a card that faces its target - the orthogonal routing of drafts `d`, `f` and
 * `g` is gone, and with it the failure the orchestrator photographed, where a vertical segment sat
 * exactly on the seam between two cards and read as a border rather than a connection.
 */

export type Box = { x: number; y: number; width: number; height: number }
export type Point = { x: number; y: number }

export type Geometry = {
  boxes: Record<string, Box>
  /** A group's own frame, keyed by the group id an arrangement chose. Empty for a plan with no
   *  groups - `i` today - rather than absent, so a caller never has to guard against `undefined`
   *  on top of an empty map. See `place.tsx`. */
  groups: Record<string, Box>
  /** Which group a member belongs to, if any - the map {@link resolveEndpoint} collapses through. */
  memberOf: Record<string, string>
  width: number
  height: number
}

export const centre = (box: Box): Point => ({ x: box.x + box.width / 2, y: box.y + box.height / 2 })

/** Do two cards share any area? The one question a hand-placed arrangement has to answer. */
export function overlaps(a: Box, b: Box): boolean {
  return (
    a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
  )
}

/** Is a point inside a card, with `pad` of slack around it? Used to check a drawn line. */
export function inside(box: Box, point: Point, pad = 0): boolean {
  return (
    point.x > box.x - pad &&
    point.x < box.x + box.width + pad &&
    point.y > box.y - pad &&
    point.y < box.y + box.height + pad
  )
}

/**
 * A cubic Bézier from one card to the next, leaving the side that actually faces the other one.
 *
 * The axis is chosen by which distance is larger, so the same edge is a horizontal sweep between
 * two cards side by side and a vertical one between two stacked - a draft never has to say which.
 *
 * <h2>The handles reach in both directions, and that is a fix</h2>
 * A handle length of half the gap **along the chosen axis alone** produces a hook rather than a
 * sweep whenever the other axis is the long one: two cards 40px apart horizontally and 140px apart
 * vertically got a 20px handle and bent almost at a right angle. The reach is therefore the larger
 * of half the axis span and two fifths of the cross span, which is what makes an offset pair read
 * as one continuous S - the shape Till picked out of draft `b`.
 *
 * <h2>`bow`</h2>
 * Two edges leaving the same card for two cards stacked under one another are the same line until
 * the last moment, and the far one runs straight through the near one. `bow` pushes the handles
 * sideways off the chord, so the far sibling takes a visible detour around its neighbour; the
 * midpoint of the curve moves by three quarters of it. A draft names the value per edge rather than
 * computing one, because which side is free is a fact about that arrangement and nothing else.
 *
 * `standoff` lifts both ends a couple of pixels clear of the card so an arrowhead lands beside the
 * border instead of under it. It is small on purpose: any more and short hops look detached.
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
 * Sample a cubic Bézier the way a browser draws it, so a test can walk along a line.
 *
 * Only the `M x y C …` form this file produces is understood; anything else returns nothing rather
 * than guessing, because a parser that silently mis-reads a path would make a test that passes for
 * the wrong reason - the one failure worse than no test.
 */
export function samplePath(d: string, steps = 40): Point[] {
  const numbers = d.match(/-?\d+(\.\d+)?/g)
  if (!d.startsWith("M") || !d.includes("C") || !numbers || numbers.length < 8) return []
  const [x0, y0, x1, y1, x2, y2, x3, y3] = numbers.slice(0, 8).map(Number)
  const points: Point[] = []
  for (let i = 0; i <= steps; i++) {
    const t = i / steps
    const u = 1 - t
    points.push({
      x: u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
      y: u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3,
    })
  }
  return points
}

/**
 * Seven lines into one, and one line into the card - the "duck feet" of a service map.
 *
 * Every service that writes to the database has the same line to draw, and drawing seven of them
 * separately is what made `postgres` look like it was under attack from all sides in the first
 * round of this ticket. They are gathered instead: each source curves into a single junction that
 * sits in a lane no card ever stands in, and one trunk leaves the junction for the sink. What a
 * reader then has to follow is one thick line with a fan at the top, and counting the strands of
 * the fan answers "how many services write to the database" without reading a single label.
 *
 * The junction is the caller's to choose, and choosing it badly is the one way this goes wrong: it
 * has to sit somewhere no card does, or the fan crosses the cards it came from. `place.ts` reserves
 * that lane, and a test checks it is still empty.
 */
export function bundle(
  sources: readonly Box[],
  sink: Box,
  junction: Point,
): { feet: string[]; trunk: string } {
  if (sources.length === 0) return { feet: [], trunk: "" }
  const feet = sources.map((box) => {
    const from = centre(box)
    const down = junction.y > from.y
    const way = down ? 1 : -1
    // A strand leaves through the side that faces the lane, not through the bottom, unless the card
    // is standing on the lane already. Leaving through the bottom is what put a strand straight
    // through whatever the arrangement had placed underneath its own source - the failure the first
    // round of this ticket shipped, one layer down.
    const sideways = Math.abs(junction.x - from.x) > box.width / 2
    const exit = sideways
      ? { x: junction.x > from.x ? box.x + box.width : box.x, y: from.y }
      : { x: from.x, y: down ? box.y + box.height : box.y }
    const reach = Math.max(Math.abs(junction.y - exit.y) / 2, 24)
    // The sideways handle never reaches past the lane it is aiming at. Letting it run a flat
    // distance overshot on a narrow canvas - the strand swung out beyond the lane and came back,
    // and on the way back it went through whatever card was standing on the other side.
    const span = Math.abs(junction.x - exit.x)
    const handle = sideways
      ? { x: exit.x + (junction.x > from.x ? 1 : -1) * Math.min(reach, span * 0.8), y: exit.y }
      : { x: exit.x, y: exit.y + way * reach }
    return `M ${exit.x} ${exit.y} C ${handle.x} ${handle.y}, ${junction.x} ${junction.y - way * reach}, ${junction.x} ${junction.y}`
  })
  return { feet, trunk: `M ${junction.x} ${junction.y} L ${junction.x} ${sink.y}` }
}

/**
 * Where an edge actually ends: a member's own box, or - when the member sits inside a group -
 * the group's frame instead, addressed by the group's own id.
 *
 * This is the one seam that makes "one arrow into the group" (Till, 2026-09-18) fall out of the
 * existing edge list rather than needing a second one written for it: `network-control -> smp`,
 * `-> hunger-games` and `-> limbo` all resolve their `to` end to the same key, the Paper group's
 * id, and the caller below draws one key once instead of three paths to three boxes.
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

/**
 * A set of ids, resolved through `resolveEndpoint` and deduplicated by the resulting key - the
 * general form `collapseToGroups` below hands to `bundle`, and the one a test needs the key from
 * as well as the box, to know which of the resolved boxes a given foot in `bundle`'s output
 * belongs to.
 */
export function resolvedSources(
  ids: readonly string[],
  geometry: Geometry,
): Array<{ key: string; box: Box }> {
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

/**
 * The boxes a set of ids actually draws from, once every grouped id has folded into its group's
 * frame and repeats have been dropped.
 *
 * Used for the database fan: the three Paper services collapse into the one frame around them, so
 * `bundle` receives one source where it used to get three, and "one line per group to postgres"
 * holds without `bundle` itself having to know groups exist - it still just draws a foot per box
 * it is handed.
 */
export function collapseToGroups(ids: readonly string[], geometry: Geometry): Box[] {
  return resolvedSources(ids, geometry).map((entry) => entry.box)
}

/**
 * What each edge kind is drawn in, and how thick.
 *
 * Colour carries the distinction, not opacity: `traffic` is the path a request takes and is drawn
 * in the interface's one accent colour, the same blue every primary action already uses. `data` is
 * bookkeeping and is drawn in the quiet neutral the rest of the interface uses for a fact nobody
 * needs to act on.
 */
export const EDGE_COLOR: Record<EdgeKind, string> = {
  traffic: "var(--primary)",
  data: "var(--muted-foreground)",
}

const WIDTH: Record<EdgeKind, number> = { traffic: 2, data: 1.25 }
const OPACITY: Record<EdgeKind, number> = { traffic: 0.9, data: 0.5 }

/**
 * Every edge that has two planned cards, drawn under the cards.
 *
 * `children` are extra `<path>` elements in the same coordinate space, drawn after the mapped
 * edges - which is how a draft adds its bundled database trunk without a second `<svg>` whose size
 * would have to be kept in step with this one by hand.
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

  // Resolved through `resolveEndpoint` rather than `geometry.boxes` directly, and deduplicated by
  // the resulting key: several edges that all resolve to the same group on both ends - the three
  // traffic edges into the Paper group, `steward-ui`'s two into the deploy group - are the same
  // drawn line once their endpoints collapse, and only the first is kept.
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
