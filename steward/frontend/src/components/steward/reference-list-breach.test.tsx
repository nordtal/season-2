import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { GameData } from "@/lib/api"
import { queryResult } from "@/lib/query-fixtures"
import { BreachContext } from "@/components/steward/breach-context"
import { ListControl } from "@/components/steward/config-controls"
import { entry } from "@/components/steward/configuration.fixtures"

const GAME: GameData = {
  version: "26.2",
  datapacks: [],
  registries: { item: [{ id: "minecraft:stone", text: "Stone" }] },
  tags: {},
  worlds: {},
}

vi.mock("@/lib/queries", () => ({
  useGameData: () => queryResult(GAME),
  useGuildChannels: () => queryResult(undefined),
  usePeople: () => queryResult([]),
}))

afterEach(cleanup)

function draw(marks: Record<string, string>) {
  const prizes = entry({
    path: "prizes",
    key: "prizes",
    label: "Prizes",
    kind: "LIST",
    refers: { to: "ITEM", optional: false },
  })
  render(
    <BreachContext value={(id) => marks[id] ?? null}>
      <ListControl
        id="prizes"
        entry={prizes}
        items={["minecraft:stone", "minecraft:stone"]}
        disabled={false}
        onChange={() => {}}
      />
    </BreachContext>,
  )
}

describe("a list of references that breaks a rule", () => {
  it("shows the list's own breach below the picker", () => {
    draw({ prizes: "Prizes has more than 1 entries." })

    expect(screen.getAllByText("Stone")).toHaveLength(2)
    expect(screen.getByText("Prizes has more than 1 entries.")).toBeTruthy()
  })

  it("shows an entry's breach on the list, since the picker draws no row per entry", () => {
    draw({ "prizes.1": "Prizes holds minecraft:stone twice." })

    expect(screen.getByText("Prizes holds minecraft:stone twice.")).toBeTruthy()
  })
})
