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

const STATISTIC_TYPE = { key: "type", values: ["STATISTIC"] }

const OBJECTIVE: ConfigEntry[] = [
  entry("key", { label: "ID", value: "" }),
  entry("type", {
    label: "Type",
    value: "HAND_IN",
    choices: {
      values: ["HAND_IN", "STATISTIC", "ADVANCEMENT"],
      strict: true,
      names: {
        HAND_IN: "Hand-in",
        STATISTIC: "Statistic",
        ADVANCEMENT: "Advancement",
      },
      icons: {
        HAND_IN: "minecraft:chest",
        STATISTIC: "minecraft:writable_book",
        ADVANCEMENT: "minecraft:knowledge_book",
      },
    },
  }),
  entry("target", { label: "Target", type: "INTEGER", value: "1" }),
  entry("aura-budget", { label: "Aura budget", type: "INTEGER", value: "0" }),
  entry("spin-budget", { label: "Spin budget", type: "INTEGER", value: "0" }),
  entry("items", {
    label: "Items",
    kind: "LIST",
    items: [],
    refers: { to: "ITEM", optional: false },
    appliesWhen: { key: "type", values: ["HAND_IN"] },
  }),
  entry("statistic", {
    label: "Statistic",
    value: "",
    refers: {
      to: "STATISTIC",
      optional: false,
      except: ["minecraft:play_one_minute"],
    },
    appliesWhen: STATISTIC_TYPE,
  }),
  entry("subjects", {
    label: "Subjects",
    kind: "LIST",
    items: [],
    refers: { to: "SUBJECT", dependsOn: "statistic", optional: false },
    appliesWhen: STATISTIC_TYPE,
  }),
  entry("advancement", {
    label: "Advancement",
    value: "",
    refers: { to: "ADVANCEMENT", optional: false },
    appliesWhen: { key: "type", values: ["ADVANCEMENT"] },
  }),
]

const MILESTONE: ConfigEntry[] = [
  entry("key", { label: "ID", value: "" }),
  entry("unlocks", {
    label: "Unlocks",
    value: "NOTHING",
    choices: {
      values: ["BORDER", "NETHER", "NOTHING"],
      strict: true,
      names: { BORDER: "Border", NETHER: "Nether", NOTHING: "Nothing" },
      icons: {
        BORDER: "minecraft:map",
        NETHER: "minecraft:netherrack",
        NOTHING: "minecraft:barrier",
      },
    },
  }),
  entry("border-diameter", {
    label: "Border diameter",
    type: "INTEGER",
    value: "0",
    appliesWhen: { key: "unlocks", values: ["BORDER"] },
  }),
  entry("objectives", {
    label: "Objectives",
    kind: "SECTIONS",
    template: OBJECTIVE,
  }),
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

/** The live track still holds values as the plugin first wrote them, without their namespace. */
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
        section(MILESTONE, {
          scalars: {
            key: "waiting",
            unlocks: "BORDER",
            "border-diameter": "20",
          },
        }),
        section(MILESTONE, {
          scalars: {
            key: "foothold",
            unlocks: "BORDER",
            "border-diameter": "99",
          },
          sections: {
            objectives: [
              section(OBJECTIVE, {
                scalars: {
                  key: "logs",
                  target: "2048",
                  "aura-budget": "30",
                  "spin-budget": "20",
                },
                lists: { items: ["OAK_LOG"] },
              }),
              section(OBJECTIVE, {
                scalars: {
                  key: "planks",
                  target: "512",
                  "aura-budget": "30",
                  "spin-budget": "16",
                },
                lists: { items: ["minecraft:oak_log"] },
              }),
              section(OBJECTIVE, {
                scalars: {
                  key: "kills",
                  type: "STATISTIC",
                  target: "50",
                  statistic: "KILL_ENTITY",
                },
                lists: { subjects: ["ZOMBIE"] },
              }),
            ],
          },
        }),
        section(MILESTONE, {
          scalars: { key: "deep", unlocks: "NETHER", "border-diameter": "0" },
        }),
      ],
    }),
  ],
}

const GAME = {
  version: "26.2",
  datapacks: [],
  registries: {
    item: [
      { id: "minecraft:oak_log", text: "Oak Log" },
      { id: "minecraft:zombie_spawn_egg", text: "Zombie Spawn Egg" },
    ],
    entity_type: [{ id: "minecraft:zombie", text: "Zombie" }],
    statistic: [
      {
        id: "minecraft:kill_entity",
        text: "Kill entity",
        subject: "entity_type",
      },
      { id: "minecraft:jump", text: "Jump" },
      { id: "minecraft:play_one_minute", text: "Time Played" },
    ],
  },
  tags: {},
  icons: {
    url: "/icons.png",
    columns: 4,
    slots: {
      "minecraft:oak_log": 0,
      "minecraft:zombie_spawn_egg": 1,
      "minecraft:map": 2,
      "minecraft:chest": 3,
      "minecraft:netherrack": 4,
      "minecraft:writable_book": 5,
    },
  },
}

function backend() {
  const saved: unknown[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
      if (init?.method === "PUT") {
        saved.push(JSON.parse(init.body ?? ""))
        return new Response(JSON.stringify(DOCUMENT), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      }
      if (url === "/api/game-data") {
        return new Response(JSON.stringify(GAME), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      }
      throw new Error(`the editor asked for ${url}, which this test did not expect`)
    }),
  )
  return saved
}

function draw(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
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
    expect(
      customEditor("milestones", {
        ...DOCUMENT,
        entries: [entry("milestones")],
      }),
    ).toBeUndefined()
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
    ).toEqual(["waiting", "foothold", "deep"])
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
    fireEvent.change(within(sheet).getByLabelText("Target"), {
      target: { value: "4096" },
    })
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
            objectives: [{ key: "logs", target: "4096" }, { key: "planks" }, { key: "kills" }],
          },
          { key: "deep" },
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
    fireEvent.change(within(sheet).getByLabelText("Spin budget"), {
      target: { value: "25" },
    })
    fireEvent.click(within(sheet).getByRole("button", { name: "Done" }))

    await waitFor(() => expect(within(season).getByTitle("Spin budget").textContent).toBe("41"))
    expect(within(list).getByTitle("Spin budget").textContent).toBe("41")
  })

  it("names a milestone's unlock by its plugin's text and icon, and shows the diameter only for a border", async () => {
    backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    const list = screen.getByRole("list", { name: "Milestones" })
    const foothold = within(list).getByRole("listitem", { name: "foothold" })
    const deep = within(list).getByRole("listitem", { name: "deep" })
    expect(within(foothold).getByText("Border")).toBeTruthy()
    expect(within(foothold).getByTitle("Border diameter").textContent).toBe("99")
    expect(within(deep).getByText("Nether")).toBeTruthy()
    expect(within(deep).queryByTitle("Border diameter")).toBeNull()
    await waitFor(() => expect(foothold.querySelector('[data-icon="minecraft:map"]')).toBeTruthy())
    expect(deep.querySelector('[data-icon="minecraft:netherrack"]')).toBeTruthy()
  })

  it("draws each objective of a closed milestone as one pill: its type, then what it names", async () => {
    backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    const foothold = within(screen.getByRole("list", { name: "Milestones" })).getByRole("listitem", {
      name: "foothold",
    })
    const logs = within(foothold).getByTitle("logs")
    await waitFor(() => expect(logs.querySelector('[data-icon="minecraft:chest"]')).toBeTruthy())
    expect(logs.querySelector('[data-icon="minecraft:oak_log"]')).toBeTruthy()
    const kills = within(foothold).getByTitle("kills")
    expect(within(kills).getByText("Kill entity")).toBeTruthy()
    expect(kills.querySelector('[data-icon="minecraft:zombie_spawn_egg"]')).toBeTruthy()
  })

  it("shows an objective only the fields of its type, and empties the other type's when the type changes", async () => {
    const saved = backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    fireEvent.click(screen.getByRole("button", { name: /foothold/ }))
    fireEvent.click(await screen.findByRole("button", { name: /logs/ }))
    const sheet = await screen.findByRole("dialog")
    expect(within(sheet).getByText("Items")).toBeTruthy()
    expect(within(sheet).queryByText("Statistic")).toBeNull()
    expect(within(sheet).queryByText("Advancement")).toBeNull()

    fireEvent.keyDown(within(sheet).getByRole("combobox", { name: "Type" }), {
      key: "Enter",
    })
    fireEvent.click(await screen.findByRole("option", { name: "Statistic" }))
    expect(within(sheet).queryByText("Items")).toBeNull()
    expect(within(sheet).getByRole("button", { name: "Statistic" })).toBeTruthy()
    fireEvent.click(within(sheet).getByRole("button", { name: "Done" }))

    fireEvent.click(await screen.findByRole("button", { name: "Save 1" }))
    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]).toMatchObject({
      changes: {
        milestones: [
          { key: "waiting" },
          {
            objectives: [{ key: "logs", type: "STATISTIC", items: [] }, { key: "planks" }, { key: "kills" }],
          },
          { key: "deep" },
        ],
      },
    })
  })

  it("asks for subjects only for a statistic that counts some, and offers no statistic the schema leaves out", async () => {
    const saved = backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    fireEvent.click(screen.getByRole("button", { name: /foothold/ }))
    fireEvent.click(await screen.findByRole("button", { name: /kills/ }))
    const sheet = await screen.findByRole("dialog")
    expect(await within(sheet).findByText("Zombie")).toBeTruthy()
    expect(within(sheet).getByText("Subjects")).toBeTruthy()

    fireEvent.click(within(sheet).getByRole("button", { name: "Statistic" }))
    const options = await screen.findByRole("listbox", { name: "Statistic" })
    expect(within(options).queryByText("Time Played")).toBeNull()
    const jump = within(options).getByRole("option", { name: /Jump/ })
    expect(jump.querySelector("svg")).toBeTruthy()
    fireEvent.click(jump)

    await waitFor(() => expect(within(sheet).queryByText("Subjects")).toBeNull())
    fireEvent.click(within(sheet).getByRole("button", { name: "Done" }))
    fireEvent.click(await screen.findByRole("button", { name: "Save 1" }))
    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]).toMatchObject({
      changes: {
        milestones: [
          { key: "waiting" },
          {
            objectives: [
              { key: "logs" },
              { key: "planks" },
              { key: "kills", statistic: "minecraft:jump", subjects: [] },
            ],
          },
          { key: "deep" },
        ],
      },
    })
  })

  it("starts a new objective from the schema's defaults", async () => {
    const saved = backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    fireEvent.click(screen.getByRole("button", { name: /deep/ }))
    fireEvent.click(await screen.findByRole("button", { name: "Objective" }))
    const sheet = await screen.findByRole("dialog")
    expect(within(sheet).getByRole("combobox", { name: "Type" }).textContent).toContain("Hand-in")
    expect(within(sheet).getByRole("textbox", { name: "Target" })).toHaveProperty("value", "1")
    expect(within(sheet).getByText("Items")).toBeTruthy()
    fireEvent.click(within(sheet).getByRole("button", { name: "Done" }))

    fireEvent.click(await screen.findByRole("button", { name: "Save 1" }))
    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]).toMatchObject({
      changes: {
        milestones: [
          { key: "waiting" },
          { key: "foothold" },
          { objectives: [{ type: "HAND_IN", target: "1", items: [] }] },
        ],
      },
    })
  })

  it("saves every value that names something in the game in its namespaced form", async () => {
    const saved = backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    fireEvent.click(screen.getByRole("button", { name: /waiting/ }))
    fireEvent.change(await screen.findByRole("textbox", { name: "Border diameter" }), { target: { value: "30" } })
    fireEvent.click(await screen.findByRole("button", { name: "Save 1" }))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]).toMatchObject({
      changes: {
        milestones: [
          { key: "waiting", "border-diameter": "30" },
          {
            objectives: [
              { key: "logs", items: ["minecraft:oak_log"] },
              { key: "planks", items: ["minecraft:oak_log"] },
              {
                key: "kills",
                statistic: "minecraft:kill_entity",
                subjects: ["minecraft:zombie"],
              },
            ],
          },
          { key: "deep" },
        ],
      },
    })
  })

  it("moves a milestone, since the order is the season's", async () => {
    const saved = backend()
    draw(<MilestonesEditor file={FILE} document={DOCUMENT} target={null} />)

    fireEvent.click(screen.getByRole("button", { name: /foothold/ }))
    fireEvent.click(await screen.findByRole("button", { name: "Move foothold up" }))
    fireEvent.click(await screen.findByRole("button", { name: "Save 1" }))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]).toMatchObject({
      changes: {
        milestones: [{ key: "foothold" }, { key: "waiting" }, { key: "deep" }],
      },
    })
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
    ).toEqual(["waiting", "foothold", "deep"])
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
