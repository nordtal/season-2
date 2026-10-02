import { describe, expect, it } from "vitest"

import { activeEntryId, resolveHref } from "@/app/app-sidebar"
import { navigation } from "@/app/navigation"
import { topologyOf } from "@/components/steward/network/topology"
import { NETWORK_MAP } from "@/lib/query-fixtures"

const NAVIGATION = navigation(topologyOf(NETWORK_MAP).names)

/** Which single row the sidebar lights up: the longest match across the whole navigation wins. */
describe("activeEntryId - the longest match wins", () => {
  it("lights up the start page for / and nothing else", () => {
    expect(activeEntryId("/", NAVIGATION)).toBe("overview")
  })

  it("does not light up the start page for a path that merely begins with a slash", () => {
    // `/` is the one href that must not be treated as a prefix, or every path would match it.
    expect(activeEntryId("/season", NAVIGATION)).not.toBe("overview")
  })

  it("picks the nested entry over its parent, which is the whole reason it is not a predicate", () => {
    // /operations/updates/27 matches "Updates" by its fixed part; nothing shorter may win.
    expect(activeEntryId("/operations/updates/27", NAVIGATION)).toBe("operations-updates")
    expect(activeEntryId("/operations/backups", NAVIGATION)).toBe("operations-backups")
  })

  it("keeps the right service selected rather than all of them", () => {
    /** Every service entry shares /services, so the resolved href with the real name decides. */
    expect(activeEntryId("/services/limbo", NAVIGATION)).toBe("service-limbo")
    expect(activeEntryId("/services/steward", NAVIGATION)).toBe("service-steward")
  })

  it("keeps Backups selected while you are reading one backup run", () => {
    expect(activeEntryId("/operations/backups/27", NAVIGATION)).toBe("operations-backups")
  })

  it("answers null for a path that is in no group, so the sidebar draws no selection", () => {
    expect(activeEntryId("/nowhere-at-all", NAVIGATION)).toBeNull()
  })
})

describe("resolveHref", () => {
  it("leaves a path with no parameters alone", () => {
    expect(resolveHref("/operations")).toBe("/operations")
  })

  it("substitutes a parameter and escapes it, because a service name reaches the URL", () => {
    expect(resolveHref("/services/$name", { name: "steward" })).toBe("/services/steward")
    expect(resolveHref("/services/$name", { name: "a b" })).toBe("/services/a%20b")
  })
})
