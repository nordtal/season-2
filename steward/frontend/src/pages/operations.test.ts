import { describe, expect, it } from "vitest"

import type { Run } from "@/lib/api"
import { cancellable, summaryOf } from "@/pages/operations"

function row(over: Partial<Run> = {}): Run {
  return {
    id: 79,
    scope: [],
    kind: "RESTART",
    status: "PENDING",
    actorKind: "HOST",
    actorId: "",
    said: {},
    requested: "2026-09-20T20:00:00Z",
    scheduledFor: "2026-09-20T20:01:00Z",
    countdownEnd: "2026-09-20T20:01:00Z",
    moving: [],
    started: "",
    finished: "",
    ...over,
  }
}

/**
 * A copy of `UpdateDirectory#cancelCountdown`: PENDING before its schedule or RUNNING before its end.
 */
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
    expect(cancellable(row({ status: "RUNNING", countdownEnd: "2026-09-20T20:00:00Z" }), now)).toBe(false)
    expect(cancellable(row({ scheduledFor: "2026-09-20T20:00:30Z" }), now)).toBe(false)
  })

  it("says no to a run that is over, whatever its moment was", () => {
    for (const status of ["DONE", "FAILED", "CANCELLED"] as const) {
      expect(cancellable(row({ status, scheduledFor: "2026-09-21T04:00:00Z" }), now)).toBe(false)
    }
  })

  it("says no to the kinds that never count down", () => {
    /** The SQL lists none of these kinds. */
    for (const kind of ["START"] as const) {
      expect(cancellable(row({ kind }), now)).toBe(false)
    }
    for (const kind of ["RESTART", "UPDATE", "BACKUP", "DOWN"] as const) {
      expect(cancellable(row({ kind }), now)).toBe(true)
    }
  })

  it("says no to a row with no moment at all rather than throwing", () => {
    /** A running row resolves before it counts down. */
    expect(cancellable(row({ status: "RUNNING", countdownEnd: undefined }), now)).toBe(false)
    expect(cancellable(row({ scheduledFor: "" }), now)).toBe(false)
    expect(cancellable(row({ scheduledFor: "not a time" }), now)).toBe(false)
  })
})

/** One fact per line, counted in the bundle's words. */
describe("summaryOf - what a run did, counted", () => {
  it("counts the services that moved and the files they moved, each in its own number", () => {
    const report: Run["report"] = {
      stage: "DONE",
      services: [
        { service: "smp", state: "HEALTHY", changes: [{ artefact: "smp", from: "1", to: "2", state: "MOVING" }] },
        {
          service: "proxy",
          state: "FAILED",
          changes: [
            { artefact: "proxy", from: "1", to: "2", state: "MOVING" },
            { artefact: "limbo", from: "1", to: "2", state: "MOVING" },
          ],
        },
      ],
      notes: [],
    }

    expect(summaryOf(row({ status: "DONE", report }))).toEqual(["2 services, 3 artefacts", "1 failed"])
  })
})
