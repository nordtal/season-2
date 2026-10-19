import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react"
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
            line: {
              key: "journal.console",
              args: { service: { kind: "text", value: "smp" }, command: { kind: "text", value: LONG_DETAIL } },
            },
          },
        ],
      }),
    )
    draw(<JournalPage />)

    const cell = assertElement(
      (await screen.findByText(`Typed "${LONG_DETAIL}" into the console of smp.`)).closest("td"),
      "a <td> for the detail",
    )
    expect(cell.className).not.toMatch(/(^|\s)whitespace-nowrap(\s|$)/)
  })
})

/** Somebody the roster no longer knows is named as such. */
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

    await screen.findAllByText("Access granted")
    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(screen.getByText("bob")).toBeTruthy()
    expect(screen.getByText(/^Granted 30 days of access until /)).toBeTruthy()
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

    await screen.findByText("Revoked 1 grant of access.")
    expect(await screen.findByText("no Discord name on record")).toBeTruthy()
    // The id lives in the popover, next to a button that copies it.
    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })

  it("names the host as the host", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        journal: () => [
          {
            id: "j3",
            occurred: "2026-09-18T09:00:00Z",
            action: "CONSOLE",
            actor: { kind: "HOST" },
            line: {
              key: "journal.console",
              args: { service: { kind: "text", value: "smp" }, command: { kind: "text", value: "list" } },
            },
          },
        ],
      }),
    )
    draw(<JournalPage />)

    await screen.findByText('Typed "list" into the console of smp.')
    expect((await screen.findAllByText("host"))[0].closest("[data-entity='unknown']")).toBeTruthy()
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
            line: {
              key: "journal.settle-by-hand",
              args: {
                reference: { kind: "text", value: "NT-7" },
                days: { kind: "number", value: 30 },
                ordered: { kind: "number", value: 30 },
                donation: { kind: "money", value: { minor: 0, currency: "EUR" } },
              },
            },
          },
        ],
      }),
    )
    draw(<JournalPage />)

    await screen.findAllByText("Payment settled")
    expect(screen.getByText("Steward")).toBeTruthy()
  })
})

/** On a phone an entry is its time and action, plus what they do not already say. */
async function off(detail: string | RegExp): Promise<string[]> {
  return [...assertElement((await screen.findByText(detail)).closest("tr"), "a row").querySelectorAll("td")]
    .filter((cell) => cell.getAttribute("data-phone") === "off")
    .map((cell) => cell.getAttribute("data-label") ?? "action")
}

describe("JournalPage - the phone card says each thing once", () => {
  it("leaves out a detail without values and a concerned person who is the actor", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        journal: () => [
          {
            id: "j5",
            occurred: "2026-09-18T09:00:00Z",
            action: "START_GAME",
            actor: { kind: "PERSON", person: "214906139328839681" },
            line: { key: "journal.start-game" },
          },
          {
            id: "j6",
            occurred: "2026-09-18T09:01:00Z",
            action: "WEB_PUSH_UNSUBSCRIBE",
            actor: { kind: "PERSON", person: "214906139328839681" },
            subject: "214906139328839681",
            line: { key: "journal.web-push-unsubscribe" },
          },
          ...JOURNAL_ENTRIES(),
        ],
      }),
    )
    draw(<JournalPage />)

    expect(await off("Started the Hunger Games.")).toEqual(["Concerns", "Detail"])
    expect(await off("Push alerts off for this browser.")).toEqual(["Concerns", "Detail"])
    expect(await off(/^Granted 30 days of access until /)).toEqual([])
  })
})

describe("JournalPage - the subject is an exact id, asked for on Enter", () => {
  it("asks for the typed id only once Enter is pressed, and for every entry again once it is cleared", async () => {
    const fetched = backend({ journal: JOURNAL_ENTRIES })
    vi.stubGlobal("fetch", fetched)
    draw(<JournalPage />)
    const asked = () => fetched.mock.calls.map(([url]) => url).filter((url) => url.startsWith("/api/journal"))

    const field = await screen.findByRole<HTMLInputElement>("searchbox", { name: "Discord id concerned" })
    fireEvent.change(field, { target: { value: "300000000000000002" } })
    await screen.findAllByText("Access granted")
    expect(asked().some((url) => url.includes("subject="))).toBe(false)

    fireEvent.submit(field)
    await waitFor(() => expect(asked()).toContain("/api/journal?limit=200&subject=300000000000000002"))

    fireEvent.click(screen.getByRole("button", { name: "Clear the search" }))
    expect(field.value).toBe("")
    expect(screen.queryByRole("button", { name: "Clear the search" })).toBeNull()
  })
})

describe("JournalPage - the filters ask for a payment reference and the least days on Enter", () => {
  it("asks for the typed reference and the days at least, each once Enter is pressed", async () => {
    const fetched = backend({ journal: JOURNAL_ENTRIES })
    vi.stubGlobal("fetch", fetched)
    draw(<JournalPage />)
    const asked = () => fetched.mock.calls.map(([url]) => url).filter((url) => url.startsWith("/api/journal"))

    fireEvent.click(await screen.findByRole("button", { name: "Filters" }))
    const reference = await screen.findByRole<HTMLInputElement>("searchbox", { name: "Payment reference" })
    fireEvent.change(reference, { target: { value: " NT-ABCD " } })
    fireEvent.submit(reference)
    await waitFor(() => expect(asked()).toContain("/api/journal?limit=200&reference=NT-ABCD"))

    const days = screen.getByRole<HTMLInputElement>("searchbox", { name: "Days granted, at least" })
    fireEvent.change(days, { target: { value: "30" } })
    expect(asked().some((url) => url.includes("minDays="))).toBe(false)
    fireEvent.submit(days)
    await waitFor(() => expect(asked()).toContain("/api/journal?limit=200&reference=NT-ABCD&minDays=30"))
    expect(screen.getByRole("button", { name: "Filters 2" })).toBeTruthy()
  })
})
