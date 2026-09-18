import { describe, expect, it } from "vitest"

import { DELTA } from "./h"
import { LENS } from "./i"
import { type Arrangement, geometryOf } from "./place"
import { DATABASE_CLIENTS, EDGES, layoutFaults, type NodeId } from "./topology"
import { type Box, bundle, curve, inside, overlaps, samplePath } from "./wires"

/**
 * The picture, checked without a browser - which is what the third round of steward/81 bought.
 *
 * The first two rounds laid the cards out with CSS grids and read their positions back out of the
 * DOM. jsdom has no layout, so every rectangle was zero there and nothing about the drawing could
 * be asserted: a line running through the middle of a card was found by photographing it, twice,
 * and one of them (`g`'s loop back into its own source) survived a whole review round. Cards are
 * now placed at points a plan names, so the geometry is a pure function of that plan and these are
 * ordinary assertions about numbers.
 *
 * What this still cannot say is whether the result is nice to look at. That needs an eye, and the
 * ticket says whose.
 */

const PLANS = [
  ["h wide", DELTA.wide],
  ["h narrow", DELTA.narrow],
  ["i wide", LENS.wide],
  ["i narrow", LENS.narrow],
] as const

const TRAFFIC = EDGES.filter((edge) => edge.kind === "traffic")

/** How far into a card a line may reach before it counts as running through it. */
const SLACK = -4

function crossings(d: string, boxes: Record<string, Box>, allowed: string[]): string[] {
  const hit = new Set<string>()
  for (const point of samplePath(d)) {
    for (const [id, box] of Object.entries(boxes)) {
      if (allowed.includes(id)) continue
      if (inside(box, point, SLACK)) hit.add(id)
    }
  }
  return [...hit]
}

describe.each(PLANS)("%s", (_name, arrangement: Arrangement) => {
  it("places every service in navigation.ts, exactly once, and the box the traffic comes from", () => {
    expect(layoutFaults(arrangement.spots.map((spot) => spot.id as NodeId))).toEqual([])
  })

  it("keeps every card inside the canvas", () => {
    const { boxes, width, height } = geometryOf(arrangement)
    for (const [id, box] of Object.entries(boxes)) {
      expect(`${id} left ${box.x}`).toBe(`${id} left ${Math.max(box.x, 0)}`)
      expect(`${id} right ${box.x + box.width}`).toBe(
        `${id} right ${Math.min(box.x + box.width, width)}`,
      )
      expect(`${id} bottom ${box.y + box.height}`).toBe(
        `${id} bottom ${Math.min(box.y + box.height, height)}`,
      )
    }
  })

  it("never lets two cards touch", () => {
    const { boxes } = geometryOf(arrangement)
    const ids = Object.keys(boxes)
    for (let a = 0; a < ids.length; a++) {
      for (let b = a + 1; b < ids.length; b++) {
        const pair = `${ids[a]} and ${ids[b]}`
        expect(overlaps(boxes[ids[a]], boxes[ids[b]]) ? pair : "clear").toBe("clear")
      }
    }
  })

  it("draws no traffic line through a card it does not belong to", () => {
    const { boxes } = geometryOf(arrangement)
    for (const edge of TRAFFIC) {
      const from = boxes[edge.from]
      const to = boxes[edge.to]
      if (!from || !to) continue
      const d = curve(from, to, arrangement.bows?.[`${edge.from}-${edge.to}`] ?? 0)
      const through = crossings(d, boxes, [edge.from, edge.to])
      expect(`${edge.from}->${edge.to} through ${through.join(", ")}`).toBe(
        `${edge.from}->${edge.to} through `,
      )
    }
  })

  it("keeps the database lane clear of every card", () => {
    const { boxes } = geometryOf(arrangement)
    const junction = arrangement.junction
    if (!junction) return
    const sink = boxes.postgres
    const sources = DATABASE_CLIENTS.map((client) => boxes[client]).filter(
      (box): box is Box => Boolean(box),
    )
    const merged = bundle(sources, sink, junction)

    for (const [id, box] of Object.entries(boxes)) {
      expect(inside(box, junction) ? `junction inside ${id}` : "clear").toBe("clear")
    }

    DATABASE_CLIENTS.forEach((client, index) => {
      const through = crossings(merged.feet[index], boxes, [client, "postgres"])
      expect(`${client} foot through ${through.join(", ")}`).toBe(`${client} foot through `)
    })
  })
})
