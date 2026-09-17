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

import { SERVICES } from "@/app/navigation"
import { NetworkDesignsPage } from "@/pages/designs"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The three drafts of steward/81, rendered against a fake `/api/services`.
 *
 * <h2>What a test here can and cannot answer</h2>
 * jsdom has no layout, so nothing about where a box ended up or where a line was drawn is visible
 * from here - and a test pretending otherwise would be measuring its own stub. What it can hold is
 * everything that is a decision rather than a position: that all ten services are drawn and none is
 * quietly missing, that a healthy box is green **here** while the sidebar stays silent, that a
 * count of nobody is different from no count at all, and that the four drift states each draw what
 * they were decided to draw. The geometry is for a browser at 390px and 1440px, which is where the
 * ticket says it will be looked at.
 */

function service(over: Record<string, unknown> = {}) {
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

/**
 * Ten containers, and every interesting shape among them at once: a service with players and one
 * without, all four drift states, one that is down and one still starting.
 */
const TABLE = {
  services: [
    service({ service: "smp", players: 3 }),
    service({ service: "hunger-games", players: 0, drift: "OUTDATED" }),
    service({ service: "limbo", players: 4, health: "starting" }),
    service({ service: "network-control", players: 7, drift: "LOCAL" }),
    service({ service: "discord-bot", drift: "UNKNOWN" }),
    service({ service: "postgres", image: "postgres:17-alpine" }),
    service({ service: "caddy", image: "caddy:2" }),
    service({ service: "steward-ui" }),
    service({ service: "steward-worker" }),
    service({ service: "steward-deployer", state: "exited", status: "Exited (0)" }),
  ],
  drift: { checkedAt: new Date().toISOString(), reached: true, unverifiable: [] },
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

function draw(variant: string, table: unknown = TABLE) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      if (url === "/api/services") return json(table)
      // Every node's toolbar carries a `RecreateButton` (steward/81, second round), and that
      // component asks `/api/deployer` unconditionally to know whether to disable itself - not
      // something a rendering test of the graph itself has any reason to special-case per node.
      if (url === "/api/deployer") return json({ available: true })
      throw new Error(`the draft asked for ${url}, which this test did not expect`)
    }),
  )

  const root = createRootRoute()
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/designs/network", component: NetworkDesignsPage }),
    createRoute({ getParentRoute: () => root, path: "/services/$name", component: () => null }),
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [`/designs/network?v=${variant}`] }),
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

/** One box, found the same way the line drawing finds it. */
function box(id: string): HTMLElement {
  const found = document.querySelector<HTMLElement>(`[data-node="${id}"]`)
  if (!found) throw new Error(`no box was drawn for ${id}`)
  return found
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe.each([["a"], ["b"], ["c"], ["d"], ["e"], ["f"], ["g"]])("draft %s", (variant) => {
  it("draws every service in navigation.ts, plus the box the traffic comes from", async () => {
    draw(variant)
    await waitFor(() => expect(box("smp")).toBeTruthy())

    const drawn = [...document.querySelectorAll("[data-node]")].map(
      (node) => (node as HTMLElement).dataset.node,
    )
    for (const name of SERVICES) expect(drawn, `${name} is not drawn`).toContain(name)
    expect(drawn).toContain("players")
    // One box each. A draft that drew a service twice would draw its lines twice too.
    expect(new Set(drawn).size).toBe(drawn.length)
  })

  it("draws the healthy ones green, which the sidebar does not", async () => {
    draw(variant)
    await waitFor(() => expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy())

    const dot = within(box("smp")).getByLabelText("healthy")
    expect(dot.className).toContain("bg-success")
    expect(within(box("steward-deployer")).getByLabelText("unhealthy")).toBeTruthy()
    expect(within(box("limbo")).getByLabelText("starting")).toBeTruthy()
  })

  it("puts a count on the four that have one and nothing at all on the six that do not", async () => {
    draw(variant)
    await waitFor(() => expect(box("smp")).toBeTruthy())

    expect(within(box("smp")).getByTitle("players").textContent).toBe("3")
    expect(within(box("limbo")).getByTitle("players").textContent).toBe("4")
    expect(within(box("network-control")).getByTitle("players").textContent).toBe("7")
    // Nobody is on, and that is a number: a server with zero players is a running server, and the
    // box has to say so rather than look like one that never answered (steward/86).
    expect(within(box("hunger-games")).getByTitle("players").textContent).toBe("0")
    // The entry box carries the network's total, which is network-control's own row.
    expect(within(box("players")).getByTitle("players").textContent).toBe("7")

    for (const silent of ["postgres", "caddy", "steward-ui", "steward-worker", "discord-bot"]) {
      expect(
        within(box(silent)).queryByTitle("players"),
        `${silent} has no player count and must draw none`,
      ).toBeNull()
    }
  })

  it("marks the three image states that are not current, and leaves the current one unmarked", async () => {
    draw(variant)
    await waitFor(() => expect(box("smp")).toBeTruthy())

    expect(within(box("hunger-games")).getByLabelText("a newer image exists")).toBeTruthy()
    expect(within(box("network-control")).getByLabelText("built on this host")).toBeTruthy()
    expect(within(box("discord-bot")).getByLabelText("image not compared")).toBeTruthy()

    // Up to date says nothing, the way the sidebar's dot and the Issues tile say nothing.
    const current = within(box("smp"))
    expect(current.queryByLabelText("a newer image exists")).toBeNull()
    expect(current.queryByLabelText("built on this host")).toBeNull()
    expect(current.queryByLabelText("image not compared")).toBeNull()
  })

  it("puts the running tag under the name, not the whole reference", async () => {
    draw(variant)
    await waitFor(() => expect(box("smp")).toBeTruthy())

    expect(box("smp").textContent).toContain("1.4.0")
    expect(box("smp").textContent).not.toContain("ghcr.io")
    expect(box("postgres").textContent).toContain("17-alpine")
  })

  it("drops a count that the worker no longer trusts, rather than drawing a zero", async () => {
    // network-control stopped writing, so `/api/services` omits `players` everywhere. That is not
    // an empty network; it is nobody having said (steward/86). Four boxes lose an item and the
    // picture keeps its shape.
    const silent = {
      ...TABLE,
      services: TABLE.services.map((row) => {
        const without: Record<string, unknown> = { ...row }
        delete without.players
        return without
      }),
    }
    draw(variant, silent)
    await waitFor(() => expect(box("smp")).toBeTruthy())

    for (const name of ["smp", "hunger-games", "limbo", "network-control", "players"]) {
      expect(within(box(name)).queryByTitle("players"), `${name} must draw no count`).toBeNull()
    }
    expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy()
  })
})

describe("the drafts are reachable from one another", () => {
  it("offers all seven letters, with the one on screen marked", async () => {
    draw("b")
    await waitFor(() => expect(box("smp")).toBeTruthy())

    for (const letter of ["a", "b", "c", "d", "e", "f", "g"]) {
      const link = screen.getByRole("link", { name: letter })
      expect(link.getAttribute("href")).toBe(`/designs/network?v=${letter}`)
    }
  })
})
