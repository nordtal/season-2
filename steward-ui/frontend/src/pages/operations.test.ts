import { describe, expect, it } from "vitest"

import type { Run } from "@/lib/api"
import { cancellable } from "@/pages/operations"

/**
 * Which row carries a Cancel (steward/131).
 *
 * The rule is a copy of the worker's SQL, and a copy is only worth anything while it agrees:
 * `UpdateDirectory#cancelCountdown` takes `status IN ('PENDING','RUNNING')`, the four kinds that
 * count down, and `not_before > now()`. Everything below is one of those three clauses said in
 * TypeScript - and the last two tests are the pair that look identical in every other column of
 * the table, which is why the button cannot be drawn from the status alone.
 */
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
    // THE PAIR THAT MATTERS. This row and the first one above are both RUNNING; the only
    // difference is on which side of now `not_before` falls, and only one of them can still be
    // taken back. A button drawn from the status would be on both.
    expect(cancellable(row({ status: "RUNNING", notBefore: "2026-09-20T20:00:00Z" }), now))
      .toBe(false)
    expect(cancellable(row({ notBefore: "2026-09-20T20:00:30Z" }), now))
      .toBe(false)
  })

  it("says no to a run that is over, whatever its moment was", () => {
    for (const status of ["DONE", "FAILED", "CANCELLED"]) {
      expect(cancellable(row({ status, notBefore: "2026-09-21T04:00:00Z" }), now)).toBe(false)
    }
  })

  it("says no to the kinds that never count down", () => {
    // REPORT and START stop nothing and the SQL does not list them, so a Cancel on one of those
    // rows would be a tap that can only ever answer "too late".
    for (const kind of ["REPORT", "START", "APPLY"]) {
      expect(cancellable(row({ kind }), now)).toBe(false)
    }
    for (const kind of ["RESTART", "UPDATE", "BACKUP", "DOWN"]) {
      expect(cancellable(row({ kind }), now)).toBe(true)
    }
  })

  it("says no to a row with no moment at all rather than throwing", () => {
    // `not_before` is NOT NULL in the schema, so this is the API layer having answered oddly -
    // and an unparseable date must read as "not cancellable", never as NaN > now.
    expect(cancellable(row({ notBefore: "" }), now)).toBe(false)
    expect(cancellable(row({ notBefore: "not a time" }), now)).toBe(false)
  })
})
