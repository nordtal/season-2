import { cleanup, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { DocumentDraft, DocumentView } from "@/lib/api"
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

/** What the bot's renderer answers for the contribution, cut to what this page draws. */
function rendered(draft: DocumentDraft): Record<string, unknown> {
  const de = draft.language === "de"
  if (draft.declaration.shape === "MODAL") {
    return {
      title: "Link",
      components: [{ type: 18, label: "Code", component: { type: 4, placeholder: "six digits" } }],
    }
  }
  return {
    components: [
      {
        type: 17,
        accent_color: 0x4a63d8,
        components: [
          { type: 10, content: de ? "## access-de" : "## Access" },
          { type: 10, content: "### Prices\n**€1.00** for 30 days\nEnds <t:1767225600:D> for <@1>" },
        ],
      },
      {
        type: 1,
        components: [
          { type: 2, style: 1, custom_id: "buy", label: "Buy access" },
          { type: 2, style: 5, url: "https://nordtal.eu", label: "Site" },
        ],
      },
    ],
  }
}

function serve(render = rendered) {
  vi.stubGlobal("fetch", backend(texts, undefined, { documents: documents([contribution, modal]), render }))
}

describe("the list of documents", () => {
  it("draws a Discord document as the bot's renderer drew it: its box, its markdown and its buttons", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    const card = await screen.findByRole("article", { name: "Contribution" })
    await waitFor(() => expect(card.textContent).toMatch(/Access.*Prices.*€1\.00 for 30 days/))
    expect(card.textContent).toContain("Buy access")
    expect(card.textContent).toContain("Site")
    expect(card.textContent).not.toContain("**")
    expect(within(card).getByText("Access").closest("div")?.className).toContain("text-xl")
  })

  it("sends the draft with its declaration, whose examples fill it", async () => {
    const asked: DocumentDraft[] = []
    serve((draft) => {
      asked.push(draft)
      return rendered(draft)
    })
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    await waitFor(() => expect(asked).not.toEqual([]))
    expect(asked.find((draft) => draft.declaration.name === "contribution")).toMatchObject({
      declaration: { slots: [{ id: "tiers" }, { id: "buy" }] },
      document: contribution.packaged,
      language: "en",
    })
  })

  it("draws Discord's times in the reader's language and its members as a mention", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    const card = await screen.findByRole("article", { name: "Contribution" })
    await waitFor(() => expect(card.textContent).toContain("@member"))
    expect(card.textContent).toMatch(/Ends (December 31, 2025|January 1, 2026) for/)
  })

  it("draws a modal as its title and each field under its label, with the field's hint inside", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Link")
    const card = await screen.findByRole("article", { name: "Link code" })
    await waitFor(() => expect(card.textContent).toMatch(/Link.*Code.*six digits/))
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
    await waitFor(() => expect(screen.getByRole("region", { name: "Deutsch" }).textContent).toContain("access-de"))
    expect(screen.getByRole("region", { name: "English" }).textContent).toContain("Buy access")
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
