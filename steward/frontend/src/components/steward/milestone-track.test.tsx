import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigDocument, ConfigEntry, GameData, SmpTrack } from "@/lib/api"
import { SmpActions, trackSteps } from "@/components/steward/milestone-track"
import { TooltipProvider } from "@/components/ui/tooltip"
import { words } from "@/lib/query-fixtures"

/** The whole track in the file's order, each task's details and action behind a popover, and no command in sight. */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } })
}

function entry(key: string, value?: string, more: Partial<ConfigEntry> = {}): ConfigEntry {
  return {
    path: key,
    key,
    label: key,
    explanation: "",
    noExplanationNeeded: true,
    filled: true,
    value,
    items: [],
    sections: [],
    kind: "SCALAR",
    type: "STRING",
    editable: true,
    secret: false,
    environmentOverridden: false,
    ...more,
  }
}

function objective(key: string, type: string, target: number, more: Partial<Record<string, string[]>> = {}) {
  return [
    entry("key", key),
    entry("type", type),
    entry("role", "gathering", { label: "Role" }),
    entry("target", String(target)),
    entry("items", undefined, { label: "Items", kind: "LIST", items: more.items ?? [], refers: ITEM }),
  ]
}

function milestone(key: string, unlocks: string, border: number, objectives: ConfigEntry[][]) {
  return [
    entry("key", key),
    entry("unlocks", unlocks, { label: "Unlocks" }),
    entry("border-diameter", String(border), { label: "Border diameter", type: "INTEGER" }),
    entry("objectives", undefined, { kind: "SECTIONS", sections: objectives }),
  ]
}

const ITEM = { to: "ITEM", optional: false } as const

const FILE: ConfigDocument = {
  service: "smp",
  name: "milestones.yml",
  path: "smp/milestones",
  label: "",
  live: true,
  readable: true,
  writable: true,
  revision: "r1",
  restartRequired: false,
  entries: [
    entry("milestones", undefined, {
      kind: "SECTIONS",
      sections: [
        milestone("waiting", "BORDER", 20, []),
        milestone("foothold", "BORDER", 9900, [
          objective("logs", "HAND_IN", 2048, { items: ["minecraft:oak_log", "SPRUCE_LOG", "minecraft:gone"] }),
          objective("coal", "STATISTIC", 1500),
        ]),
        milestone("nether", "NETHER", 0, [objective("obsidian", "HAND_IN", 64)]),
      ],
    }),
  ],
}

const GAME: GameData = {
  version: "26.2",
  datapacks: [],
  registries: {
    item: [
      { id: "minecraft:oak_log", text: "Oak Log" },
      { id: "minecraft:spruce_log", text: "Spruce Log" },
    ],
  },
  tags: {},
}

/** Alphabetical, as the database answers, so the file's order has to be applied. */
const TRACK: SmpTrack = {
  milestones: [
    {
      key: "foothold",
      state: "ACTIVE",
      objectives: [
        {
          key: "coal",
          type: "STATISTIC",
          amount: 1500,
          target: 1500,
          completed: true,
          completedAt: "2026-10-02T10:00:00Z",
        },
        { key: "logs", type: "HAND_IN", amount: 512, target: 2048, completed: false },
      ],
    },
    {
      key: "nether",
      state: "LOCKED",
      objectives: [{ key: "obsidian", type: "HAND_IN", amount: 0, target: 64, completed: false }],
    },
    { key: "waiting", state: "UNLOCKED", unlocked: "2026-10-01T18:00:00Z", objectives: [] },
  ],
}

type Row = { status: string; result?: string }

function backend({ row = { status: "DONE", result: "Objective closed." } }: { row?: Row } = {}) {
  const sent: { url: string; body: unknown }[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
      if (init?.method === "POST") {
        sent.push({ url, body: JSON.parse(init.body ?? "") })
        return json(202, { id: String(sent.length), status: "PENDING" })
      }
      if (url === "/api/smp/track") return json(200, TRACK)
      if (url === "/api/setting-groups/smp/milestones") return json(200, FILE)
      if (url === "/api/game-data") return json(200, GAME)
      if (url.startsWith("/api/commands/"))
        return json(200, { id: url.split("/").pop(), ...row, result: row.result && words(row.result) })
      throw new Error(`the card asked for ${url}, which this test did not expect`)
    }),
  )
  return sent
}

function draw(node: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{node}</TooltipProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("trackSteps", () => {
  it("puts the database's progress into the file's order, each step beside its own settings", () => {
    const steps = trackSteps(TRACK, FILE)

    expect(steps.map((step) => step.key)).toEqual(["waiting", "foothold", "nether"])
    expect(steps[1].fields.map((field) => field.key)).toEqual(["unlocks", "border-diameter"])
    expect(steps[1].objectives.map((task) => [task.key, task.amount])).toEqual([
      ["logs", 512],
      ["coal", 1500],
    ])
    expect(steps[1].objectives[0].fields.map((field) => field.key)).toEqual(["role", "items"])
  })

  it("keeps the database's order without the file, and a row the file lacks at the end", () => {
    expect(trackSteps(TRACK).map((step) => step.key)).toEqual(["foothold", "nether", "waiting"])

    const extra: SmpTrack = { milestones: [...TRACK.milestones, { key: "gone", state: "LOCKED", objectives: [] }] }
    expect(trackSteps(extra, FILE).map((step) => step.key)).toEqual(["waiting", "foothold", "nether", "gone"])
  })
})

describe("SmpActions", () => {
  it("draws every milestone, marks the active one, and names no command", async () => {
    backend()
    draw(<SmpActions />)

    const list = await screen.findByRole("list", { name: "Milestones" })
    await waitFor(() =>
      expect(
        within(list)
          .getAllByRole("listitem", { name: /^(Waiting|Foothold|Nether)$/ })
          .map((item) => item.getAttribute("aria-label")),
      ).toEqual(["Waiting", "Foothold", "Nether"]),
    )
    expect(screen.getByRole("listitem", { name: "Foothold" }).getAttribute("aria-current")).toBe("step")
    expect(screen.getByText("1 / 3")).toBeTruthy()
    expect(screen.getByText("25 %")).toBeTruthy()
    expect(screen.queryByRole("textbox")).toBeNull()
    expect(document.body.textContent).not.toMatch(/smp objective|smp milestone|\/smp/)
  })

  it("completes an open task from its popover, after asking", async () => {
    const sent = backend()
    draw(<SmpActions />)

    fireEvent.click(await screen.findByRole("button", { name: "Logs" }))
    const popover = await screen.findByRole("dialog")
    expect(within(popover).getByText("512 / 2,048")).toBeTruthy()
    expect(await within(popover).findByText("Oak Log")).toBeTruthy()
    expect(within(popover).getByText("Spruce Log")).toBeTruthy()
    expect(within(popover).getByText("minecraft:gone")).toBeTruthy()
    expect(within(popover).getByText("gathering")).toBeTruthy()
    fireEvent.click(within(popover).getByRole("button", { name: "Complete" }))

    const dialog = await screen.findByRole("alertdialog")
    expect(sent).toHaveLength(0)
    fireEvent.click(within(dialog).getByRole("button", { name: "Complete" }))
    await waitFor(() => expect(sent).toEqual([{ url: "/api/smp/objective", body: { key: "logs" } }]))
    expect(await within(dialog).findByText("Objective closed.")).toBeTruthy()
  })

  it("shows a step's settings under their schema labels", async () => {
    backend()
    draw(<SmpActions />)

    fireEvent.click(await screen.findByRole("button", { name: /^Foothold/ }))
    const popover = await screen.findByRole("dialog")
    expect(within(popover).getByText("Border diameter")).toBeTruthy()
    expect(within(popover).getByText("9,900")).toBeTruthy()
    expect(within(popover).getByText("BORDER")).toBeTruthy()
    expect(within(popover).getByText("1 of 2 tasks")).toBeTruthy()
  })

  it("offers nothing to do on a finished task or one of a locked milestone", async () => {
    backend()
    draw(<SmpActions />)

    fireEvent.click(await screen.findByRole("button", { name: "Coal" }))
    expect(within(await screen.findByRole("dialog")).queryByRole("button", { name: "Complete" })).toBeNull()
    fireEvent.keyDown(document.activeElement ?? document.body, { key: "Escape" })
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())

    fireEvent.click(screen.getByRole("button", { name: "Obsidian" }))
    expect(within(await screen.findByRole("dialog")).queryByRole("button", { name: "Complete" })).toBeNull()
  })

  it("unlocks the active milestone and no other", async () => {
    const sent = backend()
    draw(<SmpActions />)

    await screen.findByRole("listitem", { name: "Foothold" })
    expect(screen.getAllByRole("button", { name: "Unlock" })).toHaveLength(1)
    fireEvent.click(screen.getByRole("button", { name: "Unlock" }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Unlock" }))

    await waitFor(() => expect(sent).toEqual([{ url: "/api/smp/milestone", body: { key: "foothold" } }]))
  })

  it("says so when nobody picked the row up, rather than that it failed", async () => {
    backend({ row: { status: "EXPIRED" } })
    draw(<SmpActions />)

    fireEvent.click(await screen.findByRole("button", { name: "Unlock" }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Unlock" }))

    expect(await within(dialog).findByText(/Nobody picked this up within two minutes/)).toBeTruthy()
  })
})
