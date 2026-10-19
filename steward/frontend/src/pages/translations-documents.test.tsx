import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { Document, DocumentDraft, DocumentView } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import { words } from "@/lib/query-fixtures"
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

/** A server's own tab list footer. */
function footer(service: string): DocumentView {
  return view(
    { namespace: service, name: "tab-footer", area: "Tab list", shape: "TAB_LIST" },
    { parts: [] },
    {
      game: true,
      services: [service],
    },
  )
}

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

  it("lists a game document under the game, named by each server that shows it", async () => {
    vi.stubGlobal("fetch", backend(texts, undefined, { documents: documents([footer("smp"), footer("hunger-games")]) }))
    draw(<Page />)

    await open(/^In game/)
    await open("Tab list")
    const cards = await screen.findAllByRole("article", { name: "Tab footer" })
    expect(cards.map((card) => within(card).getByRole("heading").parentElement?.textContent)).toEqual([
      "Tab footerHunger Games",
      "Tab footerSMP",
    ])
  })

  it("lists a text of a bundle as a card of its own", async () => {
    serve()
    draw(<Page />)

    await open(/^In game/)
    const card = await screen.findByRole("article", { name: "Welcome" })
    expect(within(card).getByRole("button", { name: "Welcome" }).textContent).toContain("Welcome to the server")
  })
})

/** Opens the contribution's editor in its card. */
async function openContribution(): Promise<HTMLElement> {
  await open(/^Discord/)
  await open("Contribution")
  within(await screen.findByRole("article", { name: "Contribution" }))
    .getByRole("button", { name: "Contribution" })
    .click()
  return screen.findByRole("region", { name: "Contribution" })
}

describe("an open document", () => {
  it("opens its editor in its card, with the component count and a lock on what the code requires", async () => {
    serve()
    draw(<Page />)

    const editor = await openContribution()
    const card = screen.getByRole("article", { name: "Contribution" })
    expect(card.hasAttribute("data-open")).toBe(true)
    expect(await within(editor).findByText("7 of 40 components")).toBeTruthy()
    expect(within(editor).getByDisplayValue("Access")).toBeTruthy()
    expect(within(editor).getAllByLabelText("Required by the code").length).toBeGreaterThan(0)
    expect(within(editor).getByRole<HTMLButtonElement>("button", { name: "Save" }).disabled).toBe(true)

    fireEvent.click(within(editor).getByRole("button", { name: "Close" }))
    await waitFor(() => expect(screen.queryByRole("region", { name: "Contribution" })).toBeNull())
  })

  it("saves the edited document at once and marks it changed in the list", async () => {
    const saved: [string, Document][] = []
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([contribution, modal]),
        render: rendered,
        saveDocument: (path, document) => {
          saved.push([path, document])
          return documents([{ ...contribution, written: document }, modal])
        },
      }),
    )
    draw(<Page />)

    const editor = await openContribution()
    fireEvent.change(within(editor).getByDisplayValue("Access"), { target: { value: "Support" } })
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0][0]).toBe("discord/contribution")
    expect(JSON.stringify(saved[0][1])).toContain('"en":"Support"')
    expect(await within(screen.getByRole("article", { name: "Contribution" })).findByText("Changed")).toBeTruthy()
  })

  it("sends the draft as it stands to the admin as a direct message", async () => {
    const asked: unknown[] = []
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([contribution, modal]),
        render: rendered,
        preview: (body) => {
          asked.push(body)
          return "bot:7"
        },
        commands: { "bot:7": { id: "bot:7", status: "DONE", result: words("Sent to you in Discord.") } },
      }),
    )
    draw(<Page />)

    const editor = await openContribution()
    fireEvent.change(within(editor).getByDisplayValue("Access"), { target: { value: "Support" } })
    fireEvent.click(within(editor).getByRole("button", { name: "Send it to me in Discord" }))

    expect(await within(editor).findByText("Sent to you in Discord.")).toBeTruthy()
    expect(asked).toHaveLength(1)
    expect(asked[0]).toMatchObject({ declaration: { name: "contribution" }, language: "en" })
    expect(JSON.stringify(asked[0])).toContain('"en":"Support"')
  })

  it("refuses a save the check refuses and names why", async () => {
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([contribution, modal]),
        render: rendered,
        problems: (draft) =>
          JSON.stringify(draft.document).includes("Support")
            ? [{ error: true, text: "it draws 41 components, Discord draws 40" }]
            : [],
      }),
    )
    draw(<Page />)

    const editor = await openContribution()
    fireEvent.change(within(editor).getByDisplayValue("Access"), { target: { value: "Support" } })

    expect(await within(editor).findByText("it draws 41 components, Discord draws 40")).toBeTruthy()
    expect(within(editor).getByRole<HTMLButtonElement>("button", { name: "Save" }).disabled).toBe(true)
  })

  it("shows the packaged and the saved version side by side when a release changed the packaged one", async () => {
    const written = { parts: [{ type: "TEXT" as const, lines: [{ text: { en: "Old access" } }] }] }
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([{ ...contribution, written, outdated: true }, modal]),
        render: rendered,
      }),
    )
    draw(<Page />)

    const editor = await openContribution()
    expect(within(editor).getByText("Changed")).toBeTruthy()
    expect(within(editor).getByText("Release changed")).toBeTruthy()
    expect(within(editor).getByRole("heading", { name: "Packaged" })).toBeTruthy()
    expect(within(editor).getByRole("heading", { name: "Saved" })).toBeTruthy()
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
