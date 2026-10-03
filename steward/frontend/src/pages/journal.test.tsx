import { cleanup, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { JournalPage } from "@/pages/journal"
import { IDENTIFIER_PATTERN } from "@/components/steward/identity"
import { assertElement, backend, draw } from "@/pages/access.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** A line's values may run long, so they wrap while every other cell stays `whitespace-nowrap`. */
describe("JournalPage - Detail is running text, not a field", () => {
  it("lets the Detail cell wrap, rather than forcing it onto one unbroken line", async () => {
    const LONG_DETAIL = "30 days granted by hm.ally from the admin panel; the Minecraft account was linked beforehand"
    vi.stubGlobal(
      "fetch",
      backend({
        journal: () => [
          {
            id: "j1",
            occurred: "2026-09-18T09:00:00Z",
            action: "GRANT_ACCESS",
            actor: { kind: "PERSON", person: "214906139328839681" },
            subject: "214906139328839681",
            line: { key: "journal.written", args: { detail: { kind: "text", value: LONG_DETAIL } } },
          },
        ],
      }),
    )
    draw(<JournalPage />)

    const cell = assertElement((await screen.findByText(LONG_DETAIL)).closest("td"), "a <td> for the detail")
    expect(cell.className).not.toMatch(/(^|\s)whitespace-nowrap(\s|$)/)
  })
})

/** Somebody the roster no longer knows is named as such, the id still copyable in the popover. */
const JOURNAL_ENTRIES = () => [
  {
    id: "j1",
    occurred: "2026-09-18T09:00:00Z",
    action: "GRANT_ACCESS",
    actor: { kind: "PERSON", person: "214906139328839681" },
    subject: "300000000000000002",
    line: {
      key: "journal.grant-access",
      args: { days: { kind: "number", value: 30 }, until: { kind: "instant", value: "2026-10-18T09:00:00Z" } },
    },
  },
]

describe("JournalPage - profiles, never user ids", () => {
  it("draws the names of both people and neither of their ids", async () => {
    vi.stubGlobal("fetch", backend({ journal: JOURNAL_ENTRIES }))
    draw(<JournalPage />)

    await screen.findByText("Access granted")
    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(screen.getByText("bob")).toBeTruthy()
    // The admin bundle's line, its values typed: a number counted, a moment in the browser's own zone.
    expect(screen.getByText(/^30 days of access, until /)).toBeTruthy()
    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })

  it("says so rather than going anonymous for somebody the roster does not know", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        journal: () => [
          {
            id: "j2",
            occurred: "2026-09-18T09:00:00Z",
            action: "REVOKE_ACCESS",
            actor: { kind: "PERSON", person: "999999999999999999" },
            line: { key: "journal.revoke-access", args: { grants: { kind: "number", value: 1 } } },
          },
        ],
      }),
    )
    draw(<JournalPage />)

    await screen.findByText("1 grant of access revoked.")
    expect(await screen.findByText("no Discord name on record")).toBeTruthy()
    // Still not the number: the id lives in the popover, next to a button that copies it.
    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })

  it("names an action no longer listed by its own name, and the host as the host", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        journal: () => [
          {
            id: "j3",
            occurred: "2026-09-18T09:00:00Z",
            action: "RECREATE",
            actor: { kind: "HOST" },
            line: { key: "journal.written", args: { detail: { kind: "text", value: "smp: recreated" } } },
          },
        ],
      }),
    )
    draw(<JournalPage />)

    await screen.findByText("smp: recreated")
    expect(screen.getAllByText("recreate").length).toBeGreaterThan(0)
    expect((await screen.findByText("host")).closest("[data-entity='unknown']")).toBeTruthy()
  })

  it("draws Steward itself when no admin was behind the line", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        journal: () => [
          {
            id: "j3",
            occurred: "2026-09-18T09:00:00Z",
            action: "SETTLE",
            actor: { kind: "STEWARD" },
            line: { key: "journal.written", args: { detail: { kind: "text", value: "settled by the poll loop" } } },
          },
        ],
      }),
    )
    draw(<JournalPage />)

    await screen.findByText("Payment settled")
    expect(screen.getByText("Steward")).toBeTruthy()
  })
})
