import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigDocument, ConfigEntry } from "@/lib/api"
import { clearDraft } from "@/lib/drafts"
import { customEditor } from "@/components/steward/config-editors"
import { MilestonesEditor } from "@/components/steward/milestones/milestones-editor"
import { moved } from "@/components/steward/milestones/model"
import { TooltipProvider } from "@/components/ui/tooltip"

/** The milestones editor: chosen by the descriptor, drawn from the schema, compact, and saved like any group. */

const FILE = "smp/milestones"

function entry(key: string, more: Partial<ConfigEntry> = {}): ConfigEntry {
  return {
    path: key,
    key,
    label: key,
    explanation: "",
    noExplanationNeeded: true,
    filled: true,
    kind: "SCALAR",
    type: "STRING",
    editable: true,
    secret: false,
    environmentOverridden: false,
    ...more,
  }
}

const OBJECTIVE: ConfigEntry[] = [
  entry("key", { label: "ID", value: "" }),
  entry("type", { label: "Type", value: "HAND_IN", choices: { values: ["HAND_IN", "STATISTIC"], strict: true } }),
  entry("target", { label: "Target", type: "INTEGER", value: "1" }),
  entry("aura-budget", { label: "Aura budget", type: "INTEGER", value: "0" }),
  entry("spin-budget", { label: "Spin budget", type: "INTEGER", value: "0" }),
  entry("items", { label: "Items", kind: "LIST", items: [], refers: { to: "ITEM", optional: false } }),
]

const MILESTONE: ConfigEntry[] = [
  entry("key", { label: "ID", value: "" }),
  entry("unlocks", { label: "Unlocks", value: "NOTHING", choices: { values: ["BORDER", "NOTHING"], strict: true } }),
  entry("border-diameter", { label: "Border diameter", type: "INTEGER", value: "0" }),
  entry("objectives", { label: "Objectives", kind: "SECTIONS", template: OBJECTIVE }),
]

type Values = {
  scalars?: Record<string, string>
  lists?: Record<string, string[]>
  sections?: Record<string, ConfigEntry[][]>
}

function section(template: ConfigEntry[], { scalars = {}, lists = {}, sections = {} }: Values): ConfigEntry[] {
  return template.map((field) => {
    if (field.kind === "SECTIONS") return { ...field, sections: sections[field.key] ?? [] }
    if (field.kind === "LIST") return { ...field, items: lists[field.key] ?? [] }
    return { ...field, value: scalars[field.key] ?? field.value }
  })
}

const DOCUMENT: ConfigDocument = {
  service: "smp",
  name: "milestones",
  path: FILE,
  label: "",
  live: true,
  readable: true,
  writable: true,
  revision: "r1",
  restartRequired: false,
  entries: [
    entry("milestones", {
      kind: "SECTIONS",
      template: MILESTONE,
      sections: [
        section(MILESTONE, { scalars: { key: "waiting", unlocks: "BORDER", "border-diameter": "20" } }),
        section(MILESTONE, {
          scalars: { key: "foothold", unlocks: "BORDER", "border-diameter": "99" },
          sections: {
            objectives: [
              section(OBJECTIVE, {
                scalars: { key: "logs", target: "2048", "aura-budget": "30", "spin-budget": "20" },
                lists: { items: ["minecraft:oak_log"] },
              }),
              section(OBJECTIVE, {
                scalars: { key: "planks", target: "512", "aura-budget": "30", "spin-budget": "16" },
                lists: { items: ["minecraft:oak_log"] },
              }),
            ],
          },
        }),
      ],
    }),
  ],
}

function backend() {
  const saved: unknown[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
      if (init?.method === "PUT") {
        saved.push(JSON.parse(init.body ?? ""))
        return new Response(JSON.stringify(DOCUMENT), { status: 200, headers: { "Content-Type": "application/json" } })
      }
      if (url === "/api/game-data") {
        const game = {
          version: "26.2",
          datapacks: [],
          registries: { item: [{ id: "minecraft:oak_log", text: "Oak Log" }] },
          tags: {},
        }
        return new Response(JSON.stringify(game), { status: 200, headers: { "Content-Type": "application/json" } })
      }
      throw new Error(`the editor asked for ${url}, which this test did not expect`)
    }),
  )
  return saved
}

function draw(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <TooltipProvider>{node}</TooltipProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  clearDraft(FILE)
  vi.unstubAllGlobals()
})

describe("customEditor", () => {
  it("draws a group its descriptor names with the milestones editor, and falls back where it cannot read it", () => {
    expect(customEditor("milestones", DOCUMENT)).toBe(MilestonesEditor)
    expect(customEditor("milestones", { ...DOCUMENT, entries: [entry("milestones")] })).toBeUndefined()
    expect(customEditor("nonexistent", DOCUMENT)).toBeUndefined()
    expect(customEditor(undefined, DOCUMENT)).toBeUndefined()
  })
})

describe("MilestonesEditor", () => {
  it("shows every milestone as one closed line, its settings as marks", () => {
    backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    const list = screen.getByRole("list", { name: "Milestones" })
    expect(
      within(list)
        .getAllByRole("listitem")
        .map((item) => item.getAttribute("aria-label")),
    ).toEqual(["waiting", "foothold"])
    expect(within(list).getByText("99")).toBeTruthy()
    expect(screen.queryByRole("textbox")).toBeNull()
  })

  it("edits an objective in its sheet and saves the whole track", async () => {
    const saved = backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    fireEvent.click(screen.getByRole("button", { name: /foothold/ }))
    fireEvent.click(await screen.findByRole("button", { name: /logs/ }))
    const sheet = await screen.findByRole("dialog")
    expect(await within(sheet).findByText("Oak Log")).toBeTruthy()
    fireEvent.change(within(sheet).getByLabelText("Target"), { target: { value: "4096" } })
    fireEvent.click(within(sheet).getByRole("button", { name: "Done" }))

    fireEvent.click(await screen.findByRole("button", { name: "Save 1" }))
    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]).toMatchObject({
      revision: "r1",
      changes: {
        milestones: [
          { key: "waiting" },
          {
            key: "foothold",
            objectives: [{ key: "logs", target: "4096", items: ["minecraft:oak_log"] }, { key: "planks" }],
          },
        ],
      },
    })
  })

  it("sums its objectives' budgets per milestone and for the season, and follows an edit", async () => {
    backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    const list = screen.getByRole("list", { name: "Milestones" })
    const season = screen.getByText("Season").parentElement
    if (!season) throw new Error("the season's sums are drawn without their line")
    expect(within(list).getByTitle("Aura budget").textContent).toBe("60")
    expect(within(list).getByTitle("Spin budget").textContent).toBe("36")
    expect(within(season).getByTitle("Aura budget").textContent).toBe("60")
    expect(within(season).getByTitle("Spin budget").textContent).toBe("36")

    fireEvent.click(screen.getByRole("button", { name: /foothold/ }))
    fireEvent.click(await screen.findByRole("button", { name: /logs/ }))
    const sheet = await screen.findByRole("dialog")
    fireEvent.change(within(sheet).getByLabelText("Spin budget"), { target: { value: "25" } })
    fireEvent.click(within(sheet).getByRole("button", { name: "Done" }))

    await waitFor(() => expect(within(season).getByTitle("Spin budget").textContent).toBe("41"))
    expect(within(list).getByTitle("Spin budget").textContent).toBe("41")
  })

  it("moves a milestone, since the order is the season's", async () => {
    const saved = backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    fireEvent.click(screen.getByRole("button", { name: /foothold/ }))
    fireEvent.click(await screen.findByRole("button", { name: "Move foothold up" }))
    fireEvent.click(await screen.findByRole("button", { name: "Save 1" }))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]).toMatchObject({ changes: { milestones: [{ key: "foothold" }, { key: "waiting" }] } })
  })

  it("drops the unsaved track with Discard and shows the stored order again", async () => {
    const saved = backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    fireEvent.click(screen.getByRole("button", { name: /foothold/ }))
    fireEvent.click(await screen.findByRole("button", { name: "Move foothold up" }))
    fireEvent.click(await screen.findByRole("button", { name: "Discard" }))

    const list = screen.getByRole("list", { name: "Milestones" })
    expect(
      within(list)
        .getAllByRole("listitem")
        .map((item) => item.getAttribute("aria-label")),
    ).toEqual(["waiting", "foothold"])
    expect(screen.queryByRole("button", { name: "Save 1" })).toBeNull()
    expect(saved).toHaveLength(0)
  })
})

describe("moved", () => {
  it("moves one element and leaves the list alone for a place outside it", () => {
    expect(moved(["a", "b", "c"], 2, 0)).toEqual(["c", "a", "b"])
    expect(moved(["a", "b"], 0, 2)).toEqual(["a", "b"])
  })
})
