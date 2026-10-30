import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { Vitals } from "@/components/steward/network/node"
import { NetworkTable } from "@/components/steward/network/table"
import { topologyOf } from "@/components/steward/network/topology"
import { NetworkPanel } from "@/components/steward/network/view"
import { TooltipProvider } from "@/components/ui/tooltip"
import { datasetOf } from "@/lib/test-elements"
import type { NetworkMap, Service } from "@/lib/api"
import { NETWORK_MAP } from "@/lib/query-fixtures"
import { serviceTitle } from "@/lib/words"

/** The mark beside the people icon, which a screen reader hears as `players`. */
const playersOf = (element: HTMLElement) =>
  within(element).queryByText("players", { selector: ".sr-only" })?.parentElement ?? null
const countIn = (element: HTMLElement) => playersOf(element)?.querySelector(".tnum")?.textContent

const { names: SERVICES, sections: SECTIONS } = topologyOf(NETWORK_MAP)

function Table() {
  return <NetworkTable sections={SECTIONS} />
}

/**
 * jsdom has no layout, so these tests hold decisions and not positions. A browser checks the geometry.
 */

function service(over: Partial<Service> = {}): Service {
  return {
    service: "smp",
    containerId: "abc",
    image: "ghcr.io/nordtal/smp:1.4.0",
    state: "running",
    status: "Up 3 hours (healthy)",
    hasConsole: true,
    drift: "UP_TO_DATE",
    health: "healthy",
    startedAt: new Date(Date.now() - 3 * 60 * 60 * 1000).toISOString(),
    memoryBytes: 1_200_000_000,
    memoryLimitBytes: 4_000_000_000,
    cpuPercent: 4.2,
    ...over,
  }
}

const TABLE = {
  services: [
    service({ service: "smp", players: 3 }),
    service({ service: "hunger-games", players: 0, drift: "OUTDATED" }),
    service({ service: "limbo", players: 4, health: "starting" }),
    service({ service: "proxy", players: 7, drift: "LOCAL" }),
    service({ service: "discord-bot", drift: "UNKNOWN" }),
    service({ service: "postgres", image: "postgres:17-alpine" }),
    service({ service: "caddy", image: "caddy:2" }),
    service({ service: "steward" }),
    service({ service: "steward-agent", state: "exited", status: "Exited (0)", alert: "down" }),
    service({ service: "steward-bunq" }),
  ],
  drift: { checkedAt: new Date().toISOString(), reached: true, unverifiable: [] },
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

function draw(table: unknown = TABLE, component: () => React.ReactNode = NetworkPanel, map: NetworkMap = NETWORK_MAP) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      if (url === "/api/services") return json(table)
      if (url === "/api/topology") return json(map)
      if (url === "/api/agent") return json({ available: true })
      throw new Error(`the view asked for ${url}, which this test did not expect`)
    }),
  )

  const root = createRootRoute()
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/", component: component }),
    createRoute({ getParentRoute: () => root, path: "/services/$name", component: () => null }),
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ["/"] }),
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

function row(id: string): HTMLElement {
  const found = document.querySelector<HTMLElement>(`[data-row="${id}"]`)
  if (!found) throw new Error(`no row was drawn for ${id}`)
  return found
}

function box(id: string): HTMLElement {
  const found = document.querySelector<HTMLElement>(`[data-node="${id}"]`)
  if (!found) throw new Error(`no box was drawn for ${id}`)
  return found
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("the network view", () => {
  it("draws every served service, plus the box the traffic comes from", async () => {
    draw()
    await waitFor(() => expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy())

    const drawn = [...document.querySelectorAll("[data-node]")].map((node) => datasetOf(node).node)
    for (const name of SERVICES) expect(drawn, `${name} is not drawn`).toContain(name)
    expect(drawn).toContain("players")
    expect(new Set(drawn).size).toBe(drawn.length)
  })

  it("draws the healthy ones green, which the sidebar does not", async () => {
    draw()
    await waitFor(() => expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy())

    const dot = within(box("smp")).getByLabelText("healthy")
    expect(dot.className).toContain("bg-success")
    expect(within(box("steward-agent")).getByLabelText("unhealthy")).toBeTruthy()
    expect(within(box("limbo")).getByLabelText("starting")).toBeTruthy()
  })

  it("puts a count on the four that have one and nothing at all on the five that do not", async () => {
    draw()
    await waitFor(() => expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy())

    expect(countIn(box("smp"))).toBe("3")
    expect(countIn(box("limbo"))).toBe("4")
    expect(countIn(box("proxy"))).toBe("7")
    /**
     * Zero players is a running server, and the box shows the zero and does not look unanswered.
     */
    expect(countIn(box("hunger-games"))).toBe("0")
    expect(countIn(box("players"))).toBe("7")

    for (const silent of ["postgres", "caddy", "steward", "steward-agent", "steward-bunq", "discord-bot"]) {
      expect(playersOf(box(silent)), `${silent} has no player count and must draw none`).toBeNull()
    }
  })

  /**
   * Sharing the row truncated the identifier at 390px. The test asks whether the two are siblings, which is the shape of the defect and needs no measuring.
   */
  it("never puts the count on the identifier's own row, which is what truncated it", async () => {
    draw()
    await waitFor(() => expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy())

    for (const name of ["smp", "limbo", "proxy", "hunger-games"]) {
      const identifier = within(box(name)).getByRole("link", { name: serviceTitle(name) })
      const count = playersOf(box(name))
      expect(count, `${name} draws a count`).not.toBeNull()
      expect(count?.parentElement, `${name}: the count must live on the tag line, not beside the identifier`).not.toBe(
        identifier.parentElement,
      )
    }

    const entry = box("players")
    expect(within(entry).getByText("players", { selector: ":not(.sr-only)" }).parentElement).toBe(
      playersOf(entry)?.parentElement,
    )
  })

  it("marks the three image states that are not current, and leaves the current one unmarked", async () => {
    draw()
    await waitFor(() => expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy())

    expect(within(box("hunger-games")).getByLabelText("outdated")).toBeTruthy()
    expect(within(box("proxy")).getByLabelText("local build")).toBeTruthy()
    expect(within(box("discord-bot")).getByLabelText("unchecked")).toBeTruthy()

    const current = within(box("smp"))
    expect(current.queryByLabelText("outdated")).toBeNull()
    expect(current.queryByLabelText("local build")).toBeNull()
    expect(current.queryByLabelText("unchecked")).toBeNull()
  })

  it("puts the running tag under the name, not the whole reference", async () => {
    draw()
    await waitFor(() => expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy())

    expect(box("smp").textContent).toContain("1.4.0")
    expect(box("smp").textContent).not.toContain("ghcr.io")
    expect(box("postgres").textContent).toContain("17-alpine")
  })

  it("drops a count that steward no longer trusts, rather than drawing a zero", async () => {
    /**
     * proxy stopped writing, so `players` is absent everywhere. Nobody has reported a count, which is not an empty network.
     */
    const silent = {
      ...TABLE,
      services: TABLE.services.map((entry) => {
        const without: Record<string, unknown> = { ...entry }
        delete without.players
        return without
      }),
    }
    draw(silent)
    await waitFor(() => expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy())

    for (const name of ["smp", "hunger-games", "limbo", "proxy", "players"]) {
      expect(playersOf(box(name)), `${name} must draw no count`).toBeNull()
    }
    expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy()
  })
})

describe("a stopped node says nothing rather than half of something", () => {
  /**
   * Rendered on its own, because the vitals are in a tooltip. A dash behind the word "cpu" reads as a fault.
   */
  const OFF = {
    service: "smp",
    containerId: "abc",
    image: "ghcr.io/nordtal/smp:1.4.0",
    state: "exited",
    status: "Exited (0) 2 hours ago",
    hasConsole: true,
    drift: "UP_TO_DATE" as const,
  }

  it("draws no cpu and no memory line for a container that is not running", () => {
    render(<Vitals service={OFF} />)

    const text = document.body.textContent ?? ""
    expect(text).toContain("not running")
    expect(text).not.toContain("cpu")
    expect(text).not.toContain("memory")
    expect(text).not.toContain("\u2013")
  })

  it("still draws both for a container that is running, which is the point of having them", () => {
    render(<Vitals service={service()} />)

    const text = document.body.textContent ?? ""
    expect(text).toContain("cpu")
    expect(text).toContain("memory")
    expect(text).not.toContain("\u2013")
  })
})

/**
 * The test renders `Wires` itself, because `geometry.test.ts` would stay green if the deduplication broke.
 */
describe("the view collapses a group's edges into one drawn line each", () => {
  it("draws one traffic edge into each group and one data foot out of the Paper group", async () => {
    draw()
    await waitFor(() => expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy())

    const edgeKeys = [...document.querySelectorAll("[data-edge]")].map((el) => datasetOf(el).edge)
    expect(edgeKeys.filter((key) => key === "proxy-paper")).toHaveLength(1)
    expect(edgeKeys.filter((key) => key === "steward-steward-ops")).toHaveLength(1)
    expect(edgeKeys).toHaveLength(5)

    expect(document.querySelectorAll("[data-foot]")).toHaveLength(4)
  })
})

/**
 * The test renders `NetworkTable` directly, because `vitest.setup.ts` stubs `matchMedia` to never match.
 */
describe("the network on a phone", () => {
  it("draws one row per service, in the sections' order, and no row for players", async () => {
    draw(TABLE, Table)
    await waitFor(() => expect(within(row("smp")).getByLabelText("healthy")).toBeTruthy())

    const drawn = [...document.querySelectorAll("[data-row]")].map((node) => datasetOf(node).row)
    for (const name of SERVICES) expect(drawn, `${name} has no row`).toContain(name)
    expect(new Set(drawn).size).toBe(drawn.length)
    expect(drawn).toEqual(SECTIONS.flatMap((section) => section.members))
    expect(drawn).not.toContain("players")
  })

  it("prints a section heading for each group of the plan", async () => {
    draw(TABLE, Table)
    await waitFor(() => expect(within(row("smp")).getByLabelText("healthy")).toBeTruthy())

    for (const section of SECTIONS) {
      expect(screen.getByRole("heading", { name: section.title }), `${section.title} has no heading`).toBeTruthy()
    }
  })

  it("carries the same four facts a card does, and nothing it does not", async () => {
    draw(TABLE, Table)
    await waitFor(() => expect(within(row("smp")).getByLabelText("healthy")).toBeTruthy())

    expect(within(row("smp")).getByLabelText("healthy")).toBeTruthy()
    expect(row("smp").textContent).toContain("1.4.0")
    expect(row("smp").textContent).not.toContain("ghcr.io")
    expect(countIn(row("smp"))).toBe("3")
    expect(within(row("smp")).getByRole("link", { name: "SMP" })).toBeTruthy()
    expect(within(row("smp")).getByRole("link", { name: "open SMP" })).toBeTruthy()

    for (const silent of ["postgres", "caddy", "steward", "steward-agent", "steward-bunq", "discord-bot"]) {
      expect(playersOf(row(silent))).toBeNull()
    }
    expect(within(row("hunger-games")).getByLabelText("outdated")).toBeTruthy()
    expect(within(row("proxy")).getByLabelText("local build")).toBeTruthy()
    expect(within(row("discord-bot")).getByLabelText("unchecked")).toBeTruthy()
    expect(within(row("smp")).queryByLabelText("outdated")).toBeNull()
  })
})

describe("a service the drawing does not know", () => {
  it("draws the table instead of a picture that leaves it out", async () => {
    const grown: NetworkMap = {
      services: [
        ...NETWORK_MAP.services,
        { name: "pack-host", section: "Steward", entry: false, reaches: [], storesIn: [] },
      ],
    }
    draw(TABLE, NetworkPanel, grown)
    await waitFor(() => expect(within(row("smp")).getByLabelText("healthy")).toBeTruthy())

    expect(row("pack-host")).toBeTruthy()
    expect(document.querySelector("[data-node]")).toBeNull()
  })
})
