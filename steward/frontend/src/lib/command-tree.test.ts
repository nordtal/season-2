import { describe, expect, it } from "vitest"

import type { CommandNode } from "@/lib/api"
import { complete, suggest } from "@/lib/command-tree"

/** A small server: give with two arguments, execute that runs any command again, and a namespaced copy of stop. */
const TREE: CommandNode[] = [
  { name: "", children: [1, 2, 3, 4, 5] },
  { name: "execute", children: [6, 7] },
  { name: "give", children: [8] },
  { name: "list", executes: true, children: [9] },
  { name: "stop", executes: true },
  { name: "minecraft:stop", executes: true },
  { name: "as", children: [10] },
  { name: "run", redirect: 0 },
  { name: "targets", argument: true, children: [11] },
  { name: "uuids", executes: true },
  { name: "targets", argument: true, redirect: 1 },
  { name: "item", argument: true, executes: true },
]

describe("suggest", () => {
  it("offers the words that begin with what is typed, the plain before the namespaced", () => {
    expect(suggest(TREE, "s").words).toEqual([{ name: "stop", more: false }])
    expect(suggest(TREE, "").words.map((word) => word.name)).toEqual([
      "execute",
      "give",
      "list",
      "stop",
      "minecraft:stop",
    ])
    expect(suggest(TREE, "MI").words.map((word) => word.name)).toEqual(["minecraft:stop"])
  })

  it("walks the words before, and names the argument expected instead of completing it", () => {
    expect(suggest(TREE, "list ").words).toEqual([{ name: "uuids", more: false }])
    expect(suggest(TREE, "give ")).toEqual({ from: 5, words: [], arguments: ["targets"] })
    expect(suggest(TREE, "give Steve ")).toEqual({ from: 11, words: [], arguments: ["item"] })
    expect(suggest(TREE, "give Steve diamond ")).toEqual({ from: 19, words: [], arguments: [] })
  })

  it("carries on where a redirect leads, as Brigadier does", () => {
    expect(suggest(TREE, "execute run gi").words).toEqual([{ name: "give", more: true }])
    expect(suggest(TREE, "execute as @a ").words.map((word) => word.name)).toEqual(["as", "run"])
  })

  it("offers nothing after a word the server does not know, and nothing without a tree", () => {
    expect(suggest(TREE, "lsit u")).toEqual({ from: 5, words: [], arguments: [] })
    expect(suggest([], "li")).toEqual({ from: 0, words: [], arguments: [] })
  })
})

describe("complete", () => {
  it("replaces the word being typed, and adds a space only where the command goes on", () => {
    expect(complete("execute ru", 8, { name: "run", more: true })).toBe("execute run ")
    expect(complete("st", 0, { name: "stop", more: false })).toBe("stop")
  })
})
