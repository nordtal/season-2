import { RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import type { ReactNode } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { IDENTIFIER_PATTERN, PersonIdentity, minecraftHeadUrl } from "@/components/steward/identity"
import { asInput } from "@/lib/test-elements"

/** Identifiers appear only in the popover, each beside a button that copies it. */

const DISCORD_ID = "214906139328839681"
const MC_UUID = "11111111-2222-3333-4444-555555555555"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("PersonIdentity - closed, the id is never drawn", () => {
  it("shows the guild nickname and never the raw ids", () => {
    render(
      <PersonIdentity
        discordId={DISCORD_ID}
        discordDisplayName="Ally"
        discordUsername="alice"
        mcUuid={MC_UUID}
        mcName="AliceMC"
      />,
    )

    expect(screen.getByText("Ally")).toBeTruthy()
    expect(document.body.textContent).not.toContain(DISCORD_ID)
    expect(document.body.textContent).not.toContain(MC_UUID)
  })

  it("falls back to the username when there is no guild nickname, still without an id", () => {
    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" />)

    expect(screen.getByText("alice")).toBeTruthy()
    expect(document.body.textContent).not.toContain(DISCORD_ID)
  })

  it("falls back to readable text, not the raw id, for an account with no observed name", () => {
    render(<PersonIdentity discordId={DISCORD_ID} />)

    expect(document.body.textContent).not.toContain(DISCORD_ID)
    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
    expect(screen.getByText(/no discord name on record/i)).toBeTruthy()
  })

  it("draws nothing that matches an id or a uuid pattern at all, for a fully populated person", () => {
    render(
      <PersonIdentity
        discordId={DISCORD_ID}
        discordDisplayName="Ally"
        discordAvatarUrl="https://cdn.discordapp.com/a.png"
        mcUuid={MC_UUID}
        mcName="AliceMC"
      />,
    )

    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })
})

describe("PersonIdentity - system", () => {
  it("shows Steward and never opens a popover, since there is no id behind it", () => {
    render(<PersonIdentity system />)

    expect(screen.getByText("Steward")).toBeTruthy()
    expect(screen.queryByRole("button")).toBeNull()
  })

  it("ignores a person's fields when system is set, rather than drawing them beside Steward", () => {
    render(<PersonIdentity system discordId={DISCORD_ID} discordDisplayName="Ally" />)

    expect(screen.getByText("Steward")).toBeTruthy()
    expect(screen.queryByText("Ally")).toBeNull()
    expect(document.body.textContent).not.toContain(DISCORD_ID)
  })
})

describe("PersonIdentity - opened, the popover is the one place to copy from", () => {
  it("reveals the Discord id once the popover is opened", async () => {
    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" />)

    fireEvent.click(screen.getByRole("button"))

    const field = await screen.findByLabelText("Discord-ID")
    expect(asInput(field).value).toBe(DISCORD_ID)
  })

  it("reveals the Minecraft uuid as well when the account is linked", async () => {
    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" mcUuid={MC_UUID} mcName="AliceMC" />)

    fireEvent.click(screen.getByRole("button"))

    const field = await screen.findByLabelText("Minecraft-UUID")
    expect(asInput(field).value).toBe(MC_UUID)
  })

  it("says plainly that nothing is linked, rather than offering a uuid field with nothing in it", async () => {
    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" />)

    fireEvent.click(screen.getByRole("button"))

    await screen.findByText(/no minecraft account linked/i)
    expect(screen.queryByLabelText("Minecraft-UUID")).toBeNull()
  })

  it("copies the id it is asked to copy, and not the other one", async () => {
    const writeText = vi.fn<(text: string) => Promise<void>>().mockResolvedValue(undefined)
    // The fields of `navigator` are prototype getters, so spreading it copies nothing.
    vi.stubGlobal("navigator", { clipboard: { writeText } })

    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" mcUuid={MC_UUID} mcName="AliceMC" />)
    fireEvent.click(screen.getByRole("button"))
    await screen.findByLabelText("Minecraft-UUID")

    fireEvent.click(screen.getByRole("button", { name: "Copy Minecraft-UUID" }))

    expect(writeText).toHaveBeenCalledExactlyOnceWith(MC_UUID)
  })

  it("leaves the id selectable when the clipboard API is unavailable, rather than failing silently", async () => {
    /** `navigator.clipboard` needs a secure context, which http://127.0.0.1 is not. */
    vi.stubGlobal("navigator", { clipboard: undefined })

    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" />)
    fireEvent.click(screen.getByRole("button"))

    const field = asInput(await screen.findByLabelText("Discord-ID"))
    expect(field.readOnly).toBe(true)
    expect(field.value).toBe(DISCORD_ID)
    expect(() => fireEvent.click(screen.getByRole("button", { name: "Copy Discord-ID" }))).not.toThrow()
  })
})

function inRouter(card: ReactNode) {
  const root = createRootRoute({ component: () => <>{card}</> })
  const person = createRoute({ getParentRoute: () => root, path: "/access/$id" })
  const router = createRouter({
    routeTree: root.addChildren([person]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  })
  return render(<RouterProvider router={router} />)
}

describe("PersonIdentity - the way to the person's page", () => {
  it("links the opened card to the person's page under Users", async () => {
    inRouter(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" />)

    fireEvent.click(await screen.findByRole("button"))

    const link = await screen.findByRole("link", { name: "Open their page" })
    expect(link.getAttribute("href")).toBe(`/access/${DISCORD_ID}`)
  })

  it("offers no link without a Discord id, since the page is keyed by it", async () => {
    inRouter(<PersonIdentity mcUuid={MC_UUID} mcName="AliceMC" face="minecraft" />)

    fireEvent.click(await screen.findByRole("button"))

    await screen.findByText(/no discord account on record/i)
    expect(screen.queryByRole("link")).toBeNull()
  })

  it("stays a plain card, with no link and no warning, where no router is mounted", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined)
    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" />)

    fireEvent.click(screen.getByRole("button"))

    await screen.findByLabelText("Discord-ID")
    expect(screen.queryByRole("link")).toBeNull()
    expect(warn).not.toHaveBeenCalled()
    warn.mockRestore()
  })
})

describe("PersonIdentity - nothing half-written, and no mark nobody can see", () => {
  it("writes no line under the Discord name, whether the name is fresh or ancient", () => {
    render(<PersonIdentity discordId={DISCORD_ID} discordDisplayName="Ally" />)
    fireEvent.click(screen.getByRole("button"))

    expect(screen.queryByText(/confirmed/i)).toBeNull()
    /**
     * The en dash that `format.ts` writes for nothing. After a word it reads as half a sentence.
     */
    expect(document.body.textContent).not.toContain("\u2013")
  })

  it("writes no line under the Minecraft name either", () => {
    render(<PersonIdentity discordId={DISCORD_ID} discordDisplayName="Ally" mcUuid={MC_UUID} mcName="AllyMC" />)
    fireEvent.click(screen.getByRole("button"))

    expect(screen.queryByText(/\bseen\b/i)).toBeNull()
  })

  it("draws no asterisk after either name, and no mark for a stylesheet to find", () => {
    render(<PersonIdentity discordId={DISCORD_ID} discordDisplayName="Ally" mcUuid={MC_UUID} mcName="AllyMC" />)
    fireEvent.click(screen.getByRole("button"))

    expect(document.body.textContent).not.toContain("*")
    expect(document.querySelector("[data-stale]")).toBeNull()
    expect(document.querySelector("[title*='Not confirmed']")).toBeNull()
  })

  it("still says so when there is genuinely nothing on record - that is a sentence, not a stub", () => {
    render(<PersonIdentity discordId={DISCORD_ID} />)
    fireEvent.click(screen.getByRole("button"))

    expect(screen.getAllByText("no Discord name on record").length).toBeGreaterThan(0)
    expect(screen.getByText(/never seen/i)).toBeTruthy()
  })
})

describe("PersonIdentity, Minecraft face - no asterisk, no tooltip", () => {
  it("draws the name alone", () => {
    render(<PersonIdentity face="minecraft" mcUuid={MC_UUID} mcName="AliceMC" />)

    expect(screen.getByText("AliceMC")).toBeTruthy()
    expect(document.body.textContent).not.toContain("*")
    expect(document.querySelector("[title*='Not confirmed']")).toBeNull()
  })
})

describe("minecraftHeadUrl", () => {
  /**
   * Both UUID spellings answer at api.mineatar.io. The hyphens are removed, so each face has one URL and one cache entry.
   *
   * The default base carries `?scale=16`, so the UUID goes before the query.
   */
  const UNDASHED = MC_UUID.replace(/-/g, "")
  const MINEATAR = "https://api.mineatar.io/face"

  it("composes the uuid onto the configured base, without its hyphens", () => {
    expect(minecraftHeadUrl(MINEATAR, MC_UUID)).toBe(`${MINEATAR}/${UNDASHED}`)
  })

  it("puts the uuid in the path and keeps the query behind it", () => {
    /** Appending the UUID to the end would give a URL that loads and is not this player's face. */
    expect(minecraftHeadUrl(`${MINEATAR}?scale=16`, MC_UUID)).toBe(`${MINEATAR}/${UNDASHED}?scale=16`)
  })

  it("keeps a query with several parameters whole", () => {
    expect(minecraftHeadUrl(`${MINEATAR}?scale=16&overlay=true`, MC_UUID)).toBe(
      `${MINEATAR}/${UNDASHED}?scale=16&overlay=true`,
    )
  })

  it("strips a trailing slash rather than doubling it, query or no query", () => {
    expect(minecraftHeadUrl(`${MINEATAR}/`, MC_UUID)).toBe(`${MINEATAR}/${UNDASHED}`)
    expect(minecraftHeadUrl(`${MINEATAR}/?scale=16`, MC_UUID)).toBe(`${MINEATAR}/${UNDASHED}?scale=16`)
  })

  it("leaves a uuid that already came without hyphens alone", () => {
    expect(minecraftHeadUrl(MINEATAR, UNDASHED)).toBe(`${MINEATAR}/${UNDASHED}`)
  })

  it("still works for a base pointing anywhere else, because it is configuration", () => {
    expect(minecraftHeadUrl("https://mc-heads.net/avatar", MC_UUID)).toBe(`https://mc-heads.net/avatar/${UNDASHED}`)
  })

  it("answers nothing when no base is configured, rather than a broken relative path", () => {
    expect(minecraftHeadUrl(undefined, MC_UUID)).toBeNull()
  })
})

describe("PersonIdentity, Minecraft face - the name and the head, never the uuid", () => {
  it("shows the name without ever drawing the uuid", () => {
    render(<PersonIdentity face="minecraft" mcUuid={MC_UUID} mcName="AliceMC" />)

    expect(screen.getByText("AliceMC")).toBeTruthy()
    expect(document.body.textContent).not.toContain(MC_UUID)
  })

  it("says a name has not been observed yet, rather than showing nothing", () => {
    render(<PersonIdentity face="minecraft" mcUuid={MC_UUID} />)

    /** The fallback is short, because a longer one is truncated at 390px. */
    expect(screen.getByText(/no name yet/i)).toBeTruthy()
  })
})
