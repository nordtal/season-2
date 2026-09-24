import { describe, expect, it } from "vitest"

import type { ConfigEntry, MessageEntry } from "@/lib/api"
import { humanFileName } from "@/components/steward/config-controls"
import {
  ancestorsOf,
  configTree,
  filterTree,
  humanise,
  leafCount,
  messageName,
  messageTree,
  type TreeNode,
} from "@/lib/settings-tree"

function config(over: Partial<ConfigEntry> & { path: string }): ConfigEntry {
  const key = over.path.slice(over.path.lastIndexOf(".") + 1)
  return {
    key,
    label: key,
    kind: "SCALAR",
    type: "STRING",
    value: "",
    comments: [],
    editable: true,
    inSchema: true,
    secret: false,
    ...over,
  } as ConfigEntry
}

function message(over: Partial<MessageEntry> & { key: string }): MessageEntry {
  return { inBundle: true, args: [], section: [], ...over }
}

/** The tree as text, one line per node, so a whole shape fits in one expectation. */
function shape<L>(nodes: TreeNode<L>[], depth = 0): string[] {
  return nodes.flatMap((node) =>
    node.kind === "leaf"
      ? [`${"  ".repeat(depth)}${node.id}`]
      : [`${"  ".repeat(depth)}[${node.labels.join(" > ")}]`, ...shape(node.children, depth + 1)],
  )
}

describe("humanise", () => {
  it("turns a key segment into a sentence-case name", () => {
    expect(humanise("farm-world")).toBe("Farm world")
    expect(humanise("discord-bot")).toBe("Discord bot")
    expect(humanise("smp")).toBe("SMP")
  })

  it("splits camelCase and keeps a known acronym upper-case", () => {
    expect(humanise("logFailedRequests")).toBe("Log failed requests")
    expect(humanise("serverUuid")).toBe("Server UUID")
    expect(humanise("backgroundProfiler")).toBe("Background profiler")
    expect(humanise("HTTPServer")).toBe("HTTP server")
    expect(humanise("base-url")).toBe("Base URL")
    expect(humanise("identity")).toBe("Identity")
    expect(humanise("ENABLED")).toBe("Enabled")
    expect(humanise("discordSRV")).toBe("Discord SRV")
  })
})

describe("humanFileName", () => {
  it("reads a file name by the same word rule", () => {
    expect(humanFileName("bStats/config.yml")).toBe("bStats Config")
    expect(humanFileName("spark/config.json")).toBe("Spark Config")
    expect(humanFileName("discordSRV/config.yml")).toBe("Discord SRV Config")
  })
})

describe("a config file as a tree", () => {
  it("hangs every key under the section it is in, named by the section's own label", () => {
    const tree = configTree([
      config({ path: "grave", kind: "MAP", label: "Graves" }),
      config({ path: "grave.decay", label: "Decay" }),
      config({ path: "grave.limit", label: "Limit" }),
      config({ path: "language", label: "Language" }),
    ])

    expect(shape(tree)).toEqual(["[Graves]", "  grave.decay", "  grave.limit", "language"])
  })

  it("folds a chain of sections with one child each into one row", () => {
    const tree = configTree([
      config({ path: "a", kind: "MAP", label: "A" }),
      config({ path: "a.b", kind: "MAP", label: "B" }),
      config({ path: "a.b.c", label: "C" }),
      config({ path: "a.b.d", label: "D" }),
      config({ path: "top", label: "Top" }),
    ])

    expect(shape(tree)).toEqual(["[A > B]", "  a.b.c", "  a.b.d", "top"])
  })

  it("does not make a file click through its only section first", () => {
    const tree = configTree([
      config({ path: "worker", kind: "MAP", label: "Worker" }),
      config({ path: "worker.url", label: "Url" }),
      config({ path: "worker.token", label: "Token" }),
    ])

    expect(shape(tree)).toEqual(["worker.url", "worker.token"])
  })

  it("names a section the file never listed after its key", () => {
    const tree = configTree([config({ path: "farm-world.size", label: "Size" }), config({ path: "x", label: "X" })])

    expect(shape(tree)).toEqual(["[Farm world]", "  farm-world.size", "x"])
  })
})

describe("a message bundle as a tree", () => {
  const entries = [
    message({ key: "smp.grave.decay", section: ["Smp", "Graves"] }),
    message({ key: "smp.grave.limit", section: ["Smp", "Graves"] }),
    message({ key: "smp.welcome", section: ["Smp"] }),
  ]

  it("takes the section names from the spec, and drops the prefix every key shares", () => {
    expect(shape(messageTree(entries))).toEqual(["[Graves]", "  smp.grave.decay", "  smp.grave.limit", "smp.welcome"])
  })

  it("falls back to the key segment when the spec names no section", () => {
    const tree = messageTree([message({ key: "dm.granted", section: [null] }), message({ key: "other" })])
    expect(shape(tree)).toEqual(["[Dm]", "  dm.granted", "other"])
  })

  it("shows a message by its name, or by its last segment made readable", () => {
    expect(messageName(message({ key: "a.b.farm-reset", name: "Farm reset notice" }))).toBe("Farm reset notice")
    expect(messageName(message({ key: "a.b.farm-reset" }))).toBe("Farm reset")
  })
})

describe("walking a tree", () => {
  const tree = messageTree([
    message({ key: "a.b.one" }),
    message({ key: "a.b.two" }),
    message({ key: "a.c.three" }),
    message({ key: "d" }),
  ])

  it("counts leaves", () => {
    expect(leafCount(tree)).toBe(4)
  })

  it("names the branches to open for a key, outermost first", () => {
    expect(ancestorsOf(tree, "a.b.two")).toEqual(["a", "a.b"])
    expect(ancestorsOf(tree, "d")).toEqual([])
    expect(ancestorsOf(tree, "missing")).toBeNull()
  })

  it("keeps only the branches that still hold a match", () => {
    expect(shape(filterTree(tree, (leaf) => leaf.id.endsWith("three")))).toEqual(["[A]", "  [C]", "    a.c.three"])
  })
})
