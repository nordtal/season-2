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
 * Where the cards go, said in coordinates rather than left to a grid.
 *
 * <h2>Why a plan and not a grid</h2>
 * Till, 2026-09-17: the grid arrangement is fairly boring and may get more creative - and, in the
 * same note, the cards sit so close together that the connection between them cannot be seen. Both are the same complaint from two sides - a grid puts every card in a cell of
 * equal size, so the space between two cards is whatever the gap happens to be, and it is the same
 * everywhere whether a line runs there or not. A plan can put 90px between two cards a line has to
 * cross and 30px between two that are merely neighbours, and it can leave a lane empty on purpose.
 *
 * The cost is that nothing reflows by itself: every arrangement is written twice, once wide and
 * once narrow, and an eleventh service is a hand edit in both. That is a real cost and it is why
 * `layoutFaults` exists - it is the test that says which name was forgotten.
 *
 * <h2>Every card is the same size</h2>
 * One size, set here and read by `ServiceNode` through `--node-w` / `--node-h`. Till asked for it
 * outright, and it is what lets a plan be a list of centre points: a card's rectangle is derivable,
 * so the geometry the lines are drawn from is known before anything renders. jsdom can therefore
 * check the picture, which is new - the first two rounds of this ticket could only be checked by
 * photographing them.
 *
 * <h2>The reserved lane</h2>
 * An arrangement may name a `junction`. Every database line then curves into that one point and a
 * single trunk carries them the rest of the way, which is the merging Till asked for. The point has
 * to sit where no card does; `spotsAreClear` is the test that says so.
 *
 * <h2>Groups - one border, one endpoint</h2>
 * Till, 2026-09-18: the three Paper services (`smp`, `hunger-games`, `limbo`) and, separately,
 * `steward-worker` and `steward-deployer` should pack tightly under one shared grey border, and an
 * edge that used to fan out to their members should collapse into a single line to the group. A
 * `GroupSpec` names its members and its own centre point; the members' own positions are *derived*
 * from that centre (`groupMemberSpots`) rather than written out card by card, for the same reason
 * every other spot here is a centre point and not a memory: an eleventh member is one name added to
 * the list, not three numbers recomputed by hand. `geometryOf` turns a `GroupSpec` into a `Box` of
 * its own (`groupBox`) and a reverse map from member id to group id; `wires.tsx`'s `resolveEndpoint`
 * is what an edge or a database source is actually resolved against, and that is where "the group
 * behaves as one endpoint" lives - `place.tsx` only describes the shape, it never merges an edge.
 */

/**
 * 144×76, and the height is measured rather than chosen: 12px of vertical padding, a 16px
 * identifier row, a 17px tag row, two 2px gaps and a 24px toolbar come to 73, and the card is given
 * three more so that the toolbar has somewhere to sit rather than somewhere to bulge out of.
 */
export const NODE = { width: 144, height: 76 }

export type Spot = { id: NodeId; x: number; y: number }

/**
 * Several cards, packed into one bordered unit that behaves as a single endpoint for an edge.
 *
 * `x`/`y` is the centre of the group's own frame, not of any one member - the same convention as
 * `Spot`, so a group is placed exactly the way a lone card is. Members stack in one column, in the
 * order given, `GROUP_GAP` apart; there is no horizontal variant because neither group Till named
 * needs one, and a column is what `h`'s two side lanes already are.
 */
export type GroupSpec = { id: string; members: NodeId[]; x: number; y: number }

export type Arrangement = {
  width: number
  height: number
  spots: Spot[]
  groups?: GroupSpec[]
  /** Where the database lines meet before entering the sink, or nothing to draw them separately. */
  junction?: Point
  /** Per-edge detours, keyed by the resolved `from-to` (a group's own id once an endpoint sits
   *  inside one), for the siblings that would otherwise run through each other. See `curve` in
   *  `wires.tsx`. */
  bows?: Record<string, number>
}

export type Plan = { wide: Arrangement; narrow: Arrangement }

/**
 * The gap inside a group and the frame around it - Tailwind's `gap-1` in raw pixels because a
 * group's members are positioned the same way every other card is, by a centre point handed to
 * `ServiceNode`, and a Tailwind class cannot reach across an absolutely positioned sibling to
 * produce one. `PADDING` is chosen so the border reads as a frame around the stack rather than a
 * fourth card of its own: enough room that it clears a member's own rounded corner on every side.
 */
export const GROUP_GAP = 4
export const GROUP_PADDING = 10

/** Where a group's members land: one column, centred on the group's own point, `GROUP_GAP` apart. */
export function groupMemberSpots(group: GroupSpec): Spot[] {
  const total = group.members.length * NODE.height + (group.members.length - 1) * GROUP_GAP
  let y = group.y - total / 2 + NODE.height / 2
  return group.members.map((id) => {
    const spot = { id, x: group.x, y }
    y += NODE.height + GROUP_GAP
    return spot
  })
}

/** The frame around a group: its members' own bounding box, plus `GROUP_PADDING` on every side. */
export function groupBox(group: GroupSpec): Box {
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
export function allSpots(arrangement: Arrangement): Spot[] {
  return [...arrangement.spots, ...(arrangement.groups ?? []).flatMap(groupMemberSpots)]
}

/** The rectangle of every card in an arrangement, which is what the lines are drawn from. */
export function geometryOf(arrangement: Arrangement): Geometry {
  const boxes: Record<string, Box> = {}
  for (const spot of allSpots(arrangement)) {
    boxes[spot.id] = {
      x: spot.x - NODE.width / 2,
      y: spot.y - NODE.height / 2,
      width: NODE.width,
      height: NODE.height,
    }
  }

  const groups: Record<string, Box> = {}
  const memberOf: Record<string, string> = {}
  for (const group of arrangement.groups ?? []) {
    groups[group.id] = groupBox(group)
    for (const member of group.members) memberOf[member] = group.id
  }

  return { boxes, groups, memberOf, width: arrangement.width, height: arrangement.height }
}

/**
 * The picture's own top-level shapes, for the two checks a member's own box would answer wrongly:
 * whether anything overlaps, and whether everything stays on the canvas. A member sitting inside
 * its group's frame is not a collision, it is what the frame is for - so those two checks want one
 * region per group, not one per member, the same resolution `wires.tsx`'s `resolveEndpoint` gives
 * an edge. A lone card outside any group is unaffected and keeps its own box.
 */
export function regionsOf(arrangement: Arrangement): Record<string, Box> {
  const geometry = geometryOf(arrangement)
  const regions: Record<string, Box> = { ...geometry.boxes }
  for (const group of arrangement.groups ?? []) {
    for (const member of group.members) delete regions[member]
    regions[group.id] = geometry.groups[group.id]
  }
  return regions
}

/**
 * How wide the thing holding the picture is.
 *
 * In jsdom this stays 0 and the wide arrangement is used, which is stated here rather than treated
 * as an accident: a rendering test sees the wide plan, and the narrow one is checked by the same
 * geometry assertions run against its coordinates directly.
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
 * A draft, drawn from its plan.
 *
 * Every draft of steward/81's third round is this component and a pair of coordinate tables, which
 * is the point: what Till is choosing between is the arrangement, so the arrangement has to be the
 * only thing that differs. The cards, the colours, the tooltips and the toolbar are identical in
 * all of them by construction rather than by care.
 *
 * <h2>Narrow is a second plan, not a reflow</h2>
 * Below the wide plan's own width the narrow one is used - a different set of points, not the same
 * points squeezed. Under even that, the whole picture is scaled down rather than clipped or given a
 * horizontal scrollbar: a topology picture that has to be dragged sideways is not one you can read
 * at a glance, and dropping to 0.9 of the size costs less than that.
 */
export function Field({ plan, id }: { plan: Plan; id: string }) {
  const outer = useRef<HTMLDivElement>(null)
  const available = useAvailableWidth(outer)
  const network = useNetwork()

  const arrangement = available > 0 && available < plan.wide.width ? plan.narrow : plan.wide
  const scale = available > 0 ? Math.min(1, available / arrangement.width) : 1
  const geometry = geometryOf(arrangement)

  // Resolved through the group map rather than read off `geometry.boxes` directly: three of the
  // seven database clients (`smp`, `hunger-games`, `limbo`) share the Paper group's frame, so this
  // is five boxes, not seven, and the group's foot leaves from its own border rather than from
  // whichever member happens to be listed first.
  const sources = collapseToGroups(DATABASE_CLIENTS, geometry)
  const sink = geometry.boxes.postgres
  const merged =
    arrangement.junction && sink ? bundle(sources, sink, arrangement.junction) : null

  return (
    <div ref={outer} className="w-full">
      <div
        className="relative mx-auto origin-top"
        style={{
          width: arrangement.width,
          height: arrangement.height,
          transform: scale === 1 ? undefined : `scale(${scale})`,
          marginBottom: scale === 1 ? undefined : -arrangement.height * (1 - scale),
          ["--node-w" as string]: `${NODE.width}px`,
          ["--node-h" as string]: `${NODE.height}px`,
        }}
      >
        {/* One shared frame per group, drawn before the cards so a card's own z-10 (see
            `ServiceNode`) always wins - the border reads as sitting behind the stack, never on
            top of it, without this needing a z-index of its own. */}
        {(arrangement.groups ?? []).map((group) => {
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

        {allSpots(arrangement).map((spot) => (
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

        <Wires
          id={id}
          geometry={geometry}
          edges={merged ? TRAFFIC : EDGES}
          bows={arrangement.bows}
        >
          {merged ? (
            <>
              {merged.feet.map((d, index) => (
                <path
                  key={index}
                  data-foot={index}
                  d={d}
                  fill="none"
                  stroke={EDGE_COLOR.data}
                  strokeWidth={1}
                  strokeOpacity={0.45}
                  strokeDasharray="2 6"
                  strokeLinecap="round"
                />
              ))}
              <path
                data-trunk=""
                d={merged.trunk}
                fill="none"
                stroke={EDGE_COLOR.data}
                strokeWidth={1.75}
                strokeOpacity={0.6}
                strokeLinecap="round"
              />
            </>
          ) : null}
        </Wires>
      </div>
    </div>
  )
}

/** Only here so a draft file can name the edge sets without importing `topology` as well. */
export { DATA, TRAFFIC }
