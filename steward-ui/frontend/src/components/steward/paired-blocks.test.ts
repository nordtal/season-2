import { describe, expect, it } from "vitest"

import { pairedBlocks, pairedPaths } from "@/components/steward/paired-blocks"
import type { ConfigEntry } from "@/lib/api"

/**
 * The rule steward/130 needed and did not invent for one file: two sibling blocks with the same
 * keys are one list, so they are drawn as one row per key.
 *
 * Everything here is about the *refusals*. Pairing two blocks that only look alike is worse than
 * not pairing at all: the interface would then draw tier 7's hour beside tier 8's colour, which is
 * exactly the invisible mistake this exists to prevent - only now with the form's authority behind
 * it.
 */
function field(over: Partial<ConfigEntry> & { key: string; path: string }): ConfigEntry {
  return {
    label: over.key,
    comments: [],
    explanation: "",
    noExplanationNeeded: true,
    filled: true,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    line: 1,
    editable: true,
    secret: false,
    inSchema: true,
    ...over,
  }
}

function block(name: string, keys: string[], values: string[] = []): ConfigEntry[] {
  return [
    field({ key: name, path: name, kind: "MAP", editable: false }),
    ...keys.map((key, at) =>
      field({ key, path: `${name}.${key}`, value: values[at] ?? "" }),
    ),
  ]
}

/** The shape `prestige.yml` has: `hours` and `colours`, thirteen keys each, in the same order. */
const LADDER = [
  field({ key: "admin", path: "admin", value: "#ff5555" }),
  ...block("hours", ["tier-01", "tier-02", "tier-03"], ["0", "2", "5"]),
  ...block("colours", ["tier-01", "tier-02", "tier-03"], ["#5fbfae", "#5ea9d6", "#6f93e0"]),
]

describe("pairedBlocks", () => {
  it("pairs two sibling blocks that carry the same keys, one row per key", () => {
    const pairs = pairedBlocks(LADDER)

    expect(pairs).toHaveLength(1)
    expect(pairs[0].left.path).toBe("hours")
    expect(pairs[0].right.path).toBe("colours")
    expect(pairs[0].rows.map((row) => row.key)).toEqual(["tier-01", "tier-02", "tier-03"])
    // Left is the left block's key and right is the right block's - not the other way round, and
    // not the same one twice.
    expect(pairs[0].rows[1].left.path).toBe("hours.tier-02")
    expect(pairs[0].rows[1].right.path).toBe("colours.tier-02")
  })

  it("refuses two blocks whose keys differ, rather than dropping the odd one out", () => {
    const wrong = [
      ...block("hours", ["tier-01", "tier-02", "tier-03"]),
      ...block("colours", ["tier-01", "tier-02"]),
    ]

    expect(pairedBlocks(wrong)).toEqual([])
  })

  it("refuses two blocks whose keys are the same but in a different order", () => {
    // The whole failure this rule exists to prevent, stated as a test: same keys, same count,
    // and row two would pair tier-03's hour with tier-02's colour.
    const shuffled = [
      ...block("hours", ["tier-01", "tier-02", "tier-03"]),
      ...block("colours", ["tier-01", "tier-03", "tier-02"]),
    ]

    expect(pairedBlocks(shuffled)).toEqual([])
  })

  it("refuses blocks that are not adjacent in the file", () => {
    const parted = [
      ...block("hours", ["tier-01", "tier-02"]),
      field({ key: "admin", path: "admin" }),
      ...block("colours", ["tier-01", "tier-02"]),
    ]

    expect(pairedBlocks(parted)).toEqual([])
  })

  it("refuses blocks that are not siblings", () => {
    const nested = [
      ...block("hours", ["tier-01"]),
      field({ key: "inner", path: "hours.inner", kind: "MAP", editable: false }),
      field({ key: "tier-01", path: "hours.inner.tier-01" }),
    ]

    expect(pairedBlocks(nested)).toEqual([])
  })

  it("refuses a pair whose members are not scalars, because a row is not a place for a sub-tree", () => {
    const deep = [
      field({ key: "hours", path: "hours", kind: "MAP", editable: false }),
      field({ key: "tier-01", path: "hours.tier-01", kind: "MAP", editable: false }),
      field({ key: "colours", path: "colours", kind: "MAP", editable: false }),
      field({ key: "tier-01", path: "colours.tier-01", kind: "MAP", editable: false }),
    ]

    expect(pairedBlocks(deep)).toEqual([])
  })

  it("never lets one block be the right of one pair and the left of the next", () => {
    const three = [
      ...block("a", ["one"]),
      ...block("b", ["one"]),
      ...block("c", ["one"]),
    ]

    const pairs = pairedBlocks(three)
    expect(pairs).toHaveLength(1)
    expect([pairs[0].left.path, pairs[0].right.path]).toEqual(["a", "b"])
  })

  it("names every path a pair has taken over, so nothing is drawn twice", () => {
    expect([...pairedPaths(pairedBlocks(LADDER))].sort()).toEqual([
      "colours",
      "colours.tier-01",
      "colours.tier-02",
      "colours.tier-03",
      "hours",
      "hours.tier-01",
      "hours.tier-02",
      "hours.tier-03",
    ])
    // `admin` is not one of them: it sits beside the two blocks and stays an ordinary field.
    expect(pairedPaths(pairedBlocks(LADDER)).has("admin")).toBe(false)
  })
})
