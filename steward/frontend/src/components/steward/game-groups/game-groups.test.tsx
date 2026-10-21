import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigDocument, ConfigEntry } from "@/lib/api"
import { clearDraft } from "@/lib/drafts"
import { customEditor } from "@/components/steward/config-editors"
import { AwardsEditor } from "@/components/steward/game-groups/awards-editor"
import { DuelsEditor } from "@/components/steward/game-groups/duels-editor"
import { chance } from "@/components/steward/game-groups/model"
import { WheelEditor } from "@/components/steward/game-groups/wheel-editor"
import { TooltipProvider } from "@/components/ui/tooltip"

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

const number = (key: string, label: string, value: string) => entry(key, { label, type: "INTEGER", value })

function list(key: string, label: string, template: ConfigEntry[], rows: Record<string, string>[]): ConfigEntry {
  return {
    ...entry(key, { label, kind: "SECTIONS", template }),
    sections: rows.map((row) => template.map((field) => ({ ...field, value: row[field.key] ?? field.value }))),
  }
}

const ITEM = entry("item", { label: "Item", value: "", refers: { to: "ITEM", optional: false } })

const BOX = [
  entry("world", { label: "World", value: "nordtal" }),
  ...["min-x", "min-y", "min-z", "max-x", "max-y", "max-z"].map((key) =>
    number(key, `${key.startsWith("min") ? "Min" : "Max"} ${key.slice(-1).toUpperCase()}`, "0"),
  ),
]

function document(name: string, entries: ConfigEntry[]): ConfigDocument {
  return {
    service: "smp",
    name,
    path: `smp/${name}`,
    label: "",
    live: true,
    readable: true,
    writable: true,
    revision: "r1",
    restartRequired: false,
    entries,
  }
}

const AWARDS = document("advancement-awards", [
  list(
    "advancement-awards",
    "Advancement awards",
    [
      entry("advancement", { label: "Advancement", value: "", refers: { to: "ADVANCEMENT", optional: false } }),
      number("aura", "Aura", "0"),
    ],
    [
      { advancement: "minecraft:story/mine_stone", aura: "2" },
      { advancement: "minecraft:story/smelt_iron", aura: "4" },
    ],
  ),
])

const WHEEL = document("wheel", [
  list("wheel-regions", "Wheel regions", BOX, [{ "min-x": "3978", "max-x": "3978" }]),
  list(
    "wheel-prizes",
    "Wheel prizes",
    [ITEM, number("amount", "Amount", "1"), number("weight", "Weight", "1")],
    [
      { item: "minecraft:oak_log", amount: "64", weight: "997" },
      { item: "minecraft:elytra", amount: "1", weight: "3" },
    ],
  ),
])

/** The loadouts carry a weight here as the published schema before this change did, which the editor must still read. */
const LOADOUT = [ITEM, number("amount", "Amount", "1"), number("weight", "Weight", "1")]

const DUELS = document("duels", [
  list(
    "duel-platforms",
    "Duel platforms",
    [entry("type", { label: "Type", value: "SWORD" }), ...BOX],
    [
      { type: "SWORD", "min-x": "99" },
      { type: "BOW", "min-x": "111" },
    ],
  ),
  number("duel-arena-base-y", "Duel arena base Y", "200"),
  number("duel-stake", "Duel stake", "10"),
  list("duel-loadout-sword", "Sword loadout", LOADOUT, [{ item: "minecraft:iron_sword" }]),
  list("duel-loadout-bow", "Bow loadout", LOADOUT, [
    { item: "minecraft:bow" },
    { item: "minecraft:arrow", amount: "64" },
  ]),
])

const GAME = {
  version: "26.2",
  datapacks: [],
  registries: {
    item: [
      { id: "minecraft:oak_log", text: "Oak Log" },
      { id: "minecraft:elytra", text: "Elytra" },
      { id: "minecraft:iron_sword", text: "Iron Sword" },
      { id: "minecraft:bow", text: "Bow" },
      { id: "minecraft:arrow", text: "Arrow" },
    ],
    advancement: [
      { id: "minecraft:story/mine_stone", text: "Stone Age", frame: "task", icon: "minecraft:oak_log" },
      { id: "minecraft:story/smelt_iron", text: "Acquire Hardware", frame: "task", icon: "minecraft:elytra" },
    ],
  },
  tags: {},
  icons: { url: "/icons.png", columns: 4, slots: { "minecraft:oak_log": 0, "minecraft:elytra": 1 } },
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } })
}

function backend(saved: ConfigDocument) {
  const puts: unknown[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
      if (init?.method === "PUT") {
        puts.push(JSON.parse(init.body ?? ""))
        return json(saved)
      }
      if (url === "/api/game-data") return json(GAME)
      throw new Error(`the editor asked for ${url}, which this test did not expect`)
    }),
  )
  return puts
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
  for (const name of ["advancement-awards", "wheel", "duels"]) clearDraft(`smp/${name}`)
  vi.unstubAllGlobals()
})

describe("customEditor", () => {
  it("draws each game group with its own editor, and falls back where the shape is another", () => {
    expect(customEditor("advancement-awards", AWARDS)).toBe(AwardsEditor)
    expect(customEditor("wheel", WHEEL)).toBe(WheelEditor)
    expect(customEditor("duels", DUELS)).toBe(DuelsEditor)
    expect(customEditor("wheel", AWARDS)).toBeUndefined()
    expect(customEditor("duels", { ...DUELS, entries: DUELS.entries.slice(0, 3) })).toBeUndefined()
  })
})

describe("chance", () => {
  it("keeps one decimal from one percent and two significant digits below it", () => {
    expect(chance(120, 1000)).toBe("12.0%")
    expect(chance(3, 1000)).toBe("0.30%")
    expect(chance(0, 1000)).toBe("0%")
    expect(chance(1, 0)).toBe("0%")
  })
})

describe("game group editors", { timeout: 15_000 }, () => {
  it("sums the awarded aura as it is edited, and saves the whole list", async () => {
    const puts = backend(AWARDS)
    draw(<AwardsEditor file="smp/advancement-awards" document={AWARDS} target={null} />)

    expect(await screen.findByText("Acquire Hardware")).toBeTruthy()
    expect(screen.getByText("6")).toBeTruthy()
    fireEvent.change(screen.getByRole("textbox", { name: "Acquire Hardware aura" }), { target: { value: "10" } })
    expect(screen.getByText("12")).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: "Save 1" }))
    await waitFor(() => expect(puts).toHaveLength(1))
    expect(puts[0]).toMatchObject({
      changes: {
        "advancement-awards": [
          { advancement: "minecraft:story/mine_stone", aura: "2" },
          { advancement: "minecraft:story/smelt_iron", aura: "10" },
        ],
      },
    })
  })

  it("shows each prize's chance on its tile and opens its fields below the grid", async () => {
    backend(WHEEL)
    draw(<WheelEditor file="smp/wheel" document={WHEEL} target={null} />)

    expect((await screen.findByRole("button", { name: /Oak Log/ })).textContent).toContain("99.7%")
    const elytra = screen.getByRole("button", { name: /Elytra/ })
    expect(within(elytra).getByText("0.30%")).toBeTruthy()
    fireEvent.click(elytra)
    expect(elytra.getAttribute("aria-pressed")).toBe("true")
    fireEvent.change(screen.getByRole("textbox", { name: "Elytra Weight" }), { target: { value: "1997" } })
    expect(within(elytra).getByText("66.7%")).toBeTruthy()
    expect(screen.getByRole("textbox", { name: "Min X" })).toHaveProperty("value", "3978")
  })

  it("draws a card per duel type with its platform and loadout", async () => {
    backend(DUELS)
    draw(<DuelsEditor file="smp/duels" document={DUELS} target={null} />)

    expect(screen.getByRole("textbox", { name: /Duel stake/ })).toHaveProperty("value", "10")
    const arrow = await screen.findByRole("textbox", { name: "Arrow Amount" })
    expect(arrow).toHaveProperty("value", "64")
    const bow = arrow.closest<HTMLElement>("[data-slot=card]")
    if (bow === null) throw new Error("the loadout sits in no card")
    expect(bow.querySelector("[data-slot=card-title]")?.textContent).toBe("Bow")
    expect(within(bow).getByRole("textbox", { name: "Min X" })).toHaveProperty("value", "111")
    expect(within(bow).queryByText("Iron Sword")).toBeNull()
  })
})
