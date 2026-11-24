import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigDocument, ConfigEntry, SpawnExceptionBlock } from "@/lib/api"
import { customEditor } from "@/components/steward/config-editors"
import {
  SpawnExceptionsEditor,
  blocksFromSections,
  iconSideLabels,
  mergeCaptured,
  readsSpawnExceptions,
} from "@/components/steward/game-groups/spawn-exceptions-editor"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function field(key: string, more: Partial<ConfigEntry> = {}): ConfigEntry {
  return {
    path: `entries.${key}`,
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
    value: "",
    rules: [],
    ...more,
  }
}

const DOC: ConfigDocument = {
  service: "smp",
  name: "spawn-exceptions",
  label: "Spawn exceptions",
  path: "smp/spawn-exceptions",
  live: true,
  readable: true,
  writable: true,
  revision: "r1",
  entries: [
    field("entries", {
      path: "entries",
      label: "Doors and trapdoors",
      kind: "SECTIONS",
      sections: [],
      template: [
        field("world"),
        field("x"),
        field("y"),
        field("z"),
        field("label"),
        field("kind"),
        field("facing"),
        field("default-state"),
        field("reset-mode"),
        field("reset-minutes"),
        field("reset-seconds"),
        field("icon-side"),
      ],
    }),
  ],
}

describe("SpawnExceptionsEditor", () => {
  it("recognises the spawn-exceptions schema and registers in customEditor", () => {
    expect(readsSpawnExceptions(DOC)).toBe(true)
    expect(customEditor("spawn-exceptions", DOC)).toBe(SpawnExceptionsEditor)
    expect(customEditor("spawn-exceptions", { ...DOC, entries: [] })).toBeUndefined()
  })

  it("labels icon sides by direction in the block's default state", () => {
    expect(iconSideLabels("DOOR", "NORTH", "CLOSED")).toEqual({ front: "North", back: "South" })
    expect(iconSideLabels("DOOR", "NORTH", "OPEN")).toEqual({ front: "East", back: "West" })
    expect(iconSideLabels("DOOR", "EAST", "CLOSED")).toEqual({ front: "East", back: "West" })
    expect(iconSideLabels("DOOR", "EAST", "OPEN")).toEqual({ front: "North", back: "South" })

    expect(iconSideLabels("TRAPDOOR", "NORTH", "CLOSED")).toEqual({ front: "Top", back: "Bottom" })
    expect(iconSideLabels("TRAPDOOR", "NORTH", "OPEN")).toEqual({ front: "North", back: "South" })
    expect(iconSideLabels("TRAPDOOR", "EAST", "OPEN")).toEqual({ front: "East", back: "West" })
  })

  it("merges captured blocks while preserving existing labels, timers and icon sides", () => {
    const existing = [
      {
        world: "nordtal",
        x: "10",
        y: "64",
        z: "20",
        label: "Town hall",
        kind: "DOOR",
        facing: "NORTH",
        "default-state": "CLOSED",
        "reset-mode": "AUTO_RESET",
        "reset-minutes": "1",
        "reset-seconds": "30",
        "icon-side": "FRONT",
      },
    ]
    const captured: SpawnExceptionBlock[] = [
      { world: "nordtal", x: 10, y: 64, z: 20, kind: "DOOR", facing: "NORTH", defaultOpen: false },
      { world: "nordtal", x: 12, y: 65, z: 20, kind: "TRAPDOOR", facing: "EAST", defaultOpen: true },
    ]
    const merged = mergeCaptured(existing, captured)
    expect(merged).toHaveLength(2)
    expect(merged[0]?.label).toBe("Town hall")
    expect(merged[0]?.["reset-mode"]).toBe("AUTO_RESET")
    expect(merged[0]?.["icon-side"]).toBe("FRONT")
    expect(merged[1]?.["default-state"]).toBe("OPEN")
    expect(merged[1]?.["reset-mode"]).toBe("STAY")
    expect(merged[1]?.["icon-side"]).toBe("BOTH")

    expect(blocksFromSections(merged)).toEqual(captured)
  })

  it("renders the capture button and empty state", () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response(JSON.stringify({ taker: "READY" }), { status: 200 }))),
    )
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <SpawnExceptionsEditor file="smp/spawn-exceptions" document={DOC} target={null} />
      </QueryClientProvider>,
    )
    expect(screen.getByRole("button", { name: "Select in the game" })).toBeTruthy()
    expect(screen.getByText("No door or trapdoor exceptions configured.")).toBeTruthy()
  })
})
