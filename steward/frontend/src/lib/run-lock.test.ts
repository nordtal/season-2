import { describe, expect, it } from "vitest"

import type { Run } from "@/lib/api"
import { lockTitle } from "@/lib/run-lock"
import { t } from "@/lib/texts"

const run = (status: Run["status"]): Run => ({
  id: 42,
  scope: [],
  kind: "BACKUP",
  status,
  actorKind: "HOST",
  actorId: "",
  steps: [],
  requested: "2026-11-16T12:00:00Z",
  scheduledFor: "2026-11-16T13:00:00Z",
  moving: [],
})

describe("run request wording", () => {
  it("confirms a request without claiming that execution has started", () => {
    expect(t("steward.operations.entered", { kind: "Backup", run: 42 })).toBe("Run #42 requested: Backup")
  })

  it("names a queued run without claiming that it is running", () => {
    expect(lockTitle(run("PENDING"))).toBe("Run #42 is queued: Backup")
  })

  it("names a running run", () => {
    expect(lockTitle(run("RUNNING"))).toBe("Run #42 is running: Backup")
  })
})
