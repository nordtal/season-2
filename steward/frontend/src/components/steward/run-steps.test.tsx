import { cleanup, fireEvent, render, screen, within } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { kept, RunSteps, type StepLike } from "@/components/steward/run-steps"

afterEach(cleanup)

const UP: StepLike = { step: "STANDBY_UP", outcome: "DONE", service: "limbo-standby", lines: [] }
const NO_COUNT: StepLike = {
  step: "PLAYERS_MOVED",
  outcome: "WARNING",
  service: "smp",
  lines: [{ key: "run.reason", args: { reason: { kind: "choice", value: "players-unknown" } } }],
}
const DUMP: StepLike = {
  step: "DUMP_DATABASE",
  outcome: "FAILED",
  lines: [
    { key: "run.reason", args: { reason: { kind: "choice", value: "tool-failed" } } },
    { key: "run.exit-code", args: { code: { kind: "number", value: 2 } } },
  ],
}
const DOWN: StepLike = { step: "STANDBY_DOWN", outcome: "DONE", service: "limbo-standby", lines: [] }
const OFFSITE: StepLike = { step: "OFFSITE_COPY", lines: [] }

const RUN = [UP, NO_COUNT, DUMP, DOWN, OFFSITE]

describe("kept - the steps a filter keeps", () => {
  it("keeps one outcome, one service, or both, in the run's order", () => {
    expect(kept(RUN, {})).toEqual(RUN)
    expect(kept(RUN, { outcome: "DONE" })).toEqual([UP, DOWN])
    expect(kept(RUN, { service: "smp" })).toEqual([NO_COUNT])
    expect(kept(RUN, { outcome: "DONE", service: "smp" })).toEqual([])
  })
})

describe("RunSteps - a run's steps as a view", () => {
  it("draws nothing for a run without steps", () => {
    const { container } = render(<RunSteps steps={[]} title="Report" />)
    expect(container.innerHTML).toBe("")
  })

  it("shows each step with its outcome, its kind, its service and its facts, and an open one as running", () => {
    render(<RunSteps steps={RUN} title="Report" />)

    expect(screen.getAllByRole("listitem").map((row) => row.textContent)).toEqual([
      "doneStandby up limbo-standby",
      "warningPlayers moved smpno recent player count",
      "failedDump the databasethe tool failedexit code 2",
      "doneStandby down limbo-standby",
      "runningOffsite copy",
    ])
  })

  it("filters by outcome with a count on each choice, and by a service once more than one is named", () => {
    render(<RunSteps steps={RUN} title="Report" />)

    const outcomes = screen.getByRole("group", { name: "Outcome" })
    expect(
      within(outcomes)
        .getAllByRole("button")
        .map((chip) => chip.textContent),
    ).toEqual(["all 5", "failed 1", "warning 1", "done 2"])

    fireEvent.click(within(outcomes).getByRole("button", { name: "failed 1" }))
    expect(within(outcomes).getByRole("button", { name: "failed 1" }).getAttribute("aria-pressed")).toBe("true")
    expect(screen.getAllByRole("listitem")).toHaveLength(1)

    fireEvent.click(within(outcomes).getByRole("button", { name: "all 5" }))
    const services = screen.getByRole("group", { name: "Service" })
    fireEvent.click(within(services).getByRole("button", { name: "smp" }))
    expect(screen.getAllByRole("listitem").map((row) => row.textContent)).toEqual([
      "warningPlayers moved smpno recent player count",
    ])
  })

  it("offers no service filter when the steps name one service or none", () => {
    render(<RunSteps steps={[UP, DUMP]} title="Report" />)
    expect(screen.queryByRole("group", { name: "Service" })).toBeNull()
  })
})
