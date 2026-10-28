import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { Actor, Alert, JournalItem } from "@/lib/api"
import { JournalPage, edges, lasted } from "@/pages/journal"
import { IDENTIFIER_PATTERN } from "@/components/steward/identity"
import { backend, draw, journalPage } from "@/pages/access.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
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
    expect((await screen.findAllByText("Host"))[0].closest("[data-entity='unknown']")).toBeNull()
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

const ALERT: Omit<Alert, "level"> = {
  type: "service",
  subject: "smp",
  title: { key: "alert.not-running", args: { service: { kind: "text", value: "smp" } } },
  detail: [],
  measured: [{ gauge: "CONTAINER_STATE", subject: "smp", state: "exited" }],
  path: "/services/smp",
}
const STEWARD: Actor = { kind: "STEWARD" }

/** An outage of smp from 09:00 to 09:42 with a console line during it, newest first. */
const OUTAGE = (): JournalItem[] => [
  {
    key: "e3",
    occurred: "2026-09-18T09:42:00Z",
    actor: STEWARD,
    // An all-clear names its action, not the subject the alert measured.
    alert: {
      ...ALERT,
      level: "ok",
      subject: "services",
      measured: [],
      title: { key: "alert.clear", args: { type: { kind: "text", value: "service" } } },
    },
    opened: "2026-09-18T09:00:00Z",
  },
  {
    key: "e2",
    occurred: "2026-09-18T09:10:00Z",
    actor: { kind: "HOST" },
    entry: {
      id: "e2",
      occurred: "2026-09-18T09:10:00Z",
      action: "CONSOLE",
      actor: { kind: "HOST" },
      line: {
        key: "journal.console",
        args: { service: { kind: "text", value: "smp" }, command: { kind: "text", value: "list" } },
      },
    },
  },
  { key: "e1", occurred: "2026-09-18T09:00:00Z", actor: STEWARD, alert: { ...ALERT, level: "down" } },
]

function edge(key: string): string | null | undefined {
  return document.querySelector(`[data-key='${key}']`)?.getAttribute("data-edge")
}

describe("JournalPage - an alert's span", () => {
  it("draws the alert's edge along the entries while it is open, and the all-clear says how long it lasted", async () => {
    vi.stubGlobal("fetch", backend({ stream: OUTAGE }))
    draw(<JournalPage />)

    expect(await screen.findByText("after 42 min")).toBeTruthy()
    expect([edge("e3"), edge("e2"), edge("e1")]).toEqual(["ok", "down", "down"])
  })

  it("leaves the entries before and after an outage without an edge", () => {
    const before: JournalItem = { key: "e0", occurred: "2026-09-18T08:00:00Z", actor: { kind: "STEWARD" } }
    const after: JournalItem = { key: "e4", occurred: "2026-09-18T10:00:00Z", actor: { kind: "STEWARD" } }
    expect(edges([after, ...OUTAGE(), before])).toEqual([undefined, "ok", "down", "down", undefined])
  })

  it("says hours once an alert lasted an hour or more", () => {
    expect(lasted("2026-09-18T09:00:00Z", "2026-09-18T10:05:00Z")).toBe("after 1 h 5 min")
  })
})

describe("JournalPage - a run is one row that links to its page", () => {
  it("draws the run's kind, status, services and why it failed", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        stream: () => [
          {
            key: "r0000000000000000042",
            occurred: "2026-09-18T09:00:00Z",
            actor: STEWARD,
            run: {
              id: 42,
              kind: "UPDATE",
              status: "FAILED",
              services: ["smp"],
              why: [{ key: "alert.words", args: { text: { kind: "text", value: "pull failed" } } }],
            },
          },
        ],
      }),
    )
    draw(<JournalPage />)

    const link = await screen.findByRole("link", { name: "Update" })
    expect(link.getAttribute("href")).toBe("/operations/updates/42")
    expect(screen.getByText("pull failed")).toBeTruthy()
    expect(screen.getAllByText("failed").length).toBeGreaterThan(0)
  })
})

describe("JournalPage - a backup run", () => {
  it("links to its page under Backups", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        stream: () => [
          {
            key: "r0000000000000000249",
            occurred: "2026-10-23T03:00:00Z",
            actor: STEWARD,
            run: { id: 249, kind: "BACKUP", status: "DONE", services: [], why: [] },
          },
        ],
      }),
    )
    draw(<JournalPage />)

    const link = await screen.findByRole("link", { name: "Backup" })
    expect(link.getAttribute("href")).toBe("/operations/backups/249")
  })
})

describe("JournalPage - open now", () => {
  it("lists what is open with its level, and says nothing is open otherwise", async () => {
    const fetched = backend({})
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) =>
      url === "/api/alerts"
        ? new Response(
            JSON.stringify({ checkedAt: "2026-09-18T09:30:00Z", level: "down", alerts: [{ ...ALERT, level: "down" }] }),
            {
              status: 200,
              headers: { "content-type": "application/json" },
            },
          )
        : fetched(url, init),
    )
    draw(<JournalPage />)

    expect(await screen.findByRole("link", { name: "smp is not running" })).toBeTruthy()
    expect(document.querySelector("[data-alert='down']")).toBeTruthy()
  })

  it("says nothing is open when nothing is", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw(<JournalPage />)
    expect(await screen.findByText("Nothing open")).toBeTruthy()
  })
})

describe("JournalPage - one search field", () => {
  it("asks for the typed words once Enter is pressed and shows how the server read them", async () => {
    const fetched = backend({ journal: JOURNAL_ENTRIES })
    const asked: string[] = []
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      asked.push(url)
      if (url.startsWith("/api/journal?") && url.includes("q=")) {
        const terms = [
          { kind: "PAYMENT", word: "nt-abcdef", values: ["NT-ABCDEF"] },
          { kind: "DAYS", word: "30d", values: ["30"] },
          { kind: "ACTION", word: "grant", values: ["GRANT_ACCESS"] },
        ]
        return new Response(JSON.stringify({ items: [], terms }), {
          status: 200,
          headers: { "content-type": "application/json" },
        })
      }
      return fetched(url, init)
    })
    draw(<JournalPage />)

    const field = await screen.findByRole<HTMLInputElement>("searchbox", { name: "Search the journal" })
    fireEvent.change(field, { target: { value: " nt-abcdef 30d grant " } })
    expect(asked.some((url) => url.includes("q="))).toBe(false)
    fireEvent.submit(field)
    await waitFor(() => expect(asked).toContain("/api/journal?limit=50&q=nt-abcdef+30d+grant"))
    expect(await screen.findByText("NT-ABCDEF")).toBeTruthy()
    expect(screen.getByText("30 days or more")).toBeTruthy()
    // A word that names one action also matches names, so the badge says the word, not the action.
    expect(screen.getByText("“grant”")).toBeTruthy()
  })

  it("asks for the next page after the last one's cursor", async () => {
    const fetched = backend({})
    const asked: string[] = []
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      asked.push(url)
      if (url === "/api/journal?limit=50") {
        return new Response(JSON.stringify({ ...journalPage(JOURNAL_ENTRIES()), next: "2026-09-18T09:00:00Z_ej1" }), {
          status: 200,
          headers: { "content-type": "application/json" },
        })
      }
      return fetched(url, init)
    })
    draw(<JournalPage />)

    fireEvent.click(await screen.findByRole("button", { name: "Older entries" }))
    await waitFor(() => expect(asked).toContain("/api/journal?limit=50&after=2026-09-18T09%3A00%3A00Z_ej1"))
  })
})
