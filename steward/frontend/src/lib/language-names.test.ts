import { describe, expect, it } from "vitest"

import { languageName } from "@/lib/language-names"

describe("languageName", () => {
  it("names a language in that language", () => {
    expect(languageName("en")).toBe("English")
    expect(languageName("de")).toBe("Deutsch")
    expect(languageName("fr")).toBe("Français")
  })

  it("names a language it has no name for by its language subtag, as Locales.ownName does", () => {
    expect(languageName("xx")).toBe("xx")
    expect(languageName("XX")).toBe("xx")
  })

  it("shows a tag it cannot read as itself", () => {
    expect(languageName("e")).toBe("e")
    expect(languageName("")).toBe("")
  })
})
