import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { fireEvent, render, screen, within } from "@testing-library/react"
import { vi } from "vitest"

import { TooltipProvider } from "@/components/ui/tooltip"
import { NETWORK_MAP } from "@/lib/query-fixtures"

/** The backend, the roster and the helpers the Users, Payments and Journal page tests share. */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

/** Parses a mocked fetch call's JSON body, or undefined when it carried none. */
export function requestBody(init?: RequestInit): unknown {
  return typeof init?.body === "string" ? JSON.parse(init.body) : undefined
}

export function assertElement(value: Element | null, what: string): HTMLElement {
  if (!(value instanceof HTMLElement)) throw new Error(`expected ${what}`)
  return value
}

/** A row by a name it shows, which a row draws twice: once for a desktop's table and once for a phone's card. */
export async function rowFor(name: string): Promise<HTMLElement> {
  const [first] = await screen.findAllByText(name)
  return assertElement(first?.closest("tr") ?? null, `a <tr> for ${name}`)
}

/** The first cell of a row: the person, their badges and, on a phone, last seen. */
export function badges(row: HTMLElement): HTMLElement {
  return assertElement(row.querySelector(":scope > td:first-child"), "the person and their badges")
}

/** Opens a row's actions popover when it has one, behind a button named "Actions for …". */
export async function openActions(name: string): Promise<HTMLElement> {
  const row = await rowFor(name)
  const trigger = within(row).queryByRole("button", { name: /^Actions for/ })
  if (!trigger) return row
  fireEvent.click(trigger)
  return await screen.findByRole("dialog")
}

/** Every control offered against one person, whether it is inline or inside the popover. */
export async function actionsOf(name: string): Promise<string[]> {
  const row = await rowFor(name)
  const trigger = within(row).queryByRole("button", { name: /^Actions for/ })
  const scope = trigger ? (fireEvent.click(trigger), await screen.findByRole("dialog")) : row
  return within(scope)
    .queryAllByRole("button")
    .map((button) => (button.textContent ?? "").trim())
    .filter((label) => label !== "")
}

/** Opens the grant for one person from their row's actions. */
export async function openGrant(name = "Ally") {
  const actions = await openActions(name)
  fireEvent.click(within(actions).getByRole("button", { name: "Grant access" }))
  return await screen.findByRole("alertdialog")
}

export function person(over: Partial<Record<string, unknown>>): Record<string, unknown> {
  return {
    discordId: "100000000000000001",
    memberState: "MEMBER",
    donor: false,
    admin: false,
    locale: "en",
    updated: "2026-09-01T00:00:00Z",
    accessActive: false,
    aura: 0,
    spins: 0,
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
export function manyMatches(): Record<string, unknown>[] {
  return Array.from({ length: 21 }, (_, index) =>
    person({
      discordId: `9${String(index).padStart(17, "0")}`,
      discordUsername: `searchable-${index}`,
      discordUsernameUpdated: "2026-09-10T00:00:00Z",
    }),
  )
}

/** One page of the journal's stream: each entry as its item, after the given items. */
export function journalPage(entries: Record<string, unknown>[], items: Record<string, unknown>[] = []) {
  return {
    items: [
      ...items,
      ...entries.map((entry) => ({ key: `e${String(entry.id)}`, occurred: entry.occurred, actor: entry.actor, entry })),
    ],
    terms: [],
  }
}

/** Who is signed in, in every test here: the root of the admin tree below. */
export const ME = "500000000000000000"

export function backend(
  over: {
    people?: () => Record<string, unknown>[]
    payments?: () => Record<string, unknown>[]
    /** Journal entries, newest first, which the backend answers as one page of the stream. */
    journal?: () => Record<string, unknown>[]
    /** The stream's items as they are, for alerts and runs. */
    stream?: () => Record<string, unknown>[]
    /** One person's own requests and periods, which their page asks for by their Discord id. */
    personPayments?: (discordId: string) => Record<string, unknown>[]
    grants?: (discordId: string) => Record<string, unknown>[]
    playtimePost?: (url: string, body: unknown) => { status: number; body: unknown }
    /** An aura or spins set, which the SMP carries out; DONE when polled unless a test says otherwise. */
    smpPost?: (url: string, body: unknown) => void
    smpRun?: () => Record<string, unknown>
    /** A person's inventories; none stored unless a test says otherwise. */
    inventories?: (discordId: string) => Record<string, unknown>
    /** A stack removal, which Steward answers itself. */
    removeStack?: (url: string, body: unknown) => void
    /** Called with the body of an opening in the game. */
    openInventory?: (body: unknown) => void
    /** The bot's run of a request, by kind; DONE with an empty answer unless a test says otherwise. */
    answer?: (kind: string) => Record<string, unknown>
  } = {},
) {
  return vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(async (url, init) => {
    if (url.endsWith("/playtime") && init?.method === "POST") {
      const answer = over.playtimePost?.(url, requestBody(init)) ?? {
        status: 202,
        body: { id: "bot:set_playtime", status: "PENDING" },
      }
      return json(answer.status, answer.body)
    }
    if (/^\/api\/people\/\d+\/(aura|spins)$/.test(url) && init?.method === "POST") {
      over.smpPost?.(url, requestBody(init))
      return json(202, { id: "smp:1", status: "PENDING" })
    }
    if (url === "/api/commands/smp:1") return json(200, over.smpRun?.() ?? { id: "smp:1", status: "DONE" })
    if (url === "/api/access/settle") {
      // steward books it itself and answers at once; the bot only reacts.
      return json(200, { outcome: "BOOKED", days: 30, until: "2026-10-01T00:00:00Z" })
    }
    if (url.startsWith("/api/access/")) {
      // Every other write is a request in the bot's inbox, which the bot carries out: 202 and an id to poll.
      return json(202, { id: `bot:${url.split("/").pop()!}`, status: "PENDING" })
    }
    if (url.startsWith("/api/commands/bot:")) {
      const id = url.slice("/api/commands/".length)
      return json(200, over.answer?.(id.slice("bot:".length).toUpperCase()) ?? { id, status: "DONE", answer: {} })
    }
    if (url.startsWith("/api/pack-exemptions/") && init?.method === "POST") {
      return json(200, { outcome: "CHANGED" })
    }
    if (url.startsWith("/api/admins/") && init?.method === "POST") {
      return json(200, { outcome: url.endsWith("/grant") ? "GRANTED" : "REVOKED", removed: [] })
    }
    if (url === "/api/me") return json(200, { signedIn: true, id: ME })
    if (url === "/api/people") return json(200, over.people ? over.people() : PEOPLE)
    const own = /^\/api\/people\/(\d+)\/(payments|grants)$/.exec(url)
    if (own) {
      const [, discordId, what] = own
      return json(200, (what === "payments" ? over.personPayments : over.grants)?.(discordId) ?? [])
    }
    const inventories = /^\/api\/people\/(\d+)\/inventories(\/remove|\/open)?$/.exec(url)
    if (inventories) {
      const [, discordId, action] = inventories
      if (!action) return json(200, over.inventories?.(discordId) ?? { linked: true, states: [] })
      if (action === "/open") over.openInventory?.(requestBody(init))
      else over.removeStack?.(url, requestBody(init))
      return new Response(null, { status: 204 })
    }
    if (url === "/api/game-data") return json(200, { datapacks: [], registries: {}, tags: {} })
    if (url === "/api/topology") return json(200, NETWORK_MAP)
    if (url === "/api/payments") return json(200, over.payments ? over.payments() : [])
    if (url.startsWith("/api/journal")) return json(200, journalPage(over.journal?.() ?? [], over.stream?.() ?? []))
    if (url === "/api/alerts") return json(200, { checkedAt: "2026-09-18T09:30:00Z", level: "ok", alerts: [] })
    if (url === "/api/settings") {
      return json(200, { greenDays: 3, yellowDays: 7, minecraftHeadBaseUrl: "https://crafatar.com/avatars" })
    }
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  })
}

export function draw(node: ReactNode, at = "/") {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  // A router, because Entity draws a service as a link to its page and the roster a person as one to theirs.
  const root = createRootRoute({ component: () => <>{node}</> })
  const service = createRoute({ getParentRoute: () => root, path: "/services/$name" })
  const personPage = createRoute({ getParentRoute: () => root, path: "/access/$id" })
  const router = createRouter({
    routeTree: root.addChildren([service, personPage]),
    history: createMemoryHistory({ initialEntries: [at] }),
  })
  const drawn = render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RouterProvider router={router} />
      </TooltipProvider>
    </QueryClientProvider>,
  )
  return { ...drawn, router }
}

/** Clicks one of a row's actions, opening its popover first when it has one. */
export async function clickRowAction(name: RegExp) {
  const trigger = await screen.findByRole("button", { name: /^Actions for/ })
  fireEvent.click(trigger)
  const popover = await screen.findByRole("dialog")
  fireEvent.click(within(popover).getByRole("button", { name }))
}
