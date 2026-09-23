import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ServicePage, serviceSearch } from "@/pages/service"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * steward/140: every service page has the same head and up to three tabs, and a tab with nothing
 * behind it is not there. The open tab lives in the URL, so a reload stays on it.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

const row = (name: string, over: Record<string, unknown> = {}) => ({
  service: name,
  containerId: "abc123",
  image: "ghcr.io/nordtal/minecraft:latest",
  state: "running",
  status: "Up 3 hours (healthy)",
  hasConsole: name === "smp",
  drift: "NONE",
  health: "healthy",
  digests: [],
  ...over,
})

function backend(service: Record<string, unknown>): typeof fetch {
  return vi.fn(async (url: string) => {
    if (url.startsWith("/api/services/")) return json(200, service)
    if (url === "/api/config") {
      return json(200, [
        { service: "smp", name: "config.yml", path: "smp/config.yml", readable: true, writable: true },
      ])
    }
    if (url === "/api/messages") return json(200, [])
    if (url === "/api/settings") return json(200, { minecraftHeadBaseUrl: "" })
    if (url === "/api/deployer") return json(200, { available: true, reachable: true })
    if (url === "/api/schedule") return json(200, { nextBackupAt: null, backupAt: "", zone: "UTC" })
    return json(404, { error: `not stubbed: ${url}` })
  }) as unknown as typeof fetch
}

class SilentEventSource {
  static CLOSED = 2
  readyState = 0
  onmessage = null
  onerror = null
  onopen = null
  addEventListener() {}
  removeEventListener() {}
  close() {}
}

function draw(url: string) {
  const root = createRootRoute()
  const history = createMemoryHistory({ initialEntries: [url] })
  const routeTree = root.addChildren([
    createRoute({
      getParentRoute: () => root,
      path: "/services/$name",
      component: ServicePage,
      validateSearch: serviceSearch,
    }),
  ])
  const router = createRouter({ routeTree, history })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RouterProvider router={router as never} />
      </TooltipProvider>
    </QueryClientProvider>,
  )
  return router
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("ServicePage - the head and the tabs (steward/140)", () => {
  it("gives smp three tabs and its own online line", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("smp", { hasPlugins: true, players: 2 })))
    draw("/services/smp")

    expect(await screen.findByRole("tab", { name: /console/i })).toBeTruthy()
    expect(screen.getByRole("tab", { name: /settings/i })).toBeTruthy()
    expect(screen.getByRole("tab", { name: /plugins/i })).toBeTruthy()
    expect(screen.getByText("players online")).toBeTruthy()
  })

  it("gives postgres Console alone and no online line", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("postgres", { hasPlugins: false })))
    draw("/services/postgres")

    expect(await screen.findByRole("tab", { name: /console/i })).toBeTruthy()
    expect(screen.queryByRole("tab", { name: /settings/i })).toBeNull()
    expect(screen.queryByRole("tab", { name: /plugins/i })).toBeNull()
    expect(screen.queryByText(/players? online/)).toBeNull()
  })

  it("opens the tab the URL names, so a reload stays where it was", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("smp", { hasPlugins: true })))
    draw("/services/smp?tab=plugins")

    const plugins = await screen.findByRole("tab", { name: /plugins/i })
    expect(plugins.getAttribute("aria-selected")).toBe("true")
  })

  it("sends a tab the service does not have back to Console", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("postgres", { hasPlugins: false })))
    const router = draw("/services/postgres?tab=plugins")

    await waitFor(() => expect(router.state.location.search).toEqual({}))
    const console = await screen.findByRole("tab", { name: /console/i })
    expect(console.getAttribute("aria-selected")).toBe("true")
  })

  it("draws Update, Take down and Recreate, each with a symbol of its own", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("smp", { hasPlugins: true })))
    draw("/services/smp")

    const update = await screen.findByRole("button", { name: "Update" })
    const down = await screen.findByRole("button", { name: "Take down" })
    const recreate = screen.getByRole("button", { name: "Recreate" })
    const icons = [update, down, recreate].map((button) => button.querySelector("svg")?.innerHTML)
    expect(new Set(icons).size).toBe(3)
    expect(screen.queryByText("Put down")).toBeNull()
  })
})

describe("serviceSearch", () => {
  it("keeps the two tabs it knows and a file, and drops everything else", () => {
    expect(serviceSearch({ tab: "plugins" })).toEqual({ tab: "plugins" })
    expect(serviceSearch({ tab: "settings", file: "smp/config.yml" })).toEqual({
      tab: "settings",
      file: "smp/config.yml",
    })
    expect(serviceSearch({ tab: "console" })).toEqual({})
    expect(serviceSearch({ tab: "nonsense", file: "" })).toEqual({})
  })
})
