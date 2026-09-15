import { describe, expect, it } from "vitest"

import { activeEntryId, resolveHref } from "@/app/app-sidebar"
import { NAVIGATION } from "@/app/navigation"

/**
 * Which single row the sidebar lights up.
 *
 * `activeEntryId` was exported for a test on 2026-09-13 and the test was never written - it is one
 * of the five findings of `steward/04`, all of which were checked with `tsc` and thinking rather
 * than with a red test. This is the cheapest of the five and therefore the first: a pure function
 * over the real `NAVIGATION`, so a navigation entry that breaks the rule breaks this too.
 *
 * The rule is *longest match wins, decided across the whole navigation*. The two things it exists
 * for are the two cases below: a nested path that matches its parent as well, and a parameterised
 * route that must stay selected while you are looking at a different parameter than the link
 * carries.
 */
describe("activeEntryId - the longest match wins", () => {
  it("lights up the start page for / and nothing else", () => {
    expect(activeEntryId("/", NAVIGATION)).toBe("status")
  })

  it("does not light up the start page for a path that merely begins with a slash", () => {
    // `/` is the one href that must not be treated as a prefix, or every path would match it.
    expect(activeEntryId("/season", NAVIGATION)).not.toBe("status")
  })

  it("picks the nested entry over its parent, which is the whole reason it is not a predicate", () => {
    // /operations/plan matches "Overview" (/operations) and "Plan" (/operations/plan). Before the
    // longest-match rule the sidebar drew both rows selected.
    expect(activeEntryId("/operations/plan", NAVIGATION)).toBe("operations-plan")
    expect(activeEntryId("/operations", NAVIGATION)).toBe("operations")
  })

  it("keeps the right service selected rather than all ten of them", () => {
    // Every service entry shares the fixed part /services, so the fixed part alone cannot decide;
    // the resolved href with the real name is the longer match.
    expect(activeEntryId("/services/limbo", NAVIGATION)).toBe("service-limbo")
    expect(activeEntryId("/services/steward-worker", NAVIGATION)).toBe("service-steward-worker")
  })

  it("keeps the Run row selected while you are reading a run that is not the one it links to", () => {
    // "Run" links to /operations/runs/latest; run 27 must not deselect it.
    const latest = activeEntryId("/operations/runs/latest", NAVIGATION)
    expect(latest).not.toBeNull()
    expect(activeEntryId("/operations/runs/27", NAVIGATION)).toBe(latest)
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
    expect(resolveHref("/services/$name", { name: "steward-ui" })).toBe("/services/steward-ui")
    expect(resolveHref("/services/$name", { name: "a b" })).toBe("/services/a%20b")
  })
})
