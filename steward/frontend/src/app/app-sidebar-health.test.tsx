import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { NavList } from "@/app/app-sidebar"
import { SidebarProvider } from "@/components/ui/sidebar"
import { TooltipProvider } from "@/components/ui/tooltip"
import { asElement } from "@/lib/test-elements"
import { NETWORK_MAP } from "@/lib/query-fixtures"

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
      if (url === "/api/topology") return json(NETWORK_MAP)
      if (url === "/api/services") {
        return json({
          services: [
            service({ service: "smp" }),
            service({ service: "limbo", state: "exited", status: "Exited (1)", alert: "down" }),
          ],
          drift: { checkedAt: new Date().toISOString(), reached: true, unverifiable: [] },
        })
      }
      throw new Error(`the sidebar asked for ${url}, which this test did not expect`)
    })
    draw(fetchImpl)

    await waitFor(() => expect(within(row("Limbo")).getByLabelText("unhealthy")).toBeTruthy())

    expect(within(row("SMP")).queryByLabelText(/./)).toBeNull()

    const calls = fetchImpl.mock.calls.filter(([url]) => url === "/api/services")
    expect(calls.length).toBe(1)
  })

  it("never shows the fine colour before the query has answered", async () => {
    const fetchImpl = vi.fn<(url: string) => Promise<Response>>((url: string) =>
      url === "/api/topology" ? Promise.resolve(json(NETWORK_MAP)) : new Promise<Response>(() => {}),
    )
    draw(fetchImpl)

    await waitFor(() => expect(within(row("SMP")).getByLabelText(/not read/i)).toBeTruthy())
    expect(within(row("Limbo")).getByLabelText(/not read/i)).toBeTruthy()
  })
})
