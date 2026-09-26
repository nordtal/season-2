import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ServicePage, offline, serviceSearch } from "@/pages/service"
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

function backend(service: Record<string, unknown>, active: unknown = { run: null }): typeof fetch {
  return vi.fn(async (url: string) => {
    if (url.startsWith("/api/services/")) return json(200, service)
    if (url === "/api/updates/active") return json(200, active)
    if (url === "/api/config") {
      return json(200, [{ service: "smp", name: "config.yml", path: "smp/config.yml", readable: true, writable: true }])
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

const openRun = (over: Record<string, unknown> = {}) => ({
  id: 41,
  kind: "DOWN",
  status: "PENDING",
  source: "CONSOLE",
  requestedBy: "till (123456789012345678)",
  scope: ["smp"],
  actorDiscordId: "123456789012345678",
  actorLabel: "",
  system: false,
  requested: "2026-09-24T20:00:00Z",
  notBefore: new Date(Date.now() + 60_000).toISOString(),
  started: "null",
  finished: "null",
  ...over,
})

describe("ServicePage - a run that is open", () => {
  it("names it, offers Cancel in its countdown, and locks every action", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("limbo"), { run: openRun() }))
    draw("/services/limbo")

    expect(await screen.findByRole("link", { name: "Take down #41" })).toBeTruthy()
    expect(screen.getByText("smp")).toBeTruthy()
    expect(screen.getByRole("button", { name: "Cancel" })).toBeTruthy()
    await waitFor(() => {
      for (const name of ["Update", "Take down", "Recreate"]) {
        expect((screen.getByRole("button", { name }) as HTMLButtonElement).disabled).toBe(true)
      }
    })
  })

  it("shows the stage once the countdown is over, and no Cancel", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal(
      "fetch",
      backend(row("smp"), {
        run: openRun({
          status: "RUNNING",
          notBefore: "2026-09-24T20:00:30Z",
          report: { stage: "STOPPING", services: [], notes: [] },
        }),
      }),
    )
    draw("/services/smp")

    expect(await screen.findByText("Stopping")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Cancel" })).toBeNull()
  })

  it("leaves the actions alone when no run is open", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("smp")))
    draw("/services/smp")

    const update = await screen.findByRole("button", { name: "Update" })
    await waitFor(() => expect(screen.queryByRole("link", { name: /#\d+/ })).toBeNull())
    expect((update as HTMLButtonElement).disabled).toBe(false)
  })
})

describe("offline", () => {
  const running = openRun({ status: "RUNNING" }) as never
  it("reads a run taking this service down as going offline", () => {
    expect(offline(running, "smp", "running")).toBe("going")
    expect(offline(openRun({ status: "RUNNING", scope: [] }) as never, "limbo", "running")).toBe("going")
  })
  it("does not for a run elsewhere, one still counting down, or a Start", () => {
    expect(offline(running, "limbo", "running")).toBeUndefined()
    expect(offline(openRun() as never, "smp", "running")).toBeUndefined()
    expect(offline(openRun({ status: "RUNNING", kind: "START" }) as never, "smp", "running")).toBeUndefined()
  })
  it("reads a stopped container as offline", () => {
    expect(offline(null, "smp", "exited")).toBe("gone")
    expect(offline(null, "smp", undefined)).toBeUndefined()
  })
})

/** A desktop-wide `matchMedia`: every `min-width` query matches, every `max-width` one does not. */
function wideScreen() {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: query.includes("min-width"),
    media: query,
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent: () => false,
  }))
}

describe("ServicePage - the actions arrive together", () => {
  it("draws no action button until the service is known, and a skeleton in each place", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    let answer: (response: Response) => void = () => {}
    const pending = new Promise<Response>((resolve) => (answer = resolve))
    const served = backend(row("smp", { hasPlugins: true }))
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => (url.startsWith("/api/services/") ? pending : served(url))),
    )
    draw("/services/smp")

    const slots = await screen.findAllByTestId("action-skeleton")
    expect(slots.length).toBeGreaterThanOrEqual(3)
    expect(screen.queryByRole("button", { name: "Update" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Recreate" })).toBeNull()

    answer(json(200, row("smp", { hasPlugins: true })))
    expect(await screen.findByRole("button", { name: "Update" })).toBeTruthy()
    expect(screen.getByRole("button", { name: "Take down" })).toBeTruthy()
    expect(screen.getByRole("button", { name: "Recreate" })).toBeTruthy()
    expect(screen.queryAllByTestId("action-skeleton")).toHaveLength(0)
  })
})

describe("ServicePage - leaving Settings on a wide screen", () => {
  it("opens Console from Settings, although Settings shows its first file by itself", async () => {
    wideScreen()
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("smp", { hasPlugins: true })))
    const router = draw("/services/smp?tab=settings")

    await screen.findByRole("button", { name: /config/i })
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: "settings" }))

    const console = screen.getByRole("tab", { name: /console/i })
    fireEvent.mouseDown(console)
    fireEvent.click(console)
    await waitFor(() => expect(router.state.location.search).toEqual({}))
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(router.state.location.search).toEqual({})
    expect(screen.getByRole("tab", { name: /console/i }).getAttribute("aria-selected")).toBe("true")
  })

  it("leaves Settings for another service", async () => {
    wideScreen()
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("smp", { hasPlugins: true })))
    const router = draw("/services/smp?tab=settings")

    await screen.findByRole("button", { name: /config/i })
    await router.navigate({ to: "/services/$name", params: { name: "proxy" }, search: {} })
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(router.state.location.pathname).toBe("/services/proxy")
    expect(router.state.location.search).toEqual({})
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
