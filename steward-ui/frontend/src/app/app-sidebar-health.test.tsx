import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { AppSidebar } from "@/app/app-sidebar"
import { SidebarProvider } from "@/components/ui/sidebar"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The health dot on a service row in the sidebar (steward/83).
 *
 * `useServices()` is the same hook `OverviewPage` and `OperationsPage` already call - one shared
 * query, so the sidebar reads whatever that query already knows rather than opening a second one.
 * The design question the ticket asked back: whether ten green dots would be noise. The answer
 * lives with the component ({@link HealthDot} in `components/steward/status.tsx`) - a healthy row
 * draws nothing, matching the Issues tile on the start page (steward/64, folded into a tile by
 * steward/80), which is silent for "ok" too. Only a row that is not simply fine gets a mark, and
 * "not read yet" gets its own, neutral mark rather than the fine one - `health.ts`'s own rule,
 * applied one level down.
 */

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
  const nothing = () => null
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/", component: AppSidebar }),
    createRoute({ getParentRoute: () => root, path: "/services/$name", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/plan", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/runs/$id", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/backups/$id", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/restore", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/season", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/access", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/payments", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/accounts", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/journal", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/settings", component: nothing }),
  ])
  const router = createRouter({ routeTree, history: createMemoryHistory({ initialEntries: ["/"] }) })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <SidebarProvider>
          <RouterProvider router={router as never} />
        </SidebarProvider>
      </TooltipProvider>
    </QueryClientProvider>,
  )
}

/** The `<a>` for one service row, found by its visible label. */
function row(name: string): HTMLElement {
  return screen.getByText(name).closest("a") as HTMLElement
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("AppSidebar - the health dot on a service row (steward/83)", () => {
  it("marks the one unhealthy service and draws nothing on the healthy ones", async () => {
    const fetchImpl = vi.fn(async (url: string) => {
      if (url === "/api/services") {
        return json({
          services: [
            service({ service: "smp" }),
            service({ service: "limbo", state: "exited", status: "Exited (1)" }),
          ],
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
    // The rule `health.ts` already carries: "A green light on no evidence is the one thing this
    // page must not do." A query that has not answered yet must not look the same as ten healthy
    // rows - so it gets a neutral mark, on every row, until `/api/services` actually answers.
    const fetchImpl = vi.fn(() => new Promise<Response>(() => {}))
    draw(fetchImpl)

    await waitFor(() => expect(within(row("smp")).getByLabelText(/not read/i)).toBeTruthy())
    expect(within(row("limbo")).getByLabelText(/not read/i)).toBeTruthy()
  })
})
