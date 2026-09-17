import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { AccessPage, PaymentsPage } from "@/pages/access"
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
} = {}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/people") return json(200, over.people ? over.people() : PEOPLE)
    if (url === "/api/payments") return json(200, over.payments ? over.payments() : [])
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
  it("holds 21 matches over two pages of at most 20, without the second page vanishing from the search", async () => {
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

  it("offers Unlink on a person once the command is released to the web", async () => {
    vi.stubGlobal("fetch", backend({ commands: () => [UNLINK_COMMAND] }))
    draw(<AccessPage />)

    const row = (await screen.findByText("Ally")).closest("tr") as HTMLElement
    expect(within(row).getByRole("button", { name: /unlink/i })).toBeTruthy()
  })

  it("sends that person's own Discord id, with no picker to get wrong", async () => {
    const fetched = backend({ commands: () => [UNLINK_COMMAND] })
    vi.stubGlobal("fetch", fetched)
    draw(<AccessPage />)

    const row = (await screen.findByText("Ally")).closest("tr") as HTMLElement
    fireEvent.click(within(row).getByRole("button", { name: /unlink/i }))
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

    const row = (await screen.findByText("Ally")).closest("tr") as HTMLElement
    expect(within(row).queryByRole("button", { name: /unlink/i })).toBeNull()
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
