import { describe, expect, it } from "vitest"

import { breadcrumbsFor } from "@/app/shell"

/**
 * The third of `steward/04`'s five findings: a URL nobody can read must not be a blank page.
 *
 * `decodeURIComponent` throws on a malformed escape, and `/services/%` is enough to produce one.
 * It was being called while the header rendered, so a mistyped address or a link that lost a
 * character took the whole interface down rather than showing an ugly crumb. The fix was a
 * `try`/`catch`; this is the test that was never written for it.
 *
 * The distinction the last group asserts is the one that matters: the segment is shown *as it
 * arrived*. A breadcrumb is not a value anything is computed from, so the honest answer to an
 * undecodable segment is the segment.
 */
describe("breadcrumbsFor", () => {
  it("is just Overview at the root", () => {
    expect(breadcrumbsFor("/")).toEqual([{ label: "Overview", href: "/" }])
  })

  it("names the sections it knows and builds each href cumulatively", () => {
    expect(breadcrumbsFor("/operations/runs/27")).toEqual([
      { label: "Overview", href: "/" },
      { label: "Operations", href: "/operations" },
      { label: "Run", href: "/operations/runs" },
      { label: "27", href: "/operations/runs/27" },
    ])
  })

  it("decodes a segment that a person should read", () => {
    expect(breadcrumbsFor("/services/a%20b").at(-1)).toEqual({
      label: "a b",
      href: "/services/a%20b",
    })
  })

  it("survives a malformed escape and shows the segment as it arrived", () => {
    // This is the whole finding. Before the fix this threw during render, and the screen was blank.
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
    // The label is for eyes and the href is for the router; conflating them would be the next bug.
    const crumb = breadcrumbsFor("/services/a%20b").at(-1)
    expect(crumb?.href).toBe("/services/a%20b")
    expect(crumb?.label).toBe("a b")
  })
})
