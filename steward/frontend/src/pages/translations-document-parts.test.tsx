import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { Document } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import { backend, documents, draw, listing, openItem, Page, view } from "@/pages/translations.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  resetDrafts()
})

const milestone = view(
  { name: "milestone", title: "Milestone" },
  { parts: [{ type: "TEXT", lines: [{ text: { en: "Done", de: "done-de" } }] }] },
)

describe("a part added to a document", { timeout: 15_000 }, () => {
  it("starts open, with an empty text in every language the document has", async () => {
    const saved: Document[] = []
    vi.stubGlobal(
      "fetch",
      backend(listing([], { languages: ["en", "de"] }), undefined, {
        documents: documents([milestone]),
        render: () => ({ components: [] }),
        saveDocument: (_, document) => {
          saved.push(document)
          return documents([{ ...milestone, written: document }])
        },
      }),
    )
    draw(<Page />)

    const editor = await openItem("Milestone", /^Discord/).then(() =>
      screen.findByRole("region", { name: "Milestone" }),
    )
    fireEvent.pointerDown(within(editor).getByRole("button", { name: "Add a part" }), { button: 0 })
    fireEvent.click(await screen.findByRole("menuitem", { name: "Text block" }))

    const parts = within(editor).getAllByRole("button", { name: /^Text block/ })
    expect(parts.map((part) => part.getAttribute("aria-expanded"))).toEqual(["true", "true"])
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }))
    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0].parts[1]).toEqual({ type: "TEXT", lines: [{ text: { en: "", de: "" } }] })
  })

  it("opens the parts an added part holds as well", async () => {
    vi.stubGlobal(
      "fetch",
      backend(listing([]), undefined, { documents: documents([milestone]), render: () => ({ components: [] }) }),
    )
    draw(<Page />)

    const editor = await openItem("Milestone", /^Discord/).then(() =>
      screen.findByRole("region", { name: "Milestone" }),
    )
    fireEvent.pointerDown(within(editor).getByRole("button", { name: "Add a part" }), { button: 0 })
    fireEvent.click(await screen.findByRole("menuitem", { name: "Embed" }))

    expect(within(editor).getByRole("button", { name: "Embed" }).getAttribute("aria-expanded")).toBe("true")
    const texts = within(editor).getAllByRole("button", { name: /^Text block/ })
    expect(texts.map((part) => part.getAttribute("aria-expanded"))).toEqual(["true", "true"])
  })
})
