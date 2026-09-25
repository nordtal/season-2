import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { AccessPage, JournalPage, PaymentsPage } from "@/pages/access"
import { IDENTIFIER_PATTERN } from "@/components/steward/identity"
import { TooltipProvider } from "@/components/ui/tooltip"
import { toast } from "sonner"

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

/** Who is signed in, in every test here: the root of the admin tree below. */
const ME = "500000000000000000"

function backend(over: {
  people?: () => Record<string, unknown>[]
  payments?: () => Record<string, unknown>[]
  journal?: () => Record<string, unknown>[]
  playtimePost?: (url: string, body: unknown) => { status: number; body: unknown }
  /** What the bot answered, by kind - DONE with an empty result unless a test says otherwise. */
  answer?: (kind: string) => Record<string, unknown>
} = {}) {
  const asked = new Map<string, string>()
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/playtime") && init?.method === "POST") {
      const answer = over.playtimePost?.(url, JSON.parse(String(init.body))) ?? {
        status: 202,
        body: { id: "a-playtime", kind: "SET_PLAYTIME", status: "PENDING" },
      }
      asked.set("a-playtime", "SET_PLAYTIME")
      return json(answer.status, answer.body)
    }
    if (url.startsWith("/api/access/") && !url.startsWith("/api/access/requests/")) {
      // Every write is an access_request row the bot carries out: 202 and an id to poll.
      const kind = url.split("/").pop()!.toUpperCase()
      const id = `a-${kind.toLowerCase()}`
      asked.set(id, kind)
      return json(202, { id, kind, status: "PENDING" })
    }
    if (url.startsWith("/api/access/requests/")) {
      const id = url.split("/").pop()!
      const kind = asked.get(id) ?? "UNKNOWN"
      return json(
        200,
        over.answer?.(kind) ?? { id, kind, status: "DONE", result: {} },
      )
    }
    if (url.startsWith("/api/admins/") && init?.method === "POST") {
      return json(200, { outcome: url.endsWith("/grant") ? "GRANTED" : "REVOKED", removed: [] })
    }
    if (url === "/api/me") return json(200, { signedIn: true, id: ME, webauthn: "READY" })
    if (url === "/api/people") return json(200, over.people ? over.people() : PEOPLE)
    if (url === "/api/payments") return json(200, over.payments ? over.payments() : [])
    if (url.startsWith("/api/journal")) return json(200, over.journal ? over.journal() : [])
    if (url === "/api/settings") {
      return json(200, { greenDays: 3, yellowDays: 7, minecraftHeadBaseUrl: "https://crafatar.com/avatars" })
    }
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  })
}

function draw(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  // A router, because Entity draws a service as a link to its page.
  const root = createRootRoute({ component: () => <>{node}</> })
  const service = createRoute({ getParentRoute: () => root, path: "/services/$name" })
  const router = createRouter({
    routeTree: root.addChildren([service]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RouterProvider router={router} />
      </TooltipProvider>
    </QueryClientProvider>,
  )
}

/**
 * Clicks one of a row's actions. Three or more of them sit behind the row's popover, so that is
 * opened first when there is one - the same two taps a person makes.
 */
async function clickRowAction(name: RegExp) {
  const trigger = await screen.findByRole("button", { name: /^Actions for/ })
  fireEvent.click(trigger)
  const popover = (await screen.findByRole("dialog")) as HTMLElement
  fireEvent.click(within(popover).getByRole("button", { name }))
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
          return { status: 202, body: { id: "a-playtime", status: "PENDING" } }
        },
      }),
    )
    draw(<AccessPage />)

    await clickRowAction(/play ?time/i)
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
          return { status: 202, body: { id: "a-playtime", status: "PENDING" } }
        },
      }),
    )
    draw(<AccessPage />)

    await clickRowAction(/play ?time/i)
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
        return { status: 202, body: { id: "a-playtime", status: "PENDING" } }
      },
    })
    vi.stubGlobal("fetch", fetcher)
    draw(<AccessPage />)

    await clickRowAction(/play ?time/i)
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
  it("offers Settle on an open request", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ payments: () => [OPEN_PAYMENT] }),
    )
    draw(<PaymentsPage />)

    const row = (await screen.findByText("AB12CD")).closest("tr") as HTMLElement
    expect(within(row).getByRole("button", { name: /settle/i })).toBeTruthy()
  })

  it("sends the row's own reference, with no picker to get wrong", async () => {
    const fetched = backend({ payments: () => [OPEN_PAYMENT] })
    vi.stubGlobal("fetch", fetched)
    draw(<PaymentsPage />)

    const row = (await screen.findByText("AB12CD")).closest("tr") as HTMLElement
    fireEvent.click(within(row).getByRole("button", { name: /settle/i }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Settle" }))

    // The bot's inbox, not /api/commands: the bot books it and tells the payer.
    await waitFor(() => {
      const call = fetched.mock.calls.find(([url]) => url === "/api/access/settle")
      expect(call).toBeTruthy()
    })
    const call = fetched.mock.calls.find(([url]) => url === "/api/access/settle")!
    expect(JSON.parse(String((call[1] as RequestInit).body))).toEqual({ reference: "AB12CD" })
    await waitFor(() =>
      expect(fetched.mock.calls.some(([url]) => url === "/api/access/requests/a-settle")).toBe(true),
    )
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
      }),
    )
    draw(<PaymentsPage />)

    const tab = await screen.findByRole("link", { name: /tab/i })
    const actions = tab.parentElement as HTMLElement
    expect(actions.className).not.toMatch(/flex-wrap/)
  })
})

describe("AccessPage - unlink as a row action", () => {
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

  it("offers Unlink on a linked person", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const actions = await openActions("Ally")
    expect(within(actions).getByRole("button", { name: /unlink/i })).toBeTruthy()
  })

  it("sends that person's own Discord id, with no picker to get wrong", async () => {
    const fetched = backend()
    vi.stubGlobal("fetch", fetched)
    draw(<AccessPage />)

    const actions = await openActions("Ally")
    fireEvent.click(within(actions).getByRole("button", { name: /unlink/i }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Unlink" }))

    await waitFor(() => {
      const call = fetched.mock.calls.find(([url]) => url === "/api/access/unlink")
      expect(call).toBeTruthy()
    })
    const call = fetched.mock.calls.find(([url]) => url === "/api/access/unlink")!
    expect(JSON.parse(String((call[1] as RequestInit).body))).toEqual({
      discordId: "214906139328839681",
    })
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
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    // bob has no minecraftUuid, and the row's own state is the only thing that hides the button.
    expect(await actionsOf("bob")).not.toContain("Unlink")
    expect(await actionsOf("Ally")).toContain("Unlink")
  })

  it("offers no Periods to somebody who has never had one", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    // `accessUntil` absent means no period was ever written - the dialog would open on nothing.
    expect(await actionsOf("bob")).not.toContain("Periods")
    expect(await actionsOf("Ally")).toContain("Periods")
  })

  it("offers no Revoke where there is nothing running to take away", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    expect(await actionsOf("bob")).not.toContain("Revoke")
    expect(await actionsOf("Ally")).toContain("Revoke")
  })

  it("always offers Grant, because more access can always be given", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    expect(await actionsOf("bob")).toContain("Grant")
    expect(await actionsOf("Ally")).toContain("Grant")
  })

  it("puts a row's actions behind one popover as soon as there are more than two", async () => {
    vi.stubGlobal("fetch", backend())
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

/**
 * Every access change is asked of the bot (an `access_request` row), because only the bot can
 * apply the role, send the direct message and post the admin note. Writing the tables from here
 * skipped all three, and nobody granted access from a browser was ever told.
 */
describe("AccessPage - access changes are asked of the bot", () => {
  async function openGrant() {
    fireEvent.click(await screen.findByRole("button", { name: "Grant access" }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.change(within(dialog).getByLabelText("Discord-ID"), {
      target: { value: "214906139328839681" },
    })
    return dialog
  }

  it("refuses a grant longer than 365 days before it is sent", async () => {
    const fetched = backend()
    vi.stubGlobal("fetch", fetched)
    draw(<AccessPage />)

    const dialog = await openGrant()
    fireEvent.change(within(dialog).getByLabelText("Days"), { target: { value: "366" } })
    expect(
      (within(dialog).getByRole("button", { name: "Grant" }) as HTMLButtonElement).disabled,
    ).toBe(true)
    fireEvent.change(within(dialog).getByLabelText("Days"), { target: { value: "365" } })
    expect(
      (within(dialog).getByRole("button", { name: "Grant" }) as HTMLButtonElement).disabled,
    ).toBe(false)
  })

  it("sends the grant to the bot's inbox and waits for its answer", async () => {
    const fetched = backend({
      answer: (kind) => ({
        id: "a-grant",
        kind,
        status: "DONE",
        result: { until: "2026-10-25T00:00:00Z" },
      }),
    })
    vi.stubGlobal("fetch", fetched)
    const success = vi.spyOn(toast, "success")
    draw(<AccessPage />)

    const dialog = await openGrant()
    fireEvent.click(within(dialog).getByRole("button", { name: "Grant" }))

    await waitFor(() => expect(success).toHaveBeenCalled())
    const call = fetched.mock.calls.find(([url]) => url === "/api/access/grant")!
    expect(JSON.parse(String((call[1] as RequestInit).body))).toEqual({
      discordId: "214906139328839681",
      days: 30,
    })
    expect(fetched.mock.calls.some(([url]) => url === "/api/access/requests/a-grant")).toBe(true)
    expect(fetched.mock.calls.some(([url]) => String(url).startsWith("/api/commands"))).toBe(false)
    success.mockRestore()
  })

  it("says what the bot said when it could not carry the change out", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        answer: (kind) => ({
          id: "a-grant",
          kind,
          status: "FAILED",
          result: { error: "the role could not be applied" },
        }),
      }),
    )
    const failure = vi.spyOn(toast, "error")
    draw(<AccessPage />)

    const dialog = await openGrant()
    fireEvent.click(within(dialog).getByRole("button", { name: "Grant" }))

    await waitFor(() => expect(failure).toHaveBeenCalled())
    expect(String(failure.mock.calls[0][1]?.description)).toContain(
      "the role could not be applied",
    )
    failure.mockRestore()
  })

  it("says nothing changed when the bot never picked the request up", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ answer: (kind) => ({ id: "a-grant", kind, status: "EXPIRED" }) }),
    )
    const failure = vi.spyOn(toast, "error")
    draw(<AccessPage />)

    const dialog = await openGrant()
    fireEvent.click(within(dialog).getByRole("button", { name: "Grant" }))

    await waitFor(() => expect(failure).toHaveBeenCalled())
    expect(String(failure.mock.calls[0][1]?.description)).toContain("Nothing was changed")
    failure.mockRestore()
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
    expect(await screen.findByText("no Discord name on record")).toBeTruthy()
    // Still not the number: the id lives in the popover, next to a button that copies it.
    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })

  it("draws a service it concerns as a link to that service, and an unknown actor as unknown", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        journal: () => [
          {
            id: "j3",
            occurred: "2026-09-18T09:00:00Z",
            action: "FORGET_FACTORS",
            actor: "host",
            subject: "smp",
          },
        ],
      }),
    )
    draw(<JournalPage />)

    const link = await screen.findByRole("link", { name: "smp" })
    expect(link.getAttribute("href")).toBe("/services/smp")
    expect(screen.getByText("host").closest("[data-entity='unknown']")).toBeTruthy()
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

/**
 * Admins are granted and revoked on this page. The badge says who granted whom; "Make admin" is
 * offered on a member of the guild who is none yet, and "Revoke admin" only on an admin strictly
 * below the one signed in - the server refuses every other revocation, so the button is not drawn.
 */
describe("AccessPage - the admin tree", () => {
  const TREE = [
    person({ discordId: ME, discordUsername: "me", admin: true, adminGrantedBy: null }),
    person({ discordId: "510000000000000001", discordUsername: "mine", admin: true, adminGrantedBy: ME }),
    person({
      discordId: "510000000000000002",
      discordUsername: "theirs",
      admin: true,
      adminGrantedBy: "510000000000000001",
    }),
    person({ discordId: "510000000000000003", discordUsername: "plain" }),
    person({ discordId: "510000000000000004", discordUsername: "gone", memberState: "LEFT" }),
  ]

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

  it("offers Make admin on a member who is none, and never on somebody who left", async () => {
    vi.stubGlobal("fetch", backend({ people: () => TREE }))
    draw(<AccessPage />)
    expect(await actionsOf("plain")).toContain("Make admin")
    cleanup()
    draw(<AccessPage />)
    expect(await actionsOf("gone")).not.toContain("Make admin")
  })

  it("offers Revoke admin below the one signed in, the whole way down", async () => {
    vi.stubGlobal("fetch", backend({ people: () => TREE }))
    draw(<AccessPage />)
    await waitFor(async () => expect(await actionsOf("theirs")).toContain("Revoke admin"))
  })

  it("never offers Revoke admin on the one signed in", async () => {
    vi.stubGlobal("fetch", backend({ people: () => TREE }))
    draw(<AccessPage />)
    await screen.findByText("mine")
    expect(await actionsOf("me")).not.toContain("Revoke admin")
  })

  it("sends the row's own Discord id to the grant", async () => {
    const fetched = backend({ people: () => TREE })
    vi.stubGlobal("fetch", fetched)
    draw(<AccessPage />)

    const row = (await screen.findByText("plain")).closest("tr") as HTMLElement
    const trigger = within(row).queryByRole("button", { name: /^Actions for/ })
    const scope = trigger ? (fireEvent.click(trigger), await screen.findByRole("dialog")) : row
    fireEvent.click(within(scope as HTMLElement).getByRole("button", { name: /make admin/i }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Make admin" }))

    await waitFor(() => {
      expect(fetched.mock.calls.find(([url]) => url === "/api/admins/grant")).toBeTruthy()
    })
    const call = fetched.mock.calls.find(([url]) => url === "/api/admins/grant")!
    expect(JSON.parse(String((call[1] as RequestInit).body))).toEqual({
      discordId: "510000000000000003",
    })
  })
})
