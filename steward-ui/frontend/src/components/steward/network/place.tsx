import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"

import { useNetwork } from "./data"
import { ServiceNode } from "./node"
import { DATABASE_CLIENTS, EDGES, type NodeId } from "./topology"
import {
  type Box,
  EDGE_COLOR,
  type Geometry,
  type Point,
  Wires,
  bundle,
  collapseToGroups,
} from "./wires"

/**
 * Where the cards go, said in **lanes** rather than in pixels - which is what lets one arrangement
 * cover every width instead of two covering two.
 *
 * <h2>Why a plan and not a grid</h2>
 * Till, 2026-09-17: the grid arrangement is fairly boring and may get more creative - and, in the
 * same note, the cards sit so close together that the connection between them cannot be seen. Both
 * are the same complaint from two sides - a grid puts every card in a cell of equal size, so the
 * space between two cards is whatever the gap happens to be, and it is the same everywhere whether
 * a line runs there or not. A plan can put a lane's width between two cards a line has to cross and
 * nothing at all between two that are merely neighbours, and it can leave a lane empty on purpose.
 *
 * <h2>Lanes, because the drawing stretches and the cards do not (steward/121)</h2>
 * Till, 2026-09-20, translated from the German he wrote it in: proportional would be fine, but the
 * boxes must not grow with it - only the arrows should stretch. That rules out the obvious
 * implementation - a `viewBox`
 * scaled to the container - because a `viewBox` scales the text and the borders along with the
 * gaps, which is exactly what he excluded. It also rules out the arrangement this file used to
 * hold: two hand-written coordinate tables and a jump between them at one measured width.
 *
 * So a spot names a **lane**, a number from 0 to 1, and {@link place} turns it into an x once the
 * available width is known:
 *
 * <pre>
 *   x = MARGIN + NODE.width / 2 + lane * (width - 2 * MARGIN - NODE.width)
 * </pre>
 *
 * Lane 0 is the leftmost a card can sit and still be fully on the canvas, lane 1 the rightmost,
 * lane 0.5 the middle - at every width. A card's own size never appears on the right-hand side of
 * anything but that one formula, so nothing about a card changes when the canvas does: the gaps
 * grow, the lines that cross them grow with the gaps, and the boxes do not move relative to their
 * own text. **`y` stays absolute**, because the vertical is not where the extra room is; a canvas
 * that grew taller as it grew wider would push the rest of the start page down for no reason.
 *
 * <h2>The one thing lanes cost</h2>
 * A layout that is right at 380px and right at 1200px has to be right at every width in between,
 * and "in between" is where two cards in different lanes slide past each other. Nothing may share a
 * row across lanes unless it still clears at {@link Arrangement.minWidth}, which is the narrowest
 * the formula is ever asked for - so the centre-lane cards (`players`, `postgres`, `discord-bot`)
 * each sit at a height no side card occupies. `geometry.test.ts` runs every geometric assertion at
 * several widths rather than one, which is the check that this holds.
 *
 * <h2>The reserved lane</h2>
 * An arrangement may name a `junction`. Every database line then curves into that one point and a
 * single trunk carries them the rest of the way, which is the merging Till asked for. The point has
 * to sit where no card does, and the corridors the feet descend in have to be clear as well;
 * `geometry.test.ts` is what says so, at every width.
 *
 * <h2>Groups - one border, one endpoint</h2>
 * Till, 2026-09-18: the three Paper services (`smp`, `hunger-games`, `limbo`) and, separately,
 * `steward-worker` and `steward-deployer` should pack tightly under one shared grey border, and an
 * edge that used to fan out to their members should collapse into a single line to the group. A
 * `GroupSpec` names its members and its own lane; the members' own positions are *derived* from
 * that centre (`groupMemberSpots`) rather than written out card by card, for the same reason every
 * other spot here is a point and not a memory: an eleventh member is one name added to the list,
 * not three numbers recomputed by hand. `geometryOf` turns a group into a `Box` of its own
 * (`groupBox`) and a reverse map from member id to group id; `wires.tsx`'s `resolveEndpoint` is
 * what an edge or a database source is actually resolved against, and that is where "the group
 * behaves as one endpoint" lives - this file only describes the shape, it never merges an edge.
 */

/**
 * 144x76, and the height is measured rather than chosen: 12px of vertical padding, a 16px
 * identifier row, a 17px tag row, two 2px gaps and a 24px toolbar come to 73, and the card is given
 * three more so that the toolbar has somewhere to sit rather than somewhere to bulge out of.
 *
 * **It does not change with the canvas.** That is the whole of steward/121's first half.
 */
export const NODE = { width: 144, height: 76 }

/**
 * How much room a lane-0 card leaves to the canvas edge.
 *
 * It has to be at least `GROUP_PADDING`, or a group in lane 0 hangs off the left of the canvas -
 * its frame is wider than its members on every side. It is six more than that so the frame does not
 * sit *on* the edge either: photographed at 372px on 2026-09-20, `GROUP_PADDING` exactly put both
 * group borders flush against the panel's own edge and they read as cut off rather than as frames.
 */
export const MARGIN = 16

/** A card's place: which lane it sits in, and how far down. See the class comment. */
export type Spot = { id: NodeId; lane: number; y: number }

/**
 * Several cards, packed into one bordered unit that behaves as a single endpoint for an edge.
 *
 * `lane`/`y` is the centre of the group's own frame, not of any one member - the same convention as
 * `Spot`, so a group is placed exactly the way a lone card is. Members stack in one column, in the
 * order given, `GROUP_GAP` apart; there is no horizontal variant because neither group Till named
 * needs one, and a column is what the two side lanes already are.
 */
export type GroupSpec = { id: string; members: NodeId[]; lane: number; y: number }

export type Arrangement = {
  /**
   * The narrowest canvas this arrangement is ever asked to be right at.
   *
   * It is not a preferred width and not a design size: it is the width at which two cards in
   * neighbouring lanes are closest to touching, so it is the width every collision assertion has to
   * be run at. Below it the picture is scaled down rather than broken - see `Field`.
   */
  minWidth: number
  /**
   * The widest canvas the arrangement is drawn at, however much room there is.
   *
   * A stretch with no ceiling is not what "it stretches" can have meant on a 27-inch screen: past about
   * 720px the three lanes are far enough apart that the picture stops being a diagram and becomes
   * a band with cards along its edges and nothing in the middle. Beyond this width the drawing is
   * centred in what is left rather than pulled further apart. It is a stop, not a second
   * arrangement - nothing about the picture changes when the ceiling is reached except that it
   * stops moving.
   */
  maxWidth: number
  height: number
  spots: Spot[]
  groups?: GroupSpec[]
  /** Where the database lines meet before entering the sink, or nothing to draw them separately. */
  junction?: { lane: number; y: number }
  /** Per-edge detours, keyed by the resolved `from-to` (a group's own id once an endpoint sits
   *  inside one), for the siblings that would otherwise run through each other. See `curve` in
   *  `wires.tsx`. */
  bows?: Record<string, number>
}

/**
 * One arrangement at one width: the same shape, with every lane resolved to an x.
 *
 * Everything downstream of {@link place} - the geometry, the wires, the cards themselves - works in
 * absolute pixels and knows nothing about lanes, which is why the stretch is one function and not a
 * change spread through the drawing.
 */
export type Placed = {
  width: number
  height: number
  spots: Array<{ id: NodeId; x: number; y: number }>
  groups: Array<{ id: string; members: NodeId[]; x: number; y: number }>
  junction?: Point
  bows?: Record<string, number>
}

/** The x a lane lands on at a given canvas width. The one formula the stretch consists of. */
export function laneX(lane: number, width: number): number {
  return MARGIN + NODE.width / 2 + lane * (width - 2 * MARGIN - NODE.width)
}

/** An arrangement, resolved against a canvas width. */
export function place(arrangement: Arrangement, width: number): Placed {
  const at = (lane: number) => laneX(lane, width)
  return {
    width,
    height: arrangement.height,
    spots: arrangement.spots.map((spot) => ({ id: spot.id, x: at(spot.lane), y: spot.y })),
    groups: (arrangement.groups ?? []).map((group) => ({
      id: group.id,
      members: group.members,
      x: at(group.lane),
      y: group.y,
    })),
    junction: arrangement.junction
      ? { x: at(arrangement.junction.lane), y: arrangement.junction.y }
      : undefined,
    bows: arrangement.bows,
  }
}

/**
 * The gap inside a group and the frame around it - Tailwind's `gap-1` in raw pixels because a
 * group's members are positioned the same way every other card is, by a centre point handed to
 * `ServiceNode`, and a Tailwind class cannot reach across an absolutely positioned sibling to
 * produce one. `PADDING` is chosen so the border reads as a frame around the stack rather than a
 * fourth card of its own: enough room that it clears a member's own rounded corner on every side.
 */
export const GROUP_GAP = 4
export const GROUP_PADDING = 10

type PlacedGroup = Placed["groups"][number]
type PlacedSpot = Placed["spots"][number]

/** Where a group's members land: one column, centred on the group's own point, `GROUP_GAP` apart. */
export function groupMemberSpots(group: PlacedGroup): PlacedSpot[] {
  const total = group.members.length * NODE.height + (group.members.length - 1) * GROUP_GAP
  let y = group.y - total / 2 + NODE.height / 2
  return group.members.map((id) => {
    const spot = { id, x: group.x, y }
    y += NODE.height + GROUP_GAP
    return spot
  })
}

/** The frame around a group: its members' own bounding box, plus `GROUP_PADDING` on every side. */
export function groupBox(group: PlacedGroup): Box {
  const members = groupMemberSpots(group)
  const top = members[0].y - NODE.height / 2
  const bottom = members[members.length - 1].y + NODE.height / 2
  return {
    x: group.x - NODE.width / 2 - GROUP_PADDING,
    y: top - GROUP_PADDING,
    width: NODE.width + GROUP_PADDING * 2,
    height: bottom - top + GROUP_PADDING * 2,
  }
}

/** Every card an arrangement places, individually named or packed into a group. */
export function allSpots(placed: Placed): PlacedSpot[] {
  return [...placed.spots, ...placed.groups.flatMap(groupMemberSpots)]
}

/** The rectangle of every card, which is what the lines are drawn from. */
export function geometryOf(placed: Placed): Geometry {
  const boxes: Record<string, Box> = {}
  for (const spot of allSpots(placed)) {
    boxes[spot.id] = {
      x: spot.x - NODE.width / 2,
      y: spot.y - NODE.height / 2,
      width: NODE.width,
      height: NODE.height,
    }
  }

  const groups: Record<string, Box> = {}
  const memberOf: Record<string, string> = {}
  for (const group of placed.groups) {
    groups[group.id] = groupBox(group)
    for (const member of group.members) memberOf[member] = group.id
  }

  return { boxes, groups, memberOf, width: placed.width, height: placed.height }
}

/**
 * The picture's own top-level shapes, for the two checks a member's own box would answer wrongly:
 * whether anything overlaps, and whether everything stays on the canvas. A member sitting inside
 * its group's frame is not a collision, it is what the frame is for - so those two checks want one
 * region per group, not one per member, the same resolution `wires.tsx`'s `resolveEndpoint` gives
 * an edge. A lone card outside any group is unaffected and keeps its own box.
 */
export function regionsOf(placed: Placed): Record<string, Box> {
  const geometry = geometryOf(placed)
  const regions: Record<string, Box> = { ...geometry.boxes }
  for (const group of placed.groups) {
    for (const member of group.members) delete regions[member]
    regions[group.id] = geometry.groups[group.id]
  }
  return regions
}

/**
 * How wide the thing holding the picture is.
 *
 * In jsdom this stays 0, and since steward/121 that is no longer "the wide plan wins" but "the
 * narrowest the one plan is defined at" - `minWidth`. A rendering test therefore sees the tightest
 * version of the picture, which is the one where anything that can collide does; the widths above
 * it are checked by `geometry.test.ts` running the same assertions against several of them.
 */
function useAvailableWidth(ref: React.RefObject<HTMLElement | null>): number {
  const [width, setWidth] = useState(0)

  const measure = useCallback(() => {
    const element = ref.current
    if (!element) return
    const next = element.getBoundingClientRect().width
    setWidth((current) => (Math.abs(current - next) < 0.5 ? current : next))
  }, [ref])

  useLayoutEffect(measure)

  useEffect(() => {
    const element = ref.current
    if (!element || typeof ResizeObserver === "undefined") return
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [ref, measure])

  return width
}

const TRAFFIC = EDGES.filter((edge) => edge.kind === "traffic")
const DATA = EDGES.filter((edge) => edge.kind === "data")

/**
 * The picture, drawn from its arrangement at whatever width it has been given.
 *
 * <h2>It stretches; it does not scale</h2>
 * The canvas is the container's own width, never a fixed one, and every card is placed by
 * {@link place} at that width. Pull the window wider and the lanes move apart, the lines between
 * them grow, and every card is the same 144x76 with the same 11px tag underneath it that it was
 * before - which is steward/121 in one sentence.
 *
 * The one exception is below `minWidth`, and it is a safety net rather than a mode: the picture is
 * scaled down instead of overflowing, because a topology map that has to be dragged sideways is not
 * one you can read at a glance. On this interface it should never fire - below 768px the panel
 * draws {@link NetworkTable} instead of this component at all, and the narrowest the panel gets
 * above that line is around 410px, measured 2026-09-20 at a 1100px viewport where the start page's
 * two columns and the sidebar are all present at once.
 */
export function Field({ plan, id }: { plan: Arrangement; id: string }) {
  const outer = useRef<HTMLDivElement>(null)
  const available = useAvailableWidth(outer)
  const network = useNetwork()

  const width = Math.min(Math.max(available, plan.minWidth), plan.maxWidth)
  const scale = available > 0 ? Math.min(1, available / width) : 1

  const placed = place(plan, width)
  const geometry = geometryOf(placed)

  // Resolved through the group map rather than read off `geometry.boxes` directly: three of the
  // seven database clients (`smp`, `hunger-games`, `limbo`) share the Paper group's frame, so this
  // is five boxes, not seven, and the group's foot leaves from its own border rather than from
  // whichever member happens to be listed first.
  const sources = collapseToGroups(DATABASE_CLIENTS, geometry)
  const sink = geometry.boxes.postgres
  const merged = placed.junction && sink ? bundle(sources, sink, placed.junction) : null

  return (
    <div ref={outer} className="w-full">
      <div
        className="relative mx-auto origin-top"
        style={{
          width: placed.width,
          height: placed.height,
          transform: scale === 1 ? undefined : `scale(${scale})`,
          marginBottom: scale === 1 ? undefined : -placed.height * (1 - scale),
          ["--node-w" as string]: `${NODE.width}px`,
          ["--node-h" as string]: `${NODE.height}px`,
        }}
      >
        {/* One shared frame per group, drawn before the cards so a card's own z-10 (see
            `ServiceNode`) always wins - the border reads as sitting behind the stack, never on
            top of it, without this needing a z-index of its own. */}
        {placed.groups.map((group) => {
          const box = geometry.groups[group.id]
          if (!box) return null
          return (
            <div
              key={group.id}
              data-group={group.id}
              aria-hidden
              className="absolute rounded-lg border border-border"
              style={{ left: box.x, top: box.y, width: box.width, height: box.height }}
            />
          )
        })}

        {allSpots(placed).map((spot) => (
          <div
            key={spot.id}
            className="absolute"
            style={{ left: spot.x - NODE.width / 2, top: spot.y - NODE.height / 2 }}
          >
            <ServiceNode
              id={spot.id}
              service={network.service(spot.id)}
              players={network.players(spot.id)}
            />
          </div>
        ))}

        <Wires id={id} geometry={geometry} edges={merged ? TRAFFIC : EDGES} bows={placed.bows}>
          {merged ? (
            <>
              {merged.feet.map((d, index) => (
                <path
                  key={index}
                  data-foot={index}
                  d={d}
                  fill="none"
                  stroke={EDGE_COLOR.data}
                  strokeWidth={1.25}
                  strokeOpacity={0.5}
                  strokeDasharray="2 6"
                  strokeLinecap="round"
                />
              ))}
              {/* The trunk is the same kind of line as the feet and is drawn the same way - dashed
                  and in the same neutral - only thicker, because it is the five of them together.
                  It was solid and 1.75 until steward/121, which made a 38px stub under `postgres`
                  read as a different sort of connection entirely. */}
              <path
                data-trunk=""
                d={merged.trunk}
                fill="none"
                stroke={EDGE_COLOR.data}
                strokeWidth={2}
                strokeOpacity={0.6}
                strokeDasharray="2 6"
                strokeLinecap="round"
              />
            </>
          ) : null}
        </Wires>
      </div>
    </div>
  )
}

/** Only here so a caller can name the edge sets without importing `topology` as well. */
export { DATA, TRAFFIC }
