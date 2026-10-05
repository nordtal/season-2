import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { act, cleanup, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { OverviewPage } from "@/pages/overview"
import { TooltipProvider } from "@/components/ui/tooltip"
import { NETWORK_MAP } from "@/lib/query-fixtures"

/**
 * The Issues tile: what steward judged, and how complete that reading is.
 *
 * Rendered in a real memory router, so a link to a missing route fails.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

const HOUR = 3_600_000

/** A host with room to spare, so only the value under test can trip anything. */
const HOST = {
  memoryTotalBytes: 8_000_000_000,
  memoryAvailableBytes: 4_000_000_000,
  diskTotalBytes: 100_000_000_000,
  diskUsedBytes: 40_000_000_000,
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

function backup(hoursAgo: number) {
  return {
    name: "nordtal-s2_mc-smp-20260912T044500Z.tar.zst",
    bytes: 1_500_000_000,
    human: "1.5 GB",
    modified: new Date(Date.now() - hoursAgo * HOUR).toISOString(),
    partial: false,
    offsite: true,
  }
}

/** The database dump, so the tile has no missing dump to be red about. */
function dump(hoursAgo: number) {
  return {
    name: "nordtal-20260912T024500Z.dump",
    bytes: 40_000_000,
    human: "40 MB",
    modified: new Date(Date.now() - hoursAgo * HOUR).toISOString(),
    partial: false,
  }
}

/** One alert as `/api/alerts` lists it. */
function alert(subject: string, level: "warn" | "down") {
  return {
    type: "service",
    level,
    subject,
    title: { key: "alert.not-running", args: { service: { kind: "text", value: subject } } },
    detail: [],
    path: "/",
  }
}

/** A reading steward finished, with these alerts in it. */
function reading(alerts: unknown[], unreadable: string | null = null) {
  return { checkedAt: new Date().toISOString(), unreadable, level: "ok", alerts, recent: [] }
}

/** Everything the start page asks for; `alerts` is a function so a test can hold it open. */
function backend(over: {
  services?: unknown[]
  backups?: unknown[]
  alerts?: () => Promise<unknown>
  season?: unknown
}) {
  return vi.fn<(url: string) => Promise<Response>>(async (url: string) => {
    if (url === "/api/services") {
      return json(200, {
        services: over.services ?? [service()],
        drift: { checkedAt: new Date().toISOString(), reached: true, unverifiable: [] },
      })
    }
    if (url === "/api/host") return json(200, HOST)
    if (url === "/api/topology") return json(200, NETWORK_MAP)
    if (url === "/api/backups") return json(200, over.backups ?? [backup(2), dump(2)])
    if (url === "/api/alerts") return json(200, await (over.alerts?.() ?? Promise.resolve(reading([]))))
    /** The other tiles answer emptily, so nothing else can be why a test passes or fails. */
    if (url === "/api/agent") return json(200, { available: true })
    if (url.startsWith("/api/metrics")) return json(200, { points: [] })
    if (url.startsWith("/api/updates")) return json(200, [])
    if (url === "/api/season") {
      return json(200, over.season ?? { phase: "SMP", launch: null, smpStart: null })
    }
    if (url.startsWith("/api/journal")) return json(200, [])
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  })
}

/** A route this test never draws, only routes to. */
const nothing = () => null

/** The default a held promise's resolver starts as. */
const noop = () => undefined

/** The page under a router holding only the routes it links into. */
function draw() {
  const root = createRootRoute()
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/", component: OverviewPage }),
    createRoute({ getParentRoute: () => root, path: "/operations", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/backups", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/services/$name", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/season", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/journal", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/alerts", component: nothing }),
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ["/"] }),
  })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  /** The tooltip provider the Shell normally supplies, which the table's badges need. */
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RouterProvider router={router} />
      </TooltipProvider>
    </QueryClientProvider>,
  )
}

/** The Issues tile, walked up from its label to the div holding value and hint. */
function issuesTile(): HTMLElement | null {
  const label = screen.queryByText("Issues")
  return label?.closest("div") ?? null
}

/** What the tile says altogether, or "" before it renders. */
const said = () => issuesTile()?.textContent ?? ""

const COULD_NOT_READ = /could not/

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("OverviewPage - the Issues tile prints what steward judged", () => {
  it("names every alert's subject and is red when one is", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ alerts: () => Promise.resolve(reading([alert("smp", "down"), alert("bot", "warn")])) }),
    )
    draw()

    /** The tile prints an alert's subject, not its title. */
    await waitFor(() => expect(said()).toContain("smp, bot"))
    expect(said()).toContain("2")
    expect(issuesTile()?.innerHTML).toContain("text-destructive")
    expect(said()).not.toMatch(COULD_NOT_READ)
  })

  it("keeps the tile empty while steward has not read the stack yet", async () => {
    /** A settled "0" must never appear before steward has looked. */
    vi.stubGlobal("fetch", backend({ alerts: () => Promise.resolve({ ...reading([]), checkedAt: undefined }) }))
    draw()

    await waitFor(() => expect(issuesTile()).not.toBeNull())
    await waitFor(() => expect(issuesTile()!.querySelector("[data-slot='skeleton-text']")).not.toBeNull())
    expect(said()).not.toContain("0")
  })

  it("says it could not read everything beside what it last knew", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ alerts: () => Promise.resolve(reading([alert("bot", "warn")], "the agent did not answer")) }),
    )
    draw()

    await waitFor(() => expect(said()).toContain("bot"))
    expect(said()).toMatch(COULD_NOT_READ)
  })

  it("draws the dash, never a zero, when the alerts could not be asked for", async () => {
    vi.stubGlobal("fetch", async (url: string) => {
      if (url === "/api/alerts") return json(503, { error: "Broken." })
      return backend({})(url)
    })
    draw()

    await waitFor(() => expect(said()).toMatch(COULD_NOT_READ))
    expect(said()).not.toContain("0")
  })
})

describe("OverviewPage - the tile that replaced the old banner", () => {
  /** The value tells "nothing read yet" from an evidenced "0". */
  it("shows a placeholder while reading, and a settled zero only once a healthy stack answers", async () => {
    let answer: (value: unknown) => void = noop
    const held = new Promise<unknown>((resolve) => {
      answer = resolve
    })
    vi.stubGlobal("fetch", backend({ alerts: () => held }))
    draw()

    // Waiting: the tile already exists and already says something, and that something is not "0".
    await waitFor(() => expect(issuesTile()).not.toBeNull())
    expect(said()).not.toContain("0")

    await act(async () => {
      answer(reading([]))
    })

    /** Settled and fine: the evidenced zero, and nothing reading as failed. */
    await waitFor(() => expect(said()).toContain("0"))
    expect(said()).not.toMatch(COULD_NOT_READ)
  })
})

/**
 * CPU is the tallest tile, so below `lg` it spans its row rather than stretching a neighbour.
 *
 * At `lg` it returns to one column, keeping all six tiles in one row.
 */
describe("OverviewPage - the CPU tile never shares a row with a shorter one", () => {
  it("spans the whole row below `lg`, where it would otherwise stretch a shorter neighbour", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    await waitFor(() => expect(screen.getByText("CPU")).toBeTruthy())
    /** The grid item carrying the span is the parent of `Stat`'s div. */
    const tile = screen.getByText("CPU").closest("div")?.parentElement
    expect(tile?.className).toContain("col-span-2")
    expect(tile?.className).toContain("min-[26rem]:col-span-3")
    expect(tile?.className).toContain("lg:col-span-1")
  })
})

/** The tile order, read in DOM order since the defect is an order: CPU, Memory, Disk, Latest backup, Behind, Issues. */
function metricLabels(): string[] {
  const grid = screen.getByText("CPU").closest("div")?.parentElement?.parentElement
  return Array.from(grid?.children ?? []).map((tile) => tile.querySelector("span")?.textContent ?? "")
}

describe("OverviewPage - the order of the number row", () => {
  it("reads CPU, Memory, Disk, Latest backup, Behind, Issues", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    await waitFor(() => expect(screen.getByText("CPU")).toBeTruthy())
    expect(metricLabels()).toEqual(["CPU", "Memory", "Disk", "Latest backup", "Behind", "Issues"])
  })
})

/** The Latest backup tile links to `/operations/backups`, which holds the full list. */
describe("OverviewPage - the Latest backup tile links to the page that holds the detail", () => {
  it("points the tile at /operations/backups", async () => {
    vi.stubGlobal("fetch", backend({ backups: [backup(2), dump(2)] }))
    draw()

    const label = await screen.findByText("Latest backup")
    const anchor = label.closest("a")
    expect(anchor).not.toBeNull()
    expect(anchor?.getAttribute("href")).toBe("/operations/backups")
  })
})

/** The heading is the proxy's player count, and a dash while no row carries one. */
describe("OverviewPage - the heading is how many are in the game", () => {
  it("says the count instead of the page's own name", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        services: [service(), service({ service: "proxy", players: 7 })],
      }),
    )
    draw()

    await waitFor(() => expect(screen.getByText("players online").closest("p")?.textContent).toContain("7"))
    expect(screen.queryByRole("heading", { name: "Overview" })).toBeNull()
  })

  it("draws a dash, not a zero, when no row carries a player count", async () => {
    vi.stubGlobal("fetch", backend({ services: [service()] }))
    draw()

    const line = await screen.findByText("players online")
    expect(line.closest("p")?.textContent).toContain("\u2013")
    expect(line.closest("p")?.textContent).not.toContain("0")
  })
})

/**
 * The bottom section is the network picture and the actions, with no service table beside it.
 *
 * jsdom has no layout, so only presence is asserted, not position.
 */
describe("OverviewPage - the bottom section", () => {
  it("draws the network picture and no longer draws the service table", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        services: [
          service({ service: "smp", players: 3 }),
          service({ service: "postgres" }),
          service({ service: "proxy", players: 3 }),
        ],
      }),
    )
    draw()

    await waitFor(() => expect(document.querySelector('[data-node="smp"]')).not.toBeNull())
    expect(screen.getByRole("heading", { name: "Network" })).toBeTruthy()
    /** Every service the arrangement names is drawn, with or without a container behind it. */
    expect(document.querySelector('[data-node="postgres"]')).not.toBeNull()

    /** No disclosure element remains. */
    expect(document.querySelector("details")).toBeNull()
    expect(screen.queryByText(/of 3 healthy/)).toBeNull()
  })
})
