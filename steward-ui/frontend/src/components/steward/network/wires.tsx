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
  /** A group's own frame, keyed by the group id the arrangement chose. Empty rather than absent
   *  when an arrangement draws no frames, so a caller never has to guard against `undefined` on
   *  top of an empty map. See `place.tsx`. */
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
 * Walk a path the way a browser draws it, so a test can ask where a line actually goes.
 *
 * Only the three commands this file emits are understood - `M`, `L` and `C`, absolute, in that
 * vocabulary and no other. Anything else returns nothing rather than guessing, because a parser
 * that silently mis-reads a path would make a test that passes for the wrong reason, which is the
 * one failure worse than having no test.
 *
 * <h2>It used to understand one cubic and nothing else</h2>
 * Until steward/121 this matched the first eight numbers of an `M … C …` string and ignored the
 * rest, which had two consequences that both hid real lines from every check: a foot with a
 * straight lead-in before its curve was read as the curve alone, and **the trunk - `M … L …`, with
 * no `C` in it at all - was read as an empty path**, so the one line that was actually drawn
 * through the middle of a card was the one line no assertion could see. The junction's trunk ran
 * from below `postgres` to the *top* of its box, and every test stayed green.
 *
 * `steps` is per segment, so a two-segment path is sampled twice as densely as a one-segment one -
 * which is what a caller checking clearances wants, rather than a fixed budget spread thinner the
 * longer the path gets.
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
      const x1 = number(), y1 = number(), x2 = number(), y2 = number()
      const x3 = number(), y3 = number()
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

/**
 * How far before the junction a foot has flattened onto the shared line, in pixels.
 *
 * It is a cap rather than a proportion: the last stretch of every foot is meant to be the *same*
 * stretch, so it must not grow with how far away the source happens to be, or the two ends of the
 * bundle would flatten at different distances and the thing a reader is supposed to see as one line
 * would be a slow convergence instead. 48px is about a third of a card's width - long enough to
 * read as shared, short enough that the curve before it is still a curve.
 */
const BUNDLE_FLAT = 48

/**
 * How early a foot leaves its own lane, as a fraction of how far sideways it has to go, and the
 * hard ceiling on the same thing.
 *
 * This is about the **shape**, not about clearance - {@link LANE_OFFSET} is what buys the room, and
 * it buys 54px of it at the narrowest width, which is more than any curve needs. What this buys is
 * that the curve reads as a turn: a foot runs straight down its source's lane, is past the sink's
 * row before it starts to bend, and then sweeps once onto the junction's line. Without the straight
 * part the whole thing is one long diagonal that happens to end horizontally, which is the shape
 * the first round of this drawing had and the shape a reader cannot trace back to a card.
 *
 * Capped by the sideways offset as well, so a short hop is mostly curve rather than mostly line.
 */
const BUNDLE_BEND = 0.9
const BUNDLE_BEND_MAX = 96

/**
 * How far off a card's own centre line a foot leaves it, on the side away from the junction.
 *
 * Zero would be the obvious choice and is wrong for a drawn reason: a card's centre line is where
 * its **traffic** edge already leaves, and three of the five sources have one. `steward-ui` sends an
 * arrow straight down to the deploy group and also writes to the database; with both leaving the
 * same point in the same direction, the dashed foot ran underneath the solid arrow for two hundred
 * pixels and read as one line drawn twice. 28px is enough that the two separate immediately and
 * little enough that the foot still plainly belongs to the card it came from.
 *
 * It is subtracted rather than added - the foot steps *away* from the junction before turning
 * towards it - and that direction is the one that is measurably safe. At the narrowest width the
 * arrangement is defined at, stepping away leaves the descent 54px clear of `postgres`; stepping
 * towards it leaves 2, which is inside the rounding of anything and is the same corridor the fault
 * this rewrite removes was running down.
 */
const LANE_OFFSET = 28

/**
 * Five lines into one, and one line into the card - the database bundle.
 *
 * Every service that writes to the database has the same line to draw, and drawing seven of them
 * separately is what made `postgres` look like it was under attack from all sides in the first
 * round of steward/81. They are gathered instead: each source runs down (or up) **its own lane**,
 * flattens onto a shared line at the junction's height, and one trunk leaves the junction for the
 * sink. What a reader then has to follow is one line with a fan hanging off it, and counting the
 * strands of the fan answers "how many services write to the database" without reading a label.
 *
 * <h2>What steward/121 changed, and why</h2>
 * Till, 2026-09-20, looking at the shipped version, translated: it also looks as though the
 * postgres lines are *trying* to bundle, and if so it is certainly not working as intended. He
 * would like them to find a way **between** the services rather than behind them, and to bundle as
 * closely as possible into one. Both halves were real and both were this function:
 *
 * - **A source left through its own side.** A card whose x was more than half a card away from the
 *   lane exited sideways, at its own height, and curved diagonally to the junction. For the two
 *   sources sitting *above* `postgres` that diagonal ran straight across `postgres` itself - which
 *   is the "behind a service" half, and it was invisible to the test because `postgres` was
 *   excluded from every foot's own crossing check as "the sink it is heading for anyway". The sink
 *   is no longer excluded, and the exit is no longer sideways: a foot leaves through the edge that
 *   **faces the junction vertically** and descends in its source's own lane, which is a corridor an
 *   arrangement already has to keep clear for the card itself.
 * - **Nothing was shared.** Each foot aimed at the junction with a handle half its own length away,
 *   so five curves of five different lengths met at a point and agreed about nothing before it.
 *   Now every one of them has the same second handle - {@link BUNDLE_FLAT} back along the junction's
 *   own row - so the last 48px of all five strands lie on top of one another. That is the "as close
 *   to one as possible" half, and it is what makes the fan read as a bus rather than as five lines
 *   that happen to end together.
 *
 * <h2>The trunk stops at the near edge, which is the whole of the fourth finding</h2>
 * His fourth finding, translated: where all the lines lead to postgres there is also some other
 * odd line drawn behind postgres. It was this line, and it was not a stray path or an edge to a node
 * that is not there: the trunk was drawn from the junction to `sink.y` - the **top** of the sink's
 * box - while the junction sits *below* the sink. So it ran from below the card, through the whole
 * card, and stopped at its far edge; the card's own `z-10` hid the middle of it and left a stub
 * poking out underneath that belonged to nothing. It now ends at whichever edge faces the junction,
 * so it is 38px long and entirely outside the card.
 *
 * The junction is the caller's to choose, and choosing it badly is the one way this still goes
 * wrong: it has to sit in a row no card occupies, and every source's lane has to be clear between
 * the source and that row. `geometry.test.ts` checks both, at several widths, with nothing excluded.
 */
export function bundle(
  sources: readonly Box[],
  sink: Box,
  junction: Point,
): { feet: string[]; trunk: string } {
  if (sources.length === 0) return { feet: [], trunk: "" }
  const feet = sources.map((box) => {
    const from = centre(box)
    // Which of the source's own horizontal edges faces the junction. A source is never level with
    // the junction in this arrangement - the junction sits in a row of its own - so this is a
    // decision and not a guess.
    const down = junction.y > from.y
    const exitY = down ? box.y + box.height : box.y
    const way = down ? 1 : -1
    const towards = Math.sign(junction.x - from.x)
    // The lane this foot runs down: the card's own, stepped LANE_OFFSET clear of the arrow that
    // leaves the same edge. A source standing in the junction's lane has no direction to step in
    // and keeps the centre, which is what makes its foot and the trunk one straight line.
    const lane = from.x - towards * LANE_OFFSET
    const drop = Math.abs(junction.y - exitY)
    const across = Math.abs(junction.x - lane)
    // How far above (or below) the junction's row the turn begins - the straight part before it is
    // what keeps a foot inside its own lane until it is past the sink's row.
    const bend = Math.min(drop, across * BUNDLE_BEND, BUNDLE_BEND_MAX)
    // Capped by the offset itself as well as by BUNDLE_FLAT, so a source standing in the junction's
    // own lane (`discord-bot`) gets a flat length of zero and draws a straight line down rather
    // than a curve that leaves the lane in order to come back to it.
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
 * Where an edge actually ends: a member's own box, or - when the member sits inside a group -
 * the group's frame instead, addressed by the group's own id.
 *
 * This is the one seam that makes "one arrow into the group" (Till, 2026-09-18) fall out of the
 * existing edge list rather than needing a second one written for it: `proxy -> smp`,
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
