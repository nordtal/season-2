import { describe, expect, it } from "vitest"

import type { PageCard } from "@/lib/documents"
import type { TreeNode } from "@/lib/settings-tree"
import { areasOf, readable } from "@/lib/translation-items"
import { view } from "@/pages/translations.fixtures"

function documentLeaf(name: string, title: string): TreeNode<PageCard> {
  const card: PageCard = { kind: "document", view: view({ name, title }, { parts: [] }) }
  return { kind: "leaf", id: name, ids: [name], value: card, wide: false }
}

function branch(id: string, labels: string[], children: TreeNode<PageCard>[]): TreeNode<PageCard> {
  return { kind: "branch", id, labels, children }
}

describe("areasOf", () => {
  it("names a section by its first branch, trails the rest, and lets a section named like its one item go loose", () => {
    const tree = [
      branch(
        "DISCORD",
        ["Discord"],
        [
          branch("DISCORD:@Link", ["Link"], [documentLeaf("link", "Link")]),
          branch(
            "DISCORD:@Tickets",
            ["Tickets", "Forms"],
            [documentLeaf("code", "Code form"), documentLeaf("ask", "Ask")],
          ),
          documentLeaf("rules", "Rules"),
        ],
      ),
      branch("OTHER", ["Other"], []),
    ]
    const areas = areasOf(tree)
    expect(areas.map((area) => [area.id, area.label, area.count])).toEqual([["DISCORD", "Discord", 4]])
    expect(
      areas[0].sections.map((section) => [
        section.label,
        section.items.map((item) => [item.name, item.trail.join(">")]),
      ]),
    ).toEqual([
      [
        "",
        [
          ["Link", ""],
          ["Rules", ""],
        ],
      ],
      [
        "Tickets",
        [
          ["Code form", "Forms"],
          ["Ask", "Forms"],
        ],
      ],
    ])
    expect(areas[0].sections[1].items[0].id).toBe("discord/code")
  })
})

describe("readable", () => {
  it("names each choice by its value and drops emphasis", () => {
    expect(readable("The phase is now **{phase, select, a {A {x}} other {B}}**.")).toBe("The phase is now {phase}.")
  })

  it("keeps a lone value and plain words", () => {
    expect(readable("{n, plural, one {# day} other {# days}}")).toBe("{n}")
    expect(readable("Hello\n {name}")).toBe("Hello {name}")
    expect(readable("<good>Saved {count}</good>")).toBe("Saved {count}")
  })
})
