import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { OperationsPage } from "@/pages/operations"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * steward/112: `/operations` used to carry its own copy of the archive list (`BackupsCard`) beside
 * the one `/operations/backups` (steward/95) now draws with volumes, retention and the remote
 * target on top - two copies of the same list is one copy that goes quietly stale. This file proves
 * the old copy is gone and a way to the new page stands in its place.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function backend(): typeof fetch {
  return vi.fn(async (url: string) => {
    if (url.startsWith("/api/updates")) return json(200, [])
    if (url === "/api/host") return json(200, { load1: 0.2, cpus: 4 })
    if (url === "/api/services") {
      return json(200, {
        services: [],
        drift: { checkedAt: new Date().toISOString(), reached: true, unverifiable: [] },
      })
    }
    if (url === "/api/backups") {
      return json(200, [
        {
          name: "nordtal-s2_mc-smp-20260912T044500Z.tar.zst",
          bytes: 1_500_000_000,
          human: "1.5 GB",
          modified: new Date().toISOString(),
          partial: false,
        },
      ])
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

describe("OperationsPage - the archive list moved to its own page (steward/112)", () => {
  it("no longer asks the backups endpoint on its own - that is /operations/backups's job now", async () => {
    const fetchMock = backend()
    vi.stubGlobal("fetch", fetchMock)
    draw()

    // Every query this page owns fires at mount, so once one of them has resolved onto the
    // screen, a query it does NOT own has already been asked for too, if it was going to be.
    await screen.findByText("No run yet")
    const asked = (fetchMock as unknown as { mock: { calls: unknown[][] } }).mock.calls.map(
      (call) => call[0],
    )
    expect(asked).not.toContain("/api/backups")
  })

  it("links to /operations/backups instead", async () => {
    vi.stubGlobal("fetch", backend())
    draw()

    await screen.findByText("No run yet")
    const link = screen.getByRole("link", { name: /backups/i })
    expect(link.getAttribute("href")).toBe("/operations/backups")
  })
})
