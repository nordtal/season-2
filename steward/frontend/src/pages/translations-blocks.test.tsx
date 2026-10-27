import { cleanup, fireEvent, screen, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { resetDrafts } from "@/lib/drafts"
import { backend, documents, draw, listing, openItem, Page, text, view } from "@/pages/translations.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

const block = (key: string, name: string, words: string) =>
  text({ key, bundle: "terms", name, texts: { en: [words] }, shown: ["CHAT", "DISCORD_MESSAGE", "STEWARD"] })

const fixture = listing(
  [
    block("terms.access", "Access", "access"),
    block("terms.network", "Network", "Nordtal"),
    block("terms.spare", "Spare", "spare"),
    text({ key: "welcome", name: "Welcome", texts: { en: ["Welcome to {@terms.network}, {@terms.access}"] } }),
  ],
  {
    uses: {
      "terms.access": { texts: ["smp/welcome"], documents: ["discord/notice"] },
      "terms.network": { texts: ["smp/welcome"], documents: [] },
      "terms.spare": { texts: [], documents: [] },
    },
  },
)

const notice = view(
  { name: "notice", title: "Notice" },
  { parts: [{ type: "TEXT", lines: [{ text: { en: "Buy {@terms.access}" } }] }] },
)

function drawPage() {
  vi.stubGlobal("fetch", backend(fixture, undefined, { documents: documents([notice]) }))
  draw(<Page />)
}

/** The blocks the list shows, by name. */
const rows = () => ["Access", "Network", "Spare"].filter((name) => screen.queryAllByRole("button", { name }).length > 0)

describe("a building block", () => {
  it("shows how often it is used in its row, and not the places every block is shown in", async () => {
    drawPage()
    await openItem("Access", /^Building blocks/)

    expect(within(screen.getByRole("button", { name: "Access" })).getByText("Used 2 times")).toBeDefined()
    expect(within(screen.getByRole("button", { name: "Network" })).getByText("Used once")).toBeDefined()
    expect(within(screen.getByRole("button", { name: "Spare" })).getByText("Not used")).toBeDefined()
    expect(screen.queryByLabelText("Shown in")).toBeNull()
  })

  it("lists each text and document that uses it, and opens one on a click", async () => {
    drawPage()
    await openItem("Access", /^Building blocks/)

    const uses = screen.getByRole("list", { name: "Used in" })
    expect(
      within(uses)
        .getAllByRole("button")
        .map((use) => use.textContent),
    ).toEqual(["Welcome", "Notice"])
    fireEvent.click(within(uses).getByRole("button", { name: "Notice" }))
    expect(await screen.findByRole("article", { name: "Notice" })).toBeDefined()
  })

  it("filters the blocks to those shared by several translations or used by one", async () => {
    drawPage()
    await openItem("Access", /^Building blocks/)
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Shared by several" }))
    expect(rows()).toEqual(["Access"])
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Used by one" }))
    expect(rows()).toEqual(["Network"])
    fireEvent.mouseDown(screen.getByRole("tab", { name: "All" }))
    expect(rows()).toEqual(["Access", "Network", "Spare"])
  })
})

describe("a text", () => {
  it("links each building block it uses, which opens the block", async () => {
    drawPage()
    await openItem("Welcome")

    const blocks = screen.getByRole("list", { name: "Building blocks it uses" })
    expect(
      within(blocks)
        .getAllByRole("button")
        .map((use) => use.textContent),
    ).toEqual(["Access", "Network"])
    fireEvent.click(within(blocks).getByRole("button", { name: "Access" }))
    expect(await screen.findByRole("article", { name: "Access" })).toBeDefined()
  })
})
