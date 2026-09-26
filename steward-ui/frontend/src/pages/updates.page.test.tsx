import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { UpdatesPage } from "@/pages/updates"
import { runPath } from "@/lib/run-path"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The Updates page: built like Backups, and kept apart from it.
 *
 * What is held here is what makes it a page of its own and not a second Backups: the schedule it
 * saves is `update.*`, never `backup.*`; the runs it lists are the UPDATE and RESTART rows of the
 * shared table and nothing else; and "Next" is the update clock's, which is off unless set.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

const FILE = "steward-worker/steward.yml"

function entry(over: Record<string, unknown>) {
  return {
    comments: [],
    explanation: "",
    noExplanationNeeded: true,
    filled: false,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    line: 1,
    editable: true,
    secret: false,
    inSchema: true,
    ...over,
  }
}

function workerConfig() {
  return {
    service: "steward-worker",
    name: "steward.yml",
    path: FILE,
    readable: true,
    writable: true,
    revision: "rev-1",
    header: [],
    entries: [
      entry({ path: "backup.at", key: "at", label: "At", value: "04:45" }),
      entry({ path: "backup.days", key: "days", label: "Days", kind: "LIST", value: undefined, items: ["MONDAY"] }),
      entry({ path: "update.at", key: "at", label: "At", value: "" }),
      entry({
        path: "update.days",
        key: "days",
        label: "Days",
        kind: "LIST",
        value: undefined,
        items: ["SUNDAY"],
      }),
    ],
  }
}

function run(id: number, kind: string) {
  return {
    id,
    kind,
    status: "DONE",
    source: "CONSOLE",
    requestedBy: "someone",
    actorDiscordId: "",
    actorLabel: "someone",
    system: false,
    scope: [],
    requested: "2026-09-24T10:00:00Z",
    notBefore: "2026-09-24T10:00:00Z",
    started: "2026-09-24T10:00:00Z",
    finished: "2026-09-24T10:01:00Z",
  }
}

function backend(over: { schedule?: unknown; put?: (body: unknown) => Response; runs?: unknown[] } = {}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/config") {
      return json(200, [{ service: "steward-worker", name: "steward.yml", path: FILE, readable: true, writable: true }])
    }
    if (url === `/api/config/${FILE}`) {
      if (init?.method === "PUT") return (over.put ?? (() => json(200, workerConfig())))(JSON.parse(String(init.body)))
      return json(200, workerConfig())
    }
    if (url.startsWith("/api/updates/available")) {
      return json(200, {
        checkedAt: new Date().toISOString(),
        resolvedAt: new Date().toISOString(),
        seasonPrerelease: false,
        hasWork: false,
        hasFailures: false,
        changes: [],
        unclaimed: [],
        notes: [],
      })
    }
    if (url.startsWith("/api/updates")) return json(200, over.runs ?? [])
    if (url === "/api/services") {
      return json(200, {
        services: [],
        drift: { checkedAt: new Date().toISOString(), reached: true, unverifiable: [] },
      })
    }
    if (url === "/api/schedule") {
      return json(
        200,
        over.schedule ?? {
          backupAt: "04:45",
          zone: "Europe/Berlin",
          nextBackupAt: null,
          updateAt: null,
          nextUpdateAt: null,
        },
      )
    }
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  })
}

function draw() {
  const root = createRootRoute()
  const nothing = () => null
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
        <RouterProvider router={router as never} />
      </TooltipProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("UpdatesPage - its own schedule", () => {
  it("saves the picked days under update.days and leaves backup.days alone", async () => {
    let sent: { changes: Record<string, unknown> } | undefined
    vi.stubGlobal(
      "fetch",
      backend({
        put: (body) => {
          sent = body as typeof sent
          return json(200, workerConfig())
        },
      }),
    )
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Schedule" }))
    // The fixture's update.days is Sunday alone; backup.days is Monday. Monday unpressed here is
    // what proves this dialog reads the update keys and not the backup ones.
    expect((await screen.findByRole("button", { name: "Sun" })).getAttribute("aria-pressed")).toBe("true")
    expect(screen.getByRole("button", { name: "Mon" }).getAttribute("aria-pressed")).toBe("false")

    fireEvent.click(screen.getByRole("button", { name: "Wed" }))
    fireEvent.click(screen.getByRole("button", { name: "Save" }))

    await waitFor(() => expect(sent).toBeTruthy())
    expect(sent!.changes).toEqual({ "update.days": ["WEDNESDAY", "SUNDAY"] })
  })

  it("says there is no schedule when the update clock is off", async () => {
    vi.stubGlobal("fetch", backend())
    draw()

    expect(await screen.findByText("not scheduled")).toBeTruthy()
  })

  it("says when the next update runs once one is scheduled", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        schedule: {
          backupAt: "04:45",
          zone: "Europe/Berlin",
          nextBackupAt: null,
          updateAt: "03:30",
          updateDays: ["SUNDAY"],
          nextUpdateAt: new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString(),
        },
      }),
    )
    draw()

    expect(await screen.findByText("03:30 Europe/Berlin")).toBeTruthy()
    expect(screen.queryByText("not scheduled")).toBeNull()
  })
})

describe("UpdatesPage - the runs and the buttons", () => {
  it("lists update and restart runs, and no backup or single-service run", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ runs: [run(5, "UPDATE"), run(4, "BACKUP"), run(3, "RESTART"), run(2, "DOWN"), run(1, "START")] }),
    )
    draw()

    expect(await screen.findByRole("link", { name: "#5" })).toBeTruthy()
    expect(screen.getByRole("link", { name: "#3" })).toBeTruthy()
    expect(screen.queryByRole("link", { name: "#4" })).toBeNull()
    expect(screen.queryByRole("link", { name: "#2" })).toBeNull()
    expect(screen.queryByRole("link", { name: "#1" })).toBeNull()
  })

  it("offers updating and restarting everything, and checking again", async () => {
    vi.stubGlobal("fetch", backend())
    draw()

    expect(await screen.findByRole("button", { name: /update everything/i })).toBeTruthy()
    expect(screen.getByRole("button", { name: /restart everything/i })).toBeTruthy()
    expect(screen.getByRole("button", { name: "Check again" })).toBeTruthy()
  })
})

describe("runPath", () => {
  it("sends a backup to Backups and every other kind to Updates", () => {
    expect(runPath({ id: 7, kind: "BACKUP" })).toEqual({ to: "/operations/backups/$id", params: { id: "7" } })
    expect(runPath({ id: 8, kind: "DOWN" })).toEqual({ to: "/operations/updates/$id", params: { id: "8" } })
  })
})
