import { describe, expect, it } from "vitest"

import { PLAN } from "./plan"
import {
  GROUP_GAP,
  GROUP_PADDING,
  MARGIN,
  NODE,
  type Placed,
  allSpots,
  geometryOf,
  groupBox,
  groupMemberSpots,
  laneX,
  place,
  regionsOf,
} from "./place"
import { DATABASE_CLIENTS, EDGES, layoutFaults } from "./topology"
import { type Box, bundle, curve, inside, overlaps, resolvedSources, samplePath } from "./wires"

/**
 * The network picture as numbers, since the plan's placement is a pure function and jsdom has no layout.
 *
 * Every assertion runs at `minWidth`, `maxWidth` and two widths between, where the failures live.
 */
const WIDTHS = [372, 410, 560, 720] as const

const LAYOUTS = WIDTHS.map((width) => [`${width}px`, place(PLAN, width)] as const)

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

describe.each(LAYOUTS)("%s", (_name, placed: Placed) => {
  it("places every service in navigation.ts, exactly once, and the box the traffic comes from", () => {
    expect(layoutFaults(allSpots(placed).map((spot) => spot.id))).toEqual([])
  })

  it("keeps every card and every group's frame inside the canvas", () => {
    const { width, height } = geometryOf(placed)
    for (const [id, box] of Object.entries(regionsOf(placed))) {
      expect(`${id} left ${box.x}`).toBe(`${id} left ${Math.max(box.x, 0)}`)
      expect(`${id} right ${box.x + box.width}`).toBe(`${id} right ${Math.min(box.x + box.width, width)}`)
      expect(`${id} bottom ${box.y + box.height}`).toBe(`${id} bottom ${Math.min(box.y + box.height, height)}`)
    }
  })

  it("never lets two cards or group frames touch", () => {
    /** Top level shapes only, since a member's box sits inside its group's frame on purpose. */
    const regions = regionsOf(placed)
    const ids = Object.keys(regions)
    for (let a = 0; a < ids.length; a++) {
      for (let b = a + 1; b < ids.length; b++) {
        const pair = `${ids[a]} and ${ids[b]}`
        expect(overlaps(regions[ids[a]], regions[ids[b]]) ? pair : "clear").toBe("clear")
      }
    }
  })

  it("draws no traffic line through a card or group it does not belong to", () => {
    /** Resolved as `wires.tsx` does, so two edges drawn as one line are checked once. */
    const geometry = geometryOf(placed)
    const regions = regionsOf(placed)
    const seen = new Set<string>()
    for (const edge of TRAFFIC) {
      const fromKey = geometry.memberOf[edge.from] ?? edge.from
      const toKey = geometry.memberOf[edge.to] ?? edge.to
      const from = regions[fromKey]
      const to = regions[toKey]
      if (!from || !to) continue
      const key = `${fromKey}-${toKey}`
      if (seen.has(key)) continue
      seen.add(key)
      const d = curve(from, to, placed.bows?.[key] ?? 0)
      const through = crossings(d, regions, [fromKey, toKey])
      expect(`${key} through ${through.join(", ")}`).toBe(`${key} through `)
    }
  })

  /** The database bundle, with `postgres` itself not excused, since a line may cut behind the sink too. */
  it("keeps the database bundle clear of every card and group, the sink included", () => {
    const geometry = geometryOf(placed)
    const regions = regionsOf(placed)
    const junction = placed.junction
    if (!junction) throw new Error("the arrangement names no junction")
    const sink = geometry.boxes.postgres
    const resolved = resolvedSources(DATABASE_CLIENTS, geometry)
    const merged = bundle(
      resolved.map((entry) => entry.box),
      sink,
      junction,
    )

    for (const [id, box] of Object.entries(regions)) {
      expect(inside(box, junction) ? `junction inside ${id}` : "clear").toBe("clear")
    }

    resolved.forEach(({ key }, index) => {
      const through = crossings(merged.feet[index], regions, [key])
      expect(`${key} foot through ${through.join(", ")}`).toBe(`${key} foot through `)
    })

    /** The trunk must be read as a real path, or it could not be checked at all. */
    expect(samplePath(merged.trunk).length).toBeGreaterThan(1)
    const throughTrunk = crossings(merged.trunk, regions, [])
    expect(`trunk through ${throughTrunk.join(", ")}`).toBe("trunk through ")
  })

  it("centres players and keeps it above every other card", () => {
    const players = placed.spots.find((spot) => spot.id === "players")
    if (!players) throw new Error("players is not a loose spot in this arrangement")
    expect(players.x).toBe(placed.width / 2)
    for (const spot of allSpots(placed)) {
      if (spot.id === "players") continue
      expect(`${spot.id} y ${spot.y}`).toBe(`${spot.id} y ${Math.max(spot.y, players.y + 1)}`)
    }
  })

  it("puts discord-bot on the centre line, below every other card", () => {
    const bot = placed.spots.find((spot) => spot.id === "discord-bot")
    if (!bot) throw new Error("discord-bot is not a loose spot in this arrangement")
    expect(bot.x).toBe(placed.width / 2)
    for (const spot of allSpots(placed)) {
      if (spot.id === "discord-bot") continue
      expect(`${spot.id} y ${spot.y}`).toBe(`${spot.id} y ${Math.min(spot.y, bot.y - 1)}`)
    }
  })

  it("keeps postgres in the middle third of the canvas, both ways", () => {
    const { boxes, width, height } = geometryOf(placed)
    const centre = {
      x: boxes.postgres.x + boxes.postgres.width / 2,
      y: boxes.postgres.y + boxes.postgres.height / 2,
    }
    expect(centre.x).toBeGreaterThan(width / 3)
    expect(centre.x).toBeLessThan((width * 2) / 3)
    expect(centre.y).toBeGreaterThan(height / 3)
    expect(centre.y).toBeLessThan((height * 2) / 3)
  })

  it("gives caddy and proxy the same top edge", () => {
    const { boxes } = geometryOf(placed)
    expect(boxes.caddy.y).toBe(boxes["proxy"].y)
  })

  it("has exactly two groups: the three Paper services, and the two deploy services", () => {
    expect(placed.groups).toHaveLength(2)
    const byMember = new Map(placed.groups.map((group) => [group.id, new Set(group.members)]))
    const paper = [...byMember.values()].find((members) => members.has("smp"))
    const deploy = [...byMember.values()].find((members) => members.has("steward-worker"))
    expect(paper && [...paper].toSorted()).toEqual(["hunger-games", "limbo", "smp"])
    expect(deploy && [...deploy].toSorted()).toEqual(["steward-deployer", "steward-worker"])
  })

  it("packs a group's members GROUP_GAP apart under one frame padded by GROUP_PADDING on every side", () => {
    for (const group of placed.groups) {
      const members = groupMemberSpots(group)
      for (let i = 1; i < members.length; i++) {
        /** Restated as a gap between stacked cards rather than a distance between centres. */
        expect(members[i].y - members[i - 1].y - NODE.height).toBe(GROUP_GAP)
      }

      const box = groupBox(group)
      const left = Math.min(...members.map((spot) => spot.x)) - NODE.width / 2
      const right = Math.max(...members.map((spot) => spot.x)) + NODE.width / 2
      const top = Math.min(...members.map((spot) => spot.y)) - NODE.height / 2
      const bottom = Math.max(...members.map((spot) => spot.y)) + NODE.height / 2
      expect(box.x).toBe(left - GROUP_PADDING)
      expect(box.y).toBe(top - GROUP_PADDING)
      expect(box.width).toBe(right - left + GROUP_PADDING * 2)
      expect(box.height).toBe(bottom - top + GROUP_PADDING * 2)
    }
  })

  it("draws exactly one traffic edge into each group, and one data edge out of each group", () => {
    const geometry = geometryOf(placed)
    const paperGroup = geometry.memberOf.smp
    const deployGroup = geometry.memberOf["steward-worker"]
    expect(paperGroup).toBeTruthy()
    expect(deployGroup).toBeTruthy()

    const traffic = new Set<string>()
    for (const edge of TRAFFIC) {
      const from = geometry.memberOf[edge.from] ?? edge.from
      const to = geometry.memberOf[edge.to] ?? edge.to
      if (to === paperGroup || to === deployGroup) traffic.add(`${from}-${to}`)
    }
    const intoPaper = [...traffic].filter((key) => key.endsWith(`-${paperGroup}`))
    const intoDeploy = [...traffic].filter((key) => key.endsWith(`-${deployGroup}`))
    expect(intoPaper).toHaveLength(1)
    expect(intoDeploy).toHaveLength(1)

    const resolved = resolvedSources(DATABASE_CLIENTS, geometry)
    const keys = resolved.map((entry) => entry.key)
    expect(keys.filter((key) => key === paperGroup)).toHaveLength(1)
    expect(keys.filter((key) => key === deployGroup)).toHaveLength(1)
  })

  it("gives both groups and nothing else the same bottom edge", () => {
    const geometry = geometryOf(placed)
    const bottoms = new Set(
      placed.groups.map((group) => {
        const box = geometry.groups[group.id]
        return box.y + box.height
      }),
    )
    expect(bottoms.size).toBe(1)
  })
})

/** The drawing stretches while the cards keep their size, each half asserted on its own. */
describe("stretching", () => {
  it("leaves every card the same size at every width", () => {
    for (const [, placed] of LAYOUTS) {
      for (const box of Object.values(geometryOf(placed).boxes)) {
        expect(`${box.width}x${box.height}`).toBe(`${NODE.width}x${NODE.height}`)
      }
    }
  })

  it("moves the lanes apart as the canvas grows, which is what lengthens the lines", () => {
    const gaps = WIDTHS.map((width) => laneX(1, width) - laneX(0, width))
    for (let i = 1; i < gaps.length; i++) {
      expect(`at ${WIDTHS[i]}px the lanes are ${gaps[i]}px apart`).toBe(
        `at ${WIDTHS[i]}px the lanes are ${Math.max(gaps[i], gaps[i - 1] + 1)}px apart`,
      )
    }
    /** By exactly the extra room, all of which goes into the gap between lane 0 and lane 1. */
    expect(gaps[gaps.length - 1] - gaps[0]).toBe(WIDTHS[WIDTHS.length - 1] - WIDTHS[0])
  })

  it("keeps a lane-0 group's frame on the canvas, which is what MARGIN is for", () => {
    for (const [, placed] of LAYOUTS) {
      const geometry = geometryOf(placed)
      const left = Math.min(...Object.values(geometry.groups).map((box) => box.x))
      expect(left).toBe(MARGIN - GROUP_PADDING)
      expect(left).toBeGreaterThan(0)
    }
  })

  it("puts nothing in the middle lane level with anything in a side lane", () => {
    /** At `minWidth` a centre card and a side card share x, so no two may share a row. */
    const placed = place(PLAN, PLAN.minWidth)
    const middle = placed.spots.filter((spot) => spot.x === placed.width / 2)
    const sides = allSpots(placed).filter((spot) => spot.x !== placed.width / 2)
    expect(middle.map((spot) => spot.id).toSorted()).toEqual(["discord-bot", "players", "postgres"])
    for (const centre of middle) {
      for (const side of sides) {
        const apart = Math.abs(centre.y - side.y)
        expect(`${centre.id} and ${side.id} ${apart >= NODE.height ? "clear" : "level"}`).toBe(
          `${centre.id} and ${side.id} clear`,
        )
      }
    }
  })
})
