import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, render, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { SERVICES } from "@/app/navigation"
import { NetworkPanel } from "@/components/steward/network/view"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The network view, rendered against a fake `/api/services`.
 *
 * It was two drafts under a throwaway route until Till chose `h` on 2026-09-18; the route and the
 * loser are deleted and every assertion below now runs once, against the panel the start page
 * actually holds, rather than twice against two letters.
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
    service({ service: "proxy", players: 7, drift: "LOCAL" }),
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

function draw(table: unknown = TABLE) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      if (url === "/api/services") return json(table)
      // Every node's toolbar carries a `RecreateButton` (steward/81, second round), and that
      // component asks `/api/deployer` unconditionally to know whether to disable itself - not
      // something a rendering test of the graph itself has any reason to special-case per node.
      if (url === "/api/deployer") return json({ available: true })
      throw new Error(`the view asked for ${url}, which this test did not expect`)
    }),
  )

  // A real router, because a node's identifier is a `Link` into `/services/$name`. The panel itself
  // has no route of its own any more - it is half of the start page - so the tree here is the one
  // thing it links into and a root that draws it.
  const root = createRootRoute()
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/", component: NetworkPanel }),
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

describe("the network view", () => {
  it("draws every service in navigation.ts, plus the box the traffic comes from", async () => {
    draw()
    await waitFor(() => expect(box("smp")).toBeTruthy())

    const drawn = [...document.querySelectorAll("[data-node]")].map(
      (node) => (node as HTMLElement).dataset.node,
    )
    for (const name of SERVICES) expect(drawn, `${name} is not drawn`).toContain(name)
    expect(drawn).toContain("players")
    // One box each. An arrangement that placed a service twice would draw its lines twice too.
    expect(new Set(drawn).size).toBe(drawn.length)
  })

  it("draws the healthy ones green, which the sidebar does not", async () => {
    draw()
    await waitFor(() => expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy())

    const dot = within(box("smp")).getByLabelText("healthy")
    expect(dot.className).toContain("bg-success")
    expect(within(box("steward-deployer")).getByLabelText("unhealthy")).toBeTruthy()
    expect(within(box("limbo")).getByLabelText("starting")).toBeTruthy()
  })

  it("puts a count on the four that have one and nothing at all on the six that do not", async () => {
    draw()
    await waitFor(() => expect(box("smp")).toBeTruthy())

    expect(within(box("smp")).getByTitle("players").textContent).toBe("3")
    expect(within(box("limbo")).getByTitle("players").textContent).toBe("4")
    expect(within(box("proxy")).getByTitle("players").textContent).toBe("7")
    // Nobody is on, and that is a number: a server with zero players is a running server, and the
    // box has to say so rather than look like one that never answered (steward/86).
    expect(within(box("hunger-games")).getByTitle("players").textContent).toBe("0")
    // The entry box carries the network's total, which is proxy's own row.
    expect(within(box("players")).getByTitle("players").textContent).toBe("7")

    for (const silent of ["postgres", "caddy", "steward-ui", "steward-worker", "discord-bot"]) {
      expect(
        within(box(silent)).queryByTitle("players"),
        `${silent} has no player count and must draw none`,
      ).toBeNull()
    }
  })

  /**
   * The identifier never shares its row with the count, because that is what took its characters
   * away.
   *
   * Measured on 2026-09-17 at 390px on the drafts of the second round: they fell back to a
   * two-column grid there, two of them narrowed it further with the rail margin their database bus
   * needed, and the identifier - which
   * carries `truncate` so that it yields rather than break the box - was the thing that yielded.
   * `proxy` and `hunger-games` rendered as `network-co…` and `hunger-ga…`, and the
   * identifier is the *first* of the four things steward/81 says a node carries. It must not be the
   * first to go.
   *
   * Sixty-four tests were green through all of it, because every one of them finds the count with
   * `getByTitle("players")` and none of them cares which row it sits in. This one does: it asks
   * whether the two are siblings, which is the shape of the defect rather than its appearance, and
   * is the one thing a jsdom test can say about a layout it cannot measure.
   */
  it("never puts the count on the identifier's own row, which is what truncated it", async () => {
    draw()
    await waitFor(() => expect(box("smp")).toBeTruthy())

    for (const name of ["smp", "limbo", "proxy", "hunger-games"]) {
      const identifier = within(box(name)).getByRole("link", { name })
      const count = within(box(name)).getByTitle("players")
      expect(
        count.parentElement,
        `${name}: the count must live on the tag line, not beside the identifier`,
      ).not.toBe(identifier.parentElement)
    }

    // And the one deliberate exception, written down rather than implied: the entry box has no
    // second line to put a count on, so its count does sit beside the label - which is harmless
    // there, because "players" is short enough that nothing has ever had to yield to it.
    const entry = box("players")
    expect(within(entry).getByText("players").parentElement).toBe(
      within(entry).getByTitle("players").parentElement,
    )
  })

  it("marks the three image states that are not current, and leaves the current one unmarked", async () => {
    draw()
    await waitFor(() => expect(box("smp")).toBeTruthy())

    expect(within(box("hunger-games")).getByLabelText("a newer image exists")).toBeTruthy()
    expect(within(box("proxy")).getByLabelText("built on this host")).toBeTruthy()
    expect(within(box("discord-bot")).getByLabelText("image not compared")).toBeTruthy()

    // Up to date says nothing, the way the sidebar's dot and the Issues tile say nothing.
    const current = within(box("smp"))
    expect(current.queryByLabelText("a newer image exists")).toBeNull()
    expect(current.queryByLabelText("built on this host")).toBeNull()
    expect(current.queryByLabelText("image not compared")).toBeNull()
  })

  it("puts the running tag under the name, not the whole reference", async () => {
    draw()
    await waitFor(() => expect(box("smp")).toBeTruthy())

    expect(box("smp").textContent).toContain("1.4.0")
    expect(box("smp").textContent).not.toContain("ghcr.io")
    expect(box("postgres").textContent).toContain("17-alpine")
  })

  it("drops a count that the worker no longer trusts, rather than drawing a zero", async () => {
    // proxy stopped writing, so `/api/services` omits `players` everywhere. That is not
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
    draw(silent)
    await waitFor(() => expect(box("smp")).toBeTruthy())

    for (const name of ["smp", "hunger-games", "limbo", "proxy", "players"]) {
      expect(within(box(name)).queryByTitle("players"), `${name} must draw no count`).toBeNull()
    }
    expect(within(box("smp")).getByLabelText("healthy")).toBeTruthy()
  })
})

/**
 * The dedup that makes "one arrow into the group" true lives in `wires.tsx`'s `Wires` component,
 * not in the geometry model `geometry.test.ts` checks - that file recomputes the same resolution
 * independently of `Wires` itself, so it would stay green even if `Wires`'s own deduplication broke
 * (checked by breaking it: commenting out the `seen`/dedup lines there left every assertion in
 * `geometry.test.ts` passing, because none of them render anything). This is the one test that
 * actually renders `h` and counts the `<path>` elements the browser would draw.
 */
describe("the view collapses a group's edges into one drawn line each (Till, 2026-09-18)", () => {
  it("draws one traffic edge into each group and one data foot out of each group", async () => {
    draw()
    await waitFor(() => expect(box("smp")).toBeTruthy())

    const edgeKeys = [...document.querySelectorAll("[data-edge]")].map(
      (el) => (el as HTMLElement).dataset.edge,
    )
    // Three raw edges - proxy to each of smp, hunger-games and limbo - collapse to this
    // one key; two more - steward-ui to steward-worker and to steward-deployer - collapse to the
    // other. The three edges that were never grouped (players to proxy, players to caddy,
    // caddy to steward-ui) are untouched by the collapse and still draw one each.
    expect(edgeKeys.filter((key) => key === "proxy-paper")).toHaveLength(1)
    expect(edgeKeys.filter((key) => key === "steward-ui-steward-ops")).toHaveLength(1)
    expect(edgeKeys).toHaveLength(5)

    // The database fan: five sources once the Paper trio and the deploy pair have each collapsed
    // to their group's own frame, down from the seven raw database clients in topology.ts.
    expect(document.querySelectorAll("[data-foot]")).toHaveLength(5)
  })
})
