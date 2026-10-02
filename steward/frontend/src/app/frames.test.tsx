import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest"

import { AppFrame } from "@/app/frames"
import type { Me } from "@/lib/api"
import { NETWORK_MAP } from "@/lib/query-fixtures"
import { SidebarProvider } from "@/components/ui/sidebar"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * Renders both shapes of the frame through a real route tree, proving there is no header element.
 *
 * jsdom has no layout, so how either shape looks is checked in a browser.
 */
const ME: Me = {
  signedIn: true,
  id: "214906139328839681",
  name: "ally",
  csrf: "t",
  webauthn: "required",
  relyingPartyId: "nordtal.eu",
  stepUpMinutes: 15,
  verified: true,
  keys: [{ id: "k1", label: "YubiKey", registeredAt: "2026-09-01T10:00:00Z" }],
}

/** Every address the navigation links to, with nothing behind it. */
const PATHS = [
  "/services/$name",
  "/operations/updates",
  "/operations/updates/$id",
  "/operations/backups",
  "/operations/backups/$id",
  "/season",
  "/access",
  "/payments",
  "/journal",
]

function drawAt(path: string, { open = true }: { open?: boolean } = {}) {
  const root = createRootRoute({
    component: () => (
      <TooltipProvider delayDuration={300}>
        <SidebarProvider defaultOpen={open}>
          <AppFrame me={ME} />
        </SidebarProvider>
      </TooltipProvider>
    ),
  })
  const children = [
    createRoute({ getParentRoute: () => root, path: "/", component: () => <p>a page</p> }),
    ...PATHS.map((to) => createRoute({ getParentRoute: () => root, path: to, component: () => <p>a page</p> })),
  ]
  const router = createRouter({
    routeTree: root.addChildren(children),
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  // The sidebar lists the served services, each with a health dot, so the shell opens two queries on mount.
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async (url: string) =>
        new Response(JSON.stringify(url === "/api/topology" ? NETWORK_MAP : { services: [] }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
    ),
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** The phone's shape is chosen by the window's width on the first render, so the width is set first. */
function asPhone() {
  atWidth(390)
}

function atWidth(value: number) {
  Object.defineProperty(window, "innerWidth", { configurable: true, writable: true, value })
}

afterEach(() => {
  Object.defineProperty(window, "innerWidth", { configurable: true, writable: true, value: 1024 })
})

describe("the frame on a desktop", () => {
  it("draws the page it was asked for, with no header anywhere", async () => {
    drawAt("/services/smp")

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    assert.isNull(
      document.querySelector("header"),
      "The top bar is gone in both states, and what it carried is the island and the account picture.",
    )
  })

  it("puts the path of the page in the island while the navigation is closed", async () => {
    drawAt("/services/smp", { open: false })

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    const island = screen.getByRole("button", { name: "Navigation" }).parentElement!
    expect(island.textContent).toContain("Steward")
    expect(island.querySelector("[data-trail]")?.textContent).toContain("smp")
    expect(island.querySelector("[data-trail]")?.getAttribute("aria-hidden")).not.toBe("true")
    expect(screen.getByRole("complementary", { hidden: true }).getAttribute("aria-hidden")).toBe("true")
  })

  it("folds the path away while the navigation is open, and keeps the mark", async () => {
    /** Asked of the trail, since "smp" is also an open navigation row and a document search would always pass. */
    drawAt("/services/smp", { open: true })

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    const island = screen.getByRole("button", { name: "Navigation" }).parentElement!
    expect(island.textContent).toContain("Steward")
    expect(island.querySelector("[data-trail]")?.getAttribute("aria-hidden")).toBe("true")
    /** The service rows come with `/api/topology`, a moment after the page. */
    expect((await screen.findByRole("link", { name: /smp/ })).getAttribute("aria-current")).toBe("page")
  })

  it("has the account within reach of the island, and opens the settings from it", async () => {
    drawAt("/")

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    fireEvent.click(screen.getByRole("button", { name: /Account/ }))

    expect(await screen.findByText("YubiKey")).toBeTruthy()
    expect(screen.getByRole("button", { name: /Sign out/ })).toBeTruthy()
  })

  it("answers the navigation toggle", async () => {
    drawAt("/")
    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())

    const toggle = screen.getByRole("button", { name: "Navigation" })
    expect(toggle.getAttribute("aria-expanded")).toBe("true")
    fireEvent.click(toggle)

    /** The reported state must change, since a screen reader gets it instead of the animation. */
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Navigation" }).getAttribute("aria-expanded")).toBe("false"),
    )
  })
})

describe("where the desktop's frame begins", () => {
  /** At `sm`, not `md`, so an upright tablet gets the column; only the desktop column honours the open cookie. */
  it.each([
    [640, "true"],
    [700, "true"],
    [639, "false"],
  ])("at %ipx the navigation starts expanded: %s", async (width, expanded) => {
    atWidth(width)
    drawAt("/services/smp")

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    expect(screen.getByRole("button", { name: "Navigation" }).getAttribute("aria-expanded")).toBe(expanded)
  })
})

describe("the frame on a phone", () => {
  it("draws a dock with the page's name, search and account, and the list folded", async () => {
    asPhone()
    drawAt("/services/smp")

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    const toggle = screen.getByRole("button", { name: "Navigation" })
    // The cookie says open; the phone's navigation has its own state and starts closed.
    expect(toggle.getAttribute("aria-expanded")).toBe("false")
    const dock = toggle.parentElement!
    await waitFor(() => expect(dock.textContent).toContain("smp"))
    expect(within(dock).getByRole("button", { name: "Search pages" })).toBeTruthy()
    expect(within(dock).getByRole("button", { name: /Account/ })).toBeTruthy()
    expect(screen.queryByRole("navigation", { name: "Pages" })).toBeNull()
  })

  it("opens the list inside the dock and closes it again when a place is tapped", async () => {
    asPhone()
    drawAt("/services/smp")
    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())

    fireEvent.click(screen.getByRole("button", { name: "Navigation" }))
    const list = await screen.findByRole("navigation", { name: "Pages" })
    fireEvent.click(within(list).getByRole("link", { name: /limbo/ }))

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Navigation" }).getAttribute("aria-expanded")).toBe("false"),
    )
  })

  it("names the Updates page, not a second Overview", async () => {
    asPhone()
    drawAt("/operations/updates")

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    expect(screen.getByRole("button", { name: "Navigation" }).parentElement!.textContent).toContain("Updates")
  })
})
