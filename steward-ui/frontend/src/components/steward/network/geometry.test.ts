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
import { DATABASE_CLIENTS, EDGES, layoutFaults, type NodeId } from "./topology"
import { type Box, bundle, curve, inside, overlaps, resolvedSources, samplePath } from "./wires"

/**
 * The picture, checked without a browser - which is what the third round of steward/81 bought.
 *
 * The first two rounds laid the cards out with CSS grids and read their positions back out of the
 * DOM. jsdom has no layout, so every rectangle was zero there and nothing about the drawing could
 * be asserted: a line running through the middle of a card was found by photographing it, twice,
 * and one of them survived a whole review round. Cards are now placed at points a plan names, so
 * the geometry is a pure function of that plan and these are ordinary assertions about numbers.
 *
 * <h2>Every assertion runs at several widths (steward/121)</h2>
 * There used to be two arrangements and this file ran everything twice, once against each. There is
 * one now, and what it runs against instead is a list of **widths** - because a plan in lanes is
 * only correct if it is correct at every width it can be given, and the failures live at the ends:
 * the narrowest is where two lanes are closest to touching, the widest is where a line that took a
 * shortcut has the most room to show it.
 *
 * The four widths below are not decoration. 372 is `minWidth`, measured off the real panel at a
 * 1024px viewport. 720 is `maxWidth`. 410 and 560 are two in between, and one of them - 410 - is
 * what the panel actually is on a 1100px screen.
 *
 * What this still cannot say is whether the result is nice to look at. That needs an eye, and the
 * ticket says whose.
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
    expect(layoutFaults(allSpots(placed).map((spot) => spot.id as NodeId))).toEqual([])
  })

  it("keeps every card and every group's frame inside the canvas", () => {
    const { width, height } = geometryOf(placed)
    for (const [id, box] of Object.entries(regionsOf(placed))) {
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
    // Resolved the same way `wires.tsx` resolves an edge: a member's `from`/`to` becomes its
    // group's own key, and two edges that resolve to the same pair are the same drawn line, so
    // only the first is checked - checking it twice would just repeat the same assertion.
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

  /**
   * The database bundle, and **`postgres` is no longer excused from this check** (steward/121).
   *
   * It used to be, on the argument that a foot is heading for the sink anyway and may pass close to
   * it. That argument let through the exact fault Till reported by eye: the two sources above
   * `postgres` left through their own sides and cut diagonally across it, and the trunk ran from
   * the junction underneath the card to the *far* edge of it. Both were lines drawn behind a
   * service, which is the one thing he asked for by name, and both were invisible here because the
   * only box that could have caught them was the one being skipped.
   */
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

    // The trunk is the line that was wrong, and until `samplePath` learned to read `M … L …` it was
    // read as an empty path and could not be checked at all.
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
    expect(paper && [...paper].sort()).toEqual(["hunger-games", "limbo", "smp"])
    expect(deploy && [...deploy].sort()).toEqual(["steward-deployer", "steward-worker"])
  })

  it("packs a group's members GROUP_GAP apart under one frame padded by GROUP_PADDING on every side", () => {
    for (const group of placed.groups) {
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

/**
 * The half of steward/121 that is not about collisions: the drawing stretches, and the cards do
 * not.
 *
 * Till, 2026-09-20: proportional would be fine, but the boxes must not grow with it - only the
 * arrows should stretch. That sentence has two halves and they need two different
 * assertions - one that something grows, one that something does not - because a change that got
 * either half alone would look right in exactly one screenshot.
 */
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
    // And by exactly the extra room, not by some fraction of it: everything a wider canvas gains
    // goes into the one gap between lane 0 and lane 1.
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
    // The one rule that makes a single arrangement survive being squeezed: at `minWidth` a centre
    // card and a side card share 30px of x, so they must never share a row. Checked against the
    // spots rather than against the drawn result, because it is a fact about the plan.
    const placed = place(PLAN, PLAN.minWidth)
    const middle = placed.spots.filter((spot) => spot.x === placed.width / 2)
    const sides = allSpots(placed).filter((spot) => spot.x !== placed.width / 2)
    expect(middle.map((spot) => spot.id).sort()).toEqual(["discord-bot", "players", "postgres"])
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
