import { describe, expect, it } from "vitest"

import type { DocumentPart, SharedPartView } from "@/lib/api"
import { languagesOf } from "@/lib/documents"

const links: DocumentPart = {
  type: "ROW",
  items: [{ link: "https://nordtal.eu", label: { en: "Rules", de: "rules-de" } }],
}

describe("a document's languages", () => {
  it("count the texts of each shared part it names, as the check reads the resolved document", () => {
    const shared: SharedPartView[] = [
      { name: "rule-links", part: links, documents: [] },
      { name: "loop", part: { type: "CONTAINER", parts: [{ type: "ROW", shared: "loop" }] }, documents: [] },
    ]
    const document = {
      parts: [
        { type: "ROW" as const, shared: "rule-links" },
        { type: "ROW" as const, shared: "loop" },
      ],
    }
    expect(languagesOf(document)).toEqual(["en"])
    expect(languagesOf(document, shared)).toEqual(["en", "de"])
  })
})
