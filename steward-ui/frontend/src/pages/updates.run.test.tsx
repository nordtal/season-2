import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { UpdateRunPage } from "@/pages/operations"
import { urlOf } from "@/lib/query-fixtures"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * What a run's report says it did.
 *
 * The run detail page has to move with the
 * Available card: the jar name and the pack hash both belong in the report.
 * `PlanReport` writes the version pair into the report, so
 * every surface that draws a report gets it - and this file holds the drawing end of it.
 *
 * The hash in the report is the pack that was on
 * the proxy **before** the run, written when the plan was made - the run never writes a new one
 * back, so it is a label, not a reading that could go stale. Forty characters of it
 * in a table cell would be too wide.
 */

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
        source: "CONSOLE",
        requestedBy: "hmtill",
        actorDiscordId: "",
        actorLabel: "hmtill",
        system: false,
        requested: "2026-09-20T18:00:00Z",
        notBefore: "2026-09-20T18:01:00Z",
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

describe("a run's report reads as versions, not as bookkeeping", () => {
  it("prints the jump the worker resolved and no jar name", async () => {
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
    /**
     * The fallback the worker writes when two names do not come apart - a renamed jar. It has to
     * stay visible as a filename: an invented version would be worse.
     */
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
