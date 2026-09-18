import { describe, expect, it } from "vitest"

import { PLAN } from "./plan"
import {
  GROUP_GAP,
  GROUP_PADDING,
  NODE,
  type Arrangement,
  allSpots,
  geometryOf,
  groupBox,
  groupMemberSpots,
  regionsOf,
} from "./place"
import { DATABASE_CLIENTS, EDGES, layoutFaults, type NodeId } from "./topology"
import { type Box, bundle, curve, inside, overlaps, resolvedSources, samplePath } from "./wires"

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

/**
 * Both arrangements of the one plan that is left. `i` stood beside it here until Till chose `h`
 * on 2026-09-18; the two lists this file used to keep - every draft, and then the subset that
 * groups anything - collapsed into this one when the loser was deleted.
 */
const PLANS = [
  ["wide", PLAN.wide],
  ["narrow", PLAN.narrow],
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
    expect(layoutFaults(allSpots(arrangement).map((spot) => spot.id as NodeId))).toEqual([])
  })

  it("keeps every card and every group's frame inside the canvas", () => {
    const { width, height } = geometryOf(arrangement)
    for (const [id, box] of Object.entries(regionsOf(arrangement))) {
      expect(`${id} left ${box.x}`).toBe(`${id} left ${Math.max(box.x, 0)}`)
      expect(`${id} right ${box.x + box.width}`).toBe(
        `${id} right ${Math.min(box.x + box.width, width)}`,
      )
      expect(`${id} bottom ${box.y + box.height}`).toBe(
        `${id} bottom ${Math.min(box.y + box.height, height)}`,
      )
    }
  })

  it("never lets two cards or group frames touch", () => {
    // A member's own box sits inside its group's frame on purpose - that is not a collision, it is
    // what the frame is for - so this checks the picture's top-level shapes (`regionsOf`, one per
    // lone card and one per group) rather than every individual box.
    const regions = regionsOf(arrangement)
    const ids = Object.keys(regions)
    for (let a = 0; a < ids.length; a++) {
      for (let b = a + 1; b < ids.length; b++) {
        const pair = `${ids[a]} and ${ids[b]}`
        expect(overlaps(regions[ids[a]], regions[ids[b]]) ? pair : "clear").toBe("clear")
      }
    }
  })

  it("draws no traffic line through a card or group it does not belong to", () => {
    // Resolved the same way `wires.tsx` resolves an edge: a member's `from`/`to` becomes its
    // group's own key, and two edges that resolve to the same pair are the same drawn line, so
    // only the first is checked - checking it twice would just repeat the same assertion.
    const geometry = geometryOf(arrangement)
    const regions = regionsOf(arrangement)
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
      const d = curve(from, to, arrangement.bows?.[key] ?? 0)
      const through = crossings(d, regions, [fromKey, toKey])
      expect(`${key} through ${through.join(", ")}`).toBe(`${key} through `)
    }
  })

  it("keeps the database lane clear of every card or group", () => {
    const geometry = geometryOf(arrangement)
    const regions = regionsOf(arrangement)
    const junction = arrangement.junction
    if (!junction) return
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
      const through = crossings(merged.feet[index], regions, [key, "postgres"])
      expect(`${key} foot through ${through.join(", ")}`).toBe(`${key} foot through `)
    })
  })
})

/**
 * Till, 2026-09-18, choosing `h` and asking for six further changes in the same message. Each of
 * these checks one of them; the seventh ("two singles and a group on the left, one single and a
 * group on the right") is the column shape the other checks below already pin down together, so it
 * has no test of its own.
 */
describe.each(PLANS)("%s - Till's six changes of 2026-09-18", (_name, arrangement: Arrangement) => {
  it("centres players and keeps it above every other card", () => {
    const players = arrangement.spots.find((spot) => spot.id === "players")
    if (!players) throw new Error("players is not a loose spot in this arrangement")
    expect(players.x).toBe(arrangement.width / 2)
    for (const spot of allSpots(arrangement)) {
      if (spot.id === "players") continue
      expect(`${spot.id} y ${spot.y}`).toBe(`${spot.id} y ${Math.max(spot.y, players.y + 1)}`)
    }
  })

  it("puts discord-bot on the centre line, below every other card", () => {
    const bot = arrangement.spots.find((spot) => spot.id === "discord-bot")
    if (!bot) throw new Error("discord-bot is not a loose spot in this arrangement")
    expect(bot.x).toBe(arrangement.width / 2)
    for (const spot of allSpots(arrangement)) {
      if (spot.id === "discord-bot") continue
      expect(`${spot.id} y ${spot.y}`).toBe(`${spot.id} y ${Math.min(spot.y, bot.y - 1)}`)
    }
  })

  it("keeps postgres in the middle third of the canvas, both ways", () => {
    const { boxes, width, height } = geometryOf(arrangement)
    const centre = {
      x: boxes.postgres.x + boxes.postgres.width / 2,
      y: boxes.postgres.y + boxes.postgres.height / 2,
    }
    expect(centre.x).toBeGreaterThan(width / 3)
    expect(centre.x).toBeLessThan((width * 2) / 3)
    expect(centre.y).toBeGreaterThan(height / 3)
    expect(centre.y).toBeLessThan((height * 2) / 3)
  })

  it("gives caddy and network-control the same top edge", () => {
    const { boxes } = geometryOf(arrangement)
    expect(boxes.caddy.y).toBe(boxes["network-control"].y)
  })

  it("has exactly two groups: the three Paper services, and the two deploy services", () => {
    const groups = arrangement.groups ?? []
    expect(groups).toHaveLength(2)
    const byMember = new Map(groups.map((group) => [group.id, new Set(group.members)]))
    const paper = [...byMember.values()].find((members) => members.has("smp"))
    const deploy = [...byMember.values()].find((members) => members.has("steward-worker"))
    expect(paper && [...paper].sort()).toEqual(["hunger-games", "limbo", "smp"])
    expect(deploy && [...deploy].sort()).toEqual(["steward-deployer", "steward-worker"])
  })

  it("packs a group's members GROUP_GAP apart under one frame padded by GROUP_PADDING on every side", () => {
    for (const group of arrangement.groups ?? []) {
      const members = groupMemberSpots(group)
      for (let i = 1; i < members.length; i++) {
        // The vertical distance between two stacked centres is one card's own height plus the gap
        // between them - restated as a gap rather than a centre distance so the assertion reads
        // as "4px apart" the way the ticket does, not as an unexplained 80.
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
    const geometry = geometryOf(arrangement)
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
})

/**
 * "The groups and `discord-bot` finish on one bottom line" only has room to mean what it says at
 * `wide`'s 600px: two group frames and one card, side by side, all ending at the same y. At 352px
 * the two frames alone already span 2px to 350px of the canvas - `GROUP_PADDING` widens a group
 * past its members' own footprint on every side, and there is no gap left between them for a third
 * box, let alone one on the same row. `discord-bot` is below both groups on `narrow` instead
 * (still centred, still the lowest card there is) rather than squeezed into a row that cannot hold
 * it - a deliberate reflow, not the same arrangement measured wrong.
 */
describe("h wide - the bottom line", () => {
  it("gives both groups and discord-bot the same bottom edge", () => {
    const geometry = geometryOf(PLAN.wide)
    const bot = geometry.boxes["discord-bot"]
    const bottoms = new Set(
      [...(PLAN.wide.groups ?? []).map((group) => geometry.groups[group.id]), bot].map(
        (box) => box.y + box.height,
      ),
    )
    expect(bottoms.size).toBe(1)
  })
})

describe("h narrow - discord-bot does not fit beside the groups", () => {
  it("keeps discord-bot clear of both groups and below both of them", () => {
    const geometry = geometryOf(PLAN.narrow)
    const regions = regionsOf(PLAN.narrow)
    const bot = geometry.boxes["discord-bot"]
    for (const group of PLAN.narrow.groups ?? []) {
      const box = regions[group.id]
      expect(overlaps(box, bot) ? `${group.id} overlaps discord-bot` : "clear").toBe("clear")
      expect(bot.y).toBeGreaterThanOrEqual(box.y + box.height)
    }
  })
})
