import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { afterEach, describe, expect, it } from "vitest"

import { dateTime, money, relative } from "@/lib/format"
import {
  choice,
  message,
  packagedTexts,
  replaceTexts,
  runs,
  since,
  span,
  t,
  type TextNode,
  type Typed,
} from "@/lib/texts"

/** The vectors Java's own test reads too, so the browser and every other target agree on each choice made. */
type Vector = { text: string; nodes: TextNode[]; args: Record<string, Typed>; plain: string }

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../..")
const vectors: Vector[] = JSON.parse(
  readFileSync(path.join(repository, "messages/src/test/resources/web-target.json"), "utf8"),
)

const PACKAGED = packagedTexts()

afterEach(() => replaceTexts(PACKAGED))

describe("the web target", () => {
  it("reads the shared vectors, and there are enough of them to mean something", () => {
    expect(vectors.length).toBeGreaterThan(15)
  })

  it.each(vectors.map((vector) => [vector.text, vector] as const))(
    "shows %s as Java's plain target does",
    (_, vector) => {
      replaceTexts({ ...PACKAGED, vector: [vector.nodes] })
      expect(message({ key: "vector", args: vector.args })).toBe(vector.plain)
    },
  )

  it("keeps a value a text node, never markup", () => {
    replaceTexts({ ...PACKAGED, vector: [[{ v: "who" }, " joined"]] })
    expect(runs("vector", { who: "<img src=x>" })).toEqual([
      { text: "<img src=x>", kind: "text", name: "who" },
      { text: " joined" },
    ])
  })

  it("shows a moment in the browser's zone and the page's own locale", () => {
    const held = "2026-10-03T18:40:00Z"
    expect(t("steward.service.held-since", { since: held, state: "exited" })).toBe(
      `Held down since ${dateTime(held)}. Docker reports the state "exited".`,
    )
    replaceTexts({ ...PACKAGED, vector: [[{ v: "at", k: "instant", s: "relative" }]] })
    expect(message({ key: "vector", args: { at: { kind: "instant", value: held } } })).toBe(relative(held))
  })

  it("shows money in its currency's smallest unit", () => {
    replaceTexts({ ...PACKAGED, vector: [[{ v: "price" }]] })
    expect(message({ key: "vector", args: { price: { kind: "money", value: { minor: 300, currency: "EUR" } } } })).toBe(
      money(300, "EUR"),
    )
    expect(money(300, "EUR")).toBe("€3.00")
  })

  it("shows a message in a message for the same reader", () => {
    replaceTexts({ ...PACKAGED, vector: [["Next: ", { v: "what" }, "."]], inner: [[{ v: "n" }, " days"]] })
    const inner = { kind: "message", value: { key: "inner", args: { n: { kind: "number", value: 2 } } } } as const
    expect(message({ key: "vector", args: { what: inner } })).toBe("Next: 2 days.")
  })

  it("chooses on an enum constant as Java names it", () => {
    expect(choice("REMOVE_PLUGIN")).toBe("remove-plugin")
    expect(t("run.kind", { kind: choice("REMOVE_PLUGIN") })).toBe("Remove plugin")
    expect(t("run.kind", { kind: choice("SOMETHING_NEW") })).toBe("something-new")
  })

  it("shows an admin's override once Steward serves it, and the key where there is no text at all", () => {
    replaceTexts({ ...PACKAGED, "steward.service.not-read": [["Nobody asked yet."]] })
    expect(t("steward.service.not-read")).toBe("Nobody asked yet.")
    expect(message({ key: "no.such.key" })).toBe("no.such.key")
  })
})

/** A span outside a message is the duration kind too, so a table cell and a sentence read alike. */
describe("span", () => {
  const NOTHING = "\u2013"
  const NOT_A_NUMBER = [null, undefined, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]

  it("shows an uptime or a run in its two largest units, dropping the fraction", () => {
    expect(span(0)).toBe("0s")
    expect(span(59.9)).toBe("59s")
    expect(span(3_600)).toBe("1h")
    expect(span(3 * 86_400 + 4 * 3_600 + 11 * 60 + 6)).toBe("3d 4h")
    expect(span(2 * 3_600 + 3 * 60 + 9)).toBe("2h 3m")
  })

  it("shows play time in every unit down to minutes, as the dialog takes it", () => {
    expect(span(86_400 + 6 * 3_600 + 30 * 60, "minutes")).toBe("1d 6h 30m")
    expect(span(2 * 86_400, "minutes")).toBe("2d")
    expect(span(86_400 + 30 * 60, "minutes")).toBe("1d 30m")
    expect(span(3_659, "minutes")).toBe("1h")
    expect(span(59, "minutes")).toBe("0m")
  })

  it("shows a dash for what is not a span", () => {
    expect(span(-1)).toBe(NOTHING)
    for (const value of NOT_A_NUMBER) expect(span(value, "minutes")).toBe(NOTHING)
  })

  it("says how long ago an instant was, and nothing for one ahead or none", () => {
    const now = Date.UTC(2026, 8, 12, 12, 0, 0)
    expect(since(new Date(now - (3 * 3_600_000 + 5 * 60_000)), now)).toBe("3h 5m")
    expect(since(new Date(now + 2_000), now)).toBe(NOTHING)
    expect(since(null, now)).toBe(NOTHING)
    expect(since("null", now)).toBe(NOTHING)
  })

  it("follows an override of the unit's word", () => {
    replaceTexts({ ...PACKAGED, "values.duration.short.minutes": [[{ v: "n" }, " min"]] })
    expect(span(30 * 60, "minutes")).toBe("30 min")
  })
})
