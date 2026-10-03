import { describe, expect, it } from "vitest"

import type { GameData } from "@/lib/api"
import { words } from "@/lib/query-fixtures"
import { choiceFor, gameChoices, gameKey, guildChoices, namespacesOf, registryOf } from "@/lib/references"

/** The catalogue read as choices: keys, names, icons, the advancement tree, and nothing refused. */

const GAME: GameData = {
  version: "26.2",
  datapacks: ["vanilla"],
  registries: {
    item: [
      { id: "minecraft:spruce_log", text: "Spruce Log" },
      { id: "minecraft:oak_log", text: "Oak Log" },
      { id: "nordtal:coin" },
      { id: "minecraft:zombie_spawn_egg", text: "Zombie Spawn Egg" },
    ],
    entity_type: [{ id: "minecraft:zombie", text: "Zombie" }],
    statistic: [
      { id: "minecraft:mine_block", text: "Times Mined", subject: "block" },
      { id: "minecraft:jump", text: "Jumps" },
    ],
    advancement: [
      { id: "minecraft:story/root", text: "Minecraft", frame: "task", icon: "minecraft:grass_block" },
      { id: "minecraft:story/mine_stone", text: "Stone Age", parent: "minecraft:story/root", frame: "task" },
      {
        id: "minecraft:story/enter_the_nether",
        text: "We Need to Go Deeper",
        parent: "minecraft:story/root",
        frame: "goal",
        description: "Build, light and enter a Nether Portal",
      },
      { id: "minecraft:story/hidden_helper", parent: "minecraft:story/root" },
    ],
  },
  tags: { item: [{ id: "minecraft:logs", values: ["minecraft:oak_log", "minecraft:spruce_log"] }] },
  icons: { url: "/icons.png", columns: 32, slots: { "minecraft:oak_log": 0, "minecraft:zombie_spawn_egg": 1 } },
}

describe("gameKey", () => {
  it("reads a bare or upper-case name as the minecraft key and keeps another namespace", () => {
    expect(gameKey("OAK_LOG")).toBe("minecraft:oak_log")
    expect(gameKey(" minecraft:oak_log ")).toBe("minecraft:oak_log")
    expect(gameKey("nordtal:coin")).toBe("nordtal:coin")
    expect(gameKey("")).toBe("")
  })
})

describe("gameChoices", () => {
  it("offers a registry by name, with an icon only where the sheet has one", () => {
    const items = gameChoices(GAME, "item")
    expect(items.choices.map((choice) => choice.name)).toEqual(["Coin", "Oak Log", "Spruce Log", "Zombie Spawn Egg"])
    expect(items.choices.find((choice) => choice.id === "minecraft:oak_log")?.icon).toBe("minecraft:oak_log")
    expect(items.choices.find((choice) => choice.id === "minecraft:spruce_log")?.icon).toBeUndefined()
    expect(items.tags).toEqual(GAME.tags.item)
  })

  it("draws a mob with its spawn egg", () => {
    expect(gameChoices(GAME, "entity_type").choices[0].icon).toBe("minecraft:zombie_spawn_egg")
  })

  it("offers advancements as their tree, only those with a display, with frame and description", () => {
    const tree = gameChoices(GAME, "advancement")
    expect(tree.tree).toBe(true)
    expect(tree.choices.map((choice) => [choice.name, choice.depth])).toEqual([
      ["Minecraft", 0],
      ["Stone Age", 1],
      ["We Need to Go Deeper", 1],
    ])
    expect(tree.choices[2]).toMatchObject({ frame: "goal", description: "Build, light and enter a Nether Portal" })
  })

  it("says why nothing is listed before any server exported", () => {
    expect(gameChoices(undefined, "item").unavailable).toBe("No server has published its game data yet.")
  })
})

describe("registryOf", () => {
  it("reads a subject's registry off the statistic beside it, and none for an untyped one", () => {
    const subject = { to: "SUBJECT", dependsOn: "statistic", optional: false } as const
    expect(registryOf(subject, GAME, "minecraft:mine_block")).toBe("block")
    expect(registryOf(subject, GAME, "MINE_BLOCK")).toBe("block")
    expect(registryOf(subject, GAME, "minecraft:jump")).toBeNull()
    expect(registryOf({ to: "ITEM", optional: false }, GAME)).toBe("item")
    expect(registryOf({ to: "DISCORD_ROLE", optional: false }, GAME)).toBeNull()
  })
})

describe("choiceFor", () => {
  it("finds a value written as a bare name, and nothing for an id nobody lists", () => {
    const items = gameChoices(GAME, "item").choices
    expect(choiceFor(items, "OAK_LOG")?.id).toBe("minecraft:oak_log")
    expect(choiceFor(items, "minecraft:gone")).toBeUndefined()
  })
})

describe("guildChoices", () => {
  it("leaves categories out and marks voice channels", () => {
    const channels = guildChoices(
      {
        available: true,
        entries: [
          { id: "1", name: "Talk", type: 4 },
          { id: "2", name: "general", type: 0 },
          { id: "3", name: "Lounge", type: 2 },
        ],
      },
      "channel",
    )
    expect(channels.choices).toEqual([
      { id: "2", name: "general", channel: "text" },
      { id: "3", name: "Lounge", channel: "voice" },
    ])
  })

  it("passes on why the guild cannot be listed", () => {
    expect(guildChoices({ available: false, reason: words("no bot token"), entries: [] }, "role").unavailable).toBe(
      "no bot token",
    )
  })
})

describe("namespacesOf", () => {
  it("puts minecraft first", () => {
    expect(namespacesOf(gameChoices(GAME, "item").choices)).toEqual(["minecraft", "nordtal"])
  })
})
