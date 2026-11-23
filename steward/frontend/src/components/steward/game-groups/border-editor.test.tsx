import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { StrictMode, type ReactNode } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigDocument, ConfigEntry } from "@/lib/api"
import { clearDraft } from "@/lib/drafts"
import { configSaved } from "@/lib/query-fixtures"
import { customEditor } from "@/components/steward/config-editors"
import {
  CustomBorderEditor,
  edgeWarnings,
  readsCustomBorder,
  shapesFrom,
  shapesOf,
  withShapes,
  type AreaShape,
} from "@/components/steward/game-groups/border-editor"
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

const CORNER = [
  entry("x", { label: "X", type: "INTEGER", value: "0" }),
  entry("z", { label: "Z", type: "INTEGER", value: "0" }),
]

const POLYGON = [
  entry("name", { label: "Name", value: "" }),
  entry("corners", { label: "Corners", kind: "SECTIONS", template: CORNER }),
]

const WORLD = [
  entry("world", { label: "World", value: "" }),
  entry("polygons", { label: "Polygons", kind: "SECTIONS", template: POLYGON }),
]

function polygonSection(name: string, corners: { x: string; z: string }[]): ConfigEntry[] {
  return [
    { ...POLYGON[0], value: name },
    {
      ...POLYGON[1],
      sections: corners.map((corner) => [
        { ...CORNER[0], value: corner.x },
        { ...CORNER[1], value: corner.z },
      ]),
    },
  ]
}

function worldSection(world: string, polygons: ConfigEntry[][]): ConfigEntry[] {
  return [
    { ...WORLD[0], value: world },
    { ...WORLD[1], sections: polygons },
  ]
}

const BORDER: ConfigDocument = {
  service: "smp",
  name: "custom-border",
  path: "smp/custom-border",
  label: "Custom border",
  live: true,
  readable: true,
  writable: true,
  revision: "r1",
  entries: [
    {
      ...entry("worlds", { label: "Worlds", kind: "SECTIONS", template: WORLD }),
      sections: [
        worldSection("nordtal", [
          polygonSection("Spawn", [
            { x: "500", z: "500" },
            { x: "900", z: "500" },
            { x: "700", z: "900" },
          ]),
        ]),
        worldSection("nordtal_nether", []),
      ],
    },
  ],
}

const WORLDS_CONFIG: ConfigDocument = {
  service: "smp",
  name: "worlds",
  path: "smp/worlds",
  label: "Worlds",
  live: true,
  readable: true,
  writable: true,
  revision: "r1",
  entries: [entry("world-nordtal", { value: "nordtal" })],
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } })
}

type DraftReply = (body: unknown) => Record<string, unknown>

function stepOf(body: unknown): string | undefined {
  return typeof body === "object" && body !== null && "step" in body && typeof body.step === "string"
    ? body.step
    : undefined
}

function backend({
  document = BORDER,
  taker = "READY",
  onDraft = () => ({ status: "DONE" }),
}: {
  document?: ConfigDocument
  taker?: string
  onDraft?: DraftReply
} = {}) {
  const opened: unknown[] = []
  const steps: unknown[] = []
  const puts: unknown[] = []
  const runs = new Map<string, Record<string, unknown>>()
  let nextId = 1

  vi.stubGlobal(
    "fetch",
    vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
      const method = init?.method ?? "GET"
      if (url === "/api/setting-groups/smp/worlds" && method === "GET") return json(WORLDS_CONFIG)
      if (url === "/api/setting-groups/smp/custom-border" && method === "PUT") {
        puts.push(JSON.parse(init?.body ?? "null"))
        return json(configSaved(document))
      }
      if (url === "/api/smp/take-position" && method === "GET") return json({ taker })
      if (url === "/api/smp/area-editor" && method === "POST") {
        opened.push(JSON.parse(init?.body ?? "null"))
        const id = `smp:${nextId++}`
        runs.set(id, { id, status: "DONE" })
        return json({ id, status: "PENDING" })
      }
      if (url === "/api/smp/area-draft" && method === "POST") {
        const body: unknown = JSON.parse(init?.body ?? "null")
        steps.push(body)
        const id = `smp:${nextId++}`
        runs.set(id, { id, ...onDraft(body) })
        return json({ id, status: "PENDING" })
      }
      if (url.startsWith("/api/commands/") && method === "GET") {
        const id = url.slice("/api/commands/".length)
        const run = runs.get(id)
        if (run) return json(run)
      }
      throw new Error(`the border editor asked for ${method} ${url}, which this test did not expect`)
    }),
  )

  return { opened, steps, puts }
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
  clearDraft("smp/custom-border")
  vi.unstubAllGlobals()
})

describe("border-editor helpers", () => {
  it("recognises the custom-border schema and registers in customEditor", () => {
    expect(readsCustomBorder(BORDER)).toBe(true)
    expect(customEditor("custom-border", BORDER)).toBe(CustomBorderEditor)
    expect(readsCustomBorder(WORLDS_CONFIG)).toBe(false)
  })

  it("extracts valid integer corners in shapesFrom and skips blank or non-integer corners", () => {
    expect(shapesFrom(undefined)).toEqual([])
    expect(
      shapesFrom({
        world: "nordtal",
        polygons: [
          {
            name: "Spawn",
            corners: [
              { x: "100", z: "200" },
              { x: "", z: "300" },
              { x: "12.5", z: "400" },
              { x: "300", z: "bad" },
              { x: "400", z: "500" },
            ],
          },
        ],
      }),
    ).toEqual([
      {
        name: "Spawn",
        corners: [
          { x: 100, z: 200 },
          { x: 400, z: 500 },
        ],
        bottom: 0,
        top: 0,
      },
    ])
  })

  it("replaces a world's polygons in withShapes and appends a card for a new world", () => {
    const shapes: AreaShape[] = [
      {
        name: "East",
        corners: [
          { x: 10, z: 20 },
          { x: 30, z: 20 },
          { x: 20, z: 40 },
        ],
        bottom: 0,
        top: 0,
      },
    ]
    const initial = [{ world: "nordtal", polygons: [{ name: "Old", corners: [] }] }]
    expect(withShapes(initial, WORLD, "nordtal", shapes)).toEqual([
      {
        world: "nordtal",
        polygons: [
          {
            name: "East",
            corners: [
              { x: "10", z: "20" },
              { x: "30", z: "20" },
              { x: "20", z: "40" },
            ],
          },
        ],
      },
    ])
    expect(withShapes(initial, WORLD, "nordtal_nether", shapes)).toEqual([
      initial[0],
      {
        world: "nordtal_nether",
        polygons: [
          {
            name: "East",
            corners: [
              { x: "10", z: "20" },
              { x: "30", z: "20" },
              { x: "20", z: "40" },
            ],
          },
        ],
      },
    ])
  })

  it("warns only for overworld regions closer than 160 blocks to 0..10239", () => {
    const cards = [
      {
        world: "nordtal",
        polygons: [
          {
            name: "Safe",
            corners: [
              { x: "160", z: "160" },
              { x: "10079", z: "160" },
              { x: "5000", z: "10079" },
            ],
          },
          {
            name: "Near west",
            corners: [
              { x: "120", z: "500" },
              { x: "500", z: "500" },
              { x: "500", z: "900" },
            ],
          },
          {
            name: "",
            corners: [
              { x: "500", z: "10150" },
              { x: "700", z: "500" },
              { x: "900", z: "700" },
            ],
          },
        ],
      },
      {
        world: "nordtal_nether",
        polygons: [{ name: "Nether edge", corners: [{ x: "10", z: "10" }] }],
      },
    ]
    expect(edgeWarnings(cards, undefined)).toEqual([])
    expect(edgeWarnings(cards, "nordtal")).toEqual([
      "Near west is 120 blocks from the edge of the map. Keep at least 160 blocks.",
      "3 is 89 blocks from the edge of the map. Keep at least 160 blocks.",
    ])
  })

  it("validates draft shapes returned in a CommandRun with shapesOf", () => {
    const answer = { world: "nordtal", shapes: [{ name: "A", corners: [{ x: 1, z: 2 }], bottom: 0, top: 0 }] }
    expect(shapesOf({ id: "smp:1", status: "DONE", answer })).toEqual(answer)
    expect(shapesOf({ id: "smp:1", status: "DONE", answer: { world: "nordtal", shapes: ["bad"] } })).toBeUndefined()
  })
})

describe("CustomBorderEditor", { timeout: 15_000 }, () => {
  it("opens a draft, adds a polygon, saves the draft into the group, and closes the draft", async () => {
    const edited: AreaShape[] = [
      {
        name: "Spawn",
        corners: [
          { x: 500, z: 500 },
          { x: 900, z: 500 },
          { x: 700, z: 900 },
        ],
        bottom: 0,
        top: 0,
      },
      {
        name: "Harbor",
        corners: [
          { x: 1000, z: 1000 },
          { x: 1200, z: 1000 },
          { x: 1100, z: 1200 },
        ],
        bottom: 0,
        top: 0,
      },
    ]
    let reads = 0
    const { opened, steps, puts } = backend({
      onDraft: (body) => {
        if (stepOf(body) === "READ") {
          reads++
          return reads === 1
            ? { status: "REFUSED", reason: "NO_AREA_DRAFT" }
            : { status: "DONE", answer: { world: "nordtal", shapes: edited } }
        }
        return { status: "DONE" }
      },
    })

    draw(<CustomBorderEditor file="smp/custom-border" document={BORDER} target={null} />)

    const editButton = screen.getByRole("button", { name: "Edit in the game" })
    await waitFor(() => expect(editButton).toHaveProperty("disabled", false))
    fireEvent.click(editButton)

    await screen.findByText("Your draft is open in the game. Only you see it until you save or discard it.")
    expect(opened).toEqual([
      {
        world: "nordtal",
        heights: false,
        shapes: [
          {
            name: "Spawn",
            corners: [
              { x: 500, z: 500 },
              { x: 900, z: 500 },
              { x: 700, z: 900 },
            ],
            bottom: 0,
            top: 0,
          },
        ],
      },
    ])

    const nameInput = screen.getByRole("textbox", { name: "Name of the new polygon" })
    const addButton = screen.getByRole("button", { name: "New polygon" })
    expect(addButton).toHaveProperty("disabled", true)
    fireEvent.change(nameInput, { target: { value: "  Harbor  " } })
    expect(addButton).toHaveProperty("disabled", false)
    fireEvent.click(addButton)

    await waitFor(() => expect(steps).toContainEqual({ step: "ADD", name: "Harbor" }))
    await waitFor(() => expect(nameInput).toHaveProperty("value", ""))

    fireEvent.click(screen.getByRole("button", { name: "Save" }))

    await waitFor(() => expect(puts).toHaveLength(1))
    expect(puts[0]).toMatchObject({
      revision: "r1",
      changes: {
        worlds: [
          {
            world: "nordtal",
            polygons: [
              {
                name: "Spawn",
                corners: [
                  { x: "500", z: "500" },
                  { x: "900", z: "500" },
                  { x: "700", z: "900" },
                ],
              },
              {
                name: "Harbor",
                corners: [
                  { x: "1000", z: "1000" },
                  { x: "1200", z: "1000" },
                  { x: "1100", z: "1200" },
                ],
              },
            ],
          },
          { world: "nordtal_nether", polygons: [] },
        ],
      },
    })
    await waitFor(() =>
      expect(steps).toEqual([{ step: "READ" }, { step: "ADD", name: "Harbor" }, { step: "READ" }, { step: "CLOSE" }]),
    )
    await waitFor(() =>
      expect(
        screen.queryByText("Your draft is open in the game. Only you see it until you save or discard it."),
      ).toBeNull(),
    )
  })

  it("closes the editing panel when smp refuses a step with NO_AREA_DRAFT", async () => {
    backend({
      onDraft: () => ({ status: "REFUSED", reason: "NO_AREA_DRAFT" }),
    })

    draw(<CustomBorderEditor file="smp/custom-border" document={BORDER} target={null} />)

    const editButton = screen.getByRole("button", { name: "Edit in the game" })
    await waitFor(() => expect(editButton).toHaveProperty("disabled", false))
    fireEvent.click(editButton)

    await screen.findByText("Your draft is open in the game. Only you see it until you save or discard it.")
    fireEvent.click(screen.getByRole("button", { name: "Save" }))

    await waitFor(() =>
      expect(
        screen.queryByText("Your draft is open in the game. Only you see it until you save or discard it."),
      ).toBeNull(),
    )
  })

  it("discards an open draft without saving the group and displays overworld edge warnings", async () => {
    const nearEdge: ConfigDocument = {
      ...BORDER,
      entries: [
        {
          ...entry("worlds", { label: "Worlds", kind: "SECTIONS", template: WORLD }),
          sections: [
            worldSection("nordtal", [
              polygonSection("Coast", [
                { x: "100", z: "500" },
                { x: "500", z: "500" },
                { x: "500", z: "900" },
              ]),
            ]),
          ],
        },
      ],
    }
    const { steps, puts } = backend({ document: nearEdge })

    draw(<CustomBorderEditor file="smp/custom-border" document={nearEdge} target={null} />)

    expect(
      await screen.findByText("Coast is 100 blocks from the edge of the map. Keep at least 160 blocks."),
    ).toBeTruthy()

    const editButton = screen.getByRole("button", { name: "Edit in the game" })
    await waitFor(() => expect(editButton).toHaveProperty("disabled", false))
    fireEvent.click(editButton)
    await screen.findByText("Your draft is open in the game. Only you see it until you save or discard it.")

    fireEvent.click(screen.getByRole("button", { name: "Discard" }))
    await waitFor(() => expect(steps).toEqual([{ step: "READ" }, { step: "CLOSE" }]))
    await waitFor(() =>
      expect(
        screen.queryByText("Your draft is open in the game. Only you see it until you save or discard it."),
      ).toBeNull(),
    )
    expect(puts).toHaveLength(0)
  })

  it("restores Save and Discard on mount and computes the 160-block warning from the in-game draft", async () => {
    const inGame: AreaShape[] = [
      {
        name: "Harbor",
        corners: [
          { x: 90, z: 500 },
          { x: 500, z: 500 },
          { x: 500, z: 900 },
        ],
        bottom: 0,
        top: 0,
      },
    ]
    const { steps } = backend({
      onDraft: (body) =>
        stepOf(body) === "READ" ? { status: "DONE", answer: { world: "nordtal", shapes: inGame } } : { status: "DONE" },
    })

    draw(<CustomBorderEditor file="smp/custom-border" document={BORDER} target={null} />)

    await screen.findByText("Your draft is open in the game. Only you see it until you save or discard it.")
    expect(screen.getByRole("button", { name: "Save" })).toBeTruthy()
    expect(screen.getByRole("button", { name: "Discard" })).toBeTruthy()
    expect(
      await screen.findByText("Harbor is 90 blocks from the edge of the map. Keep at least 160 blocks."),
    ).toBeTruthy()
    expect(steps).toEqual([{ step: "READ" }])
  })
})
