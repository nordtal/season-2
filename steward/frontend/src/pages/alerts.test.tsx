import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { Alerts } from "@/lib/api"
import { AlertsPage } from "@/pages/alerts"
import { TooltipProvider } from "@/components/ui/tooltip"

/** The alerts page against a fake `/api/alerts`, in a real router so every link has to resolve. */

const READING: Alerts = {
  checkedAt: "2026-10-02T12:00:00Z",
  level: "down",
  alerts: [
    {
      type: "service",
      level: "down",
      subject: "smp",
      title: { key: "alert.not-running", args: { service: { kind: "text", value: "smp" } } },
      detail: [{ key: "alert.docker-state", args: { state: { kind: "text", value: "exited" } } }],
      path: "/services/smp",
    },
  ],
  recent: [
    {
      id: 7,
      raised: "2026-10-02T11:58:00Z",
      raisedBy: "steward",
      type: "disk",
      level: "warn",
      subject: "disk",
      title: { key: "alert.disk", args: { percent: { kind: "number", value: 91 } } },
      detail: [],
      path: "/",
    },
  ],
}

function draw(reading: Alerts) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      if (url === "/api/alerts") {
        return new Response(JSON.stringify(reading), { headers: { "Content-Type": "application/json" } })
      }
      throw new Error(`the page asked for ${url}, which this test did not expect`)
    }),
  )
  const root = createRootRoute()
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/", component: AlertsPage }),
    createRoute({ getParentRoute: () => root, path: "/services/$name", component: () => null }),
  ])
  const router = createRouter({ routeTree, history: createMemoryHistory({ initialEntries: ["/"] }) })
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

describe("AlertsPage", () => {
  it("lists what is wrong now, each linking where it can be fixed, in the admin bundle's words", async () => {
    draw(READING)
    const link = await screen.findByRole("link", { name: "smp is not running" })
    expect(link.getAttribute("href")).toBe("/services/smp")
    expect(screen.getByText("Docker reports the state exited.")).toBeTruthy()
    expect(screen.getByText("down")).toBeTruthy()
  })

  it("lists every alert raised lately, with when and by whom", async () => {
    draw(READING)
    expect(await screen.findByRole("link", { name: "The disk is 91 % full" })).toBeTruthy()
    expect(screen.getByText(/by steward/)).toBeTruthy()
  })

  it("says all clear when nothing is wrong, and still lists the past", async () => {
    draw({ ...READING, level: "ok", alerts: [] })
    expect(await screen.findByText("All clear.")).toBeTruthy()
    expect(screen.getByRole("link", { name: "The disk is 91 % full" })).toBeTruthy()
  })

  it("shows a reading that failed rather than calling it clear", async () => {
    draw({ ...READING, alerts: [], unreadable: "the agent did not answer" })
    expect(await screen.findByText("the agent did not answer")).toBeTruthy()
    expect(screen.queryByText("All clear.")).toBeNull()
  })
})
