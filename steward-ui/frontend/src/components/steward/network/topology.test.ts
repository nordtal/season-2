import { describe, expect, it } from "vitest"

import { SERVICES } from "@/app/navigation"

import { EDGES, INGRESS, SECTIONS, imageTag, layoutFaults, type NodeId } from "./topology"

/**
 * A draft arranges the ten services by hand, and a hand-written arrangement is where the eleventh
 * service goes missing.
 *
 * It goes missing **silently**: a card that is not drawn looks exactly like a card further down the
 * page, and nothing about the picture says a name is absent from it. This is the same trade
 * `Topology.java` names for `compose.yml` and `Glyphs` for `default.json` - two copies of one fact,
 * and a failing test rather than a memory is what keeps them equal.
 *
 * **That each draft actually places all ten is asserted in `geometry.test.ts`**, against the plans
 * themselves rather than against a second list of members kept here - which is what this file did
 * until the third round, and it was a third copy of the same fact.
 */
describe("layoutFaults names what an arrangement forgot (steward/81)", () => {
  it("notices a service that no draft placed, which is the failure it exists for", () => {
    const short = SERVICES.filter((name) => name !== "limbo")
    expect(layoutFaults([INGRESS, ...short])).toEqual(["limbo is placed nowhere"])
  })

  it("notices a name that is not a service at all", () => {
    expect(layoutFaults([INGRESS, ...SERVICES, "pack-host" as NodeId])).toEqual([
      "pack-host is not in SERVICES",
    ])
  })

  it("notices the same box drawn twice", () => {
    expect(layoutFaults([INGRESS, ...SERVICES, "postgres"])).toEqual(["postgres is placed twice"])
  })
})

/**
 * The table on a phone is a second hand-written list of the same ten names (steward/121), and a
 * hand-written list is where the eleventh service goes missing - silently, the same way it goes
 * missing from an arrangement. It is checked with the same function for the same reason.
 */
describe("the sections cover every service, once (steward/121)", () => {
  it("names all ten between them and repeats none", () => {
    const rows = SECTIONS.flatMap((section) => section.members)
    // `INGRESS` is prepended rather than expected in a section: `players` is not a service and
    // deliberately has no row - see the comment on SECTIONS - but `layoutFaults` is the drawing's
    // checker and asks for it, so this hands it the one thing it is entitled to expect.
    expect(layoutFaults([INGRESS, ...rows])).toEqual([])
  })

  it("gives every section a heading and at least one row", () => {
    for (const section of SECTIONS) {
      expect(`${section.id} has ${section.members.length} rows`).not.toBe(`${section.id} has 0 rows`)
      expect(section.title).toMatch(/^[A-Z]/)
    }
  })
})

describe("the edges are between boxes that exist", () => {
  it("names only services and the entry box", () => {
    const known = new Set<string>([INGRESS, ...SERVICES])
    const unknown = EDGES.flatMap((edge) => [edge.from, edge.to]).filter((id) => !known.has(id))
    expect(unknown).toEqual([])
  })

  it("has the database at the end of every data edge, and nowhere else", () => {
    for (const edge of EDGES) {
      if (edge.kind === "data") expect(edge.to).toBe("postgres")
    }
    // Seven of the ten services hold a connection; caddy, steward-deployer and postgres itself do
    // not. Read off compose.yml, and a line here that compose does not back is a line that lies.
    expect(EDGES.filter((edge) => edge.kind === "data").length).toBe(7)
  })
})

describe("the tag under a name", () => {
  it("is the tag, not the repository", () => {
    expect(imageTag("ghcr.io/nordtal/smp:1.4.0")).toBe("1.4.0")
    expect(imageTag("postgres:17-alpine")).toBe("17-alpine")
    expect(imageTag("registry.example.com:5000/nordtal/limbo:2.0")).toBe("2.0")
  })

  it("stands in with a short digest when a reference is pinned rather than tagged", () => {
    expect(imageTag("ghcr.io/nordtal/steward-ui@sha256:abcdef1234567890")).toBe("#abcdef1")
  })

  it("shortens the bare image id docker reports for a container whose tag was rebuilt", () => {
    // Measured on this host, 2026-09-17: steward-worker's row carries exactly this and no name.
    // The `#` is the fix for the first review's finding: read without it, "334951d" next to the
    // "not compared" mark was mistaken for "334951 days" (steward/81, second round).
    expect(
      imageTag("sha256:334951d4c54754fa0bcc40bc7e483f2af78c7244fbefc28eff775ffa40c1ce07"),
    ).toBe("#334951d")
  })

  it("says what docker itself would assume when there is no tag at all", () => {
    expect(imageTag("caddy")).toBe("latest")
  })

  it("draws a dash rather than nothing when the row carries no image", () => {
    expect(imageTag(undefined)).toBe("–")
  })
})
