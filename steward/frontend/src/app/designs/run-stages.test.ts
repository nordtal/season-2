import { describe, expect, it } from "vitest"

import type { ReportLine, Run, RunNote } from "@/lib/api"
import type { MessageRef } from "@/lib/texts"
import { stagesOf } from "@/app/designs/run-stages"

type Stage = NonNullable<Run["report"]>["stage"]

function run(
  kind: Run["kind"],
  stage: Stage,
  services: ReportLine[],
  notes: RunNote[] = [],
  said: Record<string, MessageRef> = {},
): Run {
  return {
    id: 1,
    kind,
    status: stage === "FAILED" ? "FAILED" : "DONE",
    actorKind: "HOST",
    actorId: "",
    scope: [],
    requested: "2026-10-09T01:24:00Z",
    scheduledFor: "2026-10-09T01:24:00Z",
    moving: [],
    started: "2026-10-09T01:24:00Z",
    finished: "2026-10-09T01:27:00Z",
    report: { stage, services, notes },
    said,
  }
}

const moved = (artefact: string, from: string, to: string) => ({ artefact, from, to, state: "MOVING" as const })
const note = (step: RunNote["step"], outcome: RunNote["outcome"], service?: string): RunNote => ({
  step,
  outcome,
  service,
  what: { key: "report.standbys-ready", args: {} },
})

describe("stagesOf", () => {
  it("never shows a backup stage for an update, and puts each line's changes under Install", () => {
    const stages = stagesOf(
      run("UPDATE", "DONE", [{ service: "smp", state: "HEALTHY", changes: [moved("smp", "0.18.5", "0.19.0")] }]),
    )
    expect(stages.map((stage) => stage.key)).not.toContain("BACKUP")
    const install = stages.find((stage) => stage.key === "INSTALL")
    expect(install?.verdict).toBe("done")
    expect(install?.items[0]?.changes[0]?.to).toBe("0.19.0")
    expect(stages.find((stage) => stage.key === "MIGRATE")?.verdict).toBe("quiet")
  })

  it("shows what a run that failed before installing would have installed, as not reached", () => {
    const stages = stagesOf(
      run(
        "UPDATE",
        "FAILED",
        [{ service: "smp", state: "PLANNED", changes: [moved("smp", "0.18.0", "0.18.2")] }],
        [note("RELEASE", "FAILED", "steward")],
      ),
    )
    expect(stages.find((stage) => stage.key === "RELEASE")?.verdict).toBe("failed")
    const install = stages.find((stage) => stage.key === "INSTALL")
    expect(install?.verdict).toBe("not-reached")
    expect(install?.items[0]?.undone).toBe(true)
    expect(stages.find((stage) => stage.key === "START")?.verdict).toBe("not-reached")
  })

  it("puts a failed start under Starting, with its reason, and every saved volume under Backup", () => {
    const failed = stagesOf(
      run("UPDATE", "FAILED", [{ service: "smp", state: "FAILED", changes: [] }], [], {
        smp: { key: "report.not-healthy", args: {} },
      }),
    )
    const start = failed.find((stage) => stage.key === "START")
    expect(start?.verdict).toBe("failed")
    expect(start?.items[0]?.said?.key).toBe("report.not-healthy")

    const backup = stagesOf(
      run("BACKUP", "DONE", [
        { service: "database", state: "SAVED", changes: [{ artefact: "backup", to: "-", state: "MOVING" }] },
        { service: "smp", state: "HEALTHY", changes: [{ artefact: "backup", to: "-", state: "MOVING" }] },
      ]),
    )
    expect(backup.find((stage) => stage.key === "BACKUP")?.items.map((item) => item.service)).toEqual(["database"])
    expect(backup.find((stage) => stage.key === "STOP")?.items.map((item) => item.service)).toEqual(["smp"])
    expect(backup.map((stage) => stage.key)).not.toContain("INSTALL")
  })

  it("has no stage for a run that had nothing to do", () => {
    expect(stagesOf(run("UPDATE", "NOTHING_TO_DO", [{ service: "smp", state: "UNCHANGED", changes: [] }]))).toEqual([])
  })
})
