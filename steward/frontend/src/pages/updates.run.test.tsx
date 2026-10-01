import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, render, screen } from "@testing-library/react"
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

const REPORT = {
  stage: "DONE",
  services: [
    {
      service: "proxy",
      state: "HEALTHY",
      changes: [
        // As PlanReport writes it: the pair, not the filename.
        { artefact: "proxy", from: "0.9.3", to: "0.9.4" },
        {
          artefact: "resource-pack",
          from: "c0bac3a03dad681347cbe4a3bc932aff8ffd8203",
          to: "0.9.4",
        },
      ],
    },
  ],
  notes: [],
}

function backend(report: unknown = REPORT): typeof fetch {
  const impl: typeof fetch = async (input) => {
    const url = urlOf(input)
    if (url.startsWith("/api/updates/79")) {
      return json(200, {
        id: 79,
        kind: "UPDATE",
        status: "DONE",
        actorKind: "HOST",
        actorId: "",
        requested: "2026-09-20T18:00:00Z",
        scheduledFor: "2026-09-20T18:01:00Z",
        countdownEnd: "2026-09-20T18:01:00Z",
        moving: [],
        started: "2026-09-20T18:01:00Z",
        finished: "2026-09-20T18:04:00Z",
        report,
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
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ["/operations/updates/79"] }),
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

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** A report names the version pair `PlanReport` writes, and a pack by its short pre-run hash. */
describe("a run's report reads as versions, not as bookkeeping", () => {
  it("prints the jump steward resolved and no jar name", async () => {
    vi.stubGlobal("fetch", backend())
    draw()

    expect(await screen.findByText("0.9.3")).toBeTruthy()
    expect(screen.getAllByText("0.9.4").length).toBeGreaterThan(0)
    expect(screen.queryByText(/\.jar/)).toBeNull()
  })

  it("shortens the pack's fingerprint and keeps the whole of it on the title", async () => {
    vi.stubGlobal("fetch", backend())
    draw()

    const short = await screen.findByText("c0bac3a0")
    expect(short.getAttribute("title")).toBe("c0bac3a03dad681347cbe4a3bc932aff8ffd8203")
    expect(screen.queryByText("c0bac3a03dad681347cbe4a3bc932aff8ffd8203")).toBeNull()
  })

  it("leaves a filename alone when that is honestly all there is", async () => {
    /** A renamed jar falls back to its filename, since an invented version would be worse. */
    vi.stubGlobal(
      "fetch",
      backend({
        stage: "DONE",
        services: [
          {
            service: "smp",
            state: "HEALTHY",
            changes: [{ artefact: "coreprotect", from: "CoreProtect.jar", to: "22.4" }],
          },
        ],
        notes: [],
      }),
    )
    draw()

    expect(await screen.findByText("CoreProtect.jar")).toBeTruthy()
  })
})
