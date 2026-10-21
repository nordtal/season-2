import { describe, expect, it } from "vitest"

import { breadcrumbsFor } from "@/app/shell"
import type { Person } from "@/lib/api"

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

  it.each([
    ["/translations", "Translations"],
    ["/alerts", "Alerts"],
  ])("names %s by its page", (path, label) => {
    expect(breadcrumbsFor(path).at(-1)?.label).toBe(label)
  })

  it("names a user's page after the user, and by the id until the roster is there", () => {
    const ally: Person = {
      discordId: "123",
      discordDisplayName: "Ally",
      memberState: "MEMBER",
      donor: false,
      admin: false,
      locale: "en",
      updated: "2026-09-01T00:00:00Z",
      accessActive: false,
    }
    const people = [ally]
    expect(breadcrumbsFor("/access/123", people).at(-1)).toEqual({ label: "Ally", href: "/access/123" })
    expect(breadcrumbsFor("/access/123").at(-1)?.label).toBe("123")
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
