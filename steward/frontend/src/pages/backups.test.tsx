import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { Person } from "@/lib/api"
import { BackupRunDetailPage, BackupsPage } from "@/pages/backups"
import { asAnchor, asButton, asElement, asInput } from "@/lib/test-elements"
import { changesOf } from "@/lib/query-fixtures"

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

const FILE = "steward/steward"
const IDENTIFIER_PATTERN = /\b\d{17,20}\b/

function entry(over: Record<string, unknown>) {
  return {
    path: "backup.remote.endpoint",
    key: "endpoint",
    label: "Endpoint",
    explanation: "",
    noExplanationNeeded: true,
    filled: false,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    editable: true,
    secret: false,
    ...over,
  }
}

/** `steward.yml` as steward sends it: the schedule, the retention block and the remote keys. */
function stewardConfig(over: { secretValue?: string } = {}) {
  return {
    service: "steward",
    name: "steward",
    path: FILE,
    readable: true,
    writable: true,
    revision: "rev-1",
    entries: [
      entry({ path: "backup.at", key: "at", label: "At", value: "04:45" }),
      /** A LIST key with two days, so the file's value differs from the default of all seven. */
      entry({
        path: "backup.days",
        key: "days",
        label: "Days",
        kind: "LIST",
        value: undefined,
        items: ["MONDAY", "THURSDAY"],
      }),
      entry({ path: "backup.remote.endpoint", key: "endpoint", label: "Endpoint", value: "" }),
      entry({ path: "backup.remote.bucket", key: "bucket", label: "Bucket", value: "" }),
      entry({ path: "backup.remote.prefix", key: "prefix", label: "Prefix", value: "" }),
      entry({
        path: "backup.remote.access-key",
        key: "access-key",
        label: "Access key",
        secret: true,
        filled: true,
        /** A value steward never sends on a secret, so the test proves this page's own refusal to draw it. */
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

const RUNS_FILE = "steward-agent/runs"

/** steward-agent's `runs` group: the retention a backup run applies. */
function runsConfig() {
  return {
    service: "steward-agent",
    name: "runs",
    path: RUNS_FILE,
    readable: true,
    writable: true,
    revision: "rev-9",
    entries: [
      entry({
        path: "backup.retention.daily",
        key: "daily",
        label: "Daily",
        value: "14",
        type: "INTEGER",
      }),
      entry({
        path: "backup.retention.weekly",
        key: "weekly",
        label: "Weekly",
        value: "8",
        type: "INTEGER",
      }),
      entry({
        path: "backup.retention.monthly",
        key: "monthly",
        label: "Monthly",
        value: "6",
        type: "INTEGER",
      }),
      entry({
        path: "backup.retention.collapse-after-days",
        key: "collapse-after-days",
        label: "Collapse after",
        value: "3",
        type: "INTEGER",
      }),
    ],
  }
}

function run(over: Record<string, unknown> = {}) {
  return {
    id: 41,
    kind: "BACKUP",
    status: "FAILED",
    actorKind: "HOST",
    actorId: "",
    requested: "2026-09-17T04:45:00Z",
    scheduledFor: "2026-09-17T04:45:00Z",
    countdownEnd: "2026-09-17T04:45:00Z",
    moving: [],
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

function backup(over: Record<string, unknown> = {}) {
  return {
    name: "nordtal-s2_mc-smp-20260917T044500Z.tar.zst",
    bytes: 1_500_000_000,
    human: "1.5 GB",
    modified: "2026-09-17T04:45:00Z",
    partial: false,
    ...over,
  }
}

function person(over: Partial<Person> = {}): Person {
  return {
    discordId: "300000000000000077",
    memberState: "ACTIVE",
    donor: false,
    admin: true,
    locale: "en",
    updated: "2026-09-17T00:00:00Z",
    accessActive: true,
    ...over,
  }
}

function backend(
  over: {
    config?: unknown
    put?: (body: unknown) => Response
    putRuns?: (body: unknown) => Response
    runs?: unknown[]
    backups?: unknown[]
    people?: Person[]
  } = {},
) {
  return vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
    if (url === "/api/setting-groups") {
      return json(200, [
        { service: "steward", name: "steward", path: FILE, readable: true, writable: true },
        { service: "steward-agent", name: "runs", path: RUNS_FILE, readable: true, writable: true },
      ])
    }
    if (url === `/api/setting-groups/${RUNS_FILE}`) {
      if (init?.method === "PUT") {
        return (over.putRuns ?? (() => json(200, runsConfig())))(JSON.parse(init?.body ?? ""))
      }
      return json(200, runsConfig())
    }
    if (url === `/api/setting-groups/${FILE}`) {
      if (init?.method === "PUT") {
        return (over.put ?? (() => json(200, stewardConfig())))(JSON.parse(init?.body ?? ""))
      }
      return json(200, over.config ?? stewardConfig())
    }
    if (url === "/api/backups") {
      return json(200, over.backups ?? [backup()])
    }
    if (url.startsWith("/api/backups/") && init?.method === "POST") {
      return json(202, { id: 7, kind: "RESTORE", archive: url })
    }
    if (url.startsWith("/api/updates")) return json(200, over.runs ?? [run()])
    if (url === "/api/schedule") {
      return json(200, { backupAt: "04:45", zone: "Europe/Berlin", nextBackupAt: null })
    }
    if (url === "/api/people") return json(200, over.people ?? [])
    if (url === "/api/settings") return json(200, {})
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  })
}

const nothing = () => null

function draw() {
  const root = createRootRoute()
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/", component: BackupsPage }),
    createRoute({ getParentRoute: () => root, path: "/operations/updates/$id", component: nothing }),
    createRoute({ getParentRoute: () => root, path: "/operations/backups/$id", component: nothing }),
  ])
  const history = createMemoryHistory({ initialEntries: ["/"] })
  const router = createRouter({ routeTree, history })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { history }
}

function drawDetail(id: string) {
  const root = createRootRoute()
  const routeTree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: "/operations/backups/$id", component: BackupRunDetailPage }),
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [`/operations/backups/${id}`] }),
  })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** The backup page never draws a secret or a raw Discord id, even when a fixture sends one. */
describe("BackupsPage - the destination dialog never draws a secret", () => {
  it("leaves a secret's field empty even when a value arrives with it", async () => {
    vi.stubGlobal("fetch", backend({ config: stewardConfig({ secretValue: "AKIAsecret" }) }))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Destination" }))

    const key = asInput(await screen.findByLabelText("Access key"))
    expect(key.type).toBe("password")
    expect(key.value).toBe("")
    expect(document.body.textContent).not.toContain("AKIAsecret")
  })

  it("says which of the two keys is set, without saying what either is", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Destination" }))

    const set = asInput(await screen.findByLabelText("Access key"))
    const unset = asInput(screen.getByLabelText("Secret key"))
    expect(set.placeholder).toBe("set")
    expect(unset.placeholder).toBe("not set")
    expect(set.disabled).toBe(true)
  })

  it("has nothing to save once a typed value is put back the way it was", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()
    fireEvent.click(await screen.findByRole("button", { name: "Destination" }))

    const endpoint = await screen.findByLabelText("Endpoint")
    fireEvent.change(endpoint, { target: { value: "https://fsn1.your-objectstorage.com" } })
    await waitFor(() => expect(asButton(screen.getByRole("button", { name: "Save" })).disabled).toBe(false))

    fireEvent.change(endpoint, { target: { value: "" } })
    await waitFor(() => expect(asButton(screen.getByRole("button", { name: "Save" })).disabled).toBe(true))
  })

  it("sends only what was typed, together with the revision it was drawn from", async () => {
    const sent: unknown[] = []
    const fetch = backend({
      put: (body) => {
        sent.push(body)
        return json(200, stewardConfig())
      },
    })
    vi.stubGlobal("fetch", fetch)
    draw()
    fireEvent.click(await screen.findByRole("button", { name: "Destination" }))

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

describe("BackupsPage - the schedule dialog carries the retention numbers now (item 9)", () => {
  it("reads retention out of steward-agent's runs group, since the run applies it", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Schedule" }))

    const daily = asInput(await screen.findByLabelText("Daily"))
    expect(daily.value).toBe("14")
  })

  it("saves a retention change into the runs group and leaves steward's own group alone", async () => {
    let toRuns: unknown
    let toSteward: unknown
    vi.stubGlobal(
      "fetch",
      backend({
        put: (body) => {
          toSteward = body
          return json(200, stewardConfig())
        },
        putRuns: (body) => {
          toRuns = body
          return json(200, runsConfig())
        },
      }),
    )
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Schedule" }))
    fireEvent.change(asInput(await screen.findByLabelText("Daily")), { target: { value: "7" } })
    fireEvent.click(screen.getByRole("button", { name: "Save" }))

    await waitFor(() => expect(toRuns).toBeTruthy())
    expect(toRuns).toEqual({ revision: "rev-9", changes: { "backup.retention.daily": "7" } })
    expect(toSteward).toBeUndefined()
  })

  it("computes what the numbers mean, rather than only listing them", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Schedule" }))
    await screen.findByLabelText("Daily")

    // Matches Retention's own algorithm: at most daily + weekly + monthly, not stacked.
    expect(await screen.findByText(/at most 28 archives per volume/i)).toBeTruthy()
  })

  it("draws the weekdays the file actually chose, not seven decorative ones", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Schedule" }))

    /** The fixture's `backup.days` is Monday and Thursday, not all seven. */
    expect((await screen.findByRole("button", { name: "Mon" })).getAttribute("aria-pressed")).toBe("true")
    expect(screen.getByRole("button", { name: "Thu" }).getAttribute("aria-pressed")).toBe("true")
    expect(screen.getByRole("button", { name: "Tue" }).getAttribute("aria-pressed")).toBe("false")
  })

  it("saves a picked weekday as a list, in the week's own order", async () => {
    let sent: unknown
    vi.stubGlobal(
      "fetch",
      backend({
        put: (body) => {
          sent = body
          return json(200, stewardConfig())
        },
      }),
    )
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Schedule" }))
    fireEvent.click(await screen.findByRole("button", { name: "Tue" }))
    fireEvent.click(screen.getByRole("button", { name: "Save" }))

    await waitFor(() => expect(sent).toBeTruthy())
    /** In week order, not click order, and as a list, since a list of one flattens to a scalar. */
    expect(changesOf(sent)["backup.days"]).toEqual(["MONDAY", "TUESDAY", "THURSDAY"])
  })

  it("says so when every day has been turned off, because that is no backup at all", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Schedule" }))
    fireEvent.click(await screen.findByRole("button", { name: "Mon" }))
    fireEvent.click(screen.getByRole("button", { name: "Thu" }))

    expect(await screen.findByText(/no night is picked/i)).toBeTruthy()
  })
})

describe("BackupsPage - the numbers it opens with", () => {
  it("says storage is not tracked rather than inventing a number for it", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    expect(await screen.findByText("Storage available")).toBeTruthy()
    expect(screen.getByText("not tracked")).toBeTruthy()
  })

  it("says there is no nightly clock rather than drawing a next run there is none of", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    expect(await screen.findByText("no nightly clock")).toBeTruthy()
  })
})

describe("BackupsPage - the runs table (items 2, 3, 4)", () => {
  it("counts archives under the header 'Archives', not the old 'Volumes' fraction", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    expect(await screen.findByText("Archives")).toBeTruthy()
    expect(screen.queryByText("Volumes")).toBeNull()
    expect(screen.queryByText("0 of 12")).toBeNull()
  })

  it("shows how many archives a run actually wrote when some were saved", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        runs: [
          run({
            report: {
              stage: "DONE",
              notes: [],
              services: [
                { service: "a", state: "SAVED", changes: [] },
                { service: "b", state: "SAVED", changes: [] },
                { service: "c", state: "FAILED", changes: [] },
              ],
            },
          }),
        ],
      }),
    )
    draw()

    const row = (await screen.findByText("#41")).closest("tr")
    expect(row).not.toBeNull()
    expect(within(asElement(row)).getByText("2")).toBeTruthy()
  })

  it("makes the whole row a link, not only the run number", async () => {
    vi.stubGlobal("fetch", backend({}))
    const { history } = draw()

    const row = asElement((await screen.findByText("#41")).closest("tr"))
    // The "When" cell, not the run-number link.
    fireEvent.click(within(row).getByText(/2026/))

    await waitFor(() => expect(history.location.pathname).toBe("/operations/backups/41"))
  })

  it("labels the column 'Initiated by' and never prints the raw Discord id", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        runs: [run({ actorKind: "PERSON", actorId: "300000000000000077" })],
        people: [person({ discordUsername: "hm.ally" })],
      }),
    )
    draw()

    expect(await screen.findByText("Initiated by")).toBeTruthy()
    expect(screen.queryByText("Requested by")).toBeNull()
    await screen.findByText("hm.ally")
    expect(document.body.textContent).not.toMatch(IDENTIFIER_PATTERN)
  })

  it("draws Steward itself for the nightly clock, not a blank person", async () => {
    vi.stubGlobal("fetch", backend({ runs: [run({ actorKind: "STEWARD" })] }))
    draw()

    expect(await screen.findByText("Steward")).toBeTruthy()
  })

  it("names the host for a run asked for through the installer", async () => {
    vi.stubGlobal("fetch", backend({ runs: [run({ actorKind: "HOST" })] }))
    draw()

    expect(await screen.findByText("host")).toBeTruthy()
  })
})

describe("BackupsPage - the volumes panel and the archive listing are gone (items 5 & 6)", () => {
  it("draws no separate volumes panel - the run's own report line already carries that", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()
    await screen.findByText("Runs")

    expect(screen.queryByRole("heading", { name: "Volumes" })).toBeNull()
  })

  it("lists no archive files on the page itself any more", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()
    await screen.findByText("Runs")

    expect(screen.queryByText("nordtal-s2_mc-smp-20260917T044500Z.tar.zst")).toBeNull()
  })
})

describe("BackupRunDetailPage - a run's own archives, downloadable (item 6)", () => {
  it("lists the archives written inside this run's own window, each one downloadable", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        runs: [run()],
        backups: [
          backup({ name: "nordtal-s2_mc-smp-20260917T044505Z.tar.zst", modified: "2026-09-17T04:45:05Z" }),
          backup({ name: "nordtal-20260917T044550Z.dump", modified: "2026-09-17T04:45:50Z" }),
          // Written the following night, outside this run's window.
          backup({ name: "nordtal-s2_mc-smp-20260918T044500Z.tar.zst", modified: "2026-09-18T04:45:00Z" }),
        ],
      }),
    )
    drawDetail("41")

    await screen.findByText("nordtal-s2_mc-smp-20260917T044505Z.tar.zst")
    expect(await screen.findByText("nordtal-20260917T044550Z.dump")).toBeTruthy()
    expect(screen.queryByText("nordtal-s2_mc-smp-20260918T044500Z.tar.zst")).toBeNull()

    const link = asAnchor(screen.getByLabelText("Download nordtal-s2_mc-smp-20260917T044505Z.tar.zst"))
    expect(link.getAttribute("href")).toBe("/api/backups/nordtal-s2_mc-smp-20260917T044505Z.tar.zst/download")
  })

  it("says so when no run matches the id, rather than drawing an empty page", async () => {
    vi.stubGlobal("fetch", backend({ runs: [] }))
    drawDetail("999")

    expect(await screen.findByText("No such run")).toBeTruthy()
  })
})

describe("BackupsPage - what moved here from Operations", () => {
  it("offers a backup by hand", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    expect(await screen.findByRole("button", { name: /back up now/i })).toBeTruthy()
  })

  it("asks for a restore run only once what it replaces is typed back", async () => {
    const fetchMock = backend({ backups: [backup({ restoresInto: "nordtal-s2_mc-smp" })] })
    vi.stubGlobal("fetch", fetchMock)
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Restore" }))
    fireEvent.keyDown(await screen.findByRole("combobox"), { key: "Enter" })
    fireEvent.click(await screen.findByRole("option", { name: /nordtal-s2_mc-smp-20260917T044500Z/ }))
    const dialog = screen.getByRole("dialog")
    const confirm = within(dialog).getByRole("button", { name: "Restore" })
    expect(asButton(confirm).disabled).toBe(true)

    fireEvent.change(within(dialog).getByLabelText(/Type/), { target: { value: "nordtal-s2_mc-smp-plugins" } })
    expect(asButton(confirm).disabled).toBe(true)
    fireEvent.change(within(dialog).getByLabelText(/Type/), { target: { value: "nordtal-s2_mc-smp" } })
    fireEvent.click(confirm)

    await waitFor(() => {
      const posted = fetchMock.mock.calls.filter((call) => call[1]?.method === "POST")
      expect(posted.map((call) => [call[0], JSON.parse(call[1]?.body ?? "")])).toEqual([
        ["/api/backups/nordtal-s2_mc-smp-20260917T044500Z.tar.zst/restore", { confirm: "nordtal-s2_mc-smp" }],
      ])
    })
  })

  it("offers no archive that is still being written", async () => {
    vi.stubGlobal("fetch", backend({ backups: [backup({ partial: true, name: "x.tar.zst.partial" })] }))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Restore" }))
    expect(await screen.findByText("Every file still carries the .partial suffix.")).toBeTruthy()
  })
})
