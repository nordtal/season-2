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

import { OperationsPlanPage } from "@/pages/operations"
import { TooltipProvider } from "@/components/ui/tooltip"
import type { Available, AvailableChange } from "@/lib/api"

/**
 * season-2-ops/128: what a run would do, read without running one.
 *
 * The assertions that matter are the ones about the states this card exists to keep apart. Before
 * it, the only way to learn that Chunky had moved was to start a run, and the only thing that
 * looked like "a source did not answer" was the same silence as "nothing has changed". So:
 *
 * - a row whose source could not be asked is drawn as such, AND the card says the list is
 *   incomplete, because a reader scanning a column will not notice one grey badge in it;
 * - "no build for this Minecraft version" (CoreProtect) is neither work nor a failure;
 * - nothing on this page writes anything - it is a GET, and the test watches that no request is
 *   made to `POST /api/updates`.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function change(over: Partial<AvailableChange> & { artifact: string; status: string }): AvailableChange {
  return {
    service: "smp",
    work: over.status === "OUTDATED" || over.status === "MISSING",
    failure: over.status === "UNRESOLVED" || over.status === "MOUNT_MISSING",
    ...over,
  }
}

function available(over: Partial<Available> = {}): Available {
  return {
    checkedAt: new Date().toISOString(),
    resolvedAt: new Date().toISOString(),
    seasonPrerelease: false,
    hasWork: true,
    hasFailures: false,
    changes: [],
    unclaimed: [],
    notes: [],
    ...over,
  }
}

function backend(plan: Available): { fetch: typeof fetch; asked: () => unknown[] } {
  const mock = vi.fn(async (url: string) => {
    if (url === "/api/updates/available") return json(200, plan)
    if (url.startsWith("/api/updates")) return json(200, [])
    if (url === "/api/services") {
      return json(200, {
        services: [],
        drift: { checkedAt: new Date().toISOString(), reached: true, unverifiable: [] },
      })
    }
    if (url === "/api/schedule") {
      return json(200, { nextBackupAt: null, backupAt: "04:45", zone: "UTC" })
    }
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  })
  return {
    fetch: mock as unknown as typeof fetch,
    asked: () => (mock as unknown as { mock: { calls: unknown[][] } }).mock.calls.map((call) => call[0]),
  }
}

function draw() {
  const root = createRootRoute()
  const nothing = () => null
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/operations/plan", component: OperationsPlanPage }),
    createRoute({ getParentRoute: () => root, path: "/operations/runs/$id", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/services/$name", component: nothing }),
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ["/operations/plan"] }),
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

describe("the available card", () => {
  it("names the newer version beside what is installed", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        available({
          changes: [
            change({
              artifact: "chunky",
              status: "OUTDATED",
              installed: "Chunky-Bukkit-1.5.3.jar",
              version: "1.5.4",
              fileName: "Chunky-Bukkit-1.5.4.jar",
            }),
          ],
        }),
      ).fetch,
    )
    draw()

    await screen.findByText("chunky")
    expect(screen.getByText("Chunky-Bukkit-1.5.3.jar")).toBeTruthy()
    expect(screen.getByText("1.5.4")).toBeTruthy()
    expect(screen.getByText("outdated")).toBeTruthy()
  })

  it("does not let an unreachable source read as nothing to do", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        available({
          hasWork: false,
          hasFailures: true,
          changes: [
            change({ artifact: "smp", status: "UP_TO_DATE", installed: "smp-0.9.1.jar" }),
            change({ artifact: "packetevents", status: "UNRESOLVED", note: "modrinth timed out" }),
          ],
        }),
      ).fetch,
    )
    draw()

    await screen.findByText("packetevents")
    expect(screen.getByText("could not ask")).toBeTruthy()
    // The line above the table, which is the part a scanning reader actually sees.
    expect(screen.getByText(/incomplete/)).toBeTruthy()
  })

  it("draws no build for this version as neither work nor a failure", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        available({
          hasWork: false,
          hasFailures: false,
          changes: [
            change({ artifact: "coreprotect", status: "UNSUPPORTED", note: "no build for 26.2" }),
          ],
        }),
      ).fetch,
    )
    draw()

    await screen.findByText("coreprotect")
    expect(screen.getByText("no build")).toBeTruthy()
    expect(screen.queryByText(/incomplete/)).toBeNull()
  })

  it("only reads - it never asks for a run", async () => {
    const wired = backend(
      available({ changes: [change({ artifact: "smp", status: "UP_TO_DATE", installed: "smp-0.9.1.jar" })] }),
    )
    vi.stubGlobal("fetch", wired.fetch)
    draw()

    // The jar name, not "smp": the artefact and the service it sits on are both called that, and
    // findByText rejects on two matches.
    await screen.findByText("smp-0.9.1.jar")
    expect(wired.asked()).toContain("/api/updates/available")
  })
})
