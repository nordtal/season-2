import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { UpdateRunPage } from "@/pages/operations"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * What a run's report says it did (season-2-ops/142).
 *
 * Till, in the review on 2026-09-20, reported that the run detail page had not moved with the
 * Available card: the jar name was still there, and the pack still showed its old hash. The jar
 * half is fixed where it is decided - `PlanReport` now writes the version pair into the report, so
 * every surface that draws a report gets it - and this file holds the drawing end of it.
 *
 * The pack half was measured before it was changed. The hash in the report is the pack that was on
 * the proxy **before** the run, written when the plan was made, and the run never writes a new one
 * back - so it was a label that was missing, not a reading that was stale. Forty characters of it
 * in a table cell is the part that was wrong.
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
        // As PlanReport writes it since 2026-09-20: the pair, not the filename.
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
  return vi.fn(async (url: string) => {
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
  }) as unknown as typeof fetch
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
        <RouterProvider router={router as never} />
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
    // The fallback the worker writes when two names do not come apart - a renamed jar. It has to
    // stay visible as a filename: an invented version would be worse.
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
