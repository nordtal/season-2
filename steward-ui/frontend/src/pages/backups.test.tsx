import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { BackupsPage } from "@/pages/backups"

/**
 * The backup page steward/95 asked for, held to the two things it must never get wrong.
 *
 * The first is the credential: the remote target is typed here, and the whole reason that is
 * acceptable is that the two keys are `@Secret` in `StewardSpec` and therefore arrive without a
 * value. A fixture below sends one anyway - which the real worker will not do - because the
 * assertion worth having is about THIS page's own handling, not about the backend's good manners.
 *
 * The second is the fraction. A run that saved nothing and stopped the network anyway looks exactly
 * like a good one in a status badge alone, which is the argument the wireframes made on 2026-09-12
 * and the reason the column is `0 of 12` rather than a word.
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
    path: "backup.remote.endpoint",
    key: "endpoint",
    label: "Endpoint",
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

/** `steward.yml` as the worker sends it - the five remote keys, retention and the clock. */
function workerConfig(over: { secretValue?: string } = {}) {
  return {
    service: "steward-worker",
    name: "steward.yml",
    path: FILE,
    readable: true,
    writable: true,
    revision: "rev-1",
    header: [],
    entries: [
      entry({ path: "backup.keep", key: "keep", label: "Keep", value: "14", type: "INTEGER" }),
      entry({ path: "backup.at", key: "at", label: "At", value: "04:45" }),
      entry({ path: "backup.remote.endpoint", key: "endpoint", label: "Endpoint", value: "" }),
      entry({ path: "backup.remote.bucket", key: "bucket", label: "Bucket", value: "" }),
      entry({ path: "backup.remote.prefix", key: "prefix", label: "Prefix", value: "" }),
      entry({
        path: "backup.remote.access-key",
        key: "access-key",
        label: "Access key",
        secret: true,
        filled: true,
        // A value on a secret is exactly what the worker does NOT send. It is here so that this
        // page's own refusal to draw one is what the test proves.
        value: over.secretValue,
      }),
      entry({
        path: "backup.remote.secret-key",
        key: "secret-key",
        label: "Secret key",
        secret: true,
        filled: false,
        value: undefined,
      }),
    ],
  }
}

function run(over: Record<string, unknown> = {}) {
  return {
    id: 41,
    kind: "BACKUP",
    status: "FAILED",
    source: "clock",
    requestedBy: "the nightly clock",
    requested: "2026-09-17T04:45:00Z",
    notBefore: "2026-09-17T04:45:00Z",
    started: "2026-09-17T04:45:02Z",
    finished: "2026-09-17T04:46:08Z",
    report: {
      stage: "FAILED",
      notes: [],
      services: Array.from({ length: 12 }, (_, index) => ({
        service: `nordtal-s2_volume-${index}`,
        state: "FAILED",
        changes: [],
        detail: "no mount at /backup-sources",
      })),
    },
    ...over,
  }
}

function backend(over: { config?: unknown; put?: (body: unknown) => Response } = {}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/config") {
      return json(200, [
        { service: "steward-worker", name: "steward.yml", path: FILE, readable: true, writable: true },
      ])
    }
    if (url === `/api/config/${FILE}`) {
      if (init?.method === "PUT") {
        return (over.put ?? (() => json(200, workerConfig())))(JSON.parse(String(init.body)))
      }
      return json(200, over.config ?? workerConfig())
    }
    if (url === "/api/backups") {
      return json(200, [
        {
          name: "nordtal-s2_mc-smp-20260917T044500Z.tar.zst",
          bytes: 1_500_000_000,
          human: "1.5 GB",
          modified: "2026-09-17T04:45:00Z",
          partial: false,
        },
      ])
    }
    if (url.startsWith("/api/updates")) return json(200, [run()])
    if (url === "/api/schedule") {
      return json(200, { backupAt: "04:45", zone: "Europe/Berlin", nextBackupAt: null })
    }
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  })
}

function draw() {
  const root = createRootRoute()
  const nothing = () => null
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/", component: BackupsPage }),
    createRoute({ getParentRoute: () => root, path: "/operations/runs/$id", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/backups/$id", component: nothing }),
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ["/"] }),
  })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("BackupsPage - the remote target is typed here and never read back (steward/95)", () => {
  it("leaves a secret's field empty even when a value arrives with it", async () => {
    vi.stubGlobal("fetch", backend({ config: workerConfig({ secretValue: "AKIAsecret" }) }))
    draw()

    const key = (await screen.findByLabelText("Access key")) as HTMLInputElement
    expect(key.type).toBe("password")
    expect(key.value).toBe("")
    expect(document.body.textContent).not.toContain("AKIAsecret")
  })

  it("says which of the two keys is set, without saying what either is", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    const set = (await screen.findByLabelText("Access key")) as HTMLInputElement
    const unset = screen.getByLabelText("Secret key") as HTMLInputElement
    expect(set.placeholder).toContain("set")
    expect(unset.placeholder).toBe("empty")
  })

  it("has nothing to save once a typed value is put back the way it was", async () => {
    // Typed and untyped are not the same thing as changed and unchanged: the draft remembers that
    // the field was touched, and a save that sends a key back at its own value is a write to the
    // file for nothing - and, on a list of five, a revision spent for nothing.
    vi.stubGlobal("fetch", backend({}))
    draw()

    const endpoint = await screen.findByLabelText("Endpoint")
    fireEvent.change(endpoint, { target: { value: "https://fsn1.your-objectstorage.com" } })
    await waitFor(() =>
      expect((screen.getByRole("button", { name: "Save" }) as HTMLButtonElement).disabled).toBe(false),
    )

    fireEvent.change(endpoint, { target: { value: "" } })
    await waitFor(() =>
      expect((screen.getByRole("button", { name: "Save" }) as HTMLButtonElement).disabled).toBe(true),
    )
  })

  it("sends only what was typed, together with the revision it was drawn from", async () => {
    const sent: unknown[] = []
    const fetch = backend({
      put: (body) => {
        sent.push(body)
        return json(200, workerConfig())
      },
    })
    vi.stubGlobal("fetch", fetch)
    draw()

    const endpoint = await screen.findByLabelText("Endpoint")
    fireEvent.change(endpoint, { target: { value: "https://fsn1.your-objectstorage.com" } })
    fireEvent.click(screen.getByRole("button", { name: "Save" }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toEqual({
      revision: "rev-1",
      changes: { "backup.remote.endpoint": "https://fsn1.your-objectstorage.com" },
    })
  })
})

describe("BackupsPage - a run that saved nothing does not look like one that worked", () => {
  it("prints the fraction, not just the status", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    expect(await screen.findByText("0 of 12")).toBeTruthy()
  })
})

describe("BackupsPage - the numbers it opens with", () => {
  it("reads retention out of the same file the remote target is saved in", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    // Waited for rather than read once: the tile draws a dash until the config document lands,
    // which is the same dash it would draw if this page never read `backup.keep` at all.
    await waitFor(() =>
      expect(screen.getByText("Keep").parentElement?.textContent).toContain("14"),
    )
  })

  it("says there is no nightly clock rather than drawing a next run there is none of", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    expect(await screen.findByText("no nightly clock")).toBeTruthy()
  })
})
