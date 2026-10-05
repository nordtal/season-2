import { cleanup, fireEvent, render, screen, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigReference, GameData, GuildList } from "@/lib/api"
import { queryResult, words } from "@/lib/query-fixtures"
import { ReferencePicker } from "@/components/steward/reference-picker"

/** One picker per reference: search, tags, chips, the unknown id kept, and typing where nothing is listed. */

const GAME: GameData = {
  version: "26.2",
  datapacks: [],
  registries: {
    item: [
      { id: "minecraft:oak_log", text: "Oak Log" },
      { id: "minecraft:spruce_log", text: "Spruce Log" },
      { id: "minecraft:stone", text: "Stone" },
    ],
  },
  tags: { item: [{ id: "minecraft:logs", values: ["minecraft:oak_log", "minecraft:spruce_log"] }] },
}

let game: GameData | undefined = GAME

const CHANNELS: GuildList = { available: true, entries: [{ id: "11", name: "admin-log", type: 0 }] }

let channels: GuildList = CHANNELS

vi.mock("@/lib/queries", () => ({
  useGameData: () => queryResult(game),
  useGuildChannels: () => queryResult(channels),
  usePeople: () => queryResult([]),
}))

const ITEM: ConfigReference = { to: "ITEM", optional: false }

function draw(reference: ConfigReference, values: string[], multi: boolean) {
  const onChange = vi.fn<(values: string[]) => void>()
  render(
    <ReferencePicker
      id="field"
      label="Items"
      reference={reference}
      values={values}
      multi={multi}
      disabled={false}
      typed={<p>typed</p>}
      onChange={onChange}
    />,
  )
  return onChange
}

afterEach(() => {
  cleanup()
  game = GAME
  channels = CHANNELS
})

describe("ReferencePicker", () => {
  it("draws each value as a named chip and one nobody lists as a warning, kept", () => {
    draw(ITEM, ["OAK_LOG", "minecraft:gone"], true)

    expect(screen.getByText("Oak Log")).toBeTruthy()
    expect(screen.getByText("minecraft:gone")).toBeTruthy()
    expect(screen.getByRole("button", { name: "Remove minecraft:gone" })).toBeTruthy()
  })

  it("finds an item by its name or its id and adds it", async () => {
    const onChange = draw(ITEM, ["minecraft:oak_log"], true)

    fireEvent.click(screen.getByRole("button", { name: "Add to Items" }))
    const search = await screen.findByRole("searchbox", { name: "Search Items" })
    fireEvent.change(search, { target: { value: "minecraft:sto" } })
    const list = screen.getByRole("listbox", { name: "Items" })
    expect(
      within(list)
        .getAllByRole("option")
        .map((option) => option.textContent),
    ).toEqual(["Stoneminecraft:stone"])
    fireEvent.click(within(list).getByRole("option"))

    expect(onChange).toHaveBeenLastCalledWith(["minecraft:oak_log", "minecraft:stone"])
  })

  it("adds a tag's members, never the tag", async () => {
    const onChange = draw(ITEM, ["minecraft:oak_log"], true)

    fireEvent.click(screen.getByRole("button", { name: "Add to Items" }))
    fireEvent.click(await screen.findByRole("combobox", { name: "Tag" }))
    fireEvent.click(await screen.findByRole("option", { name: "#minecraft:logs" }))
    fireEvent.click(screen.getByRole("button", { name: "2" }))

    expect(onChange).toHaveBeenLastCalledWith(["minecraft:oak_log", "minecraft:spruce_log"])
  })

  it("picks one value, and clears it with none", async () => {
    const onChange = draw({ to: "DISCORD_CHANNEL", optional: false }, [], false)

    fireEvent.click(screen.getByRole("button", { name: "none" }))
    fireEvent.click(await screen.findByRole("option", { name: /^admin-log/ }))
    expect(onChange).toHaveBeenLastCalledWith(["11"])

    cleanup()
    const cleared = draw({ to: "DISCORD_CHANNEL", optional: false }, ["11"], false)
    fireEvent.click(screen.getByRole("button", { name: /admin-log/ }))
    fireEvent.click(await screen.findByRole("option", { name: "none" }))
    expect(cleared).toHaveBeenLastCalledWith([])
  })

  it("falls back to typing, with the reason, where nothing can be listed", () => {
    channels = { available: false, reason: words("no bot token in this test"), entries: [] }
    draw({ to: "DISCORD_CHANNEL", optional: false }, ["42"], false)
    expect(screen.getByText("typed")).toBeTruthy()
    expect(screen.getByText("no bot token in this test")).toBeTruthy()

    cleanup()
    game = undefined
    draw(ITEM, [], true)
    expect(screen.getByText("No server has published its game data yet.")).toBeTruthy()
  })
})
