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
    path: "backup.at",
    key: "at",
    label: "At",
    explanation: "",
    noExplanationNeeded: true,
    filled: false,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    editable: true,
    secret: false,
    environmentOverridden: false,
    ...over,
  }
}

function stewardConfig() {
  return {
    service: "steward",
    name: "steward",
    path: FILE,
    label: "",
    live: true,
    readable: true,
    writable: true,
    revision: "rev-1",
    restartRequired: false,
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
    ],
  }
}

const RUNS_FILE = "steward-agent/runs"

function runsConfig() {
  return {
    service: "steward-agent",
    name: "runs",
    path: RUNS_FILE,
    label: "",
    live: true,
    readable: true,
    writable: true,
    revision: "rev-9",
    restartRequired: false,
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
    stage: "FAILED",
    steps: Array.from({ length: 12 }, (_, index) => saving(`nordtal-s2_volume-${index}`, [], "FAILED")),
    ...over,
  }
}

/** A step that saved the archives it names, as the catalogue gives them back. */
function saving(service: string, saved: Record<string, unknown>[], outcome = "DONE") {
  return { seq: 1, step: "SAVE_VOLUME", service, outcome, lines: [], changes: [], saved, pruned: [] }
}

function archive(file: string, over: Record<string, unknown> = {}) {
  return { volume: "nordtal-s2_mc-smp", file, bytes: 1_500_000_000, tookMillis: 4000, stopVerified: true, ...over }
}

function backup(over: Record<string, unknown> = {}) {
  return {
    name: "nordtal-s2_mc-smp-20260917T044500Z.tar.zst",
    bytes: 1_500_000_000,
    human: "1.5 GB",
    modified: "2026-09-17T04:45:00Z",
    partial: false,
    offsite: true,
    inBackup: true,
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
    aura: 0,
    spins: 0,
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
        { service: "steward", name: "steward", path: FILE, label: "", live: true, readable: true, writable: true },
        {
          service: "steward-agent",
          name: "runs",
          path: RUNS_FILE,
          label: "",
          live: true,
          readable: true,
          writable: true,
        },
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

/** The table row that names a file. */
const rowOf = async (file: string) => asElement((await screen.findByText(file)).closest("tr"))

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

  it("draws the weekdays the file actually chose, not seven decorative ones", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Schedule" }))

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
    /** In week order and as a list, because a list of one flattens to a scalar. */
    expect(changesOf(sent)["backup.days"]).toEqual(["MONDAY", "TUESDAY", "THURSDAY"])
  })

  it("says so when every day has been turned off, because that is no backup at all", async () => {
    vi.stubGlobal("fetch", backend({}))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Schedule" }))
    fireEvent.click(await screen.findByRole("button", { name: "Mon" }))
    fireEvent.click(screen.getByRole("button", { name: "Thu" }))

    expect(await screen.findByText(/until a night is picked/i)).toBeTruthy()
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
            stage: "DONE",
            steps: [
              saving("a", [archive("a-20260917T044500Z.tar.zst")]),
              saving("b", [archive("b-20260917T044500Z.tar.zst")]),
              saving("c", [], "FAILED"),
            ],
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
  it("draws no separate volumes panel - the run's own steps already carry that", async () => {
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
  it("lists the archives the run saved, each one on the disk downloadable", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        runs: [
          run({
            steps: [
              saving("nordtal-s2_mc-smp", [archive("nordtal-s2_mc-smp-20260917T044505Z.tar.zst")]),
              {
                ...saving("nordtal", [archive("nordtal-20260917T044550Z.dump", { volume: "nordtal" })]),
                step: "DUMP_DATABASE",
              },
            ],
          }),
        ],
        backups: [
          backup({ name: "nordtal-s2_mc-smp-20260917T044505Z.tar.zst", modified: "2026-09-17T04:45:05Z" }),
          backup({ name: "nordtal-20260917T044550Z.dump", modified: "2026-09-17T04:45:50Z" }),
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

  it("marks an archive pruned since with its rule, and one gone from the disk as missing", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        runs: [
          run({
            steps: [
              saving("nordtal-s2_mc-smp", [
                archive("nordtal-s2_mc-smp-20260917T044505Z.tar.zst", { rule: "RETENTION" }),
                archive("nordtal-s2_mc-limbo-20260917T044505Z.tar.zst"),
              ]),
              {
                ...saving("", []),
                step: "PRUNE",
                service: undefined,
                pruned: [archive("nordtal-s2_mc-smp-20260910T044500Z.tar.zst", { rule: "OVER_BUDGET" })],
              },
            ],
          }),
        ],
        backups: [],
      }),
    )
    drawDetail("41")

    expect(
      within(await rowOf("nordtal-s2_mc-smp-20260917T044505Z.tar.zst")).getByText("pruned, no retention rule kept it"),
    ).toBeTruthy()
    expect(
      within(await rowOf("nordtal-s2_mc-limbo-20260917T044505Z.tar.zst")).getByText("missing from the disk"),
    ).toBeTruthy()
    expect(
      within(await rowOf("nordtal-s2_mc-smp-20260910T044500Z.tar.zst")).getByText("pruned, over the disk budget"),
    ).toBeTruthy()
    expect(screen.queryByLabelText(/^Download /)).toBeNull()
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
    const dialog = screen.getByRole("alertdialog")
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

  it("marks the archive of a volume that left the backup", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        backups: [
          backup({ restoresInto: "nordtal-s2_mc-smp" }),
          backup({
            name: "nordtal-s2_mc-proxy-20260917T044500Z.tar.zst",
            restoresInto: "nordtal-s2_mc-proxy",
            inBackup: false,
          }),
        ],
      }),
    )
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Restore" }))
    fireEvent.keyDown(await screen.findByRole("combobox"), { key: "Enter" })

    const left = await screen.findByRole("option", { name: /nordtal-s2_mc-proxy-20260917T044500Z/ })
    expect(left.textContent).toContain("no longer in the backup")
    const kept = await screen.findByRole("option", { name: /nordtal-s2_mc-smp-20260917T044500Z/ })
    expect(kept.textContent).not.toContain("no longer in the backup")
  })

  it("offers no archive that is still being written", async () => {
    vi.stubGlobal("fetch", backend({ backups: [backup({ partial: true, name: "x.tar.zst.partial" })] }))
    draw()

    fireEvent.click(await screen.findByRole("button", { name: "Restore" }))
    expect(await screen.findByText("Every file still has the .partial suffix.")).toBeTruthy()
  })
})
