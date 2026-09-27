import { describe, expect, it } from "vitest"

import type { Run } from "@/lib/api"
import { cancellable } from "@/pages/operations"

function row(over: Partial<Run> = {}): Run {
  return {
    id: 79,
    scope: [],
    kind: "RESTART",
    status: "PENDING",
    source: "CONSOLE",
    requestedBy: "hmtill",
    actorDiscordId: "",
    actorLabel: "hmtill",
    system: false,
    requested: "2026-09-20T20:00:00Z",
    notBefore: "2026-09-20T20:01:00Z",
    started: "",
    finished: "",
    ...over,
  }
}

/** A copy of `UpdateDirectory#cancelCountdown`: PENDING or RUNNING, a counting kind, and `not_before > now()`. */
describe("cancellable - the window the backend would still take a row back in", () => {
  const now = new Date("2026-09-20T20:00:30Z")

  it("says yes to a countdown that is still running", () => {
    expect(cancellable(row(), now)).toBe(true)
    expect(cancellable(row({ status: "RUNNING" }), now)).toBe(true)
  })

  it("says yes to the row entered for tonight, hours before anything happens", () => {
    expect(cancellable(row({ notBefore: "2026-09-21T04:00:00Z" }), now)).toBe(true)
  })

  it("says no once the moment has arrived, which is the run that is actually stopping things", () => {
    /** Both rows are RUNNING and differ only in `not_before`, so a status alone would put a Cancel on both. */
    expect(cancellable(row({ status: "RUNNING", notBefore: "2026-09-20T20:00:00Z" }), now)).toBe(false)
    expect(cancellable(row({ notBefore: "2026-09-20T20:00:30Z" }), now)).toBe(false)
  })

  it("says no to a run that is over, whatever its moment was", () => {
    for (const status of ["DONE", "FAILED", "CANCELLED"]) {
      expect(cancellable(row({ status, notBefore: "2026-09-21T04:00:00Z" }), now)).toBe(false)
    }
  })

  it("says no to the kinds that never count down", () => {
    /** The SQL lists none of these kinds, so a Cancel there could only answer "too late". */
    for (const kind of ["REPORT", "START", "APPLY"]) {
      expect(cancellable(row({ kind }), now)).toBe(false)
    }
    for (const kind of ["RESTART", "UPDATE", "BACKUP", "DOWN"]) {
      expect(cancellable(row({ kind }), now)).toBe(true)
    }
  })

  it("says no to a row with no moment at all rather than throwing", () => {
    /** `not_before` is NOT NULL, but an unparseable date must still read as not cancellable, never as NaN > now. */
    expect(cancellable(row({ notBefore: "" }), now)).toBe(false)
    expect(cancellable(row({ notBefore: "not a time" }), now)).toBe(false)
  })
})
