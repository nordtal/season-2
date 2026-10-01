import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { UpdatesPage } from "@/pages/updates"
import { TooltipProvider } from "@/components/ui/tooltip"
import type { Available, AvailableChange } from "@/lib/api"

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === "string") return input
  if (input instanceof URL) return input.href
  return input.url
}

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

function backend(plan: Available, fresh?: Available): { fetch: typeof fetch; asked: () => unknown[] } {
  const mock = vi.fn<typeof fetch>(async (input) => {
    const url = requestUrl(input)
    if (url === "/api/updates/available?refresh") return json(200, fresh ?? plan)
    if (url === "/api/updates/available") return json(200, plan)
    if (url.startsWith("/api/updates")) return json(200, [])
    if (url === "/api/setting-groups") return json(200, [])
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
    fetch: mock,
    asked: () => mock.mock.calls.map((call) => requestUrl(call[0])),
  }
}

function nothing() {
  return null
}

function draw() {
  const root = createRootRoute()
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/operations/updates", component: UpdatesPage }),
    createRoute({ getParentRoute: () => root, path: "/operations/updates/$id", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/services/$name", component: nothing }),
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ["/operations/updates"] }),
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

/** Shows what a run would install without running one; the forced re-read is a parameter on the same GET. */
describe("the available card", () => {
  it("names the jump rather than the filename and the version", async () => {
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
    expect(screen.getByText("1.5.3")).toBeTruthy()
    expect(screen.getByText("1.5.4")).toBeTruthy()
    expect(screen.getByText("outdated")).toBeTruthy()
    /** The filename column is gone, since the version was being read out of it by eye. */
    expect(screen.queryByText("Chunky-Bukkit-1.5.3.jar")).toBeNull()
  })

  it("leaves out everything a run would not touch", async () => {
    /** Up to date rows are absent, so the states that are not work have their own tests below. */
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
            change({ artifact: "vulcan", status: "UP_TO_DATE", installed: "Vulcan-2.9.0.jar" }),
            change({ artifact: "viaversion", status: "UP_TO_DATE", installed: "ViaVersion-5.5.0.jar" }),
          ],
        }),
      ).fetch,
    )
    draw()

    await screen.findByText("chunky")
    expect(screen.queryByText("vulcan")).toBeNull()
    expect(screen.queryByText("viaversion")).toBeNull()
  })

  it("says so plainly when there is nothing rather than drawing an empty table", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        available({
          hasWork: false,
          changes: [change({ artifact: "vulcan", status: "UP_TO_DATE", installed: "Vulcan-2.9.0.jar" })],
        }),
      ).fetch,
    )
    draw()

    expect(await screen.findByText("Nothing to install")).toBeTruthy()
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

  it("says what a run would install when nothing is installed yet", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        available({
          changes: [
            change({ artifact: "chunky", status: "MISSING", version: "1.6.0", fileName: "Chunky-Bukkit-1.6.0.jar" }),
          ],
        }),
      ).fetch,
    )
    draw()

    await screen.findByText("chunky")
    expect(screen.getByText("nothing")).toBeTruthy()
    expect(screen.getByText("1.6.0")).toBeTruthy()
  })

  it("keeps the artefact with no build for this version, and calls it unsupported", async () => {
    /** Still listed though neither work nor a failure, since it answers why CoreProtect has no update. */
    vi.stubGlobal(
      "fetch",
      backend(
        available({
          hasWork: false,
          hasFailures: false,
          changes: [
            change({
              artifact: "coreprotect",
              status: "UNSUPPORTED",
              note: "Modrinth coreprotect for 26.2/paper: no stable release is tagged for this platform.",
            }),
          ],
        }),
      ).fetch,
    )
    draw()

    await screen.findByText("coreprotect")
    expect(screen.getByText("unsupported")).toBeTruthy()
    expect(screen.queryByText(/incomplete/)).toBeNull()
    /** The note belongs in the title and the badge, not in a table cell. */
    expect(screen.queryByText(/no stable release/)).toBeNull()
  })

  it("says how old the reading is", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        available({
          checkedAt: new Date(Date.now() - 4 * 60 * 60 * 1000).toISOString(),
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

    expect(await screen.findByText("4 hours ago")).toBeTruthy()
  })

  it("asks the sources again when the button is pressed, and redraws from that answer", async () => {
    /** The only way to skip steward's six hour cache, so it must replace what is on screen. */
    const wired = backend(
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
      available({
        changes: [
          change({
            artifact: "chunky",
            status: "OUTDATED",
            installed: "Chunky-Bukkit-1.5.3.jar",
            version: "1.6.0",
            fileName: "Chunky-Bukkit-1.6.0.jar",
          }),
        ],
      }),
    )
    vi.stubGlobal("fetch", wired.fetch)
    draw()

    await screen.findByText("1.5.4")
    fireEvent.click(screen.getByRole("button", { name: "Check again" }))

    expect(await screen.findByText("1.6.0")).toBeTruthy()
    expect(wired.asked()).toContain("/api/updates/available?refresh")
  })

  it("only reads - it never asks for a run", async () => {
    const wired = backend(
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
    )
    vi.stubGlobal("fetch", wired.fetch)
    draw()

    await screen.findByText("chunky")
    expect(wired.asked()).toContain("/api/updates/available")
    expect(wired.asked()).not.toContain("/api/updates")
  })
})
