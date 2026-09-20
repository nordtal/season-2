import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { HealthDot } from "@/components/steward/status"

/**
 * The switch steward/81 turned one line of `HealthDot` into, and the rule it must not break.
 *
 * Till decided both halves on 2026-09-16: **green in the network view, silent in the sidebar**. A
 * picture of state whose healthy boxes carry nothing looks like a query that failed; a sidebar
 * where nothing is the message makes the one row that matters easy to find. So the component draws
 * both, and the caller says which - `quiet` defaults to the sidebar's answer, so no existing call
 * site had to change and going loud is the thing that has to be typed.
 *
 * The second test below is the one that matters in six months' time: it reads `app-sidebar.tsx` and
 * checks that the sidebar still says nothing about `quiet`, because the way this rule gets broken
 * is not somebody arguing with it - it is somebody adding `quiet={false}` to a row while making the
 * dots line up.
 */

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
    // `health.ts`'s rule one level down: "A green light on no evidence is the one thing this page
    // must not do." Turning the quiet off must not turn that into a green dot for a service the
    // query has not answered for yet.
    const { rerender } = render(<HealthDot />)
    expect(screen.getByLabelText(/not read/i).className).not.toContain("bg-success")

    rerender(<HealthDot quiet={false} />)
    expect(screen.getByLabelText(/not read/i).className).not.toContain("bg-success")
  })
})

/**
 * Whether a source file has opinions about `quiet`.
 *
 * Exported and taken as a string rather than reading the file itself, so the test below can prove
 * the detector detects. A guard whose only evidence is that it found nothing has not been shown to
 * work - that is the same hole `no-middle-dot.test.ts` closes with its second test.
 */
export function mentionsQuiet(source: string): string[] {
  return source
    .split("\n")
    .map((line, index) => [line, index + 1] as const)
    .filter(([line]) => /\bquiet\b/.test(line))
    .map(([line, number]) => `${number}: ${line.trim()}`)
}

describe("the sidebar stays silent (steward/83, unchanged by steward/81)", () => {
  it("does not pass the switch at all, so it keeps the quiet default", () => {
    const source = readFileSync(sidebarFile, "utf8")
    expect(source, "the sidebar has stopped drawing a health dot, so this guard is guarding air")
      .toContain("<HealthDot")
    expect(
      mentionsQuiet(source),
      "app-sidebar.tsx now mentions `quiet`. Till's decision of 2026-09-16 is that the sidebar" +
        " draws nothing for a healthy service - ten green dots beside a service list say nothing" +
        " that their absence would not, and the one row that is not fine is what a silent sidebar" +
        " makes easy to see. The network view is the caller that goes loud, not this one.",
    ).toEqual([])
  })

  it("would notice if it did, which is what makes the line above evidence", () => {
    const broken = ['<HealthDot service={service} quiet={false} className="ml-auto" />'].join("\n")
    expect(mentionsQuiet(broken).length).toBe(1)
  })
})

/**
 * Held down is its own reading (steward/134).
 *
 * `service_hold` is the only place the difference between "somebody put this down" and "this fell
 * over" exists - the container state is `exited` either way. Until this ticket that difference was
 * drawn on exactly one page, the service's own, so the sidebar and the network view showed a
 * deliberate hold in the colour of an outage.
 */
describe("a held service is not a broken one (steward/134)", () => {
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
    // The hold describes being stopped. One that is up and failing its healthcheck is not what
    // anybody asked for, and `health.ts` keeps it red for the same reason.
    render(<HealthDot service={{ ...held, state: "running", health: "unhealthy" }} quiet={false} />)
    expect(screen.getByLabelText("unhealthy").className).toContain("bg-destructive")
  })
})
