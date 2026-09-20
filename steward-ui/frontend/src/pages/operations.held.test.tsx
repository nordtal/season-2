import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { Service } from "@/lib/api"
import { OperationsPage } from "@/pages/operations"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * One way back up for everything that was put down (steward/134).
 *
 * `/update start` without an argument was built for the case of "not knowing after a restart which
 * ones you held", and in Steward the only way back was the service page of each one in turn. The
 * button below sends exactly that request - `kind: "START"` with no `services` - and it appears
 * only when there is more than one hold, because for a single one the service's own page is the
 * shorter way and a button that is always there is one nobody reads.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function service(over: Partial<Service> = {}): Service {
  return {
    service: "smp",
    containerId: "abc123",
    image: "ghcr.io/nordtal/smp:0.9.4",
    state: "running",
    status: "Up 3 hours (healthy)",
    hasConsole: true,
    drift: "UP_TO_DATE",
    health: "healthy",
    ...over,
  }
}

const HELD = { since: "2026-09-20T18:00:00Z", by: "hmtill" }

function down(name: string, hold?: Service["hold"]): Service {
  return service({
    service: name,
    state: "exited",
    status: "Exited (0) 5 minutes ago",
    health: undefined,
    hold,
  })
}

function backend(services: Service[]): typeof fetch {
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/updates" && init?.method === "POST") {
      return json(200, { id: 91, kind: "START", status: "PENDING", notBefore: "2026-09-20T18:00:00Z" })
    }
    if (url.startsWith("/api/updates")) return json(200, [])
    if (url === "/api/host") return json(200, { load1: 0.2, cpus: 4 })
    if (url === "/api/services") {
      return json(200, {
        services,
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
    createRoute({ getParentRoute: () => root, path: "/operations", component: OperationsPage }),
    createRoute({ getParentRoute: () => root, path: "/operations/backups", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/plan", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/services/$name", component: nothing }),
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ["/operations"] }),
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

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("starting every held service at once", () => {
  it("offers the button once more than one service is held", async () => {
    vi.stubGlobal("fetch", backend([down("smp", HELD), down("hunger-games", HELD), service()]))
    draw()

    expect(await screen.findByRole("button", { name: /start held/i })).toBeTruthy()
  })

  it("does not offer it for a single hold, which the service's own page already covers", async () => {
    vi.stubGlobal("fetch", backend([down("smp", HELD), service({ service: "proxy" })]))
    draw()

    // Waiting for the table the same answer fills, so "not there" is read after the query landed.
    expect(await screen.findByRole("link", { name: "proxy" })).toBeTruthy()
    expect(screen.queryByRole("button", { name: /start held/i })).toBeNull()
  })

  it("does not offer it for a service that is merely stopped", async () => {
    // A crash is not a hold, and starting "everything held" would not bring it back either.
    vi.stubGlobal("fetch", backend([down("smp"), down("hunger-games"), service({ service: "proxy" })]))
    draw()

    expect(await screen.findByRole("link", { name: "proxy" })).toBeTruthy()
    expect(screen.queryByRole("button", { name: /start held/i })).toBeNull()
  })

  it("asks for a START with no scope at all, which is what lifts every hold", async () => {
    // The shape is the ticket's own: `kind: "START"` and no `services`. A list of names assembled
    // in the browser would be a second opinion about what is held - and it would miss a hold this
    // table's answer is too old to know about.
    const fetchMock = backend([down("smp", HELD), down("hunger-games", HELD), service()])
    vi.stubGlobal("fetch", fetchMock)
    draw()

    fireEvent.click(await screen.findByRole("button", { name: /start held/i }))
    fireEvent.click(await screen.findByRole("button", { name: "Now" }))

    await waitFor(() => {
      const post = (fetchMock as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls
        .find(([url, init]) => url === "/api/updates" && init?.method === "POST")
      expect(post, "nothing was posted to /api/updates").toBeTruthy()
      const body = JSON.parse(String(post![1].body))
      expect(body.kind).toBe("START")
      expect(body.services).toBeUndefined()
    })
  })
})
