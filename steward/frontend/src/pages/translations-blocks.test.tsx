import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { DocumentPart } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import {
  type Around,
  backend,
  documents,
  draw,
  listing,
  openItem,
  Page,
  text,
  view,
} from "@/pages/translations.fixtures"

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
    text({
      key: "greeting",
      name: "Greeting",
      texts: { en: ["Hi on {@terms.network}, {@terms.gone}"] },
      shown: ["CHAT", "DISCORD_MESSAGE", "STEWARD"],
    }),
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
  {
    parts: [
      { type: "TEXT", lines: [{ text: { en: "Buy {@terms.access}" } }] },
      { type: "TEXT", shared: "rule-links" },
    ],
  },
)

const rules: DocumentPart = { type: "TEXT", lines: [{ text: { en: "Read the rules" } }] }
const shared = [{ name: "rule-links", part: rules, documents: ["discord/notice"] }]

function drawPage(around: Around = {}) {
  vi.stubGlobal(
    "fetch",
    backend(fixture, undefined, { documents: documents([notice], { sharedParts: shared }), ...around }),
  )
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

describe("a shared part", () => {
  it("is listed under Building blocks with the documents that name it, and opens one on a click", async () => {
    drawPage()
    const part = await openItem("rule-links", /^Building blocks/)

    expect(within(screen.getByRole("button", { name: "rule-links" })).getByText("Used once")).toBeDefined()
    const uses = within(part).getByRole("list", { name: "Used in" })
    fireEvent.click(within(uses).getByRole("button", { name: "Notice" }))
    expect(await screen.findByRole("article", { name: "Notice" })).toBeDefined()
  })

  it("is edited on its own and saved after showing each document it changes", async () => {
    const saved: [string, DocumentPart | null][] = []
    drawPage({
      sharedPartChanges: () => [
        { declaration: notice.declaration, game: false, before: notice.packaged, after: notice.packaged, problems: [] },
      ],
      saveSharedPart: (name, part) => {
        saved.push([name, part])
        return documents([notice], { sharedParts: [{ ...shared[0], part: part ?? rules }] })
      },
    })
    const part = await openItem("rule-links", /^Building blocks/)

    fireEvent.change(within(part).getByDisplayValue("Read the rules"), { target: { value: "Read the house rules" } })
    fireEvent.click(within(part).getByRole("button", { name: "Save" }))
    const dialog = await screen.findByRole("dialog", { name: "Save the shared part rule-links" })
    expect(await within(dialog).findByText("Notice")).toBeDefined()
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0][0]).toBe("rule-links")
    expect(JSON.stringify(saved[0][1])).toContain("Read the house rules")
  })

  it("is a hit of the search, and opens from there", async () => {
    drawPage({
      search: () => [{ kind: "SHARED_PART", id: "rule-links", language: "en", field: "TEXT", exact: true, text: "x" }],
    })
    fireEvent.change(await screen.findByRole("searchbox", { name: "Search the texts" }), {
      target: { value: "rules" },
    })

    fireEvent.click(await screen.findByRole("button", { name: "rule-links" }))
    expect(await screen.findByRole("article", { name: "rule-links" })).toBeDefined()
  })

  it("opens from the badge of a document that names it", async () => {
    drawPage()
    const document = await openItem("Notice", /^Discord/)

    fireEvent.click(within(document).getByRole("button", { name: "Shared part rule-links" }))
    expect(await screen.findByRole("article", { name: "rule-links" })).toBeDefined()
  })
})

describe("a building block reference", () => {
  it("opens its block from the block's words in the preview", async () => {
    drawPage()
    await openItem("Welcome")

    expect(screen.queryByRole("list", { name: "Building blocks it uses" })).toBeNull()
    fireEvent.click(within(screen.getByRole("figure", { name: "Chat" })).getByRole("button", { name: "Nordtal" }))
    expect(await screen.findByRole("article", { name: "Network" })).toBeDefined()
  })

  it("opens its block from its chip in the editor", async () => {
    drawPage()
    await openItem("Welcome")

    fireEvent.click(screen.getByRole("button", { name: "{@terms.access}" }))
    expect(await screen.findByRole("article", { name: "Access" })).toBeDefined()
  })

  it("to a block the list does not hold opens nothing", async () => {
    drawPage()
    await openItem("Greeting")

    expect(within(screen.getByRole("figure", { name: "Chat" })).queryByRole("button", { name: "something" })).toBeNull()
  })
})

describe("a preview", () => {
  it("draws a building block's words in every place, and a block it cannot find as missing", async () => {
    drawPage()
    await openItem("Greeting")

    for (const place of ["Chat", "Discord message", "Steward"]) {
      expect(screen.getByRole("figure", { name: place }).textContent).toContain("Hi on Nordtal, something")
    }
  })
})
