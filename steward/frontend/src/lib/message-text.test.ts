import { describe, expect, it } from "vitest"

import type { MessageEntry } from "@/lib/api"
import { languagesByBundle, languagesOf } from "@/lib/message-text"

const won: MessageEntry = {
  bundle: "smp",
  key: "duel.won",
  inBundle: true,
  texts: {},
  overrides: {},
  name: "Duel won",
  args: [
    { name: "winner.name", kind: "name", type: "player", global: false, action: false, exampleWords: {} },
    { name: "server.name", kind: "text", type: "service", global: true, action: false, exampleWords: {} },
  ],
  section: [],
  shown: [],
  limit: 0,
}

describe("languagesOf", () => {
  it("lists every language a jar ships or an admin wrote, English first and each once", () => {
    const shipped = { ...won, texts: { fr: ["Salut"], en: ["Hi"] } }
    const written = { ...won, overrides: { de: ["Hallo"], fr: ["Coucou"] } }
    expect(languagesOf([shipped, written])).toEqual(["en", "de", "fr"])
    expect(languagesOf([])).toEqual(["en"])
  })

  it("offers a language the network speaks before any jar ships it or an admin wrote it", () => {
    const shipped = { ...won, texts: { fr: ["Salut"], en: ["Hi"] } }
    expect(languagesOf([shipped], ["de", "nl", "en"])).toEqual(["en", "de", "fr", "nl"])
  })
})

describe("languagesByBundle", () => {
  it("keeps a bundle that ships English only to English and what an admin wrote", () => {
    const steward = { ...won, bundle: "steward", texts: { en: ["Page"] } }
    const written = { ...won, bundle: "steward", key: "other", texts: { en: ["Other"] }, overrides: { fr: ["Autre"] } }
    const smp = { ...won, texts: { en: ["Hi"], de: ["Hallo"] } }
    const languages = languagesByBundle([steward, written, smp], ["en", "de", "nl"])
    expect(languages.get("steward")).toEqual(["en", "fr"])
    expect(languages.get("smp")).toEqual(["en", "de", "nl"])
  })
})
