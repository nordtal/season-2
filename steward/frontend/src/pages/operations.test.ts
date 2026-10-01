import { describe, expect, it } from "vitest"

import type { Run } from "@/lib/api"
import { cancellable } from "@/pages/operations"

function row(over: Partial<Run> = {}): Run {
  return {
    id: 79,
    scope: [],
    kind: "RESTART",
    status: "PENDING",
    actorKind: "HOST",
    actorId: "",
    requested: "2026-09-20T20:00:00Z",
    scheduledFor: "2026-09-20T20:01:00Z",
    countdownEnd: "2026-09-20T20:01:00Z",
    moving: [],
    started: "",
    finished: "",
    ...over,
  }
}

/** A copy of `UpdateDirectory#cancelCountdown`: a counting kind, PENDING before its schedule or RUNNING before its end. */
describe("cancellable - the window the backend would still take a row back in", () => {
  const now = new Date("2026-09-20T20:00:30Z")

  it("says yes to a countdown that is still running", () => {
    expect(cancellable(row(), now)).toBe(true)
    expect(cancellable(row({ status: "RUNNING" }), now)).toBe(true)
  })

  it("says yes to the row entered for tonight, hours before anything happens", () => {
    expect(cancellable(row({ scheduledFor: "2026-09-21T04:00:00Z" }), now)).toBe(true)
  })

  it("says no once the moment has arrived, which is the run that is actually stopping things", () => {
    /** A RUNNING row counts to its countdown's end, a PENDING one to its schedule; either past is too late. */
    expect(cancellable(row({ status: "RUNNING", countdownEnd: "2026-09-20T20:00:00Z" }), now)).toBe(false)
    expect(cancellable(row({ scheduledFor: "2026-09-20T20:00:30Z" }), now)).toBe(false)
  })

  it("says no to a run that is over, whatever its moment was", () => {
    for (const status of ["DONE", "FAILED", "CANCELLED"]) {
      expect(cancellable(row({ status, scheduledFor: "2026-09-21T04:00:00Z" }), now)).toBe(false)
    }
  })

  it("says no to the kinds that never count down", () => {
    /** The SQL lists none of these kinds, so a Cancel there could only answer "too late". */
    for (const kind of ["START"]) {
      expect(cancellable(row({ kind }), now)).toBe(false)
    }
    for (const kind of ["RESTART", "UPDATE", "BACKUP", "DOWN"]) {
      expect(cancellable(row({ kind }), now)).toBe(true)
    }
  })

  it("says no to a row with no moment at all rather than throwing", () => {
    /** A running row resolves before it counts down, and an unparseable date is never NaN > now either. */
    expect(cancellable(row({ status: "RUNNING", countdownEnd: "null" }), now)).toBe(false)
    expect(cancellable(row({ scheduledFor: "" }), now)).toBe(false)
    expect(cancellable(row({ scheduledFor: "not a time" }), now)).toBe(false)
  })
})
