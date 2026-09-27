import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { NavList } from "@/app/app-sidebar"
import { SidebarProvider } from "@/components/ui/sidebar"
import { TooltipProvider } from "@/components/ui/tooltip"
import { asElement } from "@/lib/test-elements"

/**
 * The health dot on a service row in the sidebar.
 *
 * `useServices()` is the same hook `OverviewPage` and `OperationsPage` already call - one shared
 * query, so the sidebar reads whatever that query already knows rather than opening a second one.
 * Whether ten green dots would be noise is answered by the component ({@link HealthDot} in
 * `components/steward/status.tsx`) - a healthy row draws nothing, matching the Issues tile on the
 * start page, which is silent for "ok" too. Only a row that is not simply fine gets a mark, and
 * "not read yet" gets its own, neutral mark rather than the fine one - `health.ts`'s own rule,
 * applied one level down.
 */

/** A route this test never draws, only routes to. */
const nothing = () => null

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

function service(over: Record<string, unknown> = {}) {
  return {
    service: "smp",
    containerId: "abc",
    image: "ghcr.io/nordtal/smp:1",
    state: "running",
    status: "Up 3 hours (healthy)",
    hasConsole: true,
    drift: "UP_TO_DATE",
    health: "healthy",
    ...over,
  }
}

/**
 * The sidebar under a router that resolves every place it links to - `/services/$name` alone
 * covers all ten service rows, same as `router.tsx`'s real tree.
 */
function draw(fetchImpl: ReturnType<typeof vi.fn>) {
  vi.stubGlobal("fetch", fetchImpl)

  const root = createRootRoute()
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/", component: () => <NavList marker="text" /> }),
    createRoute({ getParentRoute: () => root, path: "/services/$name", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/updates", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/updates/$id", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/backups", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/backups/$id", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/season", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/access", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/payments", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/journal", component: nothing }),
  ])
  const router = createRouter({ routeTree, history: createMemoryHistory({ initialEntries: ["/"] }) })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <SidebarProvider>
          <RouterProvider router={router} />
        </SidebarProvider>
      </TooltipProvider>
    </QueryClientProvider>,
  )
}

/** The `<a>` for one service row, found by its visible label. */
function row(name: string): HTMLElement {
  return asElement(screen.getByText(name).closest("a"))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("NavList - the health dot on a service row", () => {
  it("marks the one unhealthy service and draws nothing on the healthy ones", async () => {
    const fetchImpl = vi.fn<(url: string) => Promise<Response>>(async (url: string) => {
      if (url === "/api/services") {
        return json({
          services: [service({ service: "smp" }), service({ service: "limbo", state: "exited", status: "Exited (1)" })],
          drift: { checkedAt: new Date().toISOString(), reached: true, unverifiable: [] },
        })
      }
      throw new Error(`the sidebar asked for ${url}, which this test did not expect`)
    })
    draw(fetchImpl)

    await waitFor(() => expect(within(row("limbo")).getByLabelText("unhealthy")).toBeTruthy())

    // Silence is the fine state: a healthy row draws no dot at all, not a green one.
    expect(within(row("smp")).queryByLabelText(/./)).toBeNull()

    // One query for all ten services, not one per row.
    const calls = fetchImpl.mock.calls.filter(([url]) => url === "/api/services")
    expect(calls.length).toBe(1)
  })

  it("never shows the fine colour before the query has answered", async () => {
    /**
     * The rule `health.ts` already carries: "A green light on no evidence is the one thing this
     * page must not do." A query that has not answered yet must not look the same as ten healthy
     * rows - so it gets a neutral mark, on every row, until `/api/services` actually answers.
     */
    const fetchImpl = vi.fn<() => Promise<Response>>(() => new Promise<Response>(() => {}))
    draw(fetchImpl)

    await waitFor(() => expect(within(row("smp")).getByLabelText(/not read/i)).toBeTruthy())
    expect(within(row("limbo")).getByLabelText(/not read/i)).toBeTruthy()
  })
})
