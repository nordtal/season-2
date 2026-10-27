import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { Document, DocumentDraft, DocumentPart, DocumentView } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import { setPendingTextJump } from "@/lib/settings-search"
import { backend, documents, draw, listing, open, openItem, Page, text, view } from "@/pages/translations.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
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
    title: "Contribution",
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
    title: "Code form",
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
    { namespace: service, name: "tab-footer", title: "Tab footer", area: "Tab list", shape: "TAB_LIST" },
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

    const card = await openItem("Contribution", /^Discord/)
    await waitFor(() => expect(card.textContent).toMatch(/Access.*Prices.*€1\.00 for 30 days/))
    expect(card.textContent).toContain("Buy access")
    expect(card.textContent).toContain("Site")
    expect(card.textContent).not.toContain("**")
    expect(within(card).getAllByText("Access")[0].closest("div")?.className).toContain("text-xl")
  })

  it("sends the draft with its declaration, whose examples fill it", async () => {
    const asked: DocumentDraft[] = []
    serve((draft) => {
      asked.push(draft)
      return rendered(draft)
    })
    draw(<Page />)

    await openItem("Contribution", /^Discord/)
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

    const card = await openItem("Contribution", /^Discord/)
    await waitFor(() => expect(card.textContent).toContain("@member"))
    expect(card.textContent).toMatch(/Ends (December 31, 2025|January 1, 2026) for/)
  })

  it("draws a modal as its title and each field under its label, with the field's hint inside", async () => {
    serve()
    draw(<Page />)

    const card = await openItem("Code form", /^Discord/)
    await waitFor(() => expect(card.textContent).toMatch(/Link.*Code.*six digits/))
  })

  it("lists a game document under the game, named by each server that shows it", async () => {
    vi.stubGlobal("fetch", backend(texts, undefined, { documents: documents([footer("smp"), footer("hunger-games")]) }))
    draw(<Page />)

    await open(/^In game/)
    const rows = within(await screen.findByRole("region", { name: "Tab list" })).getAllByRole("button")
    expect(rows.map((row) => row.textContent)).toEqual(["Tab footerHunger Games", "Tab footerSMP"])
  })

  it("lists a text of a bundle as an item of its own, with its words", async () => {
    serve()
    draw(<Page />)

    await open(/^In game/)
    expect((await screen.findByRole("button", { name: "Welcome" })).textContent).toContain("Welcome to the server")
  })
})

/** Opens the contribution and hands back its editor. */
async function openContribution(): Promise<HTMLElement> {
  await openItem("Contribution", /^Discord/)
  return screen.findByRole("region", { name: "Contribution" })
}

/** Whether `second` stands after `first` in the page. */
const after = (first: Node, second: Node) =>
  Boolean(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING)

/** Opens the folded text block that holds the title and returns the title's field. */
function unfold(editor: HTMLElement): HTMLElement {
  fireEvent.click(within(editor).getByRole("button", { name: /^Text block\s*Access$/ }))
  return within(editor).getByDisplayValue("Access")
}

// Fifteen seconds, because an open editor draws every part with its pickers, which takes close to five on CI.
describe("an open document", { timeout: 15_000 }, () => {
  it("opens its editor beside the list, with the component count and a lock on what the code requires", async () => {
    serve()
    draw(<Page />)

    const editor = await openContribution()
    const card = screen.getByRole("article", { name: "Contribution" })
    expect(await within(card).findByText("7 of 40 components")).toBeTruthy()
    // Each part folds to a line naming its kind and first words; only the first one starts open.
    expect(within(editor).getByRole("button", { name: "Embed" }).getAttribute("aria-expanded")).toBe("true")
    expect(
      within(editor)
        .getByRole("button", { name: /^Buttons\s*Buy access$/ })
        .getAttribute("aria-expanded"),
    ).toBe("false")
    expect(unfold(editor)).toBeTruthy()
    expect(within(editor).getAllByLabelText("Required by the code").length).toBeGreaterThan(0)
    expect(within(editor).getByRole<HTMLButtonElement>("button", { name: "Save" }).disabled).toBe(true)

    fireEvent.click(within(card).getByRole("button", { name: "Back" }))
    await waitFor(() => expect(screen.queryByRole("region", { name: "Contribution" })).toBeNull())
  })

  it("edits and previews one language at a time, picked with the page's language toggle", async () => {
    serve()
    draw(<Page />)

    const editor = await openContribution()
    const card = screen.getByRole("article", { name: "Contribution" })
    // What the card draws outside the editor: the preview.
    const previewed = (shown: string) =>
      within(card)
        .queryAllByText(shown)
        .filter((each) => !editor.contains(each))
    unfold(editor)
    expect(within(editor).queryByRole("tablist", { name: "Language" })).toBeNull()

    fireEvent.mouseDown(screen.getByRole("tab", { name: "DE" }))
    expect(await within(editor).findByDisplayValue("access-de")).toBeTruthy()
    expect(within(editor).queryByDisplayValue("Access")).toBeNull()
    await waitFor(() => expect(previewed("access-de")).toHaveLength(1))
    expect(previewed("Access")).toHaveLength(0)

    fireEvent.mouseDown(screen.getByRole("tab", { name: "EN" }))
    await waitFor(() => expect(previewed("Access")).toHaveLength(1))
    expect(previewed("access-de")).toHaveLength(0)
  })

  it("draws its name above, then its preview, then its editor", async () => {
    serve()
    draw(<Page />)

    const editor = await openContribution()
    const card = screen.getByRole("article", { name: "Contribution" })
    const name = within(card).getByRole("heading", { name: "Contribution" })
    const preview = within(card)
      .getAllByText("Access")
      .find((each) => !editor.contains(each))!
    expect(editor.contains(name)).toBe(false)
    expect(name.compareDocumentPosition(preview) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(preview.compareDocumentPosition(editor) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("lists every document in the page's language, and marks one that lacks it", async () => {
    const english = view({ name: "donation", title: "Donation" }, { parts: [contribution.packaged.parts[0]] })
    const donation = { ...english, packaged: { parts: [{ type: "TEXT" as const, lines: [{ text: { en: "Give" } }] }] } }
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, { documents: documents([contribution, donation]), render: rendered }),
    )
    draw(<Page />)

    await openItem("Contribution", /^Discord/)
    fireEvent.mouseDown(screen.getByRole("tab", { name: "DE" }))
    const row = await screen.findByRole("button", { name: "Contribution" })
    await waitFor(() => expect(row.textContent).toContain("access-de"))
    const other = screen.getByRole("button", { name: "Donation" })
    expect(other.textContent).toContain("Give")
    expect(within(other).getByText("Not translated")).toBeTruthy()
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
    fireEvent.change(unfold(editor), { target: { value: "Support" } })
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0][0]).toBe("discord/contribution")
    expect(JSON.stringify(saved[0][1])).toContain('"en":"Support"')
    expect(await within(screen.getByRole("article", { name: "Contribution" })).findByText("Changed")).toBeTruthy()
    expect(within(screen.getByRole("button", { name: "Contribution" })).getByText("Changed")).toBeTruthy()
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
        commands: { "bot:7": { id: "bot:7", status: "DONE" } },
      }),
    )
    draw(<Page />)

    const editor = await openContribution()
    fireEvent.change(unfold(editor), { target: { value: "Support" } })
    const send = within(editor).getByRole("button", { name: "Send it to me in Discord" })
    fireEvent.click(send)

    await waitFor(() => expect(within(send).getByRole("status").textContent).toBe("Done"))
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
            ? [{ error: true, text: "it draws 41 components, Discord draws 40", part: [] }]
            : [],
      }),
    )
    draw(<Page />)

    const editor = await openContribution()
    fireEvent.change(unfold(editor), { target: { value: "Support" } })

    expect(await within(editor).findByText("it draws 41 components, Discord draws 40")).toBeTruthy()
    expect(within(editor).getByRole<HTMLButtonElement>("button", { name: "Save" }).disabled).toBe(true)
  })

  it("names a problem at the part it is in, and a folded part shows the problems inside it", async () => {
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([contribution, modal]),
        render: rendered,
        problems: () => [
          { error: false, text: "the title warns", part: [0, 0] },
          { error: false, text: "the prices warn", part: [0, 1] },
          { error: false, text: "the row warns", part: [1] },
        ],
      }),
    )
    draw(<Page />)

    const editor = await openContribution()
    const title = unfold(editor)
    const titleWarning = await within(editor).findByText("the title warns")
    const titlePart = within(editor).getByRole("button", { name: /^Text block\s*$/ })
    expect(after(titlePart, titleWarning) && after(titleWarning, title)).toBe(true)
    const prices = within(editor).getByRole("button", { name: /^Text block\s*Prices$/ })
    const pricesWarning = within(editor).getByText("the prices warn")
    expect(after(prices, pricesWarning)).toBe(true)
    const row = within(editor).getByRole("button", { name: /^Buttons/ })
    const rowWarning = within(editor).getByText("the row warns")
    expect(after(pricesWarning, row) && after(row, rowWarning)).toBe(true)
  })

  it("lists every save and restores an older one or the packaged one in one click", async () => {
    const older = { parts: [{ type: "TEXT" as const, lines: [{ text: { en: "Old access" } }] }] }
    const newer = { parts: [{ type: "TEXT" as const, lines: [{ text: { en: "New access" } }] }] }
    const saved: Document[] = []
    const reset: string[] = []
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([{ ...contribution, written: newer }, modal]),
        render: rendered,
        versions: () => [
          { id: 2, document: newer, actor: { kind: "STEWARD" }, saved: "2026-10-19T08:00:00Z" },
          { id: 1, document: older, actor: { kind: "STEWARD" }, saved: "2026-10-18T08:00:00Z" },
        ],
        saveDocument: (_, document) => {
          saved.push(document)
          return documents([{ ...contribution, written: document }, modal])
        },
        resetDocument: (path) => {
          reset.push(path)
          return documents([contribution, modal])
        },
      }),
    )
    draw(<Page />)

    const editor = await openContribution()
    fireEvent.click(within(editor).getByRole("button", { name: "Versions" }))
    // The newest save is live; the older one and the packaged one are offered.
    expect(await within(editor).findByText("Live")).toBeTruthy()
    const restores = within(editor).getAllByRole("button", { name: "Restore" })
    expect(restores).toHaveLength(2)

    fireEvent.click(restores[0])
    await waitFor(() => expect(saved).toEqual([older]))
    fireEvent.click(within(editor).getAllByRole("button", { name: "Restore" })[1])
    await waitFor(() => expect(reset).toEqual(["discord/contribution"]))
  })

  // Fifteen seconds, because it draws the game field with its glyph pickers, which took close to five on CI.
  it(
    "edits a game text in the game's own field, with its tones and glyphs, and adds only text blocks",
    { timeout: 15_000 },
    async () => {
      const title = view(
        {
          namespace: "smp",
          name: "duel-result-title",
          title: "Duel result title",
          area: "Duels",
          shape: "TITLE",
          values: { aura: { kind: "number", example: "3" } },
        },
        { parts: [{ type: "TEXT", lines: [{ text: { en: "<good>Won</good>" } }, { text: { en: "+{aura} aura" } }] }] },
        { game: true, services: ["smp"] },
      )
      vi.stubGlobal("fetch", backend(texts, undefined, { documents: documents([title]), render: () => ({}) }))
      draw(<Page />)

      await openItem("Duel result title")
      const editor = await screen.findByRole("region", { name: "Duel result title" })
      // The field draws the text as it looks, not its markup, and offers the game's glyphs; Discord's line styles are gone.
      expect(within(editor).queryByDisplayValue("<good>Won</good>")).toBeNull()
      expect(within(editor).getAllByRole("button", { name: "Insert a glyph" })).toHaveLength(2)
      expect(within(editor).queryByText("Plain")).toBeNull()
      fireEvent.click(within(editor).getAllByRole("button", { name: "Source" })[0])
      expect(within(editor).getByDisplayValue("<good>Won</good>")).toBeTruthy()

      fireEvent.pointerDown(within(editor).getByRole("button", { name: "Add a part" }), { button: 0, ctrlKey: false })
      expect((await screen.findAllByRole("menuitem")).map((item) => item.textContent)).toEqual(["Text block"])
    },
  )

  it("draws each part of the open document in a box of its own", async () => {
    const title = view(
      {
        namespace: "smp",
        name: "duel-result-title",
        title: "Duel result title",
        area: "Duels",
        shape: "TITLE",
        values: {},
      },
      { parts: [{ type: "TEXT", lines: [{ text: { en: "<good>Won</good>" } }] }] },
      { game: true, services: ["smp"] },
    )
    vi.stubGlobal("fetch", backend(texts, undefined, { documents: documents([title]), render: () => ({}) }))
    draw(<Page />)

    const opened = await openItem("Duel result title")
    const part = within(opened).getByRole("button", { expanded: true, name: /Text/ }).closest("div.rounded-md")
    expect(part?.className).toMatch(/\bborder\b/)
  })

  it("names the part's and the line's condition apart and keeps the source switch in the field's toolbar", async () => {
    const title = view(
      {
        namespace: "smp",
        name: "duel-result-title",
        title: "Duel result title",
        area: "Duels",
        shape: "TITLE",
        values: { aura: { kind: "number", example: "3" } },
      },
      { parts: [{ type: "TEXT", lines: [{ text: { en: "<good>Won</good>" } }] }] },
      { game: true, services: ["smp"], named: { aura: { kind: "number", example: "3" } } },
    )
    vi.stubGlobal("fetch", backend(texts, undefined, { documents: documents([title]), render: () => ({}) }))
    draw(<Page />)

    await openItem("Duel result title")
    const editor = await screen.findByRole("region", { name: "Duel result title" })
    expect(within(editor).getByText("Part shown when")).toBeTruthy()
    expect(within(editor).getByText("Line shown when")).toBeTruthy()
    const toolbar = within(editor).getByRole("toolbar")
    expect(within(toolbar).getByRole("button", { name: "Source" })).toBeTruthy()
    expect(within(toolbar).getByRole("button", { name: "As it looks" })).toBeTruthy()
  })

  it("offers a menu item line's condition the item's own values beside the document's", async () => {
    const grave = view(
      {
        namespace: "smp",
        name: "grave",
        title: "Grave",
        area: "Graves",
        shape: "MENU",
        values: { loot: { kind: "number", example: "3" } },
        slots: [{ id: "owner", type: "ITEM", required: true, values: { known: { kind: "boolean", example: "true" } } }],
      },
      { parts: [{ type: "ITEM", slot: "owner", lines: [{ text: { en: "Owner" }, when: "known" }] }] },
      { game: true, services: ["smp"], named: { loot: { kind: "number", example: "3" } } },
    )
    vi.stubGlobal("fetch", backend(texts, undefined, { documents: documents([grave]), render: () => ({}) }))
    draw(<Page />)

    await openItem("Grave")
    const editor = await screen.findByRole("region", { name: "Grave" })
    if (!within(editor).queryByText("Line shown when")) {
      fireEvent.click(within(editor).getByRole("button", { expanded: false, name: /Item/ }))
    }
    const [mode, value] = within(editor).getAllByRole("combobox", { name: "Line shown when" })
    expect(mode.textContent).toBe("is set")
    expect(value.textContent).toBe("known")
    fireEvent.keyDown(value, { key: "Enter" })
    expect((await screen.findAllByRole("option")).map((option) => option.textContent)).toEqual(["loot", "known"])
  })

  it("shows a game draft as it stands to the admin's player in game", async () => {
    const asked: unknown[] = []
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([footer("smp")]),
        render: () => ({}),
        preview: (body) => {
          asked.push(body)
          return "smp:8"
        },
        commands: { "smp:8": { id: "smp:8", status: "DONE" } },
      }),
    )
    draw(<Page />)

    await openItem("Tab footer")
    const editor = await screen.findByRole("region", { name: "Tab footer" })
    expect(within(editor).queryByRole("button", { name: "Send it to me in Discord" })).toBeNull()
    const show = within(editor).getByRole("button", { name: "Show it to my player in game" })
    fireEvent.click(show)

    await waitFor(() => expect(within(show).getByRole("status").textContent).toBe("Done"))
    expect(asked[0]).toMatchObject({ declaration: { name: "tab-footer", shape: "TAB_LIST" }, language: "en" })
  })

  it("saves a part as a shared part after showing each document it changes before and after", async () => {
    const saved: [string, DocumentPart | null][] = []
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([contribution, modal]),
        render: rendered,
        sharedPartChanges: () => [
          {
            declaration: modal.declaration,
            game: false,
            before: modal.packaged,
            after: modal.packaged,
            problems: ["it does not place the input code"],
          },
        ],
        saveSharedPart: (name, part) => {
          saved.push([name, part])
          return documents([contribution, modal], { sharedParts: part ? [{ name, part, documents: [] }] : [] })
        },
      }),
    )
    draw(<Page />)

    const editor = await openContribution()
    // The last part is the row of buttons.
    fireEvent.pointerDown(within(editor).getAllByRole("button", { name: "Shared part" }).at(-1)!, { button: 0 })
    fireEvent.click(await screen.findByRole("menuitem", { name: "Save as a shared part" }))
    const dialog = await screen.findByRole("dialog", { name: "Save as a shared part" })
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Name" }), { target: { value: "buttons" } })

    expect(await within(dialog).findByText("it does not place the input code")).toBeTruthy()
    expect(within(dialog).getByText("Before")).toBeTruthy()
    expect(within(dialog).getByText("After")).toBeTruthy()
    // A change the check refuses in any document saves nothing.
    expect(within(dialog).getByRole<HTMLButtonElement>("button", { name: "Save" }).disabled).toBe(true)
    expect(saved).toEqual([])
  })

  it("saves a shared part that no document refuses and marks the part as its copy", async () => {
    const saved: [string, DocumentPart | null][] = []
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([contribution, modal]),
        render: rendered,
        sharedPartChanges: () => [],
        saveSharedPart: (name, part) => {
          saved.push([name, part])
          return documents([contribution, modal], { sharedParts: part ? [{ name, part, documents: [] }] : [] })
        },
      }),
    )
    draw(<Page />)

    const editor = await openContribution()
    fireEvent.pointerDown(within(editor).getAllByRole("button", { name: "Shared part" }).at(-1)!, { button: 0 })
    fireEvent.click(await screen.findByRole("menuitem", { name: "Save as a shared part" }))
    const dialog = await screen.findByRole("dialog", { name: "Save as a shared part" })
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Name" }), { target: { value: "buttons" } })
    expect(await within(dialog).findByText("No saved document holds a copy of this shared part.")).toBeTruthy()
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }))

    await waitFor(() => expect(saved).toEqual([["buttons", contribution.packaged.parts[1]]]))
    expect(await within(editor).findByText("Shared part buttons")).toBeTruthy()
  })

  it("inserts a copy of a shared part from the menu that adds a part", async () => {
    const links: DocumentPart = { type: "ROW", items: [{ link: "https://nordtal.eu/rules", label: { en: "Rules" } }] }
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([contribution, modal], {
          sharedParts: [{ name: "rule-links", part: links, documents: [] }],
        }),
        render: rendered,
      }),
    )
    draw(<Page />)

    const editor = await openContribution()
    const adds = within(editor).getAllByRole("button", { name: "Add a part" })
    fireEvent.pointerDown(adds.at(-1)!, { button: 0 })
    fireEvent.click(await screen.findByRole("menuitem", { name: "Shared part rule-links" }))

    expect(await within(editor).findByText("Shared part rule-links")).toBeTruthy()
    expect(within(editor).getByRole("button", { name: /^Buttons\s*Rules$/ })).toBeTruthy()
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
    const card = screen.getByRole("article", { name: "Contribution" })
    expect(within(card).getByText("Changed")).toBeTruthy()
    expect(within(card).getByText("Release changed")).toBeTruthy()
    expect(within(editor).getByRole("heading", { name: "Packaged" })).toBeTruthy()
    expect(within(editor).getByRole("heading", { name: "Saved" })).toBeTruthy()
  })

  it("names each change a release made to the saved document", async () => {
    const written = { parts: [{ type: "TEXT" as const, lines: [{ text: { en: "Old access" } }] }] }
    const adapted = [
      { change: "SLOT_ADDED" as const, subject: "buy" },
      { change: "SLOT_REMOVED" as const, subject: "refund" },
    ]
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([{ ...contribution, written, outdated: true, adapted }, modal]),
        render: rendered,
      }),
    )
    draw(<Page />)

    const changes = within(await openContribution()).getByRole("list", { name: "Adapted to the release" })
    expect(within(changes).getByText("Code element added at the end")).toBeTruthy()
    expect(within(changes).getByText("buy")).toBeTruthy()
    expect(within(changes).getByText("Code element removed")).toBeTruthy()
    expect(within(changes).getByText("refund")).toBeTruthy()
  })

  it("says that the packaged document shows, and why, when a release set the saved one aside", async () => {
    const adapted = [{ change: "SET_ASIDE" as const, subject: "The slot buy is not placed." }]
    vi.stubGlobal(
      "fetch",
      backend(texts, undefined, {
        documents: documents([{ ...contribution, written: contribution.packaged, outdated: true, adapted }, modal]),
        render: rendered,
      }),
    )
    draw(<Page />)

    const changes = within(await openContribution()).getByRole("list", { name: "Adapted to the release" })
    expect(within(changes).getByText("Set aside, the packaged document shows")).toBeTruthy()
    expect(within(changes).getByText("The slot buy is not placed.")).toBeTruthy()
  })
})

describe("a jump from the command palette", () => {
  it("opens the text in the language of the hit", async () => {
    serve()
    draw(<Page />)
    await screen.findByRole("button", { name: /^Discord/ })

    setPendingTextJump({ id: "smp/welcome", language: "en" })

    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "Welcome" }).textContent).toBe("Welcome to the server"),
    )
  })

  it("opens a document in the language of the hit", async () => {
    serve()
    draw(<Page />)
    await screen.findByRole("button", { name: /^Discord/ })

    setPendingTextJump({ id: "document:discord/contribution", language: "de" })

    await screen.findByRole("region", { name: "Contribution" })
    expect(screen.getByRole("tab", { name: "DE" }).getAttribute("aria-selected")).toBe("true")
  })
})

describe("a link", () => {
  it("to a document opens it, with the area it is listed in, and scrolls its row into view", async () => {
    const scrolled: Element[] = []
    vi.spyOn(Element.prototype, "scrollIntoView").mockImplementation(function (this: Element) {
      scrolled.push(this)
    })
    vi.stubGlobal("fetch", backend(texts, undefined, { documents: documents([contribution]) }))
    draw(<Page state={{ document: "discord/contribution" }} />)

    expect(await screen.findByRole("article", { name: "Contribution" })).toBeDefined()
    const current = screen
      .getAllByRole("button", { name: "Contribution" })
      .find((row) => row.getAttribute("aria-current") === "true")
    expect(current).toBeDefined()
    expect(scrolled).toContain(current)
  })
})

describe("a document's draft", { timeout: 15_000 }, () => {
  it("outlives the page and is marked on its row, and leaving the site asks first", async () => {
    serve()
    const first = draw(<Page />)
    fireEvent.change(unfold(await openContribution()), { target: { value: "Support" } })
    first.unmount()

    draw(<Page state={{ area: "DISCORD" }} />)
    const row = (await screen.findAllByRole("button", { name: "Contribution" }))[0]
    expect(within(row).getByLabelText("Changed")).toBeDefined()
    fireEvent.click(row)
    const editor = await screen.findByRole("region", { name: "Contribution" })
    fireEvent.click(within(editor).getByRole("button", { name: /^Text block\s*Support$/ }))
    expect(within(editor).getByDisplayValue("Support")).toBeDefined()

    const leaving = new Event("beforeunload", { cancelable: true })
    window.dispatchEvent(leaving)
    expect(leaving.defaultPrevented).toBe(true)
  })
})
