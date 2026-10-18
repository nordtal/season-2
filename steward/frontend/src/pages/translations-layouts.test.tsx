import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { LayoutView } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import { setPendingTextJump } from "@/lib/settings-search"
import { backend, draw, layouts, listing, open, Page, source, text, view } from "@/pages/translations.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

const discord = { bundle: "access", path: "discord-bot/access", services: ["discord-bot"] }

const texts = listing(
  [
    text({
      ...discord,
      key: "contribution.title",
      name: "Title",
      shown: ["DISCORD_MESSAGE"],
      texts: { en: ["Access"], de: ["access-de"] },
    }),
    text({
      ...discord,
      key: "contribution.prices",
      name: "Prices",
      shown: ["DISCORD_MESSAGE"],
      texts: { en: ["Prices"] },
    }),
    text({
      ...discord,
      key: "contribution.tier-line",
      name: "Tier line",
      shown: ["DISCORD_MESSAGE"],
      texts: { en: ["{price} for {days} days"] },
      args: [
        { name: "price", global: false, action: false, example: "€1.00", exampleWords: {} },
        { name: "days", global: false, action: false, example: "1", exampleWords: {} },
      ],
    }),
    text({
      ...discord,
      key: "contribution.button",
      name: "Button",
      shown: ["DISCORD_BUTTON"],
      texts: { en: ["Buy access"], de: ["buy-access-de"] },
    }),
    text({ ...discord, key: "link.title", name: "Link title", shown: ["DISCORD_MODAL"], texts: { en: ["Link"] } }),
    text({ ...discord, key: "link.label", name: "Code label", shown: ["DISCORD_MODAL"], texts: { en: ["Code"] } }),
    text({ ...discord, key: "link.hint", name: "Code hint", shown: ["DISCORD_MODAL"], texts: { en: ["six digits"] } }),
    text({ key: "welcome", name: "Welcome", texts: { en: ["Welcome to the server"] } }),
  ],
  { languages: ["en", "de"] },
)

const contribution: LayoutView = view(
  {
    name: "contribution",
    elements: [
      { id: "tiers", kind: "LIST", keys: ["access/contribution.tier-line"] },
      { id: "buy", kind: "BUTTON", keys: ["access/contribution.button"] },
    ],
    parts: [
      {
        type: "CONTAINER",
        accent: "#4a63d8",
        parts: [
          { type: "TEXT", lines: [{ key: "access/contribution.title", style: "TITLE" }] },
          { type: "TEXT", lines: [{ key: "access/contribution.prices", style: "HEADING" }, { list: "tiers" }] },
        ],
      },
      { type: "ROW", elements: ["buy"] },
    ],
  },
  [
    "access/contribution.title",
    "access/contribution.prices",
    "access/contribution.tier-line",
    "access/contribution.button",
  ],
)

const modal: LayoutView = view(
  {
    name: "link-code",
    area: "Link",
    elements: [{ id: "code", kind: "INPUT", keys: ["access/link.label", "access/link.hint"] }],
    parts: [
      { type: "TEXT", lines: [{ key: "access/link.title", style: "TITLE" }] },
      { type: "ROW", elements: ["code"] },
    ],
  },
  ["access/link.title", "access/link.label", "access/link.hint"],
)

const answer = layouts([contribution, modal], {
  tiers: [
    { days: 30, priceCents: 300 },
    { days: 60, priceCents: 500 },
  ],
})

function serve() {
  vi.stubGlobal("fetch", backend(texts, undefined, { layouts: answer }))
}

describe("the list of layouts", () => {
  it("draws a Discord layout as its finished message, its price list filled from the live tiers", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    const card = await screen.findByRole("article", { name: "Contribution" })
    within(card).getByRole("button", { name: "Access" })
    within(card).getByRole("button", { name: "Buy access" })
    const lines = within(card)
      .getAllByRole("button")
      .map((line) => line.textContent)
      .filter((line) => line?.includes("days"))
    expect(lines).toEqual([expect.stringMatching(/3[.,]00 for 30 days/), expect.stringMatching(/5[.,]00 for 60 days/)])
    expect(screen.queryByRole("article", { name: "Title" })).toBeNull()
  })

  it("draws a modal as its title and each field under its label, with the field's hint inside", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Link")
    const card = await screen.findByRole("article", { name: "Link code" })
    expect(within(card).queryByText("Nordtal")).toBeNull()
    within(card).getByRole("button", { name: "Link" })
    within(card).getByRole("button", { name: "Code" })
    expect(within(card).getByRole("button", { name: "six digits" })).toBeTruthy()
  })

  it("lists a text that no layout shows as a layout of one part", async () => {
    serve()
    draw(<Page />)

    await open(/^In game/)
    const card = await screen.findByRole("article", { name: "Welcome" })
    expect(within(card).getByRole("button", { name: "Welcome" }).textContent).toContain("Welcome to the server")
  })
})

describe("an open layout", () => {
  it("opens a clicked text in the editor beside the preview, and the preview follows the typing", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    fireEvent.click(
      within(await screen.findByRole("article", { name: "Contribution" })).getByRole("button", { name: "Access" }),
    )

    screen.getByRole("heading", { level: 1, name: "Contribution" })
    const field = await source("Title")
    expect(field.value).toBe("Access")
    fireEvent.change(field, { target: { value: "Members" } })

    expect(screen.getByRole("button", { name: "Members", pressed: true })).toBeTruthy()
    screen.getByRole("button", { name: "Save 1" })
  })

  it("picks another text from the preview or from the list of its texts", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    fireEvent.click(
      within(await screen.findByRole("article", { name: "Contribution" })).getByRole("button", { name: "Access" }),
    )
    fireEvent.click(screen.getByRole("button", { name: "Buy access" }))
    expect((await source("Button")).value).toBe("Buy access")

    fireEvent.click(within(screen.getByRole("region", { name: /^Texts/ })).getByRole("button", { name: /^Prices/ }))
    expect((await source("Prices")).value).toBe("Prices")
  })

  it("draws the preview in the language the editor shows", async () => {
    serve()
    draw(<Page />)

    await open(/^Discord/)
    await open("Contribution")
    fireEvent.click(
      within(await screen.findByRole("article", { name: "Contribution" })).getByRole("button", { name: "Access" }),
    )
    fireEvent.mouseDown(await screen.findByRole("tab", { name: /DE/ }))

    await screen.findByRole("button", { name: "access-de", pressed: true })
    expect(screen.getByRole("button", { name: "buy-access-de" })).toBeTruthy()
  })

  it("opens a layout of one part as its editor with its own previews, and goes back to the list", async () => {
    serve()
    draw(<Page />)

    await open(/^In game/)
    fireEvent.click(
      within(await screen.findByRole("article", { name: "Welcome" })).getByRole("button", { name: "Welcome" }),
    )

    screen.getByRole("heading", { level: 1, name: "Welcome" })
    expect((await screen.findByRole("textbox", { name: "Welcome" })).textContent).toBe("Welcome to the server")
    screen.getByRole("figure", { name: "Chat" })

    fireEvent.click(screen.getByRole("button", { name: "Translations" }))
    expect(await screen.findByRole("button", { name: /^Discord/ })).toBeTruthy()
  })
})

describe("a jump from the command palette", () => {
  it("lands in the layout that holds the text, picked and in the language of the hit", async () => {
    serve()
    draw(<Page />)
    await screen.findByRole("button", { name: /^Discord/ })

    setPendingTextJump({ id: "access/contribution.button", language: "de" })

    await screen.findByRole("heading", { level: 1, name: "Contribution" })
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Button" }).textContent).toBe("buy-access-de"))
    expect(screen.getByRole("button", { name: "buy-access-de", pressed: true })).toBeTruthy()
  })
})
