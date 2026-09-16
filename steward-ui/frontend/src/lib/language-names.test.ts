import { describe, expect, it } from "vitest"

import { languageName } from "@/lib/language-names"

describe("languageName", () => {
  it("names the two tags this project actually ships bundles for", () => {
    expect(languageName("en")).toBe("English")
    expect(languageName("de")).toBe("Deutsch")
  })

  it("is case- and whitespace-insensitive on the tag, the way the file itself is not", () => {
    expect(languageName("DE")).toBe("Deutsch")
    expect(languageName(" en ")).toBe("English")
  })

  it("falls back to the tag itself rather than to nothing, for a tag with no known name", () => {
    expect(languageName("fr")).toBe("fr")
    expect(languageName("")).toBe("")
  })
})
