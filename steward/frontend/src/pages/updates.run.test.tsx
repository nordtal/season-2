import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { UpdateRunPage } from "@/pages/operations"
import { urlOf } from "@/lib/query-fixtures"
import { TooltipProvider } from "@/components/ui/tooltip"

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

const text = (value: string) => ({ kind: "text", value })

/** The rows a finished update writes, with its lines as Steward words them. */
const STEPS = [
  {
    seq: 1,
    step: "INSTALL",
    service: "proxy",
    outcome: "DONE",
    lines: [{ key: "run.moved", args: { artefact: text("proxy"), from: text("0.9.3"), to: text("0.9.4") } }],
    changes: [{ artefact: "proxy", from: "0.9.3", to: "0.9.4" }],
    saved: [],
    pruned: [],
  },
]

function backend(
  steps: unknown = STEPS,
  stage = "DONE",
  kind = "UPDATE",
  scheduledFor = "2026-09-20T18:00:00Z",
): typeof fetch {
  const impl: typeof fetch = async (input) => {
    const url = urlOf(input)
    if (url.startsWith("/api/updates/79")) {
      return json(200, {
        id: 79,
        kind,
        status: "DONE",
        actorKind: "HOST",
        actorId: "",
        scope: [],
        requested: "2026-09-20T18:00:00Z",
        scheduledFor,
        countdownEnd: "2026-09-20T18:01:00Z",
        moving: [],
        started: "2026-09-20T18:01:00Z",
        finished: "2026-09-20T18:04:00Z",
        stage,
        steps,
      })
    }
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  }
  return vi.fn<typeof fetch>(impl)
}

function draw() {
  const root = createRootRoute()
  const routeTree = root.addChildren([
    createRoute({
      getParentRoute: () => root,
      path: "/operations/updates/$id",
      component: UpdateRunPage,
    }),
    createRoute({ getParentRoute: () => root, path: "/operations/updates", component: () => null }),
    createRoute({ getParentRoute: () => root, path: "/operations/backups/$id", component: () => null }),
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ["/operations/updates/79"] }),
  })
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

describe("a run's stages give their coarse facts and open to their steps", () => {
  it("shows a stage's facts as pills and its steps' lines only once it is opened", async () => {
    vi.stubGlobal("fetch", backend())
    draw()

    const stage = await screen.findByRole("button", { name: "Install" })
    expect(screen.getByText("proxy 0.9.3 → 0.9.4")).toBeTruthy()
    expect(stage.getAttribute("aria-expanded")).toBe("false")

    fireEvent.click(stage)
    expect(stage.getAttribute("aria-expanded")).toBe("true")
    const steps = document.getElementById(stage.getAttribute("aria-controls") ?? "")!
    expect(within(steps).getByText("proxy 0.9.3 → 0.9.4")).toBeTruthy()
  })

  it("opens a failed stage with its reason as a pill and every fact on its own line", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        [
          {
            seq: 1,
            step: "DUMP_DATABASE",
            outcome: "FAILED",
            reason: "TOOL_FAILED",
            lines: [
              { key: "run.reason", args: { reason: { kind: "choice", value: "tool-failed" } } },
              { key: "run.exit-code", args: { code: { kind: "number", value: 2 } } },
            ],
            changes: [],
            saved: [],
            pruned: [],
          },
        ],
        "FAILED",
      ),
    )
    draw()

    const stage = await screen.findByRole("button", { name: "Backup" })
    expect(stage.getAttribute("aria-expanded")).toBe("true")
    expect(screen.getAllByText("the tool failed")).toHaveLength(2)
    expect(screen.getByText("exit code 2")).toBeTruthy()
    expect(screen.getByText("Dump the database")).toBeTruthy()
  })

  it("draws a stage that did nothing like every other, with a pill that says so", async () => {
    vi.stubGlobal(
      "fetch",
      backend([
        ...STEPS,
        { seq: 2, step: "PLAYERS_MOVED", outcome: "DONE", lines: [], changes: [], players: [], saved: [], pruned: [] },
      ]),
    )
    draw()

    const players = await screen.findByRole("button", { name: "Players" })
    expect(players.className).toBe(screen.getByRole("button", { name: "Install" }).className)
    expect(within(players.parentElement!).getByText("nothing")).toBeTruthy()
  })

  it("sends a backup run to its page under Backups", async () => {
    vi.stubGlobal("fetch", backend([], "DONE", "BACKUP"))
    const router = draw()

    await waitFor(() => expect(router.state.location.pathname).toBe("/operations/backups/79"))
  })

  it("says nothing is written yet while a run has no step", async () => {
    vi.stubGlobal("fetch", backend([], "PLANNED"))
    draw()

    expect(await screen.findByText("nothing written yet")).toBeTruthy()
  })
})

describe("a run's header is compact", () => {
  it("names the run's outcome once, and the host as a word", async () => {
    vi.stubGlobal("fetch", backend())
    draw()

    await screen.findByText("Requested by")
    expect(screen.getAllByText(/^done$/i)).toHaveLength(1)
    expect(screen.getByText("Host")).toBeTruthy()
  })

  it("names the earliest start only when it is not the moment of asking", async () => {
    vi.stubGlobal("fetch", backend())
    draw()
    await screen.findByText("Requested by")
    expect(screen.queryByText("No earlier than")).toBeNull()
    cleanup()

    vi.stubGlobal("fetch", backend(STEPS, "DONE", "UPDATE", "2026-09-20T20:00:00Z"))
    draw()
    expect(await screen.findByText("No earlier than")).toBeTruthy()
  })

  it("draws its figures with no separator to hang at a line's end", async () => {
    vi.stubGlobal("fetch", backend())
    draw()

    const figures = (await screen.findByText("Requested by")).closest("dl")
    expect(figures?.querySelector("[data-slot='separator']")).toBeNull()
  })
})
