import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { AccessPage } from "@/pages/access"
import { assertElement, backend, draw, person, rowFor } from "@/pages/access.fixtures"

/**
 * The Access page's values: each opens its own dialog, and last seen orders the roster.
 */

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("AccessPage - each value opens its own dialog", () => {
  it("opens the grant from access, and the play time, aura and spins from their figures", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ people: () => [person({ discordUsername: "alice", playtimeSeconds: 3_600, aura: 7, spins: 2 })] }),
    )
    draw(<AccessPage />)

    const row = await rowFor("alice")
    for (const [value, title] of [
      [/never had access/, "Grant access to alice"],
      [/^Playtime/, "Set play time"],
      [/^Aura/, "Set aura"],
      [/^Spins/, "Set spins"],
    ] as const) {
      fireEvent.click(within(row).getAllByRole("button", { name: value })[0])
      const dialog = await screen.findByRole("alertdialog")
      expect(within(dialog).getByText(title)).toBeTruthy()
      fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }))
      await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    }
  })
})

const SEEN = () => [
  person({ discordId: "1", discordUsername: "never" }),
  person({ discordId: "2", discordUsername: "earlier", lastSeen: "2026-09-01T00:00:00Z" }),
  person({ discordId: "3", discordUsername: "here", online: true, lastSeen: "2026-08-01T00:00:00Z" }),
  person({ discordId: "4", discordUsername: "later", lastSeen: "2026-09-20T00:00:00Z", aura: 5 }),
]
const order = () =>
  [...document.querySelectorAll<HTMLElement>("tbody tr")].map(
    (row) => row.querySelector(":scope > td:first-child a")?.textContent,
  )

const seen = async (name: string) =>
  assertElement((await rowFor(name)).querySelector('td[data-label="Last seen"]'), "last seen")

describe("AccessPage - last seen", () => {
  it("says Online, when the person was last online, or Never, in a column and under the person", async () => {
    vi.stubGlobal("fetch", backend({ people: SEEN }))
    draw(<AccessPage />)

    expect((await seen("here")).textContent).toBe("Online")
    expect((await seen("never")).textContent).toBe("Never")
    expect((await seen("earlier")).textContent).toMatch(/ago$/)
    expect((await rowFor("here")).querySelector(":scope > td:first-child")?.textContent).toContain("Online")
  })

  it("lists online people first and then by last seen, and sorts by a heading both ways", async () => {
    vi.stubGlobal("fetch", backend({ people: SEEN }))
    draw(<AccessPage />)

    await rowFor("never")
    expect(order()).toEqual(["here", "later", "earlier", "never"])
    expect(screen.getByRole("columnheader", { name: "Last seen" }).getAttribute("aria-sort")).toBe("descending")

    fireEvent.click(screen.getByRole("button", { name: "Aura" }))
    await waitFor(() => expect(order()[0]).toBe("later"))
    expect(screen.getByRole("columnheader", { name: "Aura" }).getAttribute("aria-sort")).toBe("descending")
    fireEvent.click(screen.getByRole("button", { name: "Aura" }))
    await waitFor(() => expect(order().at(-1)).toBe("later"))
    expect(screen.getByRole("columnheader", { name: "Aura" }).getAttribute("aria-sort")).toBe("ascending")
  })
})
