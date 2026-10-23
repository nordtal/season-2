import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, render, screen, waitFor, within } from "@testing-library/react"
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
    lines: [
      {
        key: "run.change",
        args: {
          artefact: text("proxy"),
          change: { kind: "message", value: { key: "run.moved", args: { from: text("0.9.3"), to: text("0.9.4") } } },
        },
      },
    ],
    changes: [{ artefact: "proxy", from: "0.9.3", to: "0.9.4" }],
    saved: [],
    pruned: [],
  },
]

function backend(steps: unknown = STEPS, stage = "DONE", kind = "UPDATE"): typeof fetch {
  const impl: typeof fetch = async (input) => {
    const url = urlOf(input)
    if (url.startsWith("/api/updates/79")) {
      return json(200, {
        id: 79,
        kind,
        status: "DONE",
        actorKind: "HOST",
        actorId: "",
        requested: "2026-09-20T18:00:00Z",
        scheduledFor: "2026-09-20T18:01:00Z",
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

describe("a run's steps read as Steward words them", () => {
  it("prints each step with its service and its lines", async () => {
    vi.stubGlobal("fetch", backend())
    draw()

    expect(await screen.findByText("proxy 0.9.3 → 0.9.4")).toBeTruthy()
    expect(screen.getByText("Install")).toBeTruthy()
  })

  it("names a failed step's reason and exit code", async () => {
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

    expect(await screen.findByText("the tool failed")).toBeTruthy()
    expect(screen.getByText("exit code 2")).toBeTruthy()
    expect(screen.getByText("Dump the database")).toBeTruthy()
  })

  it("sends a backup run to its page under Backups", async () => {
    vi.stubGlobal("fetch", backend([], "DONE", "BACKUP"))
    const router = draw()

    await waitFor(() => expect(router.state.location.pathname).toBe("/operations/backups/79"))
  })

  it("says there is no report yet while a run has no step", async () => {
    vi.stubGlobal("fetch", backend([], "PLANNED"))
    draw()

    expect(await screen.findByText("No report yet")).toBeTruthy()
  })
})

describe("a run's figures wrap on a phone", () => {
  it("draws the status and the other figures with no separator to hang at a line's end", async () => {
    vi.stubGlobal("fetch", backend())
    draw()

    const figures = (await screen.findByText("Requested by")).closest("div")?.parentElement
    expect(figures?.textContent).toContain("Status")
    expect(figures?.querySelector("[data-slot='separator']")).toBeNull()
  })

  it("names the run's outcome once, as its stage", async () => {
    vi.stubGlobal("fetch", backend())
    draw()

    await screen.findByText("proxy 0.9.3 → 0.9.4")
    const status = screen.getByText("Status").parentElement!
    expect(within(status).getAllByText(/^done$/i)).toHaveLength(1)
  })
})
