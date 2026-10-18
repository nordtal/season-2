import { describe, expect, it } from "vitest"

import { breadcrumbsFor } from "@/app/shell"

/** `decodeURIComponent` throws on `/services/%`. The test covers the header's `try`/`catch`. */
describe("breadcrumbsFor", () => {
  it("is just Overview at the root", () => {
    expect(breadcrumbsFor("/")).toEqual([{ label: "Overview", href: "/" }])
  })

  it("names the sections it knows and builds each href cumulatively", () => {
    expect(breadcrumbsFor("/operations/updates/27")).toEqual([
      { label: "Overview", href: "/" },
      { label: "Operations", href: "/operations" },
      { label: "Updates", href: "/operations/updates" },
      { label: "27", href: "/operations/updates/27" },
    ])
  })

  it("decodes a segment that a person should read", () => {
    expect(breadcrumbsFor("/services/a%20b").at(-1)).toEqual({
      label: "a b",
      href: "/services/a%20b",
    })
  })

  it("survives a malformed escape and shows the segment as it arrived", () => {
    expect(() => breadcrumbsFor("/services/%")).not.toThrow()
    expect(breadcrumbsFor("/services/%").at(-1)).toEqual({
      label: "%",
      href: "/services/%",
    })
  })

  it.each(["%E0%A4%A", "%C3", "%zz", "100%"])(
    "survives %s, which is the same fault in four other shapes",
    (segment) => {
      expect(() => breadcrumbsFor(`/services/${segment}`)).not.toThrow()
    },
  )

  it("keeps the href encoded even when the label is decoded, so the crumb still links", () => {
    // The label is for people and the href is for the router. The test keeps them apart.
    const crumb = breadcrumbsFor("/services/a%20b").at(-1)
    expect(crumb?.href).toBe("/services/a%20b")
    expect(crumb?.label).toBe("a b")
  })
})
