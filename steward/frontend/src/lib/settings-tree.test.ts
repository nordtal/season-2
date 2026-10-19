import { describe, expect, it } from "vitest"

import type { ConfigEntry, MessageEntry, MessageSurface, MessageText } from "@/lib/api"
import { view } from "@/pages/translations.fixtures"
import { humanFileName } from "@/components/steward/config-controls"
import {
  ancestorsOf,
  configTree,
  filterTree,
  humanise,
  leafCount,
  messageName,
  textsTree,
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
    explanation: "",
    noExplanationNeeded: false,
    filled: true,
    editable: true,
    secret: false,
    environmentOverridden: false,
    ...over,
  }
}

function message(over: Partial<MessageEntry> & { key: string }): MessageEntry {
  return {
    bundle: "smp",
    inBundle: true,
    texts: {},
    overrides: {},
    args: [],
    section: [],
    shown: [],
    limit: 0,
    ...over,
  }
}

/** What a view of one of smp's game documents says beyond its declaration. */
const GAME = { game: true, services: ["smp"] }

const PLACES: Record<string, MessageSurface> = {
  CHAT: "GAME",
  GUI: "GAME",
  DISCORD_MESSAGE: "DISCORD",
  STEWARD: "STEWARD",
}

function text(over: Partial<MessageEntry> & { key: string }): MessageText {
  return { entry: message({ shown: ["CHAT"], ...over }), path: "smp/smp", services: ["smp"], previews: {} }
}

function steward(key: string, section: string[]): MessageText {
  return text({ key, bundle: "steward", section: ["Steward", ...section], shown: ["STEWARD"] })
}

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
      config({ path: "agent", kind: "MAP", label: "Agent" }),
      config({ path: "agent.url", label: "Url" }),
      config({ path: "agent.token", label: "Token" }),
    ])

    expect(shape(tree)).toEqual(["agent.url", "agent.token"])
  })

  it("names a section the file never listed after its key", () => {
    const tree = configTree([config({ path: "farm-world.size", label: "Size" }), config({ path: "x", label: "X" })])

    expect(shape(tree)).toEqual(["[Farm world]", "  farm-world.size", "x"])
  })
})

describe("the texts as a tree", () => {
  it("lists each text under where its first place is, then under its topic, by the section names", () => {
    const tree = textsTree(
      [
        text({ key: "smp.grave.decay", section: ["Smp", "Graves"] }),
        text({ key: "dm.granted", bundle: "admin", section: ["Direct message"], shown: ["DISCORD_MESSAGE", "CHAT"] }),
        text({ key: "smp.grave.limit", section: ["Smp", "Graves"], shown: ["GUI"] }),
        text({ key: "page.title", bundle: "steward", section: ["Page"], shown: ["STEWARD"] }),
      ],
      PLACES,
    )
    expect(shape(tree)).toEqual([
      "[Discord > Direct message]",
      "  admin/dm.granted",
      "[In game > Smp > Graves]",
      "  smp/smp.grave.decay",
      "  smp/smp.grave.limit",
      "[Steward & Admin > Page]",
      "  steward/page.title",
    ])
  })

  it("lists Steward's own texts under the page they are on, the shell's first and those of several pages last", () => {
    const tree = textsTree(
      [
        steward("steward.form.changed", ["Forms"]),
        steward("steward.alerts.all-clear", ["Alerts page"]),
        steward("steward.shell.pages", ["Shell"]),
      ],
      PLACES,
    )
    expect(shape(tree)).toEqual([
      "[Every page > Shell]",
      "  steward/steward.shell.pages",
      "[Alerts > Alerts page]",
      "  steward/steward.alerts.all-clear",
      "[Several pages > Forms]",
      "  steward/steward.form.changed",
    ])
  })

  it("lists other bundles' texts shown in Steward after Steward's pages, apart from a page of the same name", () => {
    const tree = textsTree(
      [
        text({ key: "alerts.disk", bundle: "admin", section: ["Alerts"], shown: ["STEWARD"] }),
        steward("steward.alerts.all-clear", ["Alerts page"]),
        steward("steward.shell.pages", ["Shell"]),
      ],
      PLACES,
    )
    expect(shape(tree)).toEqual([
      "[Every page > Shell]",
      "  steward/steward.shell.pages",
      "[Alerts > Alerts page]",
      "  steward/steward.alerts.all-clear",
      "[Alerts]",
      "  admin/alerts.disk",
    ])
  })

  it("keeps the values apart as building blocks, wherever they are shown", () => {
    const tree = textsTree(
      [
        text({ key: "values.yes", bundle: "values", section: ["Values"] }),
        text({ key: "welcome", shown: ["DISCORD_MESSAGE"] }),
      ],
      PLACES,
    )
    expect(shape(tree)).toEqual(["[Discord]", "  smp/welcome", "[Building blocks > Values]", "  values/values.yes"])
  })

  it("lists two bundles' texts of one key apart, under the topic they share", () => {
    const tree = textsTree(
      [
        text({ key: "command.unknown", bundle: "proxy", section: ["Command"] }),
        text({ key: "command.unknown", bundle: "paper-common", section: ["Command"] }),
        text({ key: "welcome", shown: ["DISCORD_MESSAGE"] }),
      ],
      PLACES,
    )
    expect(shape(tree)).toEqual([
      "[Discord]",
      "  smp/welcome",
      "[In game > Command]",
      "  proxy/command.unknown",
      "  paper-common/command.unknown",
    ])
  })

  it("falls back to the key segment when the spec names no section", () => {
    const tree = textsTree(
      [text({ key: "dm.granted", section: [null] }), text({ key: "other", shown: ["STEWARD"] })],
      PLACES,
    )
    expect(shape(tree)).toEqual(["[In game > Dm]", "  smp/dm.granted", "[Steward & Admin]", "  smp/other"])
  })

  it("lists a Discord document as one leaf under its area, beside the texts of that topic", () => {
    const tree = textsTree(
      [
        text({ key: "contribution.thanks", bundle: "admin", section: ["Contribution"], shown: ["DISCORD_MESSAGE"] }),
        text({ key: "welcome", shown: ["CHAT"] }),
      ],
      PLACES,
      [view({ name: "contribution", area: "Contribution" }, { parts: [] })],
    )

    expect(shape(tree)).toEqual([
      "[Discord > Contribution]",
      "  document:discord/contribution",
      "  admin/contribution.thanks",
      "[In game]",
      "  smp/welcome",
    ])
    expect(ancestorsOf(tree, "document:discord/contribution")).toEqual(["DISCORD:@Contribution"])
  })

  it("lists a menu and a game text under the game", () => {
    const tree = textsTree(
      [text({ key: "welcome", shown: ["CHAT"] }), text({ key: "hello", shown: ["DISCORD_MESSAGE"] })],
      PLACES,
      [
        view({ namespace: "smp", name: "wheel", area: "Menus", shape: "MENU" }, { parts: [] }, GAME),
        view({ namespace: "smp", name: "tab-footer", area: "Tab list", shape: "TAB_LIST" }, { parts: [] }, GAME),
      ],
    )

    expect(shape(tree)).toEqual([
      "[Discord]",
      "  smp/hello",
      "[In game]",
      "  [Menus]",
      "    document:smp/wheel",
      "  [Tab list]",
      "    document:smp/tab-footer",
      "  smp/welcome",
    ])
  })

  it("lists a document the servers share first in its area, then each server's own in the order of the filter", () => {
    const footer = (service: string) =>
      view(
        { namespace: service, name: "tab-footer", area: "Tab list", shape: "TAB_LIST" },
        { parts: [] },
        {
          ...GAME,
          services: [service],
        },
      )
    const tree = textsTree([], PLACES, [
      view({ namespace: "smp", name: "wheel", area: "Menus", shape: "MENU" }, { parts: [] }, GAME),
      footer("smp"),
      footer("hunger-games"),
      view(
        { namespace: "paper-common", name: "tab-header", area: "Tab list", shape: "TAB_LIST" },
        { parts: [] },
        {
          ...GAME,
          services: ["smp", "hunger-games", "limbo"],
        },
      ),
      footer("limbo"),
    ])

    expect(shape(tree)).toEqual([
      "[Menus]",
      "  document:smp/wheel",
      "[Tab list]",
      "  document:paper-common/tab-header",
      "  document:hunger-games/tab-footer",
      "  document:limbo/tab-footer",
      "  document:smp/tab-footer",
    ])
  })

  it("lists only the documents of the service the page is filtered to", () => {
    const tree = textsTree([], PLACES, [view({ name: "contribution" }, { parts: [] })], "smp")

    expect(shape(tree)).toEqual([])
  })

  it("shows a message by its name, or by its last segment made readable", () => {
    expect(messageName(message({ key: "a.b.farm-reset", name: "Farm reset notice" }))).toBe("Farm reset notice")
    expect(messageName(message({ key: "a.b.farm-reset" }))).toBe("Farm reset")
  })
})

describe("walking a tree", () => {
  const tree = textsTree(
    [text({ key: "a.b.one" }), text({ key: "a.b.two" }), text({ key: "a.c.three" }), text({ key: "d" })],
    PLACES,
  )

  it("counts leaves", () => {
    expect(leafCount(tree)).toBe(4)
  })

  it("names the branches to open for a key, outermost first", () => {
    expect(ancestorsOf(tree, "smp/a.b.two")).toEqual(["GAME:@A", "GAME:a.b"])
    expect(ancestorsOf(tree, "smp/d")).toEqual([])
    expect(ancestorsOf(tree, "missing")).toBeNull()
  })

  it("keeps only the branches that still hold a match", () => {
    expect(shape(filterTree(tree, (leaf) => leaf.id.endsWith("three")))).toEqual(["[A]", "  [C]", "    smp/a.c.three"])
  })
})
