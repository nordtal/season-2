import { describe, expect, it } from "vitest"

import type { DocumentDeclaration, DocumentPart } from "@/lib/api"
import {
  changePart,
  holdsRequired,
  insertValue,
  movePart,
  newLink,
  newPart,
  readWhen,
  slotPart,
  unplacedSlots,
  withVersions,
  writeWhen,
} from "@/lib/document-edit"

const declaration: DocumentDeclaration = {
  namespace: "discord",
  name: "rules",
  title: "Rules",
  area: "Rules",
  shape: "MESSAGE",
  rows: 0,
  values: {},
  slots: [
    { id: "accept", type: "BUTTON", required: true, values: {}, at: [] },
    { id: "rules", type: "LIST", required: false, values: {}, at: [] },
  ],
}

const parts: DocumentPart[] = [
  { type: "TEXT", lines: [{ text: { en: "first" } }] },
  {
    type: "CONTAINER",
    parts: [{ type: "SEPARATOR" }, { type: "ROW", items: [{ slot: "accept", label: { en: "Accept" } }] }],
  },
]

describe("document edits", () => {
  it("changes, removes and moves a part inside a container", () => {
    const changed = changePart(parts, [1, 0], () => ({ type: "GALLERY", media: ["upload://1"] }))
    expect(changed[1].parts?.[0]).toEqual({ type: "GALLERY", media: ["upload://1"] })
    expect(changePart(parts, [1, 0], () => undefined)[1].parts).toHaveLength(1)
    expect(movePart(parts, [1, 1], -1)[1].parts?.map((part) => part.type)).toEqual(["ROW", "SEPARATOR"])
    expect(movePart(parts, [0], -1)).toEqual(parts)
  })

  it("keeps a required slot and offers the slots no part places", () => {
    expect(holdsRequired(declaration, ["accept"])).toBe(true)
    expect(holdsRequired(declaration, ["rules"])).toBe(false)
    expect(unplacedSlots(declaration, parts).map((slot) => slot.id)).toEqual(["rules"])
    expect(slotPart(declaration.slots[1], ["en"])).toEqual({
      type: "TEXT",
      lines: [{ list: "rules", text: { en: "" } }],
    })
  })

  it("writes a new part's texts in every language the document has", () => {
    expect(newPart("TEXT", ["en", "de"])).toEqual({ type: "TEXT", lines: [{ text: { en: "", de: "" } }] })
    expect(newPart("SECTION", ["en", "de"]).parts).toEqual([{ type: "TEXT", lines: [{ text: { en: "", de: "" } }] }])
    expect(newLink(["en", "de"]).label).toEqual({ en: "", de: "" })
    expect(slotPart(declaration.slots[0], ["en", "de"])).toEqual({
      type: "ROW",
      items: [{ slot: "accept", label: { en: "accept", de: "accept" } }],
    })
  })

  it("reads and writes each condition the way the bot does", () => {
    for (const when of [undefined, "player", "!player", "tier=gold"]) expect(writeWhen(readWhen(when))).toBe(when)
    expect(readWhen("tier=gold")).toEqual({ mode: "EQUALS", value: "tier", constant: "gold" })
  })

  it("inserts a value at the caret and keeps one version as a plain string", () => {
    expect(insertValue("Hi !", "player", 3)).toBe("Hi {player}!")
    expect(insertValue("Hi", "player", null)).toBe("Hi{player}")
    expect(withVersions({ en: "a" }, "de", ["b"])).toEqual({ en: "a", de: "b" })
    expect(withVersions({ en: "a" }, "en", ["a", "b"])).toEqual({ en: ["a", "b"] })
  })
})
