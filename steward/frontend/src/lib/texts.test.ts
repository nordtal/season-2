import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { afterEach, describe, expect, it } from "vitest"

import { dateTime, money, relative } from "@/lib/format"
import { choice, message, packagedTexts, replaceTexts, runs, t, type TextNode, type Typed } from "@/lib/texts"

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
    const since = "2026-10-03T18:40:00Z"
    expect(t("steward.service.held-since", { since, state: "exited" })).toBe(
      `Held down since ${dateTime(since)}. Docker reports the state "exited".`,
    )
    replaceTexts({ ...PACKAGED, vector: [[{ v: "at", k: "instant", s: "relative" }]] })
    expect(message({ key: "vector", args: { at: { kind: "instant", value: since } } })).toBe(relative(since))
  })

  it("shows money in its currency's smallest unit", () => {
    replaceTexts({ ...PACKAGED, vector: [[{ v: "price" }]] })
    expect(message({ key: "vector", args: { price: { kind: "money", value: { minor: 300, currency: "EUR" } } } })).toBe(
      money(300, "EUR"),
    )
    expect(money(300, "EUR")).toBe("€3.00")
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
