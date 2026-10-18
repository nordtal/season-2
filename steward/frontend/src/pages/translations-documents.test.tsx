import { cleanup, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { DocumentView } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import { setPendingTextJump } from "@/lib/settings-search"
import { backend, documents, draw, listing, open, Page, text, view } from "@/pages/translations.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

const texts = listing(
  [
    text({ key: "welcome", name: "Welcome", texts: { en: ["Welcome to the server"] } }),
    text({
      key: "run.failed",
      bundle: "admin",
      name: "Run failed",
      shown: ["DISCORD_MESSAGE"],
      texts: { en: ["The run failed"] },
    }),
  ],
  { languages: ["en", "de"] },
)

const contribution: DocumentView = view(
  {
    name: "contribution",
    values: { donated: { kind: "flag", example: "false" }, reason: { kind: "message", example: "admin/run.failed" } },
    slots: [
      { id: "tiers", type: "LIST", required: true, values: { price: { kind: "money", example: "100" } } },
      { id: "buy", type: "BUTTON", required: true, values: {} },
    ],
  },
  {
    parts: [
      {
        type: "CONTAINER",
        accent: "#4a63d8",
        parts: [
          { type: "TEXT", lines: [{ text: { en: "Access", de: "access-de" }, style: "TITLE" }] },
          {
            type: "TEXT",
            lines: [
              { text: { en: "Prices", de: "prices-de" }, style: "HEADING" },
              { text: { en: "{price} for {days} days", de: "{price} days-de {days}" }, list: "tiers" },
              { text: { en: "Thank you", de: "thanks-de" }, when: "donated" },
              { text: { en: "Why: {reason}", de: "why-de {reason}" } },
            ],
          },
        ],
      },
      {
        type: "ROW",
        items: [
          { slot: "buy", label: { en: "Buy access", de: "buy-access-de" } },
          { link: "https://nordtal.eu", label: { en: "Site", de: "site-de" } },
        ],
      },
    ],
  },
)

const modal: DocumentView = view(
  {
    name: "link-code",
    area: "Link",
    shape: "MODAL",
    slots: [{ id: "code", type: "INPUT", required: true, values: {} }],
  },
  {
    parts: [
      { type: "TEXT", lines: [{ text: { en: "Link" }, style: "TITLE" }] },
      { type: "ROW", items: [{ slot: "code", label: { en: "Code" }, placeholder: { en: "six digits" } }] },
    ],
  },
)

const answer = documents([contribution, modal], {
  tiers: [
    { days: 30, priceCents: 300 },
    { days: 60, priceCents: 500 },
  ],
})

function serve() {
  vi.stubGlobal("fetch", backend(texts, undefined, { documents: answer }))
}

describe("the list of documents", () => {
  it("draws a Discord document as its finished message, its price list filled from the live tiers", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    const card = await screen.findByRole("article", { name: "Contribution" })
    const shown = card.textContent ?? ""
    expect(shown).toMatch(/Access.*Prices.*3[.,]00 for 30 days.*5[.,]00 for 60 days/)
    expect(shown).toContain("Buy access")
    expect(shown).toContain("Site")
  })

  it("shows a declared sum of money, which is in cents, in euros", async () => {
    vi.stubGlobal("fetch", backend(texts, undefined, { documents: documents([contribution]) }))
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    const card = await screen.findByRole("article", { name: "Contribution" })
    expect(card.textContent).toMatch(/€1[.,]00 for/)
  })

  it("leaves out a line whose condition the examples do not meet", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    const card = await screen.findByRole("article", { name: "Contribution" })
    expect(card.textContent).not.toContain("Thank you")
  })

  it("draws a value that names a text with that text", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    const card = await screen.findByRole("article", { name: "Contribution" })
    expect(card.textContent).toContain("Why: The run failed")
  })

  it("draws a modal as its title and each field under its label, with the field's hint inside", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Link")
    const card = await screen.findByRole("article", { name: "Link code" })
    expect(card.textContent).toMatch(/Link.*Code.*six digits/)
  })

  it("lists a text of a bundle as a card of its own", async () => {
    serve()
    draw(<Page />)

    await open(/^In game/)
    const card = await screen.findByRole("article", { name: "Welcome" })
    expect(within(card).getByRole("button", { name: "Welcome" }).textContent).toContain("Welcome to the server")
  })
})

describe("an open document", () => {
  it("shows its preview in each language it is written in, and goes back to the list", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    within(await screen.findByRole("article", { name: "Contribution" }))
      .getByRole("button", { name: "Contribution" })
      .click()

    await screen.findByRole("heading", { level: 1, name: "Contribution" })
    expect(screen.getByRole("region", { name: "English" }).textContent).toContain("Buy access")
    expect(screen.getByRole("region", { name: "Deutsch" }).textContent).toMatch(/access-de.*3[.,]00 days-de 30/)
    expect(screen.queryByRole("textbox")).toBeNull()

    await open("Translations")
    expect(await screen.findByRole("button", { name: /^Discord/ })).toBeTruthy()
  })
})

describe("a jump from the command palette", () => {
  it("opens the text in the language of the hit", async () => {
    serve()
    draw(<Page />)
    await screen.findByRole("button", { name: /^Discord/ })

    setPendingTextJump({ id: "smp/welcome", language: "en" })

    await screen.findByRole("heading", { level: 1, name: "Welcome" })
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "Welcome" }).textContent).toBe("Welcome to the server"),
    )
  })
})
