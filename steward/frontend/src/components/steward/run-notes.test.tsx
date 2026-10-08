import { cleanup, fireEvent, render, screen, within } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import type { RunNote } from "@/lib/api"
import { grouped, RunNotes } from "@/components/steward/run-notes"

afterEach(cleanup)

const READY: RunNote = {
  step: "STANDBY",
  outcome: "DONE",
  service: "limbo-standby",
  what: { key: "report.standbys-ready", args: {} },
}
const NO_COUNT: RunNote = {
  step: "PLAYERS",
  outcome: "WARNING",
  service: "smp",
  what: { key: "report.players-unknown", args: { seconds: { kind: "number", value: 10 } } },
}
const NOT_STOPPED: RunNote = {
  step: "STOP",
  outcome: "FAILED",
  service: "hunger-games",
  what: { key: "report.not-stopped", args: { run: { kind: "choice", value: "install" } } },
}
const STOPPED_AGAIN: RunNote = {
  step: "STANDBY",
  outcome: "DONE",
  service: "limbo-standby",
  what: { key: "report.standby-stopped", args: {} },
}
const NO_OFFSITE: RunNote = { step: "BACKUP", outcome: "SKIPPED", what: { key: "report.no-offsite", args: {} } }

const RUN = [READY, NO_COUNT, NOT_STOPPED, STOPPED_AGAIN, NO_OFFSITE]

describe("grouped - the records a filter keeps, by step", () => {
  it("groups by step in the order the run first reached each, and keeps the run's order within one", () => {
    expect(grouped(RUN, {}).map((group) => [group.step, group.notes.length])).toEqual([
      ["STANDBY", 2],
      ["PLAYERS", 1],
      ["STOP", 1],
      ["BACKUP", 1],
    ])
    expect(grouped(RUN, {})[0].notes).toEqual([READY, STOPPED_AGAIN])
  })

  it("keeps one outcome, one service, or both, and drops a step left empty", () => {
    expect(grouped(RUN, { outcome: "FAILED" })).toEqual([{ step: "STOP", notes: [NOT_STOPPED] }])
    expect(grouped(RUN, { service: "smp" })).toEqual([{ step: "PLAYERS", notes: [NO_COUNT] }])
    expect(grouped(RUN, { outcome: "DONE", service: "smp" })).toEqual([])
  })
})

describe("RunNotes - a run's records as a view", () => {
  it("draws nothing for a run without records", () => {
    const { container } = render(<RunNotes notes={[]} />)
    expect(container.innerHTML).toBe("")
  })

  it("shows each record under its step with its outcome, its service and its words", () => {
    render(<RunNotes notes={RUN} />)

    const standbys = screen.getByRole("region", { name: "Standbys" })
    const rows = within(standbys).getAllByRole("listitem")
    expect(rows.map((row) => row.textContent)).toEqual([
      "donelimbo-standbystarted and healthy",
      "donelimbo-standbystopped again",
    ])
    const backup = screen.getByRole("region", { name: "Backup" })
    expect(within(backup).getByRole("listitem").textContent).toBe(
      "skippedno offsite repository, so the archives stay on this disk",
    )
  })

  it("filters by outcome with a count on each choice, and by a service once more than one is named", () => {
    render(<RunNotes notes={RUN} />)

    const outcomes = screen.getByRole("group", { name: "Outcome" })
    expect(
      within(outcomes)
        .getAllByRole("button")
        .map((chip) => chip.textContent),
    ).toEqual(["all 5", "failed 1", "warning 1", "done 2", "skipped 1"])

    fireEvent.click(within(outcomes).getByRole("button", { name: "failed 1" }))
    expect(within(outcomes).getByRole("button", { name: "failed 1" }).getAttribute("aria-pressed")).toBe("true")
    expect(screen.getAllByRole("listitem").map((row) => row.textContent)).toEqual([
      "failedhunger-gamesnot stopped, so nothing was installed; the rest started again",
    ])

    fireEvent.click(within(outcomes).getByRole("button", { name: "all 5" }))
    const services = screen.getByRole("group", { name: "Service" })
    fireEvent.click(within(services).getByRole("button", { name: "smp" }))
    expect(screen.getAllByRole("listitem").map((row) => row.textContent)).toEqual([
      "warningsmpno recent player count, stopped after 10s",
    ])
  })

  it("offers no service filter when the records name one service or none", () => {
    render(<RunNotes notes={[READY, NO_OFFSITE]} />)
    expect(screen.queryByRole("group", { name: "Service" })).toBeNull()
  })
})
