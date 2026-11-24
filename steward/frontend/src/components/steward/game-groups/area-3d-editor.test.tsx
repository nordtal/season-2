import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { StrictMode, type ReactNode } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigDocument, ConfigEntry } from "@/lib/api"
import { clearDraft } from "@/lib/drafts"
import { configSaved } from "@/lib/query-fixtures"
import { customEditor } from "@/components/steward/config-editors"
import {
  DuelsEditor,
  SpawnEditor,
  readsDuels,
  readsSpawn,
  shapesFrom3d,
  withShapes3d,
} from "@/components/steward/game-groups/area-3d-editor"
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

const AREA_3D = [
  entry("name", { label: "Name", value: "" }),
  entry("world", { label: "World", value: "" }),
  entry("min-y", { label: "Y from", type: "INTEGER", value: "64" }),
  entry("max-y", { label: "Y to", type: "INTEGER", value: "80" }),
  entry("corners", { label: "Corners", kind: "SECTIONS", template: CORNER }),
]

function areaSection(
  name: string,
  world: string,
  minY: string,
  maxY: string,
  corners: { x: string; z: string }[],
): ConfigEntry[] {
  return [
    { ...AREA_3D[0], value: name },
    { ...AREA_3D[1], value: world },
    { ...AREA_3D[2], value: minY },
    { ...AREA_3D[3], value: maxY },
    {
      ...AREA_3D[4],
      sections: corners.map((corner) => [
        { ...CORNER[0], value: corner.x },
        { ...CORNER[1], value: corner.z },
      ]),
    },
  ]
}

const SPAWN_DOC: ConfigDocument = {
  service: "smp",
  name: "spawn",
  path: "smp/spawn",
  label: "Spawn",
  live: true,
  readable: true,
  writable: true,
  revision: "r1",
  entries: [
    {
      ...entry("protected-areas", { label: "Protected areas", kind: "SECTIONS", template: AREA_3D }),
      sections: [
        areaSection("Spawn", "nordtal", "40", "140", [
          { x: "7", z: "56" },
          { x: "138", z: "56" },
          { x: "138", z: "120" },
          { x: "7", z: "120" },
        ]),
        areaSection("Nether spawn", "nordtal_nether", "20", "120", [
          { x: "-32", z: "-32" },
          { x: "32", z: "-32" },
          { x: "32", z: "32" },
        ]),
      ],
    },
  ],
}

const DUELS_DOC: ConfigDocument = {
  service: "smp",
  name: "duels",
  path: "smp/duels",
  label: "Duels",
  live: true,
  readable: true,
  writable: true,
  revision: "r1",
  entries: [
    {
      ...entry("negotiation-area", { label: "Negotiation area", kind: "SECTIONS", template: AREA_3D }),
      sections: [
        areaSection("Negotiation", "nordtal", "68", "69", [
          { x: "99", z: "87" },
          { x: "101", z: "87" },
          { x: "101", z: "89" },
        ]),
      ],
    },
    {
      ...entry("arena", { label: "Arena", kind: "SECTIONS", template: AREA_3D }),
      sections: [
        areaSection("Arena", "nordtal", "67", "76", [
          { x: "115", z: "80" },
          { x: "131", z: "80" },
          { x: "131", z: "96" },
        ]),
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

function parseBody(init?: RequestInit): unknown {
  return typeof init?.body === "string" ? JSON.parse(init.body) : {}
}

function stepOf(body: unknown): string | undefined {
  return typeof body === "object" && body !== null && "step" in body && typeof body.step === "string"
    ? body.step
    : undefined
}

function draw(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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
  clearDraft("smp/spawn")
  clearDraft("smp/duels")
  vi.unstubAllGlobals()
})

describe("3D area editors", () => {
  it("matches spawn and duels documents with 3D polygon lists and falls back otherwise", () => {
    expect(readsSpawn(SPAWN_DOC)).toBe(true)
    expect(customEditor("spawn", SPAWN_DOC)).toBe(SpawnEditor)
    expect(readsDuels(DUELS_DOC)).toBe(true)
    expect(customEditor("duels", DUELS_DOC)).toBe(DuelsEditor)
    expect(customEditor("spawn", { ...SPAWN_DOC, entries: [] })).toBeUndefined()
    expect(customEditor("duels", { ...DUELS_DOC, entries: [DUELS_DOC.entries[0]] })).toBeUndefined()
  })

  it("maps 3D polygon sections to AreaShape with heights and preserves other worlds on write", () => {
    const entries = [
      {
        name: "Spawn",
        world: "nordtal",
        "min-y": "40",
        "max-y": "140",
        corners: [
          { x: "7", z: "56" },
          { x: "138", z: "56" },
          { x: "138", z: "120" },
        ],
      },
      {
        name: "Nether",
        world: "nordtal_nether",
        "min-y": "20",
        "max-y": "120",
        corners: [
          { x: "-10", z: "-10" },
          { x: "10", z: "-10" },
          { x: "10", z: "10" },
        ],
      },
    ]

    expect(shapesFrom3d(entries, "nordtal")).toEqual([
      {
        name: "Spawn",
        bottom: 40,
        top: 140,
        corners: [
          { x: 7, z: 56 },
          { x: 138, z: 56 },
          { x: 138, z: 120 },
        ],
      },
    ])

    const updated = withShapes3d(entries, "nordtal", [
      {
        name: "Spawn",
        bottom: 30,
        top: 150,
        corners: [
          { x: 0, z: 0 },
          { x: 10, z: 0 },
          { x: 10, z: 10 },
        ],
      },
    ])
    expect(updated).toHaveLength(2)
    expect(updated[0]).toMatchObject({ name: "Nether", world: "nordtal_nether", "min-y": "20", "max-y": "120" })
    expect(updated[1]).toMatchObject({ name: "Spawn", world: "nordtal", "min-y": "30", "max-y": "150" })
  })

  it("opens a 3D draft with heights enabled in SpawnEditor and saves updated heights back", async () => {
    const asked: { path: string; body: unknown }[] = []
    const puts: unknown[] = []
    let nextId = 0
    const runs = new Map<string, Record<string, unknown>>()

    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url
        const method = init?.method ?? "GET"
        if (url === "/api/setting-groups/smp/worlds") return json(WORLDS_CONFIG)
        if (url === "/api/smp/capture") return json({ taker: "READY" })
        if (url === "/api/smp/area-editor" && method === "POST") {
          const body = parseBody(init)
          asked.push({ path: url, body })
          const id = `smp:${++nextId}`
          runs.set(id, { id, status: "DONE" })
          return json({ id })
        }
        if (url === "/api/smp/area-draft" && method === "POST") {
          const body = parseBody(init)
          asked.push({ path: url, body })
          const id = `smp:${++nextId}`
          if (stepOf(body) === "READ" && asked.length === 1) {
            runs.set(id, { id, status: "REFUSED", reason: "NO_AREA_DRAFT" })
          } else {
            runs.set(id, {
              id,
              status: "DONE",
              answer: {
                world: "nordtal",
                shapes: [
                  {
                    name: "Spawn",
                    bottom: 35,
                    top: 155,
                    corners: [
                      { x: 10, z: 60 },
                      { x: 140, z: 60 },
                      { x: 140, z: 120 },
                    ],
                  },
                ],
              },
            })
          }
          return json({ id })
        }
        if (url.startsWith("/api/commands/")) {
          const id = decodeURIComponent(url.slice("/api/commands/".length))
          return json(runs.get(id) ?? { id, status: "DONE" })
        }
        if (url === "/api/setting-groups/smp/spawn" && method === "PUT") {
          puts.push(parseBody(init))
          return json(configSaved(SPAWN_DOC))
        }
        throw new Error(`unexpected ${method} ${url}`)
      }),
    )

    draw(<SpawnEditor file="smp/spawn" document={SPAWN_DOC} target={null} />)

    const editButton = await screen.findByRole("button", { name: "Edit in the game" })
    await waitFor(() => expect(editButton.hasAttribute("disabled")).toBe(false))
    fireEvent.click(editButton)

    await waitFor(() => expect(asked.some((call) => call.path === "/api/smp/area-editor")).toBe(true))
    const openCall = asked.find((call) => call.path === "/api/smp/area-editor")
    expect(openCall?.body).toMatchObject({ world: "nordtal", heights: true })

    const saveButton = await screen.findByRole("button", { name: "Save" })
    fireEvent.click(saveButton)

    await waitFor(() => expect(puts).toHaveLength(1))
    expect(puts[0]).toMatchObject({
      revision: "r1",
      changes: {
        "protected-areas": [
          expect.objectContaining({ name: "Nether spawn", world: "nordtal_nether" }),
          expect.objectContaining({ name: "Spawn", world: "nordtal", "min-y": "35", "max-y": "155" }),
        ],
      },
    })
  })
})
