import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { AccessPage } from "@/pages/access"
import { assertElement, backend, badges, draw, person, rowFor } from "@/pages/access.fixtures"

/** The Users page's rows: the whole row opens the person's page, and the person keeps to one line. */

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("AccessPage - the whole row opens the person's page", () => {
  it("opens the page from a click anywhere in the row outside a value", async () => {
    vi.stubGlobal("fetch", backend())
    const { router } = draw(<AccessPage />)

    const row = await rowFor("Ally")
    fireEvent.click(assertElement(row.querySelector('[data-label="Last seen"]'), "the last seen cell"))

    await waitFor(() => expect(router.state.location.pathname).toBe("/access/214906139328839681"))
  })

  it("leaves a click on a value to its own dialog, and a click inside that dialog too", async () => {
    vi.stubGlobal("fetch", backend({ people: () => [person({ discordUsername: "alice", aura: 10 })] }))
    const { router } = draw(<AccessPage />)

    const row = await rowFor("alice")
    fireEvent.click(assertElement(within(row).getAllByRole("button", { name: /^aura/i })[0] ?? null, "the aura"))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByLabelText(/^aura$/i))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(router.state.location.pathname).toBe("/")
    expect(screen.getByRole("alertdialog")).toBeTruthy()
  })

  it("leaves a tap on a tooltip to the tooltip, while a mouse click there opens the page", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ people: () => [person({ discordUsername: "alice", lastSeen: "2026-10-01T00:00:00Z" })] }),
    )
    const { router } = draw(<AccessPage />)

    const line = assertElement(badges(await rowFor("alice")).firstElementChild, "the person's line")
    const seen = assertElement(line.querySelector("[tabindex]"), "last seen with its tip")
    fireEvent.pointerDown(seen, { pointerType: "touch" })
    fireEvent.click(seen)
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(router.state.location.pathname).toBe("/")

    fireEvent.pointerDown(seen, { pointerType: "mouse" })
    fireEvent.click(seen)
    await waitFor(() => expect(router.state.location.pathname).toBe("/access/100000000000000001"))
  })

  it("draws last seen after the badges on a phone, on the person's one line", async () => {
    vi.stubGlobal("fetch", backend({ people: () => [person({ discordUsername: "boss", admin: true })] }))
    draw(<AccessPage />)

    const line = assertElement(badges(await rowFor("boss")).firstElementChild, "the person's line")
    const parts = [...line.children].map((part) => part.textContent)
    expect(parts.at(-2)).toContain("Admin")
    expect(parts.at(-1)).toBe("Never")
  })
})
