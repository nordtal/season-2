import { cleanup, fireEvent, screen, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { DocumentDraft } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import { backend, documents, draw, listing, Page, text, view } from "@/pages/translations.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

const texts = listing([text({ key: "welcome", name: "Welcome", texts: { en: ["Welcome"], de: ["welcome-de"] } })], {
  languages: ["en", "de"],
})

const run = view(
  { name: "admin-run", title: "Update run", area: "Admin channel", englishOnly: true },
  { parts: [{ type: "TEXT", lines: [{ text: { en: "The run failed" } }] }] },
)

/** The bot's drawing of the run, in the language the page asked for. */
function rendered(draft: DocumentDraft): Record<string, unknown> {
  return { components: [{ type: 10, content: `drawn in ${draft.language}` }] }
}

describe("a document in English only", { timeout: 15_000 }, () => {
  it("is marked so, never as not translated, and opens in English whatever the page's language", async () => {
    const asked: string[] = []
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([run]),
        render: (draft) => {
          asked.push(draft.language)
          return rendered(draft)
        },
      }),
    )
    draw(<Page />)

    fireEvent.mouseDown(await screen.findByRole("tab", { name: "DE" }))
    fireEvent.click(await screen.findByRole("button", { name: /^Discord/ }))
    const row = await screen.findByRole("button", { name: "Update run" })
    expect(within(row).getByText("English only")).toBeTruthy()
    expect(within(row).queryByText("Not translated")).toBeNull()

    fireEvent.click(row)
    const card = await screen.findByRole("article", { name: "Update run" })
    expect(within(card).getByText("English only")).toBeTruthy()
    expect(within(card).queryByText("Not translated")).toBeNull()
    // The open item offers no language to switch to.
    expect(within(card).queryByRole("tab", { name: "DE" })).toBeNull()
    expect(await within(card).findByText("drawn in en")).toBeTruthy()
    expect(asked).not.toContain("de")
  })
})
