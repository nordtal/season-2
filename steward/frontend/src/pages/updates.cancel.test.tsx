import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import type { Mock } from "vitest"
import { toast } from "sonner"

import { UpdatesPage } from "@/pages/updates"
import type { Run } from "@/lib/api"
import { urlOf } from "@/lib/query-fixtures"
import { TooltipProvider } from "@/components/ui/tooltip"

/** Asserts the sentence handed to sonner, since `<Toaster />` lives in the shell and this test renders one page. */
vi.mock("sonner", () => ({
  toast: {
    success: vi.fn<(message: string) => void>(),
    error: vi.fn<(message: string) => void>(),
    info: vi.fn<(message: string) => void>(),
  },
}))

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

/** A row as `/api/updates` answers with one; the caller says which run it is. */
function run(over: Partial<Run> = {}): Run {
  return {
    id: 79,
    kind: "RESTART",
    status: "PENDING",
    actorKind: "HOST",
    actorId: "",
    scope: [],
    requested: new Date().toISOString(),
    // A minute ahead: the countdown steward has not picked up yet.
    scheduledFor: new Date(Date.now() + 60_000).toISOString(),
    countdownEnd: "null",
    moving: [],
    started: "",
    finished: "",
    ...over,
  }
}

function backend(runs: Run[], onCancel?: () => Response): Mock<typeof fetch> {
  const impl: typeof fetch = async (input, init) => {
    const url = urlOf(input)
    if (url === "/api/updates/cancel" && init?.method === "POST") {
      const [first] = runs
      return onCancel ? onCancel() : json(200, { ...first, status: "CANCELLED" })
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
  }
  return vi.fn<typeof fetch>(impl)
}

/** A route this test never draws, only routes to. */
const nothing = () => null

function draw() {
  const root = createRootRoute()
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
        <RouterProvider router={router} />
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

/** On the row, since a button coming and going in the header would move the others on a phone. */
describe("the Cancel is on the row, and only while there is something to cancel", () => {
  it("is there for a countdown that has not run out", async () => {
    vi.stubGlobal("fetch", backend([run()]))
    draw()

    await screen.findByText("#79")
    expect(cancelButtons()).toHaveLength(1)
  })

  it("is there for the row entered for tonight", async () => {
    const tonight = new Date(Date.now() + 6 * 3600_000).toISOString()
    vi.stubGlobal("fetch", backend([run({ scheduledFor: tonight })]))
    draw()

    await screen.findByText("#79")
    expect(cancelButtons()).toHaveLength(1)
  })

  it("is gone once the run is actually under way", async () => {
    /** Past `countdownEnd` steward holds the lock and would answer "too late", so there is nothing to press. */
    vi.stubGlobal(
      "fetch",
      backend([run({ status: "RUNNING", countdownEnd: new Date(Date.now() - 1000).toISOString() })]),
    )
    draw()

    await screen.findByText("#79")
    expect(cancelButtons()).toHaveLength(0)
  })

  it("is on no finished row, however many of them there are", async () => {
    vi.stubGlobal(
      "fetch",
      backend([
        run({ id: 78, status: "DONE", scheduledFor: new Date(Date.now() - 3600_000).toISOString() }),
        run({ id: 77, status: "FAILED", scheduledFor: new Date(Date.now() - 7200_000).toISOString() }),
        run({ id: 76, status: "CANCELLED" }),
      ]),
    )
    draw()

    await screen.findByText("#78")
    expect(cancelButtons()).toHaveLength(0)
  })
})

/** No dialog first, since a confirmation in front of an undo is a countdown running out while it is read. */
describe("pressing it asks the backend at once", () => {
  it("sends POST /api/updates/cancel with no dialog in between", async () => {
    const fetchMock = backend([run()])
    vi.stubGlobal("fetch", fetchMock)
    draw()

    await screen.findByText("#79")
    fireEvent.click(cancelButtons()[0])

    await waitFor(() => {
      const cancel = fetchMock.mock.calls.find((call) => urlOf(call[0]) === "/api/updates/cancel")
      expect(cancel).toBeTruthy()
      expect(cancel?.[1]?.method).toBe("POST")
    })
  })

  it("says what the backend said when the countdown ran out first", async () => {
    /** A 409 means the row was claimed between tap and request, and the sentence must say so. */
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
