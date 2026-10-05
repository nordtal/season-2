import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ServicePage, offline, serviceSearch } from "@/pages/service"
import type { Run } from "@/lib/api"
import { asButton } from "@/lib/test-elements"
import { urlOf } from "@/lib/query-fixtures"
import { TooltipProvider } from "@/components/ui/tooltip"

/** Every service page has one head and up to three tabs; an empty tab is not shown, and the open one is in the URL. */

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
  return vi.fn<typeof fetch>(async (input) => {
    const url = urlOf(input)
    if (url.startsWith("/api/services/")) return json(200, service)
    if (url === "/api/updates/active") return json(200, active)
    if (url === "/api/setting-groups") {
      return json(200, [
        {
          service: "smp",
          name: "config.yml",
          path: "smp/config.yml",
          label: "",
          live: true,
          readable: true,
          writable: true,
        },
      ])
    }
    if (url === "/api/messages") return json(200, [])
    if (url === "/api/settings") return json(200, { minecraftHeadBaseUrl: "" })
    if (url === "/api/agent") return json(200, { available: true, reachable: true })
    if (url === "/api/schedule") return json(200, { nextBackupAt: null, backupAt: "", zone: "UTC" })
    return json(404, { error: `not stubbed: ${url}` })
  })
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
        <RouterProvider router={router} />
      </TooltipProvider>
    </QueryClientProvider>,
  )
  return router
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("ServicePage - the head and the tabs", () => {
  it("gives smp three tabs and its own online line", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("smp", { hasPlugins: true, players: 2 })))
    draw("/services/smp")

    expect(await screen.findByRole("tab", { name: /console/i })).toBeTruthy()
    expect(screen.getByRole("tab", { name: /settings/i })).toBeTruthy()
    expect(screen.getByRole("tab", { name: /plugins/i })).toBeTruthy()
    expect(screen.getByText("players online")).toBeTruthy()
  })

  it("puts the milestone track under the console, which a phone then reaches first", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("smp", { hasPlugins: true })))
    draw("/services/smp")

    const track = await screen.findByText("Milestone track")
    const console = screen.getByRole("region", { name: "Console" })
    expect(console.compareDocumentPosition(track) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("puts the announcement form under the bot's log, and on no other service", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("discord-bot", { hasPlugins: false })))
    draw("/services/discord-bot")

    const form = await screen.findByText("New announcement")
    const log = screen.getByRole("region", { name: "Console" })
    expect(log.compareDocumentPosition(form) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    cleanup()
    vi.stubGlobal("fetch", backend(row("smp", { hasPlugins: true })))
    draw("/services/smp")
    expect(await screen.findByText("Milestone track")).toBeTruthy()
    expect(screen.queryByText("New announcement")).toBeNull()
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

const openRun = (over: Partial<Run> = {}): Run => ({
  id: 41,
  kind: "DOWN",
  status: "PENDING",
  actorKind: "HOST",
  actorId: "",
  scope: ["smp"],
  requested: "2026-09-24T20:00:00Z",
  scheduledFor: new Date(Date.now() + 60_000).toISOString(),
  countdownEnd: new Date(Date.now() + 60_000).toISOString(),
  moving: [],
  ...over,
})

describe("ServicePage - a run that is open", () => {
  it("names it on a page in its scope, offers Cancel in its countdown, and locks every action", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("limbo"), { run: openRun({ scope: ["smp", "limbo"] }) }))
    draw("/services/limbo")

    expect(await screen.findByRole("link", { name: "Take down #41" })).toBeTruthy()
    expect(screen.getByText("smp, limbo")).toBeTruthy()
    expect(screen.getByRole("button", { name: "Cancel" })).toBeTruthy()
    await waitFor(() => {
      for (const name of ["Update", "Take down", "Recreate"]) {
        expect(asButton(screen.getByRole("button", { name })).disabled).toBe(true)
      }
    })
  })

  it("is not shown on a page outside its scope, which may still recreate but not start a run", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("limbo"), { run: openRun() }))
    draw("/services/limbo")

    await waitFor(() => expect(asButton(screen.getByRole("button", { name: "Update" })).disabled).toBe(true))
    expect(asButton(screen.getByRole("button", { name: "Take down" })).disabled).toBe(true)
    expect(asButton(screen.getByRole("button", { name: "Recreate" })).disabled).toBe(false)
    expect(screen.queryByRole("link", { name: /#41/ })).toBeNull()
    expect(screen.queryByRole("button", { name: "Cancel" })).toBeNull()
  })

  it("is shown on every page when it covers the whole network", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal("fetch", backend(row("limbo"), { run: openRun({ kind: "UPDATE", scope: [] }) }))
    draw("/services/limbo")

    expect(await screen.findByRole("link", { name: "Update #41" })).toBeTruthy()
    await waitFor(() => expect(asButton(screen.getByRole("button", { name: "Recreate" })).disabled).toBe(true))
  })

  it("shows the stage once the countdown is over, and no Cancel", async () => {
    vi.stubGlobal("EventSource", SilentEventSource)
    vi.stubGlobal(
      "fetch",
      backend(row("smp"), {
        run: openRun({
          status: "RUNNING",
          countdownEnd: "2026-09-24T20:00:30Z",
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
    expect(asButton(update).disabled).toBe(false)
  })
})

describe("offline", () => {
  const running = openRun({ status: "RUNNING" })
  it("reads a run taking this service down as going offline", () => {
    expect(offline(running, "smp", "running")).toBe("going")
    expect(offline(openRun({ status: "RUNNING", scope: [] }), "limbo", "running")).toBe("going")
  })
  it("does not for a run elsewhere, one still counting down, or a Start", () => {
    expect(offline(running, "limbo", "running")).toBeUndefined()
    expect(offline(openRun(), "smp", "running")).toBeUndefined()
    expect(offline(openRun({ status: "RUNNING", kind: "START" }), "smp", "running")).toBeUndefined()
  })
  it("reads a stopped container as offline", () => {
    expect(offline(null, "smp", "exited")).toBe("gone")
    expect(offline(null, "smp", undefined)).toBeUndefined()
  })
})

/** The default a held promise's resolver starts as, before a test decides to settle it. */
function noop(): void {}

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
    let answer: (response: Response) => void = noop
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
