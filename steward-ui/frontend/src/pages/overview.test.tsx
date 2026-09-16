import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { act, cleanup, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { OverviewPage } from "@/pages/overview"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The Issues tile, which is not the same thing as {@link summarise}.
 *
 * `health.test.ts` proves what the function decides. This file proves what the page *says* about
 * how complete that decision is - the half that lives in `overview.tsx` and that no unit test can
 * see, because the tile's text is drawn from `waiting` and `failed` rather than from the level.
 *
 * **steward/80 moved this suite off a banner and onto a tile.** Till removed the alert-style banner
 * entirely and asked for its content to become a tile in the number row, in the same shape as
 * `Behind` - a count, and beneath it the names. The defect this file was originally written for
 * still applies to the tile: the loading state only fires while there is NOTHING to report yet, so
 * with one trigger already found - an image behind, say - the tile must not look as settled as it
 * would once every query has actually answered.
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
    name: "nordtal-s2_mc-smp-20260912T044500Z.tar.zst",
    bytes: 1_500_000_000,
    human: "1.5 GB",
    modified: new Date(Date.now() - hoursAgo * HOUR).toISOString(),
    partial: false,
  }
}

/**
 * The other file in `/backups`, which since steward/40 has to be there too.
 *
 * A fixture that carries only archives is a stack whose database has never been dumped, and the
 * tile is red about it - correctly. Every test here is about something else, so they all get one.
 */
function dump(hoursAgo: number) {
  return {
    name: "nordtal-20260912T024500Z.dump",
    bytes: 40_000_000,
    human: "40 MB",
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
    if (url === "/api/backups") return json(200, over.backups ?? [backup(2), dump(2)])
    if (url === "/api/settings") {
      return json(200, await (over.settings?.() ?? Promise.resolve({ disk: 85, memory: 90, backupAgeHours: 36 })))
    }
    // The other tiles in the row. None of them feeds `waiting` or `failed`; they answer emptily so
    // that nothing else on the page can be the reason a test passes or fails.
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
 * - including `/services/$name`, which a trigger builds with `params`.
 */
function draw() {
  const root = createRootRoute()
  const nothing = () => null
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/", component: OverviewPage }),
    createRoute({ getParentRoute: () => root, path: "/operations", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/services/$name", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/season", component: nothing }),
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
 * The Issues tile itself, re-queried on every call.
 *
 * There is exactly one tile labelled "Issues" in the number row - `Stat` renders the label as its
 * own element, and this walks up to the div that also holds the value and the hint beneath it, the
 * same div `Stat` wraps all three in.
 */
function issuesTile(): HTMLElement | null {
  const label = screen.queryByText("Issues")
  return label?.closest("div") ?? null
}

/** What the tile says altogether, or "" while it has not rendered at all. */
const said = () => issuesTile()?.textContent ?? ""

const STILL_READING = /still reading/
const COULD_NOT_READ = /could not/

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("OverviewPage - the Issues tile while /api/settings is still on its way", () => {
  it("says so beside a tile that already has something to report", async () => {
    // The defect, in one render: an image is behind, so the tile already has a count to show, and
    // it must not look as settled as it would once every query has actually answered.
    vi.stubGlobal(
      "fetch",
      backend({
        services: [service({ service: "bot", drift: "OUTDATED" })],
        settings: () => new Promise(() => {}),
      }),
    )
    draw()

    // steward/64: the tile does not print the full sentence for a trigger, only its subject
    // ("bot" rather than "bot is running an older image..."), so this test follows suit.
    await waitFor(() => expect(said()).toContain("bot"))
    expect(said()).toMatch(STILL_READING)
    // And not the other sentence: nothing failed, it is simply not finished.
    expect(said()).not.toMatch(COULD_NOT_READ)
  })

  it("keeps the plain reading placeholder while there is nothing to report at all", async () => {
    // The branch that already worked, kept here so that the fix cannot be a sentence that is now
    // printed twice, or one that replaced the quiet first render. A settled "0" must never appear
    // before every query has actually answered - that is the steward/40 trap this tile still guards
    // against, one level down from the banner it replaced.
    vi.stubGlobal("fetch", backend({ settings: () => new Promise(() => {}) }))
    draw()

    await waitFor(() => expect(issuesTile()).not.toBeNull())
    expect(said()).toMatch(/reading/)
    expect(said()).not.toContain("0")
  })

  it("drops the sentence, and only then, once the thresholds have arrived", async () => {
    vi.stubGlobal("fetch", backend({ services: [service({ service: "bot", drift: "OUTDATED" })] }))
    draw()

    await waitFor(() => expect(said()).toContain("bot"))
    expect(said()).not.toMatch(STILL_READING)
  })

  it("is judging over less than it should, and the sentence is the only warning of it", async () => {
    // End to end, and the reason the sentence is worth anything: while /api/settings is open the
    // tile is YELLOW over one trigger, and the answer adds a second, RED one - a backup older than
    // the threshold that had not arrived yet. Same stack, same moment, two verdicts.
    let answer: (value: unknown) => void = () => undefined
    const held = new Promise<unknown>((resolve) => {
      answer = resolve
    })
    vi.stubGlobal(
      "fetch",
      backend({
        services: [service({ service: "bot", drift: "OUTDATED" })],
        backups: [backup(500), dump(1)],
        settings: () => held,
      }),
    )
    draw()

    await waitFor(() => expect(said()).toContain("bot"))
    expect(said()).toMatch(STILL_READING)
    expect(issuesTile()?.innerHTML).toContain("text-warning")

    await act(async () => {
      answer({ disk: 85, memory: 90, backupAgeHours: 36 })
    })

    await waitFor(() => expect(said()).toContain("nordtal-s2_mc-smp"))
    expect(said()).toContain("bot")
    expect(said()).not.toMatch(STILL_READING)
    expect(issuesTile()?.innerHTML).toContain("text-destructive")
  })

  it("still says which of the two it is when a query actually failed", async () => {
    // `failed` and `waiting` are different sentences and the tile must not collapse them: one is
    // "not finished", the other is "will not be finished".
    vi.stubGlobal("fetch", async (url: string) => {
      if (url === "/api/settings") return json(503, { error: "Broken." })
      return backend({ services: [service({ service: "bot", drift: "OUTDATED" })] })(url)
    })
    draw()

    await waitFor(() => expect(said()).toMatch(COULD_NOT_READ))
    expect(said()).not.toMatch(STILL_READING)
  })
})

describe("OverviewPage - the tile that replaced steward/64's banner", () => {
  /**
   * The seam the ticket names by name: "a test that requires the page says something when
   * `waiting` and nothing when `ok` must fail before the rebuild" - migrated here to what the
   * ticket asks the tile to keep proving, now that there is no banner left to appear or disappear.
   *
   * Before steward/80, a healthy settled stack printed no banner at all - "ok" and "nothing read
   * yet" were told apart by presence versus absence of a whole element. The tile is never absent
   * (it is built "in the same shape as Behind", steward/80's own words, and Behind always shows a
   * number) so the same distinction now has to live in the VALUE: a placeholder while waiting, and
   * only once settled the "0" that means evidenced and fine. Those two must never read alike.
   *
   * Run against the pre-steward/80 page (the banner still standing, no Issues tile at all) this
   * fails outright: `issuesTile()` never resolves, because `screen.queryByText("Issues")` finds
   * nothing to close in on.
   */
  it("shows a placeholder while reading, and a settled zero only once a healthy stack answers", async () => {
    let answerSettings: (value: unknown) => void = () => undefined
    const held = new Promise<unknown>((resolve) => {
      answerSettings = resolve
    })
    vi.stubGlobal("fetch", backend({ settings: () => held }))
    draw()

    // Waiting: the tile already exists and already says something, and that something is not "0".
    await waitFor(() => expect(issuesTile()).not.toBeNull())
    expect(said()).not.toContain("0")

    await act(async () => {
      answerSettings({ disk: 85, memory: 90, backupAgeHours: 36 })
    })

    // Settled, and every reading is fine: the tile now shows the evidenced zero, and nothing about
    // it reads as "still reading" or "could not be read".
    await waitFor(() => expect(said()).toContain("0"))
    expect(said()).not.toMatch(STILL_READING)
    expect(said()).not.toMatch(COULD_NOT_READ)
  })
})
