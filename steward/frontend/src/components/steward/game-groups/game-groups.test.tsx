import { StrictMode, type ReactNode, useState } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigDocument, ConfigEntry } from "@/lib/api"
import { clearDraft } from "@/lib/drafts"
import { customEditor } from "@/components/steward/config-editors"
import { Control } from "@/components/steward/configuration"
import { type SectionValues, sectionsFromEntry } from "@/components/steward/repeatable-cards"
import { AwardsEditor } from "@/components/steward/game-groups/awards-editor"
import { KitList } from "@/components/steward/game-groups/kits"
import { chance } from "@/components/steward/game-groups/model"
import { WheelEditor } from "@/components/steward/game-groups/wheel-editor"
import { TooltipProvider } from "@/components/ui/tooltip"
import { byte, compound, double, int, itemData, list as nbtList, stack, text } from "@/lib/item-data-fixtures"
import { configSaved } from "@/lib/query-fixtures"
import { tipOf } from "@/lib/test-elements"

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
      number("spins", "Spins", "0"),
    ],
    [
      { advancement: "minecraft:story/mine_stone", aura: "2" },
      { advancement: "minecraft:story/smelt_iron", aura: "4", spins: "1" },
    ],
  ),
])

const BLOCK = [
  entry("world", { label: "World", value: "nordtal", refers: { to: "WORLD", optional: false } }),
  number("x", "X", "0"),
  number("y", "Y", "64"),
  number("z", "Z", "0"),
]

const WHEEL = document("wheel", [
  list("wheel-blocks", "Wheel blocks", BLOCK, [{ x: "3978", y: "75", z: "2171" }]),
  list(
    "wheel-prizes",
    "Wheel prizes",
    [ITEM, number("amount", "Amount", "1"), number("weight", "Weight", "1"), entry("data", { value: "" })],
    [
      { item: "minecraft:oak_log", amount: "64", weight: "997" },
      { item: "minecraft:elytra", amount: "1", weight: "3" },
    ],
  ),
])

const PIECE = [ITEM, number("amount", "Amount", "1"), entry("data", { label: "Item data", value: "" })]

const KIT = [
  entry("name", { label: "Name", value: "", rules: [{ rule: "REQUIRED" }, { rule: "LONGEST", limit: 64 }] }),
  entry("icon", { label: "Icon", value: "minecraft:iron_sword", refers: { to: "ITEM", optional: false } }),
  entry("loadout", { label: "Loadout", kind: "SECTIONS", template: PIECE }),
]

/** One kit as smp sends it: its name and icon, and its loadout as a list in the list. */
function kit(name: string, icon: string, pieces: Record<string, string>[]): ConfigEntry[] {
  return [
    { ...KIT[0], value: name },
    { ...KIT[1], value: icon },
    {
      ...KIT[2],
      sections: pieces.map((piece) => PIECE.map((field) => ({ ...field, value: piece[field.key] ?? field.value }))),
    },
  ]
}

function kits(sections: ConfigEntry[][]): ConfigEntry {
  return {
    ...entry("duel-kits", { label: "Duel kits", kind: "SECTIONS", template: KIT }),
    rules: [{ rule: "REQUIRED" }, { rule: "MOST_ENTRIES", limit: 5 }],
    sections,
  }
}

const KITS = kits([
  kit("Sword", "minecraft:iron_sword", [{ item: "minecraft:iron_sword" }]),
  kit("Bow", "minecraft:bow", [{ item: "minecraft:bow" }, { item: "minecraft:arrow", amount: "64" }]),
])

const GAME = {
  version: "26.2",
  worlds: { smp: ["nordtal", "nordtal_nether", "nordtal_the_end"] },
  datapacks: [],
  registries: {
    item: [
      { id: "minecraft:oak_log", text: "Oak Log" },
      { id: "minecraft:elytra", text: "Elytra" },
      { id: "minecraft:iron_sword", text: "Iron Sword" },
      { id: "minecraft:bow", text: "Bow" },
      { id: "minecraft:arrow", text: "Arrow" },
    ],
    enchantment: [
      { id: "minecraft:sharpness", text: "Sharpness" },
      { id: "minecraft:mending", text: "Mending" },
      { id: "minecraft:unbreaking", text: "Unbreaking" },
      { id: "minecraft:fire_aspect", text: "Fire Aspect" },
    ],
    advancement: [
      { id: "minecraft:story/mine_stone", text: "Stone Age", frame: "task", icon: "minecraft:oak_log" },
      { id: "minecraft:story/smelt_iron", text: "Acquire Hardware", frame: "task", icon: "minecraft:elytra" },
      { id: "minecraft:story/lava_bucket", text: "Hot Stuff", frame: "task", icon: "minecraft:oak_log" },
    ],
  },
  tags: {},
  icons: { url: "/icons.png", columns: 4, slots: { "minecraft:oak_log": 0, "minecraft:elytra": 1 } },
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } })
}

type Asked = (url: string, method: string, body?: string) => Response | Promise<Response> | undefined

function backend(saved: ConfigDocument, more: Asked = () => undefined) {
  const puts: unknown[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
      if (init?.method === "PUT") {
        puts.push(JSON.parse(init.body ?? ""))
        return json(configSaved(saved))
      }
      if (url === "/api/game-data") return json(GAME)
      const answered = more(url, init?.method ?? "GET", init?.body)
      if (answered) return answered
      throw new Error(`the editor asked for ${url}, which this test did not expect`)
    }),
  )
  return puts
}

/** An iron sword taken in whole: renamed, with lore, four enchantments, an attribute and unbreakable. */
function frostbite(): Promise<string> {
  return itemData(
    stack("minecraft:iron_sword", {
      "minecraft:custom_name": compound({ text: text("Frostbite"), italic: byte(0) }),
      "minecraft:lore": nbtList([text("Forged in the north")]),
      "minecraft:enchantments": compound({
        "minecraft:sharpness": int(5),
        "minecraft:mending": int(1),
        "minecraft:unbreaking": int(3),
        "minecraft:fire_aspect": int(2),
      }),
      "minecraft:attribute_modifiers": nbtList([
        compound({
          type: text("minecraft:attack_damage"),
          id: text("minecraft:base_attack_damage"),
          amount: double(6),
          operation: text("add_value"),
          slot: text("mainhand"),
        }),
      ]),
      "minecraft:unbreakable": compound({}),
    }),
  )
}

const NOTHING = () => {}

/** A promise that settles once `arrive` is called. */
function held() {
  let arrive = NOTHING
  const promise = new Promise<void>((resolve) => {
    arrive = resolve
  })
  return { promise, arrive }
}

/** A take-in that smp answers with `run` once it is asked, and the withdrawals it is sent. */
function takeIn(run: Record<string, unknown>, sent: Promise<void> = Promise.resolve()) {
  const withdrawn: string[] = []
  const asked: unknown[] = []
  const more: Asked = (url, method, body) => {
    if (url === "/api/smp/take-in" && method === "POST") {
      asked.push(JSON.parse(body ?? "null"))
      return sent.then(() => json({ id: "smp:7", status: "PENDING" }))
    }
    if (url === "/api/commands/smp:7" && method === "DELETE") {
      withdrawn.push(url)
      return new Response(null, { status: 204 })
    }
    if (url === "/api/commands/smp:7") return json({ id: "smp:7", ...run })
    return undefined
  }
  return { more, withdrawn, asked }
}

function draw(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <StrictMode>
      <QueryClientProvider client={client}>
        <TooltipProvider>{node}</TooltipProvider>
      </QueryClientProvider>
    </StrictMode>,
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
    expect(customEditor("wheel", AWARDS)).toBeUndefined()
    expect(customEditor("duels", document("duels", [KITS]))).toBeUndefined()
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
  it("shows each award as a tile, edits its aura and spins in its dialog, and saves the whole list", async () => {
    const puts = backend(AWARDS)
    draw(<AwardsEditor file="smp/advancement-awards" document={AWARDS} target={null} />)

    const tile = await screen.findByRole("button", { name: /Acquire Hardware/ })
    expect(screen.queryByRole("textbox", { name: "Acquire Hardware aura" })).toBeNull()
    expect(screen.getByText("6")).toBeTruthy()
    fireEvent.click(tile)
    const dialog = await screen.findByRole("dialog")
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Acquire Hardware aura" }), {
      target: { value: "10" },
    })
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Acquire Hardware spins" }), {
      target: { value: "3" },
    })
    fireEvent.click(within(dialog).getByRole("button", { name: "Done" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(screen.getByText("12")).toBeTruthy()
    expect(within(tile).getByText("10")).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: "Save 1" }))
    await waitFor(() => expect(puts).toHaveLength(1))
    expect(puts[0]).toMatchObject({
      changes: {
        "advancement-awards": [
          { advancement: "minecraft:story/mine_stone", aura: "2", spins: "0" },
          { advancement: "minecraft:story/smelt_iron", aura: "10", spins: "3" },
        ],
      },
    })
  })

  it("removes an award from its dialog after asking", async () => {
    backend(AWARDS)
    draw(<AwardsEditor file="smp/advancement-awards" document={AWARDS} target={null} />)

    fireEvent.click(await screen.findByRole("button", { name: /Stone Age/ }))
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Remove" }))
    fireEvent.click(await screen.findByRole("button", { name: "Remove it" }))
    await waitFor(() => expect(screen.queryByRole("button", { name: /Stone Age/ })).toBeNull())
    expect(screen.getByRole("button", { name: /Acquire Hardware/ })).toBeTruthy()
  })

  it("shows a wheel block as a tile of its world and X, Y and Z, edits it in a dialog and saves the list", async () => {
    const puts = backend(WHEEL)
    draw(<WheelEditor file="smp/wheel" document={WHEEL} target={null} />)

    const tile = await screen.findByRole("button", { name: "Block 1" })
    expect(tile.textContent).toContain("nordtal")
    for (const value of ["3978", "75", "2171"]) expect(tile.textContent).toContain(value)
    fireEvent.click(tile)
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).queryByText("Corner 1")).toBeNull()
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Y" }), { target: { value: "80" } })
    expect(within(dialog).getByRole("button", { name: "Remove" })).toBeTruthy()
    fireEvent.click(within(dialog).getByRole("button", { name: "Done" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(tile.textContent).toContain("80")

    fireEvent.click(screen.getByRole("button", { name: "Block" }))
    expect(within(await screen.findByRole("dialog")).getByText("Block 2")).toBeTruthy()
    expect(screen.getByRole("button", { name: "Block 2", hidden: true })).toBeTruthy()
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Done" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(screen.queryByRole("button", { name: "Block 2", hidden: true })).toBeNull()

    fireEvent.click(screen.getByRole("button", { name: "Block" }))
    const added = await screen.findByRole("dialog")
    fireEvent.change(within(added).getByRole("textbox", { name: "X" }), { target: { value: "16" } })
    fireEvent.click(within(added).getByRole("button", { name: "Done" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(screen.getByRole("button", { name: "Block 2" }).textContent).toContain("16")

    fireEvent.click(screen.getByRole("button", { name: "Save 1" }))
    await waitFor(() => expect(puts).toHaveLength(1))
    expect(puts[0]).toMatchObject({
      changes: {
        "wheel-blocks": [
          { world: "nordtal", x: "3978", y: "80", z: "2171" },
          { world: "nordtal", x: "16", y: "64", z: "0" },
        ],
      },
    })
  })

  it("shows each prize's chance on its tile and edits amount and weight in its dialog, never the item", async () => {
    backend(WHEEL)
    draw(<WheelEditor file="smp/wheel" document={WHEEL} target={null} />)

    expect((await screen.findByRole("button", { name: /Oak Log/ })).textContent).toContain("99.7%")
    expect(screen.getByRole("button", { name: "Block 1" }).textContent).toContain("3978")
    const elytra = screen.getByRole("button", { name: /Elytra/ })
    expect(within(elytra).getByText("0.30%")).toBeTruthy()
    fireEvent.click(elytra)
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).queryByRole("button", { name: /Item/ })).toBeNull()
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Elytra Weight" }), { target: { value: "1997" } })
    expect(within(dialog).getByRole("textbox", { name: "Elytra Chance" })).toHaveProperty("value", "66.7%")
    expect(within(elytra).getByText("66.7%")).toBeTruthy()
  })

  it("shows a prize taken in whole as one element: its name, enchantment badges, and its lore in the tooltip", async () => {
    const wheel = document("wheel", [
      list(
        "wheel-prizes",
        "Wheel prizes",
        [ITEM, number("amount", "Amount", "1"), number("weight", "Weight", "1"), entry("data", { value: "" })],
        [
          { item: "minecraft:iron_sword", amount: "1", weight: "1", data: await frostbite() },
          { item: "minecraft:oak_log", amount: "64", weight: "1" },
        ],
      ),
    ])
    backend(wheel)
    draw(<WheelEditor file="smp/wheel" document={wheel} target={null} />)

    const tile = await screen.findByRole("button", { name: /Frostbite/ })
    expect(within(tile).getByText("Sharpness V")).toBeTruthy()
    expect(within(tile).getByText("Mending")).toBeTruthy()
    expect(within(tile).getByText("2 more")).toBeTruthy()
    expect(tipOf(tile)).toBe(
      "Unbreaking IIIFire Aspect IIForged in the north+6 Attack damage in the main handUnbreakable",
    )
    expect(tipOf(screen.getByRole("button", { name: /Oak Log/ }))).toBeNull()

    fireEvent.click(tile)
    const dialog = await screen.findByRole("dialog", { name: "Frostbite" })
    for (const shown of [
      "Sharpness V",
      "Mending",
      "Unbreaking III",
      "Fire Aspect II",
      "Forged in the north",
      "Unbreakable",
    ])
      expect(within(dialog).getByText(shown)).toBeTruthy()
    expect(within(dialog).getByRole("textbox", { name: "Frostbite Weight" })).toBeTruthy()
  })

  it("opens the dialog of an award as soon as it is added", async () => {
    backend(AWARDS)
    draw(<AwardsEditor file="smp/advancement-awards" document={AWARDS} target={null} />)

    fireEvent.click(await screen.findByRole("button", { name: "Advancement" }))
    fireEvent.click(within(await screen.findByRole("listbox")).getByRole("option", { name: /Hot Stuff/ }))
    const dialog = await screen.findByRole("dialog", { name: "Hot Stuff" })
    expect(within(dialog).getByRole("textbox", { name: "Hot Stuff aura" })).toHaveProperty("value", "0")
    fireEvent.click(within(dialog).getByRole("button", { name: "Done" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(screen.getByRole("button", { name: /Hot Stuff/ })).toBeTruthy()
  })

  it("opens the dialog of a prize as soon as it is added", async () => {
    backend(WHEEL)
    draw(<WheelEditor file="smp/wheel" document={WHEEL} target={null} />)

    fireEvent.click(await screen.findByRole("button", { name: "Prize" }))
    fireEvent.click(within(await screen.findByRole("listbox")).getByRole("option", { name: /Iron Sword/ }))
    const dialog = await screen.findByRole("dialog", { name: "Iron Sword" })
    expect(within(dialog).getByRole("textbox", { name: "Iron Sword Weight" })).toHaveProperty("value", "1")
    fireEvent.click(within(dialog).getByRole("button", { name: "Done" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(screen.getByRole("button", { name: /Iron Sword/ })).toBeTruthy()
  })

  it("takes a prize in from the game whole and saves its data with it", async () => {
    const data = await frostbite()
    const { more, asked } = takeIn({ status: "DONE", answer: { item: "minecraft:iron_sword", data } })
    const puts = backend(WHEEL, more)
    draw(<WheelEditor file="smp/wheel" document={WHEEL} target={null} />)

    fireEvent.click(await screen.findByRole("button", { name: "Prize" }))
    fireEvent.click(await screen.findByRole("button", { name: "Take in from game" }))
    const dialog = await screen.findByRole("dialog", { name: "Frostbite" })
    expect(asked).toEqual([{ purpose: "WHEEL_PRIZE" }])
    expect(within(dialog).getByText("Forged in the north")).toBeTruthy()
    fireEvent.click(within(dialog).getByRole("button", { name: "Done" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())

    fireEvent.click(screen.getByRole("button", { name: "Save 1" }))
    await waitFor(() => expect(puts).toHaveLength(1))
    expect(puts[0]).toMatchObject({
      changes: {
        "wheel-prizes": [
          { item: "minecraft:oak_log", data: "" },
          { item: "minecraft:elytra", data: "" },
          { item: "minecraft:iron_sword", amount: "1", weight: "1", data },
        ],
      },
    })
  })

  it("turns a waiting take-in into a red Cancel take-in, which withdraws it and changes nothing", async () => {
    const { more, withdrawn } = takeIn({ status: "RUNNING" })
    backend(WHEEL, more)
    draw(<WheelEditor file="smp/wheel" document={WHEEL} target={null} />)

    fireEvent.click(await screen.findByRole("button", { name: "Prize" }))
    fireEvent.click(await screen.findByRole("button", { name: "Take in from game" }))
    const cancel = await screen.findByRole("button", { name: "Cancel take-in" })
    expect(cancel.dataset.variant).toBe("destructive")
    fireEvent.click(cancel)
    expect(await screen.findByRole("button", { name: "Take in from game" })).toBeTruthy()
    await waitFor(() => expect(withdrawn).toEqual(["/api/commands/smp:7"]))
    expect(screen.queryByRole("button", { name: /Iron Sword/, hidden: true })).toBeNull()
  })

  it("withdraws a waiting take-in when the picker closes", async () => {
    const { more, withdrawn } = takeIn({ status: "RUNNING" })
    backend(WHEEL, more)
    draw(<WheelEditor file="smp/wheel" document={WHEEL} target={null} />)

    fireEvent.click(await screen.findByRole("button", { name: "Prize" }))
    fireEvent.click(await screen.findByRole("button", { name: "Take in from game" }))
    fireEvent.keyDown(await screen.findByRole("button", { name: "Cancel take-in" }), { key: "Escape" })
    await waitFor(() => expect(withdrawn).toEqual(["/api/commands/smp:7"]))
  })

  it("withdraws a take-in that was still on its way when the picker closed", async () => {
    const { promise, arrive } = held()
    const { more, withdrawn } = takeIn({ status: "RUNNING" }, promise)
    backend(WHEEL, more)
    draw(<WheelEditor file="smp/wheel" document={WHEEL} target={null} />)

    fireEvent.click(await screen.findByRole("button", { name: "Prize" }))
    const button = await screen.findByRole("button", { name: "Take in from game" })
    fireEvent.click(button)
    fireEvent.keyDown(button, { key: "Escape" })
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    arrive()
    await waitFor(() => expect(withdrawn).toEqual(["/api/commands/smp:7"]))
  })

  it("says so when a section holds nothing", async () => {
    const empty = document("advancement-awards", [{ ...AWARDS.entries[0], sections: [] }])
    backend(empty)
    draw(<AwardsEditor file="smp/advancement-awards" document={empty} target={null} />)

    expect(await screen.findByText("No advancement has an award.")).toBeTruthy()
  })
})

/** The kits as the settings form draws them, and every list it was changed to. */
function drawKits(kitList: ConfigEntry = KITS) {
  const changes: SectionValues[][] = []
  function Form() {
    const [value, setValue] = useState(() => sectionsFromEntry(kitList))
    return (
      <KitList
        entry={kitList}
        value={value}
        disabled={false}
        onChange={(next) => {
          changes.push(next)
          setValue(next)
        }}
      />
    )
  }
  draw(<Form />)
  return changes
}

describe("the duel kits", { timeout: 15_000 }, () => {
  it("are drawn by the settings form as tiles", async () => {
    backend(document("duels", [KITS]))
    draw(<Control entry={KITS} draft={{}} disabled={false} onChange={NOTHING} />)

    expect(await screen.findByRole("button", { name: /^Bow/ })).toBeTruthy()
    expect(screen.getByRole("button", { name: /^Sword/ })).toBeTruthy()
  })

  it("show each kit's icon, name and loadout on a tile, and edit name and amounts in its dialog", async () => {
    backend(document("duels", [KITS]))
    const changes = drawKits()

    const bow = await screen.findByRole("button", { name: /^Bow/ })
    expect(within(bow).getByText("64")).toBeTruthy()
    await waitFor(() => expect(tipOf(bow)).toBe("1 × Bow64 × Arrow"))
    fireEvent.click(bow)
    const dialog = await screen.findByRole("dialog", { name: "Bow" })
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Name" }), { target: { value: "Longbow" } })
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Longbow Arrow Amount" }), {
      target: { value: "32" },
    })
    fireEvent.click(within(dialog).getByRole("button", { name: "Remove Bow" }))
    fireEvent.click(await screen.findByRole("button", { name: "Remove it" }))
    await waitFor(() => expect(within(dialog).queryByRole("textbox", { name: "Longbow Bow Amount" })).toBeNull())
    fireEvent.click(within(dialog).getByRole("button", { name: "Done" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())

    expect(screen.getByRole("button", { name: /^Longbow/ })).toBeTruthy()
    expect(changes.at(-1)?.[1]).toMatchObject({
      name: "Longbow",
      icon: "minecraft:bow",
      loadout: [{ item: "minecraft:arrow", amount: "32", data: "" }],
    })
  })

  it("keep the last kit, and stop Add at the most kits the list takes", async () => {
    backend(document("duels", [KITS]))
    drawKits(kits([kit("Sword", "minecraft:iron_sword", [])]))
    fireEvent.click(await screen.findByRole("button", { name: /^Sword/ }))
    const dialog = await screen.findByRole("dialog", { name: "Sword" })
    expect(within(dialog).queryByRole("button", { name: "Remove" })).toBeNull()
    cleanup()

    drawKits(kits(["A", "B", "C", "D", "E"].map((name) => kit(name, "minecraft:bow", []))))
    expect(await screen.findByRole("button", { name: /^E/ })).toBeTruthy()
    expect(screen.getByRole("button", { name: "Kit" })).toHaveProperty("disabled", true)
  })

  it("open a new kit's dialog at once, and drop the kit when it is closed untouched", async () => {
    backend(document("duels", [KITS]))
    const changes = drawKits()

    fireEvent.click(await screen.findByRole("button", { name: "Kit" }))
    const dialog = await screen.findByRole("dialog", { name: "Kit 3" })
    expect(within(dialog).getByRole("button", { name: "Remove" })).toBeTruthy()
    fireEvent.click(within(dialog).getByRole("button", { name: "Done" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(changes.at(-1)).toHaveLength(2)

    fireEvent.click(screen.getByRole("button", { name: "Kit" }))
    const added = await screen.findByRole("dialog", { name: "Kit 3" })
    fireEvent.change(within(added).getByRole("textbox", { name: "Name" }), { target: { value: "Axe" } })
    fireEvent.click(within(added).getByRole("button", { name: "Done" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(changes.at(-1)?.[2]).toMatchObject({ name: "Axe", icon: "minecraft:iron_sword", loadout: [] })
  })

  it("take a loadout item in from the game whole, as a kit item, and show it as one element", async () => {
    const data = await frostbite()
    const { more, asked } = takeIn({ status: "DONE", answer: { item: "minecraft:iron_sword", data } })
    backend(document("duels", [KITS]), more)
    const changes = drawKits()

    fireEvent.click(await screen.findByRole("button", { name: /^Sword/ }))
    const dialog = await screen.findByRole("dialog", { name: "Sword" })
    fireEvent.click(within(dialog).getByRole("button", { name: "Item" }))
    fireEvent.click(await screen.findByRole("button", { name: "Take in from game" }))
    expect(await within(dialog).findByText("Frostbite")).toBeTruthy()
    expect(asked).toEqual([{ purpose: "DUEL_KIT" }])
    expect(within(dialog).getByText("Sharpness V")).toBeTruthy()
    expect(within(dialog).getByRole("textbox", { name: "Sword Frostbite Amount" })).toHaveProperty("value", "1")
    expect(changes.at(-1)?.[0]).toMatchObject({
      loadout: [
        { item: "minecraft:iron_sword", data: "" },
        { item: "minecraft:iron_sword", amount: "1", data },
      ],
    })
  })
})
