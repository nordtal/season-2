import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import { toast } from "sonner"

import { UpdatesPage } from "@/pages/updates"
import { TooltipProvider } from "@/components/ui/tooltip"

// The toasts, read rather than drawn: `<Toaster />` lives in the shell and this test renders one
// page. What is asserted below is the sentence handed to sonner, which is the part this page owns.
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }))

/**
 * The way back out of a run somebody has just started (steward/131).
 *
 * The rule itself is unit-tested in `operations.test.ts`; what this file is for is the two things
 * only the drawn page can be asked. First, that the button is on the ROW and only on the row that
 * can still be cancelled - the page carries three buttons in its header already, and a fourth one
 * that comes and goes up there would move the others on a phone. Second, that pressing it sends
 * the request straight away: every other button on this page opens a dialog first, and this one
 * must not, because a confirmation in front of an undo is a countdown running out while it is read.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

/** A row as `/api/updates` answers with one; the caller says which run it is. */
function run(over: Record<string, unknown> = {}) {
  return {
    id: 79,
    kind: "RESTART",
    status: "PENDING",
    source: "CONSOLE",
    requestedBy: "hmtill",
    actorDiscordId: "",
    actorLabel: "hmtill",
    system: false,
    requested: new Date().toISOString(),
    // A minute ahead: the countdown the worker has not picked up yet.
    notBefore: new Date(Date.now() + 60_000).toISOString(),
    started: "",
    finished: "",
    ...over,
  }
}

function backend(runs: unknown[], onCancel?: () => Response): typeof fetch {
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/updates/cancel" && init?.method === "POST") {
      return onCancel ? onCancel() : json(200, { ...(runs[0] as object), status: "CANCELLED" })
    }
    if (url.startsWith("/api/updates/available")) {
      return json(200, {
        checkedAt: new Date().toISOString(),
        resolvedAt: new Date().toISOString(),
        seasonPrerelease: false,
        hasWork: false,
        hasFailures: false,
        changes: [],
        unclaimed: [],
        notes: [],
      })
    }
    if (url.startsWith("/api/updates")) return json(200, runs)
    if (url === "/api/config") return json(200, [])
    if (url === "/api/services") {
      return json(200, {
        services: [],
        drift: { checkedAt: new Date().toISOString(), reached: true, unverifiable: [] },
      })
    }
    if (url === "/api/schedule") {
      return json(200, { nextBackupAt: null, backupAt: "04:45", zone: "UTC" })
    }
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  }) as unknown as typeof fetch
}

function draw() {
  const root = createRootRoute()
  const nothing = () => null
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/operations/updates", component: UpdatesPage }),
    createRoute({ getParentRoute: () => root, path: "/operations/updates/$id", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/services/$name", component: nothing }),
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ["/operations/updates"] }),
  })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RouterProvider router={router as never} />
      </TooltipProvider>
    </QueryClientProvider>,
  )
}

/** The Cancel on a run's row, told apart from the "Cancel" every dialog's own footer carries. */
function cancelButtons() {
  return screen.queryAllByRole("button", { name: /^cancel$/i })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("the Cancel is on the row, and only while there is something to cancel", () => {
  it("is there for a countdown that has not run out", async () => {
    vi.stubGlobal("fetch", backend([run()]))
    draw()

    await screen.findByText("#79")
    expect(cancelButtons()).toHaveLength(1)
  })

  it("is there for the row entered for tonight", async () => {
    const tonight = new Date(Date.now() + 6 * 3600_000).toISOString()
    vi.stubGlobal("fetch", backend([run({ notBefore: tonight })]))
    draw()

    await screen.findByText("#79")
    expect(cancelButtons()).toHaveLength(1)
  })

  it("is gone once the run is actually under way", async () => {
    // Same RUNNING status as a countdown; the moment has simply passed. The worker holds the lock
    // by now and its SQL would answer "too late", so there must be nothing to press.
    vi.stubGlobal("fetch", backend([run({ status: "RUNNING", notBefore: new Date(Date.now() - 1000).toISOString() })]))
    draw()

    await screen.findByText("#79")
    expect(cancelButtons()).toHaveLength(0)
  })

  it("is on no finished row, however many of them there are", async () => {
    vi.stubGlobal(
      "fetch",
      backend([
        run({ id: 78, status: "DONE", notBefore: new Date(Date.now() - 3600_000).toISOString() }),
        run({ id: 77, status: "FAILED", notBefore: new Date(Date.now() - 7200_000).toISOString() }),
        run({ id: 76, status: "CANCELLED" }),
      ]),
    )
    draw()

    await screen.findByText("#78")
    expect(cancelButtons()).toHaveLength(0)
  })
})

describe("pressing it asks the backend at once", () => {
  it("sends POST /api/updates/cancel with no dialog in between", async () => {
    const fetchMock = backend([run()])
    vi.stubGlobal("fetch", fetchMock)
    draw()

    await screen.findByText("#79")
    fireEvent.click(cancelButtons()[0])

    await waitFor(() => {
      const calls = (fetchMock as unknown as { mock: { calls: unknown[][] } }).mock.calls
      const cancel = calls.find((call) => call[0] === "/api/updates/cancel")
      expect(cancel).toBeTruthy()
      expect((cancel![1] as RequestInit).method).toBe("POST")
    })
  })

  it("says what the backend said when the countdown ran out first", async () => {
    // A 409 here is not this interface failing: the row was claimed between the tap and the
    // request. The sentence on screen has to be that, and not "the interface cannot be reached".
    const fetchMock = backend([run()], () => json(409, { title: "too late - the countdown has already run out" }))
    vi.stubGlobal("fetch", fetchMock)
    draw()

    await screen.findByText("#79")
    fireEvent.click(cancelButtons()[0])

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith(
        "Run #79 was not cancelled",
        expect.objectContaining({ description: expect.stringContaining("too late") }),
      )
    })
  })
})
