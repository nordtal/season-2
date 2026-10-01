import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, assert, describe, expect, it } from "vitest"

import { HealthDot } from "@/components/steward/status"

/** `HealthDot` is green in the network view and silent in the sidebar, and the sidebar must never set `quiet`. */

const here = path.dirname(fileURLToPath(import.meta.url))
const sidebarFile = path.resolve(here, "../../app/app-sidebar.tsx")

const healthy = { state: "running", health: "healthy" }

afterEach(cleanup)

describe("HealthDot - quiet by default, loud where a picture needs it", () => {
  it("draws nothing for a healthy service when nobody asked it to be loud", () => {
    const { container } = render(<HealthDot service={healthy} />)
    expect(container.innerHTML).toBe("")
  })

  it("draws a healthy service green when the caller turns the quiet off", () => {
    render(<HealthDot service={healthy} quiet={false} />)
    const dot = screen.getByLabelText("healthy")
    expect(dot.className).toContain("bg-success")
  })

  it("still draws the states that were never silent, either way round", () => {
    const { rerender } = render(<HealthDot service={{ state: "exited" }} />)
    expect(screen.getByLabelText("unhealthy").className).toContain("bg-destructive")

    rerender(<HealthDot service={{ state: "running", health: "starting" }} quiet={false} />)
    expect(screen.getByLabelText("starting").className).toContain("bg-warning")
  })

  it("never draws the fine colour for a service it has not read, loud or not", () => {
    /** Going loud must not turn an unanswered query into a green dot. */
    const { rerender } = render(<HealthDot />)
    expect(screen.getByLabelText(/not read/i).className).not.toContain("bg-success")

    rerender(<HealthDot quiet={false} />)
    expect(screen.getByLabelText(/not read/i).className).not.toContain("bg-success")
  })
})

/** Whether a source file sets `quiet`, taking a string so the test can prove the detector detects. */
export function mentionsQuiet(source: string): string[] {
  return source
    .split("\n")
    .map((line, index) => [line, index + 1] as const)
    .filter(([line]) => /\bquiet\b/.test(line))
    .map(([line, number]) => `${number}: ${line.trim()}`)
}

describe("the sidebar stays silent", () => {
  it("does not pass the switch at all, so it keeps the quiet default", () => {
    const source = readFileSync(sidebarFile, "utf8")
    assert.include(source, "<HealthDot", "the sidebar has stopped drawing a health dot, so this guard is guarding air")
    assert.deepEqual(
      mentionsQuiet(source),
      [],
      "app-sidebar.tsx now mentions `quiet`. The sidebar draws nothing for a healthy service - ten" +
        " green dots beside a service list say nothing that their absence would not, and the one" +
        " row that is not fine is what a silent sidebar makes easy to see. The network view is the" +
        " caller that goes loud, not this one.",
    )
  })

  it("would notice if it did, which is what makes the line above evidence", () => {
    const broken = ['<HealthDot service={service} quiet={false} className="ml-auto" />'].join("\n")
    expect(mentionsQuiet(broken).length).toBe(1)
  })
})

/** A held service reads as held, since `service_hold` alone tells it from a crash. */
describe("a held service is not a broken one", () => {
  const held = { state: "exited", hold: { since: "2026-09-20T18:00:00Z", by: "hmtill" } }

  it("says the word, in neither the fine colour nor the broken one", () => {
    render(<HealthDot service={held} />)
    const dot = screen.getByLabelText("held down")
    expect(dot.className).not.toContain("bg-destructive")
    expect(dot.className).not.toContain("bg-success")
  })

  it("is never silent, because being held is what somebody came to the list to find", () => {
    const { container } = render(<HealthDot service={held} />)
    expect(container.innerHTML).not.toBe("")
  })

  it("leaves a service that fell over looking like one", () => {
    render(<HealthDot service={{ state: "exited" }} />)
    expect(screen.getByLabelText("unhealthy").className).toContain("bg-destructive")
  })

  it("changes nothing about a held container that is running anyway", () => {
    /** A held service that is up and unhealthy stays red, as in `health.ts`. */
    render(<HealthDot service={{ ...held, state: "running", health: "unhealthy" }} quiet={false} />)
    expect(screen.getByLabelText("unhealthy").className).toContain("bg-destructive")
  })
})
