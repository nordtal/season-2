import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"

import { useNetwork } from "./data"
import { ServiceNode } from "./node"
import type { NodeId, Topology } from "./topology"
import { type Box, EDGE_COLOR, type Geometry, type Point, Wires, bundle, collapseToGroups } from "./wires"

/**
 * Where the cards go, in lanes from 0 to 1 that {@link place} turns into pixels, so only the gaps stretch.
 *
 * `y` stays absolute. Cards in different lanes never share a row where they collide at `minWidth`.
 */

/** 144x76, measured from the card's rows plus room for the toolbar; fixed whatever the canvas does. */
export const NODE = { width: 144, height: 76 }

/** The room a lane-0 card leaves to the canvas edge, more than `GROUP_PADDING` so a frame is not cut off. */
export const MARGIN = 16

/** A card's place: which lane it sits in, and how far down. */
export type Spot = { id: NodeId; lane: number; y: number }

/**
 * Several cards in one tinted column that acts as a single endpoint for an edge.
 *
 * `lane` and `y` are the centre of the group's frame, like a `Spot`; members stack `GROUP_GAP` apart.
 */
export type GroupSpec = { id: string; members: NodeId[]; lane: number; y: number }

export type Arrangement = {
  /** The narrowest canvas the arrangement must be right at; below it the picture is scaled down. */
  minWidth: number
  /** The widest canvas the arrangement is drawn at; beyond it the picture is centred rather than pulled apart. */
  maxWidth: number
  height: number
  spots: Spot[]
  groups?: GroupSpec[]
  /** Where the database lines meet before entering the sink, or nothing to draw them separately. */
  junction?: { lane: number; y: number }
  /** Per-edge detours keyed by the resolved `from-to`, for siblings that would otherwise run through each other. */
  bows?: Record<string, number>
}

/**
 * One arrangement at one width, with every lane resolved to an x.
 *
 * Everything downstream works in absolute pixels.
 */
export type Placed = {
  width: number
  height: number
  spots: Array<{ id: NodeId; x: number; y: number }>
  groups: Array<{ id: string; members: NodeId[]; x: number; y: number }>
  junction?: Point
  bows?: Record<string, number>
}

/** The x a lane lands on at a given canvas width. */
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
    junction: arrangement.junction ? { x: at(arrangement.junction.lane), y: arrangement.junction.y } : undefined,
    bows: arrangement.bows,
  }
}

/** The gap inside a group and the padding of its frame, in pixels since members are placed by centre point. */
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

/** The top-level shapes, one per group or lone card, for the overlap and on-canvas checks. */
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
 * 0 in jsdom resolves to `minWidth`, the tightest version of the picture.
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
    if (!element || typeof ResizeObserver === "undefined") return undefined
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [ref, measure])

  return width
}

/**
 * The picture, drawn from its arrangement at the container's width: it stretches, it does not scale.
 *
 * Only below `minWidth` is it scaled down, rather than overflowing.
 */
export function Field({ plan, topology, id }: { plan: Arrangement; topology: Topology; id: string }) {
  const outer = useRef<HTMLDivElement>(null)
  const available = useAvailableWidth(outer)
  const network = useNetwork()

  const width = Math.min(Math.max(available, plan.minWidth), plan.maxWidth)
  const scale = available > 0 ? Math.min(1, available / width) : 1

  const placed = place(plan, width)
  const geometry = geometryOf(placed)

  /** Resolved through the group map, so the Paper group's foot leaves from its own border. */
  const sources = collapseToGroups(topology.storers, geometry)
  const sink = topology.sink === undefined ? undefined : geometry.boxes[topology.sink]
  const merged = placed.junction && sink ? bundle(sources, sink, placed.junction) : null
  const traffic = topology.edges.filter((edge) => edge.kind === "traffic")

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
        {/* One tint per group, drawn before the cards so it sits behind them; a border would nest theirs. */}
        {placed.groups.map((group) => {
          const box = geometry.groups[group.id]
          if (!box) return null
          return (
            <div
              key={group.id}
              data-group={group.id}
              aria-hidden
              className="absolute rounded-lg bg-secondary/40"
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
            <ServiceNode id={spot.id} service={network.service(spot.id)} players={network.players(spot.id)} />
          </div>
        ))}

        <Wires id={id} geometry={geometry} edges={merged ? traffic : topology.edges} bows={placed.bows}>
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
              {/* The trunk is dashed like the feet and thicker, since it carries all of them. */}
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
