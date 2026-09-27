import { describe, expect, it } from "vitest"

import { SERVICES } from "@/app/navigation"

import { EDGES, INGRESS, SECTIONS, imageTag, layoutFaults } from "./topology"

/**
 * A hand-written arrangement silently loses an eleventh service; `geometry.test.ts` asserts each plan places all ten.
 */
describe("layoutFaults names what an arrangement forgot", () => {
  it("notices a service that no draft placed, which is the failure it exists for", () => {
    const short = SERVICES.filter((name) => name !== "limbo")
    expect(layoutFaults([INGRESS, ...short])).toEqual(["limbo is placed nowhere"])
  })

  it("notices a name that is not a service at all", () => {
    expect(layoutFaults([INGRESS, ...SERVICES, "pack-host"])).toEqual(["pack-host is not in SERVICES"])
  })

  it("notices the same box drawn twice", () => {
    expect(layoutFaults([INGRESS, ...SERVICES, "postgres"])).toEqual(["postgres is placed twice"])
  })
})

/** The phone's table is a second hand-written list of the ten names, checked the same way. */
describe("the sections cover every service, once", () => {
  it("names all ten between them and repeats none", () => {
    const rows = SECTIONS.flatMap((section) => section.members)
    /** `players` has no row but `layoutFaults` expects it, so it is prepended. */
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
    for (const edge of EDGES.filter((one) => one.kind === "data")) {
      expect(edge.to).toBe("postgres")
    }
    /** Seven of the ten hold a connection, as compose.yml declares; caddy, steward-deployer and postgres do not. */
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
    /** steward-worker's row shows only this, and without `#` "334951d" reads as a count of days. */
    expect(imageTag("sha256:334951d4c54754fa0bcc40bc7e483f2af78c7244fbefc28eff775ffa40c1ce07")).toBe("#334951d")
  })

  it("says what docker itself would assume when there is no tag at all", () => {
    expect(imageTag("caddy")).toBe("latest")
  })

  it("draws a dash rather than nothing when the row carries no image", () => {
    expect(imageTag(undefined)).toBe("\u2013")
  })
})
