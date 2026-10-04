import { describe, expect, it } from "vitest"

import type { MessageEntry } from "@/lib/api"
import { languagesOf, unknownPlaceholders } from "@/lib/message-text"

const won: MessageEntry = {
  bundle: "smp",
  key: "duel.won",
  inBundle: true,
  texts: {},
  overrides: {},
  name: "Duel won",
  args: [
    { name: "winner.name", kind: "name", type: "player", global: false, action: false },
    { name: "server.name", kind: "text", type: "service", global: true, action: false },
  ],
  section: [],
}

describe("unknownPlaceholders", () => {
  it("knows a role's properties and the globals, and names a property the role does not have", () => {
    expect(unknownPlaceholders(won, "{winner.name} on {server.name}")).toEqual([])
    expect(unknownPlaceholders(won, "{winner.nope} {winner}")).toEqual(["{winner.nope}", "{winner}"])
  })
})

describe("languagesOf", () => {
  it("lists every language a jar ships or an admin wrote, English first and each once", () => {
    const shipped = { ...won, texts: { fr: ["Salut"], en: ["Hi"] } }
    const written = { ...won, overrides: { de: ["Hallo"], fr: ["Coucou"] } }
    expect(languagesOf([shipped, written])).toEqual(["en", "de", "fr"])
    expect(languagesOf([])).toEqual(["en"])
  })
})
