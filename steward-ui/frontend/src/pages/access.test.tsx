import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { AccessPage, JournalPage, PaymentsPage } from "@/pages/access"
import { IDENTIFIER_PATTERN } from "@/components/steward/identity"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * steward/46: faces instead of identifiers in the Access table, search over four fields rather
 * than one, "Guild: Member" gone (it is the ordinary case), and pagination that filters the whole
 * roster before it pages rather than after.
 *
 * steward/47: `unlink` and `settle` as row actions rather than a generic command card - covered in
 * a second describe block lower in this file, against the same backend fixture.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function person(over: Partial<Record<string, unknown>>): Record<string, unknown> {
  return {
    discordId: "100000000000000001",
    memberState: "MEMBER",
    donor: false,
    admin: false,
    locale: "en",
    updated: "2026-09-01T00:00:00Z",
    accessActive: false,
    ...over,
  }
}

const PEOPLE = [
  person({
    discordId: "214906139328839681",
    discordDisplayName: "Ally",
    discordUsername: "alice",
    discordUsernameUpdated: "2026-09-10T00:00:00Z",
    discordDisplayNameUpdated: "2026-09-10T00:00:00Z",
    minecraftUuid: "11111111-2222-3333-4444-555555555555",
    mcName: "AliceMC",
    mcNameUpdated: "2026-09-10T00:00:00Z",
    linked: "2026-08-01T00:00:00Z",
    accessActive: true,
    accessUntil: "2027-01-01T00:00:00Z",
  }),
  person({
    discordId: "300000000000000002",
    discordUsername: "bob",
    discordUsernameUpdated: "2026-09-10T00:00:00Z",
    memberState: "LEFT",
  }),
  person({
    discordId: "400000000000000003",
    discordUsername: "carol",
    discordUsernameUpdated: "2026-09-10T00:00:00Z",
    memberState: "BANNED",
  }),
]

/** 21 accounts that all match one search string, to hold the "filter, then page" order. */
function manyMatches(): Record<string, unknown>[] {
  return Array.from({ length: 21 }, (_, index) =>
    person({
      discordId: `9${String(index).padStart(17, "0")}`,
      discordUsername: `searchable-${index}`,
      discordUsernameUpdated: "2026-09-10T00:00:00Z",
    }),
  )
}

function backend(over: {
  people?: () => Record<string, unknown>[]
  payments?: () => Record<string, unknown>[]
  commands?: () => Record<string, unknown>[]
  commandPost?: (body: unknown) => { status: number; body: unknown }
  journal?: () => Record<string, unknown>[]
  playtimePost?: (url: string, body: unknown) => { status: number; body: unknown }
} = {}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/playtime") && init?.method === "POST") {
      const answer = over.playtimePost?.(url, JSON.parse(String(init.body))) ?? {
        status: 200,
        body: {},
      }
      return json(answer.status, answer.body)
    }
    if (url === "/api/people") return json(200, over.people ? over.people() : PEOPLE)
    if (url === "/api/payments") return json(200, over.payments ? over.payments() : [])
    if (url.startsWith("/api/journal")) return json(200, over.journal ? over.journal() : [])
    if (url === "/api/settings") {
      return json(200, { greenDays: 3, yellowDays: 7, minecraftHeadBaseUrl: "https://crafatar.com/avatars" })
    }
    if (url === "/api/commands" && init?.method !== "POST") {
      return json(200, over.commands ? over.commands() : [])
    }
    if (url === "/api/commands" && init?.method === "POST") {
      const answer = over.commandPost?.(JSON.parse(String(init.body))) ?? {
        status: 200,
        body: { id: "run-1", status: "PENDING" },
      }
      return json(answer.status, answer.body)
    }
    if (url.startsWith("/api/commands/")) {
      return json(200, { id: url.split("/").pop(), status: "DONE", result: "done" })
    }
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  })
}

function draw(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{node}</TooltipProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("AccessPage - faces instead of identifiers", () => {
  it("never draws a raw Discord id or Minecraft uuid in the table", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findByText("Ally")

    expect(document.body.textContent).not.toContain("214906139328839681")
    expect(document.body.textContent).not.toContain("11111111-2222-3333-4444-555555555555")
  })

  it("reveals the Discord id in the identity popover, on request", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const trigger = await screen.findByText("Ally")
    fireEvent.click(trigger.closest("button") as HTMLElement)

    const field = await screen.findByLabelText("Discord-ID")
    expect((field as HTMLInputElement).value).toBe("214906139328839681")
  })
})

describe("AccessPage - the guild column", () => {
  it("says nothing extra for an ordinary member", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findByText("Ally")
    expect(screen.queryByText("Member")).toBeNull()
  })

  it("still marks somebody who left the guild", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findByText("bob")
    expect(screen.getByText("left")).toBeTruthy()
  })

  it("still marks somebody who was banned", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findByText("carol")
    expect(screen.getByText("banned")).toBeTruthy()
  })
})

describe("AccessPage - search over four things", () => {
  it("finds an account by its Minecraft name, which is not what is displayed", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "AliceMC" } })

    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(screen.queryByText("bob")).toBeNull()
  })

  it("finds an account by its Minecraft uuid, although the uuid is never shown", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), {
      target: { value: "11111111-2222-3333-4444-555555555555" },
    })

    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })

  it("finds an account by its Discord id", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "214906139328839681" } })

    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(screen.queryByText("bob")).toBeNull()
  })

  it("finds an account by its Discord username", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "bob" } })

    expect(await screen.findByText("bob")).toBeTruthy()
    expect(screen.queryByText("Ally")).toBeNull()
  })
})

describe("AccessPage - the Minecraft column draws a face", () => {
  it("shows the in-game name and a head for a linked person, not a badge", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const row = (await screen.findByText("Ally")).closest("tr") as HTMLElement
    const cell = row.querySelector('[data-label="Minecraft"]') as HTMLElement
    expect(within(cell).getByText("AliceMC")).toBeTruthy()
    expect(within(cell).queryByText("linked")).toBeNull()
    expect(cell.querySelector("img")).toBeTruthy()
  })

  it("keeps the not-linked badge, with its warning colour, for somebody with no Minecraft account", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const row = (await screen.findByText("bob")).closest("tr") as HTMLElement
    const cell = row.querySelector('[data-label="Minecraft"]') as HTMLElement
    expect(within(cell).getByText("not linked")).toBeTruthy()
  })

  it("never puts the Minecraft uuid in that cell", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const row = (await screen.findByText("Ally")).closest("tr") as HTMLElement
    const cell = row.querySelector('[data-label="Minecraft"]') as HTMLElement
    expect(cell.textContent).not.toContain("11111111-2222-3333-4444-555555555555")
  })
})

describe("AccessPage - pagination filters the whole roster before it pages", () => {
  // Fifteen seconds rather than the default five, and not because the test is slow to write: it
  // draws 21 rows and then waits twice, and it came in at 5030 ms on a CI runner on 2026-09-20 -
  // thirty milliseconds over the budget, against about a second here. A test that fails on how
  // busy the machine is says nothing about the code either way, and the assertions below are
  // unchanged: what is bought is the right to believe a red one.
  it("holds 21 matches over two pages of at most 20, without the second page vanishing from the search", { timeout: 15_000 }, async () => {
    vi.stubGlobal("fetch", backend({ people: manyMatches }))
    draw(<AccessPage />)

    await screen.findByText("searchable-0")

    fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "searchable" } })

    // Page 1: twenty rows, and the count line names the full, filtered total - not just this page.
    await waitFor(() => expect(screen.getAllByText(/searchable-/).length).toBe(20))
    expect(screen.getByText(/21 of 21/)).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: /next/i }))

    await waitFor(() => expect(screen.getAllByText(/searchable-/).length).toBe(1))
  })
})

/**
 * steward/119. Till, 2026-09-18: play time goes into the Steward user list and is overridable
 * through a dialog. The reason it has to be there at all is `Prestige.java` - the tier is derived
 * from play time on every render and stored nowhere, so `player_playtime.seconds` is the only lever
 * that exists, and season-2-ingame/23 cannot be reviewed without it.
 */
describe("AccessPage - play time in the list, and overridable", () => {
  it("prints an account's play time as a span, not as a number of seconds", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ people: () => [person({ discordUsername: "alice", playtimeSeconds: 32400 })] }),
    )
    draw(<AccessPage />)

    const cell = await screen.findByText("9 h")
    expect(cell).not.toBeNull()
    expect(screen.queryByText("32400")).toBeNull()
  })

  it("says nothing for somebody who has never been online, rather than no time at all", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ people: () => [person({ discordUsername: "alice" })] }),
    )
    draw(<AccessPage />)

    const row = await screen.findByText("alice")
    expect(row).not.toBeNull()
    expect(screen.queryByText("0 s")).toBeNull()
  })

  it("shows the minutes as well, which `duration` would have dropped (steward/126)", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [
          person({ discordUsername: "alice", playtimeSeconds: 86_400 + 6 * 3_600 + 30 * 60 }),
        ],
      }),
    )
    draw(<AccessPage />)

    expect(await screen.findByText("1 d 6 h 30 min")).not.toBeNull()
  })

  it("asks in days, hours and minutes rather than in decimal hours (steward/126)", async () => {
    // THE SLIP THIS EXISTS FOR: 37.5 typed as 375 is a plausible number of hours and an impossible
    // number of days, so three fields make the mistake visible where one field hid it.
    const calls: { url: string; body: unknown }[] = []
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [person({ discordUsername: "alice", playtimeSeconds: 0 })],
        playtimePost: (url, body) => {
          calls.push({ url, body })
          return { status: 200, body: { discordId: "100000000000000001", seconds: 0 } }
        },
      }),
    )
    draw(<AccessPage />)

    fireEvent.click(await screen.findByRole("button", { name: /play ?time/i }))
    fireEvent.change(await screen.findByLabelText(/days/i), { target: { value: "1" } })
    fireEvent.change(screen.getByLabelText(/hours/i), { target: { value: "6" } })
    fireEvent.change(screen.getByLabelText(/minutes/i), { target: { value: "30" } })
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }))

    await waitFor(() => expect(calls.length).toBe(1))
    expect(calls[0].body).toEqual({ seconds: 86_400 + 6 * 3_600 + 30 * 60 })
  })

  it("carries an out-of-range field instead of refusing it (steward/126)", async () => {
    // Till: somebody who types "0 days 50 hours" means two days and two hours and has not made a
    // mistake. An empty field is a zero for the same reason - clearing "0" to type is how three
    // number inputs are used.
    const calls: { url: string; body: unknown }[] = []
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [person({ discordUsername: "alice", playtimeSeconds: 0 })],
        playtimePost: (url, body) => {
          calls.push({ url, body })
          return { status: 200, body: { discordId: "100000000000000001", seconds: 0 } }
        },
      }),
    )
    draw(<AccessPage />)

    fireEvent.click(await screen.findByRole("button", { name: /play ?time/i }))
    fireEvent.change(await screen.findByLabelText(/days/i), { target: { value: "" } })
    fireEvent.change(screen.getByLabelText(/hours/i), { target: { value: "50" } })
    fireEvent.change(screen.getByLabelText(/minutes/i), { target: { value: "" } })
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }))

    await waitFor(() => expect(calls.length).toBe(1))
    expect(calls[0].body).toEqual({ seconds: 50 * 3_600 })
  })

  it("writes the override in seconds, from hours typed into the dialog", async () => {
    const calls: { url: string; body: unknown }[] = []
    const fetcher = backend({
      people: () => [person({ discordUsername: "alice", playtimeSeconds: 3600 })],
      playtimePost: (url, body) => {
        calls.push({ url, body })
        return { status: 200, body: { discordId: "100000000000000001", seconds: 43200 } }
      },
    })
    vi.stubGlobal("fetch", fetcher)
    draw(<AccessPage />)

    fireEvent.click(await screen.findByRole("button", { name: /play ?time/i }))
    const hours = await screen.findByLabelText(/hours/i)
    fireEvent.change(hours, { target: { value: "12" } })
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }))

    await waitFor(() => expect(calls.length).toBe(1))
    expect(calls[0].url).toBe("/api/people/100000000000000001/playtime")
    expect(calls[0].body).toEqual({ seconds: 43200 })
  })
})

describe("PaymentsPage - settle as a row action", () => {
  const OPEN_PAYMENT = {
    id: "p1",
    reference: "AB12CD",
    discordId: "214906139328839681",
    days: 30,
    amountCents: 500,
    donationCents: 0,
    status: "OPEN",
    created: "2026-09-01T00:00:00Z",
    expires: "2026-09-20T00:00:00Z",
  }
  const SETTLE_COMMAND = {
    name: "/access settle",
    path: ["access", "settle"],
    target: "DISCORD_BOT",
    adminOnly: true,
    irreversible: true,
    arguments: [{ name: "reference", kind: "REFERENCE", required: true }],
  }

  it("offers Settle on an open request once the command is released to the web", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ payments: () => [OPEN_PAYMENT], commands: () => [SETTLE_COMMAND] }),
    )
    draw(<PaymentsPage />)

    const row = (await screen.findByText("AB12CD")).closest("tr") as HTMLElement
    expect(within(row).getByRole("button", { name: /settle/i })).toBeTruthy()
  })

  it("sends the row's own reference, with no picker to get wrong", async () => {
    const fetched = backend({ payments: () => [OPEN_PAYMENT], commands: () => [SETTLE_COMMAND] })
    vi.stubGlobal("fetch", fetched)
    draw(<PaymentsPage />)

    const row = (await screen.findByText("AB12CD")).closest("tr") as HTMLElement
    fireEvent.click(within(row).getByRole("button", { name: /settle/i }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Settle" }))

    await waitFor(() => {
      const call = fetched.mock.calls.find(
        ([url, init]) => url === "/api/commands" && (init as RequestInit | undefined)?.method === "POST",
      )
      expect(call).toBeTruthy()
    })
    const call = fetched.mock.calls.find(
      ([url, init]) => url === "/api/commands" && (init as RequestInit | undefined)?.method === "POST",
    )!
    expect(JSON.parse(String((call[1] as RequestInit).body))).toEqual({
      name: "/access settle",
      arguments: { reference: "AB12CD" },
    })
  })

  // steward/116: with a bunq.me link on the payment, an OPEN row draws both Tab and Settle -
  // exactly the two-action case the column was measured against. jsdom does not lay out CSS, so
  // there is no bounding box to assert on; what is real and checkable is the class that causes the
  // wrap in the first place. `flex-wrap` on this container is what lets the two buttons stack
  // instead of overflowing - which is the bug, because the column is narrower than both together.
  it("keeps Tab and Settle on one line instead of letting them wrap", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        payments: () => [{ ...OPEN_PAYMENT, shareUrl: "https://bunq.me/xyz" }],
        commands: () => [SETTLE_COMMAND],
      }),
    )
    draw(<PaymentsPage />)

    const tab = await screen.findByRole("link", { name: /tab/i })
    const actions = tab.parentElement as HTMLElement
    expect(actions.className).not.toMatch(/flex-wrap/)
  })
})

describe("AccessPage - unlink as a row action", () => {
  const UNLINK_COMMAND = {
    name: "/access unlink",
    path: ["access", "unlink"],
    target: "DISCORD_BOT",
    adminOnly: true,
    irreversible: true,
    arguments: [{ name: "member", kind: "ACCOUNT", required: true }],
  }

  /**
   * Ally carries four actions (steward/106), so hers are behind a popover and the row holds one
   * button. Opening it is part of reaching any of them - which is the interface, not the test
   * working around it.
   */
  async function openActions(name: string): Promise<HTMLElement> {
    const row = (await screen.findByText(name)).closest("tr") as HTMLElement
    const trigger = within(row).queryByRole("button", { name: /^Actions for/ })
    if (!trigger) return row
    fireEvent.click(trigger)
    return (await screen.findByRole("dialog")) as HTMLElement
  }

  it("offers Unlink on a person once the command is released to the web", async () => {
    vi.stubGlobal("fetch", backend({ commands: () => [UNLINK_COMMAND] }))
    draw(<AccessPage />)

    const actions = await openActions("Ally")
    expect(within(actions).getByRole("button", { name: /unlink/i })).toBeTruthy()
  })

  it("sends that person's own Discord id, with no picker to get wrong", async () => {
    const fetched = backend({ commands: () => [UNLINK_COMMAND] })
    vi.stubGlobal("fetch", fetched)
    draw(<AccessPage />)

    const actions = await openActions("Ally")
    fireEvent.click(within(actions).getByRole("button", { name: /unlink/i }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Unlink" }))

    await waitFor(() => {
      const call = fetched.mock.calls.find(
        ([url, init]) => url === "/api/commands" && (init as RequestInit | undefined)?.method === "POST",
      )
      expect(call).toBeTruthy()
    })
    const call = fetched.mock.calls.find(
      ([url, init]) => url === "/api/commands" && (init as RequestInit | undefined)?.method === "POST",
    )!
    expect(JSON.parse(String((call[1] as RequestInit).body))).toEqual({
      name: "/access unlink",
      arguments: { member: "214906139328839681" },
    })
  })

  it("does not offer Unlink when the command is not released to the web", async () => {
    vi.stubGlobal("fetch", backend({ commands: () => [] }))
    draw(<AccessPage />)

    const actions = await openActions("Ally")
    expect(within(actions).queryByRole("button", { name: /unlink/i })).toBeNull()
  })
})

/**
 * steward/47 + steward/106: an action is drawn when the state of THIS row allows it, and not
 * otherwise.
 *
 * Till's finding of 2026-09-17, in one sentence: "Unlink" and "Periods" stood against every person
 * alike. Ally is linked, paid and running; Bob is none of the three. The whole of this block is
 * that those two rows must not look the same.
 */
describe("AccessPage - the actions of a row depend on that row", () => {
  const UNLINK_COMMAND = {
    name: "/access unlink",
    path: ["access", "unlink"],
    target: "DISCORD_BOT",
    adminOnly: true,
    irreversible: true,
    arguments: [{ name: "member", kind: "ACCOUNT", required: true }],
  }

  /** Every control offered against one person, whether it is inline or inside the popover. */
  async function actionsOf(name: string): Promise<string[]> {
    const row = (await screen.findByText(name)).closest("tr") as HTMLElement
    const trigger = within(row).queryByRole("button", { name: /^Actions for/ })
    const scope = trigger
      ? (fireEvent.click(trigger), (await screen.findByRole("dialog")) as HTMLElement)
      : row
    return within(scope)
      .queryAllByRole("button")
      .map((button) => (button.textContent ?? "").trim())
      .filter((label) => label !== "")
  }

  it("offers nothing to unlink for somebody with no Minecraft account", async () => {
    vi.stubGlobal("fetch", backend({ commands: () => [UNLINK_COMMAND] }))
    draw(<AccessPage />)

    // bob has no minecraftUuid. The command IS released to the web, so the only thing that can
    // hide the button is the row's own state - which is exactly what was missing.
    expect(await actionsOf("bob")).not.toContain("Unlink")
    expect(await actionsOf("Ally")).toContain("Unlink")
  })

  it("offers no Periods to somebody who has never had one", async () => {
    vi.stubGlobal("fetch", backend({ commands: () => [] }))
    draw(<AccessPage />)

    // `accessUntil` absent means no period was ever written - the dialog would open on nothing.
    expect(await actionsOf("bob")).not.toContain("Periods")
    expect(await actionsOf("Ally")).toContain("Periods")
  })

  it("offers no Revoke where there is nothing running to take away", async () => {
    vi.stubGlobal("fetch", backend({ commands: () => [] }))
    draw(<AccessPage />)

    expect(await actionsOf("bob")).not.toContain("Revoke")
    expect(await actionsOf("Ally")).toContain("Revoke")
  })

  it("always offers Grant, because more access can always be given", async () => {
    vi.stubGlobal("fetch", backend({ commands: () => [] }))
    draw(<AccessPage />)

    expect(await actionsOf("bob")).toContain("Grant")
    expect(await actionsOf("Ally")).toContain("Grant")
  })

  it("puts a row's actions behind one popover as soon as there are more than two", async () => {
    vi.stubGlobal("fetch", backend({ commands: () => [UNLINK_COMMAND] }))
    draw(<AccessPage />)

    // Ally has four; bob has one, and a popover holding a single button would be a click for
    // nothing.
    const ally = (await screen.findByText("Ally")).closest("tr") as HTMLElement
    const bob = (await screen.findByText("bob")).closest("tr") as HTMLElement
    expect(within(ally).getByRole("button", { name: /^Actions for/ })).toBeTruthy()
    expect(within(bob).queryByRole("button", { name: /^Actions for/ })).toBeNull()
    expect(within(bob).getByRole("button", { name: "Grant" })).toBeTruthy()
  })
})

describe("AccessPage - the generic command card is gone", () => {
  it("does not draw the 'Access commands' block any more", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findByText("Ally")
    expect(screen.queryByText("Access commands")).toBeNull()
  })
})

/**
 * steward/114: a table cell is a field by default (shadcn's `TableCell` carries
 * `whitespace-nowrap`), and that is right for a date or an id. `audit_log.detail` is the one
 * column on these four pages that is running prose rather than a field, and prose that never
 * wraps makes the row as wide as its longest sentence - at any width, not only the stacked one
 * `index.css` already covers. The distinction is "field vs. running text", not "table vs. card".
 */
describe("JournalPage - Detail is running text, not a field", () => {
  it("lets the Detail cell wrap, rather than forcing it onto one unbroken line", async () => {
    const LONG_DETAIL =
      "30 days granted by hm.till from the admin panel; the Minecraft account was linked beforehand"
    vi.stubGlobal(
      "fetch",
      backend({
        journal: () => [
          {
            id: "j1",
            occurred: "2026-09-18T09:00:00Z",
            action: "GRANT_ACCESS",
            actor: "hm.till",
            subject: "214906139328839681",
            detail: LONG_DETAIL,
          },
        ],
      }),
    )
    draw(<JournalPage />)

    const cell = (await screen.findByText(LONG_DETAIL)).closest("td") as HTMLElement
    expect(
      cell.className,
      "TableCell's own `whitespace-nowrap` must not survive on the Detail cell - it is the one" +
        " column here that is prose, not a field.",
    ).not.toMatch(/(^|\s)whitespace-nowrap(\s|$)/)
  })
})

describe("JournalPage - profiles, never user ids (steward/124)", () => {
  /**
   * Till, 2026-09-19: the app is to show profiles and never user ids, and the Journal was the last
   * page still printing snowflakes into two of its columns. The identity component already handles
   * the case that makes this awkward - somebody the roster no longer knows - by saying so and
   * keeping the id copyable in the popover, which is a named row rather than an anonymous one.
   */
  const ENTRIES = () => [
    {
      id: "j1",
      occurred: "2026-09-18T09:00:00Z",
      action: "GRANT_ACCESS",
      actor: "214906139328839681",
      subject: "300000000000000002",
      detail: "30 days granted",
    },
  ]

  it("draws the names of both people and neither of their ids", async () => {
    vi.stubGlobal("fetch", backend({ journal: ENTRIES }))
    draw(<JournalPage />)

    await screen.findByText("GRANT_ACCESS")
    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(screen.getByText("bob")).toBeTruthy()
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
            actor: "999999999999999999",
            detail: "left the guild long ago",
          },
        ],
      }),
    )
    draw(<JournalPage />)

    await screen.findByText("REVOKE_ACCESS")
    expect(screen.getByText("no Discord name on record")).toBeTruthy()
    // Still not the number: the id lives in the popover, next to a button that copies it.
    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })

  it("draws Steward itself when no admin was behind the line", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        journal: () => [
          { id: "j3", occurred: "2026-09-18T09:00:00Z", action: "SETTLE", detail: "nightly" },
        ],
      }),
    )
    draw(<JournalPage />)

    await screen.findByText("SETTLE")
    expect(screen.getByText("Steward")).toBeTruthy()
  })
})

/**
 * steward/114: at 1440px the ten declared column widths of the Payments table summed to 95rem
 * (1520px) in a 1152px (72rem) card - a budget problem independent of wrapping, since every one of
 * those columns is a field. Fixing it needed fewer columns (the ticket's own exit clause), not
 * narrower ones: `Created` moved to a title attribute on `Reference` and `Donation` folded into
 * `Amount`, which is real data preserved, not data dropped.
 */
describe("PaymentsPage - the column budget fits the card at 1440px", () => {
  it("keeps the declared header widths under 1152px (72rem), the measured card width", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        payments: () => [
          {
            id: "p1",
            reference: "AB12CD",
            discordId: "214906139328839681",
            days: 30,
            amountCents: 500,
            donationCents: 0,
            status: "OPEN",
            created: "2026-09-01T00:00:00Z",
            expires: "2026-09-20T00:00:00Z",
          },
        ],
      }),
    )
    draw(<PaymentsPage />)

    await screen.findByText("AB12CD")
    const headers = screen.getAllByRole("columnheader")
    const remWidths = headers.map((header) => {
      const match = header.className.match(/w-\[(\d+(?:\.\d+)?)rem\]/)
      return match ? Number.parseFloat(match[1]) : 0
    })
    const total = remWidths.reduce((sum, width) => sum + width, 0)

    expect(
      total,
      `declared column widths summed to ${total}rem against a 72rem (1152px) card - ` +
        `steward/114 measured the unfixed table at 95rem`,
    ).toBeLessThan(72)
  })
})
