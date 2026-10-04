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

export async function rowFor(name: string): Promise<HTMLElement> {
  return assertElement((await screen.findByText(name)).closest("tr"), `a <tr> for ${name}`)
}

/** Reads a toast's description back as a string, since sonner types it as arbitrary React content. */
export function toastDescription(description: unknown): string {
  return typeof description === "string" ? description : ""
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

export async function openGrant() {
  fireEvent.click(await screen.findByRole("button", { name: "Grant access" }))
  const dialog = await screen.findByRole("alertdialog")
  fireEvent.change(within(dialog).getByLabelText("Discord-ID"), {
    target: { value: "214906139328839681" },
  })
  return dialog
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

/** Who is signed in, in every test here: the root of the admin tree below. */
export const ME = "500000000000000000"

export function backend(
  over: {
    people?: () => Record<string, unknown>[]
    payments?: () => Record<string, unknown>[]
    journal?: () => Record<string, unknown>[]
    playtimePost?: (url: string, body: unknown) => { status: number; body: unknown }
    /** What the bot answered, by kind; DONE with an empty result unless a test says otherwise. */
    answer?: (kind: string) => Record<string, unknown>
  } = {},
) {
  const asked = new Map<string, string>()
  return vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(async (url, init) => {
    if (url.endsWith("/playtime") && init?.method === "POST") {
      const answer = over.playtimePost?.(url, requestBody(init)) ?? {
        status: 202,
        body: { id: "a-playtime", kind: "SET_PLAYTIME", status: "PENDING" },
      }
      asked.set("a-playtime", "SET_PLAYTIME")
      return json(answer.status, answer.body)
    }
    if (url === "/api/access/settle") {
      // steward books it itself and answers at once; the bot only reacts.
      return json(200, { outcome: "BOOKED", days: 30, until: "2026-10-01T00:00:00Z" })
    }
    if (url.startsWith("/api/access/") && !url.startsWith("/api/access/requests/")) {
      // Every other write is a request in the bot's inbox, which the bot carries out: 202 and an id to poll.
      const kind = url.split("/").pop()!.toUpperCase()
      const id = `a-${kind.toLowerCase()}`
      asked.set(id, kind)
      return json(202, { id, kind, status: "PENDING" })
    }
    if (url.startsWith("/api/access/requests/")) {
      const id = url.split("/").pop()!
      const kind = asked.get(id) ?? "UNKNOWN"
      return json(200, over.answer?.(kind) ?? { id, kind, status: "DONE", result: {} })
    }
    if (url.startsWith("/api/pack-exemptions/") && init?.method === "POST") {
      return json(200, { outcome: "CHANGED" })
    }
    if (url.startsWith("/api/admins/") && init?.method === "POST") {
      return json(200, { outcome: url.endsWith("/grant") ? "GRANTED" : "REVOKED", removed: [] })
    }
    if (url === "/api/me") return json(200, { signedIn: true, id: ME })
    if (url === "/api/people") return json(200, over.people ? over.people() : PEOPLE)
    if (url === "/api/topology") return json(200, NETWORK_MAP)
    if (url === "/api/payments") return json(200, over.payments ? over.payments() : [])
    if (url.startsWith("/api/journal")) return json(200, over.journal ? over.journal() : [])
    if (url === "/api/settings") {
      return json(200, { greenDays: 3, yellowDays: 7, minecraftHeadBaseUrl: "https://crafatar.com/avatars" })
    }
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  })
}

export function draw(node: ReactNode) {
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

/** Clicks one of a row's actions, opening its popover first when it has one. */
export async function clickRowAction(name: RegExp) {
  const trigger = await screen.findByRole("button", { name: /^Actions for/ })
  fireEvent.click(trigger)
  const popover = await screen.findByRole("dialog")
  fireEvent.click(within(popover).getByRole("button", { name }))
}
