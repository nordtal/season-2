import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { SeasonPage } from "@/pages/season"
import { TooltipProvider } from "@/components/ui/tooltip"
import { asButton, asElement, asInput } from "@/lib/test-elements"

/** The phase change dialog, and that a reason typed then cancelled never reaches the journal with the next change. */

// The page reads its open group from the URL; outside a router there is none.
vi.mock("@tanstack/react-router", () => ({ useNavigate: () => vi.fn<() => void>(), useSearch: () => ({}) }))

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

const SEASON = {
  phase: "PRE_EVENT",
  launch: "2026-10-01T18:00:00Z",
  smpStart: "2026-10-08T18:00:00Z",
}

/** One group of the network's and one of a service's, of which the page shows the first. */
const GROUPS = [
  {
    service: "network",
    name: "players",
    path: "network/players",
    label: "",
    live: true,
    readable: true,
    writable: true,
  },
  { service: "steward", name: "web", path: "steward/web", label: "", live: true, readable: true, writable: true },
]

/** The page's own routes and nothing else. */
function backend(over: { phase?: () => { status: number; body: unknown } } = {}) {
  return vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
    if (url === "/api/season/phase" && init?.method === "POST") {
      const answer = over.phase?.() ?? { status: 200, body: SEASON }
      return json(answer.status, answer.body)
    }
    if (url === "/api/season") return json(200, SEASON)
    if (url === "/api/setting-groups") return json(200, GROUPS)
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

/**
 * Presses "Switch" on one phase's row, disabled until `/api/season` answers.
 *
 * Every lookup repeats inside the `waitFor`, since a node captured before the answer may be detached.
 */
async function ask(phase: string): Promise<HTMLElement> {
  const live = () => {
    const card = screen.getByText(phase).closest("div.rounded-md")
    if (!card) throw new Error(`the row of ${phase} is not shaped the way this test assumed`)
    return asButton(within(asElement(card)).getByRole("button", { name: "Switch" }))
  }
  await waitFor(() => expect(live().disabled).toBe(false))
  fireEvent.click(live())
  return screen.findByRole("alertdialog")
}

const reasonField = () => asInput(screen.getByLabelText("Reason"))

/** What the page sent, as the backend would have read it. */
function sentPhaseChange(fetched: ReturnType<typeof backend>) {
  const call = fetched.mock.calls.find(([url, init]) => url === "/api/season/phase" && init?.method === "POST")
  if (!call) throw new Error("no phase change was sent at all")
  return JSON.parse(call[1]?.body ?? "")
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("SeasonPage - the reason that was typed and abandoned", () => {
  it("is gone from the field when the dialog is cancelled", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<SeasonPage />)

    await ask("PRE_LAUNCH")
    fireEvent.change(reasonField(), { target: { value: "No - wrong phase after all" } })
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }))

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    await ask("MAINTENANCE")
    expect(reasonField().value).toBe("")
  })

  it("is gone when the dialog is dismissed with Escape rather than with the button", async () => {
    /** Escape and an overlay click leave through `onOpenChange` too, which is what clears the reason. */
    vi.stubGlobal("fetch", backend())
    draw(<SeasonPage />)

    const dialog = await ask("PRE_LAUNCH")
    fireEvent.change(reasonField(), { target: { value: "Typo" } })
    fireEvent.keyDown(dialog, { key: "Escape", code: "Escape" })

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    await ask("MAINTENANCE")
    expect(reasonField().value).toBe("")
  })

  it("is not what the next phase change sends, which is the whole point", async () => {
    /** A journal entry against MAINTENANCE must not carry a sentence typed about PRE_LAUNCH. */
    const fetched = backend()
    vi.stubGlobal("fetch", fetched)
    draw(<SeasonPage />)

    await ask("PRE_LAUNCH")
    fireEvent.change(reasonField(), { target: { value: "No - wrong phase after all" } })
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }))
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())

    const second = await ask("MAINTENANCE")
    fireEvent.click(within(second).getByRole("button", { name: "Switch" }))

    await waitFor(() => expect(() => sentPhaseChange(fetched)).not.toThrow())
    expect(sentPhaseChange(fetched)).toEqual({ phase: "MAINTENANCE", reason: "" })
  })

  it("is sent when it was meant, so that the clearing is not simply a broken field", async () => {
    /** The other direction, so a field that never works does not pass. */
    const fetched = backend()
    vi.stubGlobal("fetch", fetched)
    draw(<SeasonPage />)

    const dialog = await ask("MAINTENANCE")
    fireEvent.change(reasonField(), { target: { value: "Postgres is being moved" } })
    fireEvent.click(within(dialog).getByRole("button", { name: "Switch" }))

    await waitFor(() => expect(() => sentPhaseChange(fetched)).not.toThrow())
    expect(sentPhaseChange(fetched)).toEqual({ phase: "MAINTENANCE", reason: "Postgres is being moved" })
  })

  it("is gone after a switch that went through", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<SeasonPage />)

    const dialog = await ask("MAINTENANCE")
    fireEvent.change(reasonField(), { target: { value: "Postgres is being moved" } })
    fireEvent.click(within(dialog).getByRole("button", { name: "Switch" }))

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    await ask("PRE_LAUNCH")
    expect(reasonField().value).toBe("")
  })

  it("asks before it switches at all, and the question names the admission rule", async () => {
    /** The dialog is the last place to say whether players can still log in before the door changes. */
    vi.stubGlobal("fetch", backend())
    draw(<SeasonPage />)

    const dialog = await ask("PRE_LAUNCH")

    expect(dialog.textContent).toContain("Admins only")
    expect(dialog.textContent).toContain("from the next")
  })
})

/**
 * The alerts in a scope carrying the refusal under test.
 *
 * `hidden: true` since Radix hides all outside the dialog; the page's own standing alert is filtered out.
 */
function refusals(scope: { queryAllByRole: typeof screen.queryAllByRole }) {
  return scope
    .queryAllByRole("alert", { hidden: true })
    .filter((one) => one.textContent?.includes("The database is not answering."))
}

describe("SeasonPage - a switch the backend refuses", () => {
  it("keeps the dialog open, because the phase has not changed", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ phase: () => ({ status: 503, body: { error: "The database is not answering." } }) }),
    )
    draw(<SeasonPage />)

    const dialog = await ask("MAINTENANCE")
    fireEvent.click(within(dialog).getByRole("button", { name: "Switch" }))

    await waitFor(() => expect(within(dialog).getByRole("button", { name: "Switch" })).toBeTruthy())
    expect(screen.queryByRole("alertdialog")).not.toBeNull()
  })

  it("shows the operator why, inside the dialog they are looking at", async () => {
    // The refusal is repeated inside the dialog, since the open dialog covers the card's copy.
    vi.stubGlobal(
      "fetch",
      backend({ phase: () => ({ status: 503, body: { error: "The database is not answering." } }) }),
    )
    draw(<SeasonPage />)

    const dialog = await ask("MAINTENANCE")
    fireEvent.click(within(dialog).getByRole("button", { name: "Switch" }))

    /** Rendered in the card underneath, which the overlay covers. */
    await waitFor(() => expect(refusals(screen)).toHaveLength(1))

    // It must also be in the dialog the operator is looking at.
    expect(refusals(within(dialog))).toHaveLength(1)
  })
})

/** The date routes on top of the page's own, recording what was asked. */
function dates(answer: { status: number; body: unknown } = { status: 200, body: {} }) {
  const base = backend()
  const sent: unknown[] = []
  const fetched = vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(
    async (url, init) => {
      if (url === "/api/season/date" && init?.method === "POST") {
        sent.push(JSON.parse(init.body ?? ""))
        return json(answer.status, answer.body)
      }
      return base(url, init)
    },
  )
  vi.stubGlobal("fetch", fetched)
  return sent
}

/** Clicks Remove on the date row for `field` and returns the confirmation dialog it opens. */
async function remove(field: string) {
  const row = asElement((await screen.findByLabelText(field)).closest("div.flex-wrap"))
  const button = await within(row).findByRole("button", { name: "Remove" })
  fireEvent.click(button)
  return screen.findByRole("alertdialog")
}

describe("SeasonPage - a date can be removed again", () => {
  it("asks first, and sends null for the date it names", async () => {
    const sent = dates()
    draw(<SeasonPage />)

    const dialog = await remove("SMP launch")
    expect(sent).toHaveLength(0)
    expect(dialog.textContent).toContain("not an undo")
    fireEvent.click(within(dialog).getByRole("button", { name: "Remove" }))

    await waitFor(() => expect(sent).toEqual([{ which: "smpStart", at: null }]))
  })

  it("says the launch is harmless, and cancelling sends nothing", async () => {
    const sent = dates()
    draw(<SeasonPage />)

    const dialog = await remove("Network launch")
    expect(dialog.textContent).toContain("Nothing else moves")
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }))

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    expect(sent).toHaveLength(0)
  })

  it("never offers Remove and Reset side by side", async () => {
    dates()
    draw(<SeasonPage />)

    const input = asInput(await screen.findByLabelText("Network launch"))
    const row = asElement(input.closest("div.flex-wrap"))
    await within(row).findByRole("button", { name: "Remove" })

    fireEvent.change(input, { target: { value: "2026-11-01T18:00" } })
    expect(within(row).queryByRole("button", { name: "Remove" })).toBeNull()
    expect(within(row).getByRole("button", { name: "Reset" })).toBeTruthy()
  })

  it("keeps the dialog open with the reason when the backend refuses", async () => {
    dates({ status: 400, body: { error: "The season is already in SMP." } })
    draw(<SeasonPage />)

    const dialog = await remove("SMP launch")
    fireEvent.click(within(dialog).getByRole("button", { name: "Remove" }))

    await waitFor(() => expect(within(dialog).getByText(/already in SMP/)).toBeTruthy())
    expect(screen.queryByRole("alertdialog")).not.toBeNull()
  })
})

describe("SeasonPage - the network's settings", () => {
  it("lists the network's own groups and no service's", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<SeasonPage />)

    expect(await screen.findByText("Players")).not.toBeNull()
    expect(screen.queryByText("Web")).toBeNull()
  })
})
