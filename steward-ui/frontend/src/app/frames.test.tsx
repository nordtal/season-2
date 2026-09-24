import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { AppFrame } from "@/app/frames"
import type { Me } from "@/lib/api"
import { SidebarProvider } from "@/components/ui/sidebar"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The frame, actually drawn - both of its shapes.
 *
 * `shell.test.tsx` says in its own closing note that the signed-in branch is not reachable in a
 * test, because it needs a router with a route tree and a memory history. That note is why this
 * file exists: it builds exactly that - a route tree of the addresses `navigation.ts` links to,
 * and nothing behind them - so that the one thing no source rule can prove is proved by rendering.
 * **There is no header element in the document.** Till's order, and the first thing anybody would
 * put back.
 *
 * What it does not prove is what either shape looks like: jsdom has no layout, so a head spaced
 * unevenly from the list below it has the same boxes here as one that is not. That is looked at in
 * a browser, at 390 and at 1440 pixels.
 */
const ME: Me = {
  signedIn: true,
  id: "214906139328839681",
  name: "till",
  csrf: "t",
  webauthn: "required",
  relyingPartyId: "nordtal.eu",
  verified: true,
  keys: [{ id: "k1", label: "YubiKey", registeredAt: "2026-09-01T10:00:00Z" }],
}

/** Every address the navigation links to, with nothing behind it - this is about the shell. */
const PATHS = [
  "/services/$name",
  "/operations",
  "/operations/plan",
  "/operations/runs/$id",
  "/operations/backups/$id",
  "/operations/restore",
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
    ...PATHS.map((to) =>
      createRoute({ getParentRoute: () => root, path: to, component: () => <p>a page</p> }),
    ),
  ]
  const router = createRouter({
    routeTree: root.addChildren(children),
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  // The sidebar's service rows carry a health dot, so the shell opens one query on mount.
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ services: [] }), {
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
    expect(
      document.querySelector("header"),
      "The top bar went on Till's order (steward/89), in both states, and what it carried is the" +
        " island and the account picture.",
    ).toBeNull()
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
    // Asked of the trail rather than of the document: "smp" is also a row in the navigation that
    // is now standing open, so a document-wide search would pass no matter what the island does.
    drawAt("/services/smp", { open: true })

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    const island = screen.getByRole("button", { name: "Navigation" }).parentElement!
    expect(island.textContent).toContain("Steward")
    expect(island.querySelector("[data-trail]")?.getAttribute("aria-hidden")).toBe("true")
    expect(screen.getByRole("link", { name: /smp/ }).getAttribute("aria-current")).toBe("page")
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

    // The state it reports has to change, because it is what a screen reader has instead of the
    // animation.
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Navigation" }).getAttribute("aria-expanded"),
      ).toBe("false"),
    )
  })
})

describe("where the desktop's frame begins", () => {
  // At Tailwind's `sm`, not `md` (2026-09-24): a tablet held upright and a small laptop window
  // were getting the phone's dock with room for the column to spare. The cookie says open, and
  // only the desktop's column honours it, so the toggle's state tells the two frames apart.
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
    expect(dock.textContent).toContain("smp")
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

  it("names Operations' overview by its group, not as a second Overview", async () => {
    asPhone()
    drawAt("/operations")

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    expect(screen.getByRole("button", { name: "Navigation" }).parentElement!.textContent).toContain("Operations")
  })
})
