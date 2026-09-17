import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type { ReactNode } from "react"

import { ShellA, ShellB, ShellC } from "@/app/frames"
import type { Me } from "@/lib/api"
import { SidebarProvider } from "@/components/ui/sidebar"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The three shells of steward/89, each actually drawn.
 *
 * `shell.test.tsx` says in its own closing note that the signed-in branch is not reachable in a
 * test, because it needs a router with a route tree and a memory history. That note is why this
 * file exists: it builds exactly that - a route tree of the addresses `navigation.ts` links to,
 * and nothing behind them - so that the one thing no source rule can prove is proved by rendering.
 * **There is no header element in the document.** Till's order, and the first thing anybody would
 * put back.
 *
 * What it does not prove is what a phone looks like: jsdom has no layout, so an island drawn 80px
 * off the right edge has the same box here as one that fits. That is measured in a browser, and
 * `fits-on-a-phone.test.ts` says where.
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
  "/accounts",
  "/journal",
  "/settings",
]

function drawAt(path: string, frame: (me: Me) => ReactNode) {
  const root = createRootRoute({
    component: () => (
      <TooltipProvider delayDuration={300}>
        <SidebarProvider defaultOpen>{frame(ME)}</SidebarProvider>
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

const shells: Array<[string, (me: Me) => ReactNode]> = [
  ["A", (me) => <ShellA me={me} />],
  ["B", (me) => <ShellB me={me} />],
  ["C", (me) => <ShellC me={me} />],
]

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

describe.each(shells)("shell %s", (name, frame) => {
  it("draws the page it was asked for, with no header anywhere", async () => {
    drawAt("/services/smp", frame)

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    expect(
      document.querySelector("header"),
      `Shell ${name} has a header element. The top bar went on Till's order (steward/89), in both` +
        " states, and what it carried is the island and the account picture.",
    ).toBeNull()
  })

  it("puts the path of the page in the island", async () => {
    drawAt("/services/smp", frame)

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    const island = screen.getAllByRole("button", { name: "Navigation" })[0]
    expect(island).toBeTruthy()
    // The crumb is inside the island in shell C and beside its toggle in A and B, so the question
    // is asked of the document: the path of the page is on the screen without a bar to carry it.
    expect(document.body.textContent).toContain("smp")
  })

  it("has the account within reach of the island, and opens the settings from it", async () => {
    drawAt("/", frame)

    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())
    fireEvent.click(screen.getByRole("button", { name: /Account/ }))

    expect(await screen.findByText("YubiKey")).toBeTruthy()
    expect(screen.getByRole("button", { name: /Sign out/ })).toBeTruthy()
  })

  it("answers the navigation toggle", async () => {
    drawAt("/", frame)
    await waitFor(() => expect(screen.getByText("a page")).toBeTruthy())

    const toggle = screen.getAllByRole("button", { name: "Navigation" })[0]
    fireEvent.click(toggle)

    // Whatever the toggle does in this shell - collapse a column, or drop a panel - the state it
    // reports has to change, because it is what a screen reader has instead of the animation.
    await waitFor(() =>
      expect(
        screen.getAllByRole("button", { name: "Navigation" })[0].getAttribute("aria-expanded"),
      ).toBe(name === "C" ? "true" : "false"),
    )
  })
})
