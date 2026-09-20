import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
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
 * - nothing on this page starts a run, and the forced re-read is a read as well: a parameter on
 *   the same GET, because it costs a great deal and changes nothing.
 *
 * season-2-ops/142 narrowed what the card draws without changing what it is for. The rows that
 * have nothing in them are gone, the two version columns became one jump, and the header lost its
 * sentence and gained a button. The states above are all still asserted here, because the whole
 * risk of "show less" is that the row that mattered was one of the ones removed.
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

function backend(plan: Available, fresh?: Available): { fetch: typeof fetch; asked: () => unknown[] } {
  const mock = vi.fn(async (url: string) => {
    if (url === "/api/updates/available?refresh") return json(200, fresh ?? plan)
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
    // The two columns this replaced. The filename is bookkeeping, and it is what the operator was
    // reading a version out of by eye before this.
    expect(screen.queryByText("Chunky-Bukkit-1.5.3.jar")).toBeNull()
  })

  it("leaves out everything a run would not touch", async () => {
    // The card listed thirty rows to say one thing. Everything that is up to date is now absent,
    // and absent means "there is nothing to do about it" - which is why the states that are NOT
    // work have their own tests below.
    vi.stubGlobal(
      "fetch",
      backend(
        available({
          changes: [
            change({ artifact: "chunky", status: "OUTDATED", installed: "Chunky-Bukkit-1.5.3.jar", version: "1.5.4", fileName: "Chunky-Bukkit-1.5.4.jar" }),
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
    // Neither work nor a failure, and still on the list: it is the answer to "why is CoreProtect
    // not here", and a row that disappears when it is nothing to worry about cannot give it.
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
    // The note is a paragraph about stable releases and platforms. It belongs on the dash as a
    // title and in the badge, not in a table cell.
    expect(screen.queryByText(/no stable release/)).toBeNull()
  })

  it("says how old the reading is, in the header and in one line", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        available({
          checkedAt: new Date(Date.now() - 4 * 60 * 60 * 1000).toISOString(),
          changes: [change({ artifact: "chunky", status: "OUTDATED", installed: "Chunky-Bukkit-1.5.3.jar", version: "1.5.4", fileName: "Chunky-Bukkit-1.5.4.jar" })],
        }),
      ).fetch,
    )
    draw()

    expect(await screen.findByText(/Last checked 4 hours ago/)).toBeTruthy()
  })

  it("asks the sources again when the button is pressed, and redraws from that answer", async () => {
    // The reading is cached for six hours in the worker, and this button is the only way to
    // shorten that from the interface. It has to replace what is on screen, or an operator who
    // pressed it has no way of telling whether anything happened.
    const wired = backend(
      available({
        changes: [change({ artifact: "chunky", status: "OUTDATED", installed: "Chunky-Bukkit-1.5.3.jar", version: "1.5.4", fileName: "Chunky-Bukkit-1.5.4.jar" })],
      }),
      available({
        changes: [change({ artifact: "chunky", status: "OUTDATED", installed: "Chunky-Bukkit-1.5.3.jar", version: "1.6.0", fileName: "Chunky-Bukkit-1.6.0.jar" })],
      }),
    )
    vi.stubGlobal("fetch", wired.fetch)
    draw()

    await screen.findByText("1.5.4")
    fireEvent.click(screen.getByRole("button", { name: "Ask the sources again" }))

    expect(await screen.findByText("1.6.0")).toBeTruthy()
    expect(wired.asked()).toContain("/api/updates/available?refresh")
  })

  it("only reads - it never asks for a run", async () => {
    const wired = backend(
      available({
        changes: [change({ artifact: "chunky", status: "OUTDATED", installed: "Chunky-Bukkit-1.5.3.jar", version: "1.5.4", fileName: "Chunky-Bukkit-1.5.4.jar" })],
      }),
    )
    vi.stubGlobal("fetch", wired.fetch)
    draw()

    await screen.findByText("chunky")
    expect(wired.asked()).toContain("/api/updates/available")
    expect(wired.asked()).not.toContain("/api/updates")
  })
})
