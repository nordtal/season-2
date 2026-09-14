import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ZustandPage } from "@/pages/zustand"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The Ampel on the page, which is not the same thing as {@link summarise}.
 *
 * `health.test.ts` proves what the function decides. This file proves what the page *says* about
 * how complete that decision is - the half that lives in `zustand.tsx` and that no unit test can
 * see, because the sentence under the light is drawn from `waiting` and `failed` rather than from
 * the level.
 *
 * The defect it was written for: the loading state above the light only fires while there is
 * NOTHING to report. With one trigger already found - an image behind, say - the page drew a
 * definite yellow light while `/api/settings` was still on its way, with no sentence saying so.
 * And the answer can turn that yellow red, because the age of the newest backup is one of the
 * three checks that need a threshold. A light that looks final over a reading that is not is the
 * failure this page argues against everywhere else.
 *
 * The page is rendered inside a real memory router rather than behind a stubbed `Link`: the
 * triggers carry `to` and `params`, and a stub would happily draw a link to a route that does not
 * exist. The tree below therefore has the four routes the page links into, and nothing else.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

const HOUR = 3_600_000

/** A host with room to spare, so only the value under test can trip anything. */
const HOST = {
  memoryTotalBytes: 8_000_000_000,
  memoryAvailableBytes: 4_000_000_000,
  diskTotalBytes: 100_000_000_000,
  diskUsedBytes: 40_000_000_000,
  containerLimits: "none",
}

function service(over: Record<string, unknown> = {}) {
  return {
    service: "smp",
    containerId: "abc",
    image: "ghcr.io/nordtal/smp:1",
    state: "running",
    status: "Up 3 hours (healthy)",
    hasConsole: true,
    drift: "UP_TO_DATE",
    health: "healthy",
    ...over,
  }
}

function backup(hoursAgo: number) {
  return {
    name: "nordtal.tar.zst",
    bytes: 1_500_000_000,
    human: "1,5 GB",
    modified: new Date(Date.now() - hoursAgo * HOUR).toISOString(),
    partial: false,
  }
}

/**
 * Everything the start page asks for.
 *
 * `settings` is a function so that a test can hold `/api/settings` open and let it answer in the
 * middle - which is the whole state under test and cannot be reached with a fixed answer.
 */
function backend(over: { services?: unknown[]; backups?: unknown[]; settings?: () => Promise<unknown> }) {
  return vi.fn(async (url: string) => {
    if (url === "/api/services") {
      return json(200, {
        services: over.services ?? [service()],
        drift: { checkedAt: new Date().toISOString(), reached: true, unverifiable: [] },
      })
    }
    if (url === "/api/host") return json(200, HOST)
    if (url === "/api/backups") return json(200, over.backups ?? [backup(2)])
    if (url === "/api/settings") {
      return json(200, await (over.settings?.() ?? Promise.resolve({ disk: 85, memory: 90, backupAgeHours: 36 })))
    }
    // The four tiles below the light. None of them feeds `waiting` or `failed`; they answer
    // emptily so that nothing else on the page can be the reason a test passes or fails.
    if (url.startsWith("/api/metrics")) return json(200, { points: [] })
    if (url.startsWith("/api/updates")) return json(200, [])
    if (url === "/api/season") return json(200, { phase: "SMP", launch: null, smpStart: null })
    if (url.startsWith("/api/journal")) return json(200, [])
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  })
}

/**
 * The page under a router whose tree is only what it links into.
 *
 * `/` carries the page itself, so `useRouterState` and every `Link` resolve against a real router
 * - including `/dienste/$name`, which a trigger builds with `params`.
 */
function draw() {
  const root = createRootRoute()
  const nothing = () => null
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/", component: ZustandPage }),
    createRoute({ getParentRoute: () => root, path: "/betrieb", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/dienste/$name", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/saison", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/journal", component: nothing }),
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ["/"] }),
  })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  // The provider the Shell normally supplies: the service table's badges are Radix tooltips and
  // throw without one, which the router turns into its error boundary rather than into a failure
  // anybody could read.
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RouterProvider router={router as never} />
      </TooltipProvider>
    </QueryClientProvider>,
  )
}

/**
 * The light itself, re-queried on every call.
 *
 * `role="status"` is NOT unique on this page: `Loading` carries it too, on every tile that has not
 * answered yet. The two are told apart by `aria-busy`, which only the skeleton sets - and holding
 * on to a node found once would be worse than ambiguous, because the loading placeholder above the
 * light is a different element that React swaps out rather than updates.
 */
function light(): HTMLElement | null {
  const lights = screen.queryAllByRole("status").filter((one) => !one.hasAttribute("aria-busy"))
  if (lights.length > 1) throw new Error(`${lights.length} Ampeln on one page`)
  return lights[0] ?? null
}

/** What the light says, or "" while it is not drawn at all. */
const said = () => light()?.textContent ?? ""

const STILL_READING = /Es wird noch gelesen/
const COULD_NOT_READ = /konnte nicht gelesen werden/

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("ZustandPage - the Ampel while /api/settings is still on its way", () => {
  it("says so under a light that already has something to report", async () => {
    // The defect, in one render: an image is behind, so the loading state above the light does not
    // fire, and the light is drawn. What was missing is the second sentence - without it the page
    // looks as though it has judged everything, over a stack it has not finished reading.
    vi.stubGlobal(
      "fetch",
      backend({
        services: [service({ service: "bot", drift: "OUTDATED" })],
        settings: () => new Promise(() => {}),
      }),
    )
    draw()

    await waitFor(() => expect(said()).toContain("älteren Image"))
    expect(said()).toMatch(STILL_READING)
    // And not the other sentence: nothing failed, it is simply not finished.
    expect(said()).not.toMatch(COULD_NOT_READ)
  })

  it("keeps the plain loading state while there is nothing to report at all", async () => {
    // The branch that already worked, kept here so that the fix cannot be a sentence that is now
    // printed twice, or one that replaced the quiet first render.
    vi.stubGlobal("fetch", backend({ settings: () => new Promise(() => {}) }))
    draw()

    expect(await screen.findByText(/Zustand wird gelesen…/)).toBeTruthy()
    expect(light()).toBeNull()
  })

  it("drops the sentence, and only then, once the thresholds have arrived", async () => {
    vi.stubGlobal("fetch", backend({ services: [service({ service: "bot", drift: "OUTDATED" })] }))
    draw()

    await waitFor(() => expect(said()).toContain("älteren Image"))
    expect(said()).not.toMatch(STILL_READING)
  })

  it("is judging over less than it should, and the sentence is the only warning of it", async () => {
    // End to end, and the reason the sentence is worth anything: while /api/settings is open the
    // light is YELLOW over one trigger, and the answer adds a second, RED one - a backup older
    // than the threshold that had not arrived yet. Same stack, same moment, two verdicts.
    let answer: (value: unknown) => void = () => undefined
    const held = new Promise<unknown>((resolve) => {
      answer = resolve
    })
    vi.stubGlobal(
      "fetch",
      backend({
        services: [service({ service: "bot", drift: "OUTDATED" })],
        backups: [backup(500)],
        settings: () => held,
      }),
    )
    draw()

    await waitFor(() => expect(within(light()!).getAllByRole("listitem")).toHaveLength(1))
    expect(said()).toMatch(STILL_READING)

    await act(async () => {
      answer({ disk: 85, memory: 90, backupAgeHours: 36 })
    })

    await waitFor(() => expect(within(light()!).getAllByRole("listitem")).toHaveLength(2))
    expect(said()).toContain("älter als die erlaubten")
    expect(said()).not.toMatch(STILL_READING)
  })

  it("still says which of the two it is when a query actually failed", async () => {
    // `failed` and `waiting` are different sentences and the page must not collapse them: one is
    // "not finished", the other is "will not be finished".
    vi.stubGlobal("fetch", async (url: string) => {
      if (url === "/api/settings") return json(503, { error: "Kaputt." })
      return backend({ services: [service({ service: "bot", drift: "OUTDATED" })] })(url)
    })
    draw()

    await waitFor(() => expect(said()).toMatch(COULD_NOT_READ))
    expect(said()).not.toMatch(STILL_READING)
  })
})
