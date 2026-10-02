import { describe, expect, it } from "vitest"

import { NETWORK_MAP } from "@/lib/query-fixtures"

import { INGRESS, imageTag, layoutFaults, topologyOf } from "./topology"

const TOPOLOGY = topologyOf(NETWORK_MAP)
const NAMES = TOPOLOGY.names

/** A hand-written arrangement silently loses a service the stack gained; `view.tsx` then draws the table instead. */
describe("layoutFaults names what an arrangement forgot", () => {
  it("notices a service that no draft placed, which is the failure it exists for", () => {
    const short = NAMES.filter((name) => name !== "limbo")
    expect(layoutFaults([INGRESS, ...short], NAMES)).toEqual(["limbo is placed nowhere"])
  })

  it("notices a name that is not a service at all", () => {
    expect(layoutFaults([INGRESS, ...NAMES, "pack-host"], NAMES)).toEqual(["pack-host is not a served service"])
  })

  it("notices the same box drawn twice", () => {
    expect(layoutFaults([INGRESS, ...NAMES, "postgres"], NAMES)).toEqual(["postgres is placed twice"])
  })
})

describe("the sections, read off the labels", () => {
  it("run from the players inwards, with what only stores data last", () => {
    expect(TOPOLOGY.sections.map((section) => section.title)).toEqual([
      "Entry",
      "Paper",
      "Steward",
      "Discord",
      "Database",
    ])
  })

  it("name every served service once between them, in the order the labels list them", () => {
    expect(layoutFaults([INGRESS, ...TOPOLOGY.sections.flatMap((section) => section.members)], NAMES)).toEqual([])
    expect(TOPOLOGY.sections[0].members).toEqual(["proxy", "caddy"])
    expect(NAMES).toHaveLength(NETWORK_MAP.services.length)
  })
})

describe("the edges, read off the labels", () => {
  it("lead from the players to each entry, and along every reach", () => {
    const traffic = TOPOLOGY.edges.filter((edge) => edge.kind === "traffic").map((edge) => `${edge.from}-${edge.to}`)
    expect(traffic).toEqual([
      "players-proxy",
      "players-caddy",
      "proxy-smp",
      "proxy-hunger-games",
      "proxy-limbo",
      "steward-steward-agent",
      "steward-steward-bunq",
      "caddy-steward",
    ])
  })

  it("bundle everything that keeps its data in postgres into the one sink", () => {
    expect(TOPOLOGY.sink).toBe("postgres")
    expect(TOPOLOGY.storers).toEqual(["discord-bot", "proxy", "limbo", "hunger-games", "smp", "steward"])
  })

  it("name no sink once data is kept in two places, since one bundle cannot reach both", () => {
    const split = topologyOf({
      services: [
        ...NETWORK_MAP.services,
        { name: "cache", section: "Database", entry: false, reaches: [], storesIn: [] },
        { name: "worker", section: "Steward", entry: false, reaches: [], storesIn: ["cache"] },
      ],
    })
    expect(split.sink).toBeUndefined()
  })
})

describe("the tag under a name", () => {
  it("is the tag, not the repository", () => {
    expect(imageTag("ghcr.io/nordtal/smp:1.4.0")).toBe("1.4.0")
    expect(imageTag("postgres:17-alpine")).toBe("17-alpine")
    expect(imageTag("registry.example.com:5000/nordtal/limbo:2.0")).toBe("2.0")
  })

  it("stands in with a short digest when a reference is pinned rather than tagged", () => {
    expect(imageTag("ghcr.io/nordtal/steward@sha256:abcdef1234567890")).toBe("#abcdef1")
  })

  it("shortens the bare image id docker reports for a container whose tag was rebuilt", () => {
    /** steward's row shows only this, and without `#` "334951d" reads as a count of days. */
    expect(imageTag("sha256:334951d4c54754fa0bcc40bc7e483f2af78c7244fbefc28eff775ffa40c1ce07")).toBe("#334951d")
  })

  it("says what docker itself would assume when there is no tag at all", () => {
    expect(imageTag("caddy")).toBe("latest")
  })

  it("draws a dash rather than nothing when the row carries no image", () => {
    expect(imageTag(undefined)).toBe("\u2013")
  })
})
