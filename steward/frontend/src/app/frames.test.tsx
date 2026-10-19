import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest"

import { AppFrame } from "@/app/frames"
import type { Me } from "@/lib/api"
import { NETWORK_MAP } from "@/lib/query-fixtures"
import { SidebarProvider } from "@/components/ui/sidebar"
import { TooltipProvider } from "@/components/ui/tooltip"

/** jsdom has no layout, so a browser checks how each shape looks. */
const ME: Me = {
  signedIn: true,
  id: "214906139328839681",
  name: "ally",
  csrf: "t",
  relyingPartyId: "nordtal.eu",
  stepUpHolds: [],
  authenticatorOffered: false,
  verified: true,
  keys: [{ id: "k1", label: "YubiKey", registeredAt: "2026-09-01T10:00:00Z" }],
}

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
    /**
     * Asks the trail, because "smp" is also an open navigation row and a search of the document would always pass.
     */
    drawAt("/services/smp", { open: true })

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    const island = screen.getByRole("button", { name: "Navigation" }).parentElement!
    expect(island.textContent).toContain("Steward")
    expect(island.querySelector("[data-trail]")?.getAttribute("aria-hidden")).toBe("true")
    expect((await screen.findByRole("link", { name: /smp/ })).getAttribute("aria-current")).toBe("page")
  })

  it.each([true, false])("carries search and account in the island with the navigation open: %s", async (open) => {
    drawAt("/services/smp", { open })

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    const island = screen.getByRole("button", { name: "Navigation" }).parentElement!
    expect(within(island).getByRole("button", { name: "Search pages" })).toBeTruthy()
    expect(within(island).getByRole("button", { name: /Account/ })).toBeTruthy()
  })

  it("puts the path after search and account, so its unfolding moves neither", async () => {
    drawAt("/services/smp", { open: false })

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    const island = screen.getByRole("button", { name: "Navigation" }).parentElement!
    const search = within(island).getByRole("button", { name: "Search pages" })
    assert.isTrue(
      Boolean(search.compareDocumentPosition(island.querySelector("[data-trail]")!) & Node.DOCUMENT_POSITION_FOLLOWING),
      "The path unfolds after the controls, which keep their offset from the island's left edge.",
    )
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

    /**
     * The reported state must change, because a screen reader gets it in place of the animation.
     */
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Navigation" }).getAttribute("aria-expanded")).toBe("false"),
    )
  })

  it("leaves the navigation open on Escape, which belongs to the layer above the page", async () => {
    drawAt("/services/smp", { open: true })
    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())

    fireEvent.keyDown(window, { key: "Escape" })
    fireEvent.keyDown(document.body, { key: "Escape" })

    expect(screen.getByRole("button", { name: "Navigation" }).getAttribute("aria-expanded")).toBe("true")
  })
})

describe("where the desktop's frame begins", () => {
  /**
   * The column starts at `sm`, not `md`, so an upright tablet gets it. Only the desktop column reads the open cookie.
   */
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
  it("draws a dock with the Steward mark and name, search and account, and the list folded", async () => {
    asPhone()
    drawAt("/services/smp")

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    const toggle = screen.getByRole("button", { name: "Navigation" })
    // The cookie says open, but the phone navigation has its own state and starts closed.
    expect(toggle.getAttribute("aria-expanded")).toBe("false")
    const dock = toggle.parentElement!
    await waitFor(() => expect(dock.textContent).toContain("Steward"))
    expect(dock.textContent).not.toContain("smp")
    expect(dock.querySelector('img[src="/icon.png"]')).toBeTruthy()
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

  it("keeps the list open on Escape", async () => {
    asPhone()
    drawAt("/services/smp")
    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())

    fireEvent.click(screen.getByRole("button", { name: "Navigation" }))
    await screen.findByRole("navigation", { name: "Pages" })
    fireEvent.keyDown(document.body, { key: "Escape" })

    expect(screen.getByRole("button", { name: "Navigation" }).getAttribute("aria-expanded")).toBe("true")
  })
})
