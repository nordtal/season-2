import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { toast } from "sonner"
import { afterEach, describe, expect, it, vi } from "vitest"

import { BackupDestinationsPanel } from "@/pages/backup-destinations"
import { TooltipProvider } from "@/components/ui/tooltip"

vi.mock("sonner", () => ({
  toast: {
    success: vi.fn<(message: string) => void>(),
    error: vi.fn<(message: string) => void>(),
    info: vi.fn<(message: string) => void>(),
  },
}))

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } })
}

const ACCOUNT = "6c0b3a52-0f4e-4c1c-9d8a-2b4f8e1d7a10"

function destinations(over: Record<string, unknown> = {}) {
  return {
    offered: true,
    accounts: [
      {
        id: ACCOUNT,
        email: "backups@example.com",
        folderId: "folder-1",
        folderName: "Nordtal backups",
        connected: true,
        connectedAt: "2026-11-20T10:00:00Z",
      },
    ],
    series: [
      {
        series: "stack",
        accountId: ACCOUNT,
        email: "backups@example.com",
        lastCopiedAt: "2026-11-23T03:00:00Z",
        lastSnapshotId: "4f1c2d",
        lastBytes: 2048,
        lastError: "rclone could not reach Drive",
        lastFailedAt: "2026-11-22T03:00:00Z",
      },
      { series: "plane" },
    ],
    repositories: [
      { id: "r-1", accountId: ACCOUNT, series: "stack", subpath: "stack-1", createdAt: "2026-11-20T10:00:00Z" },
      {
        id: "r-2",
        accountId: ACCOUNT,
        series: "plane",
        subpath: "plane-1",
        createdAt: "2026-11-20T10:00:00Z",
        recoveryDownloadedAt: "2026-11-21T10:00:00Z",
      },
    ],
    ...over,
  }
}

type Sent = { url: string; method: string; body?: unknown }

function backend(answer: unknown, sent: Sent[]) {
  return vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
    const method = init?.method ?? "GET"
    sent.push({ url, method, body: init?.body ? JSON.parse(init.body) : undefined })
    if (url === "/api/backup-destinations" && method === "GET") return json(200, answer)
    if (url === "/api/backup-destinations/google/start") {
      return json(200, { authorizeUrl: "https://accounts.google.com/o/oauth2/v2/auth?state=s" })
    }
    if (url.startsWith("/api/backup-destinations/series/")) return new Response(null, { status: 204 })
    if (url.endsWith("/recovery")) {
      return json(200, { name: "nordtal-stack-backup-recovery.txt", text: "Restic password: not the real one" })
    }
    if (url.endsWith("/disconnect")) return new Response(null, { status: 204 })
    throw new Error(`the panel asked for ${method} ${url}, which this test did not expect`)
  })
}

function draw(at = "/operations/backups") {
  const root = createRootRoute()
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/operations/backups", component: BackupDestinationsPanel }),
  ])
  const history = createMemoryHistory({ initialEntries: [at] })
  const router = createRouter({ routeTree, history })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RouterProvider router={router} />
      </TooltipProvider>
    </QueryClientProvider>,
  )
  return { history }
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe("BackupDestinationsPanel", () => {
  it("says how to set Google Drive up when steward has no client", async () => {
    vi.stubGlobal("fetch", backend(destinations({ offered: false }), []))
    draw()

    expect(await screen.findByText("Google Drive is not set up")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Connect Google account" })).toBeNull()
  })

  it("shows where each series copies, how it copied last and which recovery files are left", async () => {
    vi.stubGlobal("fetch", backend(destinations(), []))
    draw()

    const stack = await screen.findByRole("combobox", { name: "Stack backups copy to" })
    expect(stack.textContent).toContain("backups@example.com")
    expect(screen.getByRole("combobox", { name: "Plane backups copy to" }).textContent).toContain("No account, no copy")
    expect(screen.getByText(/, 2\.0 KiB$/)).toBeTruthy()
    expect(screen.getByText(/rclone could not reach Drive/)).toBeTruthy()
    expect(screen.getByText("Folder Nordtal backups")).toBeTruthy()
    expect(screen.getByRole("button", { name: "Download the recovery file of the stack backups" })).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Download the recovery file of the Plane backups" })).toBeNull()
    expect(screen.getByText(/Recovery file of the Plane backups downloaded/)).toBeTruthy()
  })

  it("saves a series' account with the account's id and none with null", async () => {
    const sent: Sent[] = []
    vi.stubGlobal("fetch", backend(destinations(), sent))
    draw()

    fireEvent.keyDown(await screen.findByRole("combobox", { name: "Plane backups copy to" }), { key: "Enter" })
    fireEvent.click(await screen.findByRole("option", { name: "backups@example.com" }))
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith("Destination saved"))

    fireEvent.keyDown(screen.getByRole("combobox", { name: "Stack backups copy to" }), { key: "Enter" })
    fireEvent.click(await screen.findByRole("option", { name: "No account, no copy" }))

    await waitFor(() =>
      expect(sent.filter((request) => request.method === "PUT")).toEqual([
        { url: "/api/backup-destinations/series/plane", method: "PUT", body: { accountId: ACCOUNT } },
        { url: "/api/backup-destinations/series/stack", method: "PUT", body: { accountId: null } },
      ]),
    )
  })

  it("sends the browser to Google's consent", async () => {
    const assign = vi.fn<(url: string) => void>()
    vi.stubGlobal("location", { assign })
    vi.stubGlobal("fetch", backend(destinations(), []))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Connect Google account" }))

    await waitFor(() => expect(assign).toHaveBeenCalledWith("https://accounts.google.com/o/oauth2/v2/auth?state=s"))
  })

  it("saves the recovery file as a download", async () => {
    const created = vi.fn<(blob: Blob) => string>(() => "blob:recovery")
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: created, revokeObjectURL: vi.fn<() => void>() }))
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined)
    const sent: Sent[] = []
    vi.stubGlobal("fetch", backend(destinations(), sent))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Download the recovery file of the stack backups" }))

    await waitFor(() => expect(clicked).toHaveBeenCalled())
    expect(await created.mock.calls[0]?.[0].text()).toBe("Restic password: not the real one")
    expect(sent).toContainEqual({
      url: `/api/backup-destinations/accounts/${ACCOUNT}/repositories/stack/recovery`,
      method: "POST",
      body: undefined,
    })
  })

  it("names the outcome of a consent once and drops it from the address", async () => {
    vi.stubGlobal("fetch", backend(destinations(), []))
    const { history } = draw("/operations/backups?google=declined")

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith("Google access was declined. Nothing was connected."))
    await waitFor(() => expect(history.location.search).toBe(""))
    expect(toast.error).toHaveBeenCalledTimes(1)
  })
})
