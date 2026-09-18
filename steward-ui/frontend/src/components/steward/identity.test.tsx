import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import {
  IDENTIFIER_PATTERN,
  MinecraftFace,
  PersonIdentity,
  STALE_AFTER_MS,
  isStale,
  minecraftHeadUrl,
} from "@/components/steward/identity"

/**
 * steward/45's whole rule, held against the one component that is allowed to break it:
 * identifiers belong in exactly one place - this display's popover, for copying. Nowhere else.
 * Closed, this component must never draw the Discord id or the Minecraft uuid; opened, its
 * popover is the one place both may appear, each next to a way to copy it.
 */

const DISCORD_ID = "214906139328839681"
const MC_UUID = "11111111-2222-3333-4444-555555555555"
const NOW = new Date("2026-09-15T12:00:00Z").getTime()

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
        now={NOW}
      />,
    )

    expect(screen.getByText("Ally")).toBeTruthy()
    expect(document.body.textContent).not.toContain(DISCORD_ID)
    expect(document.body.textContent).not.toContain(MC_UUID)
  })

  it("falls back to the username when there is no guild nickname, still without an id", () => {
    render(
      <PersonIdentity discordId={DISCORD_ID} discordUsername="alice" now={NOW} />,
    )

    expect(screen.getByText("alice")).toBeTruthy()
    expect(document.body.textContent).not.toContain(DISCORD_ID)
  })

  it("falls back to readable text, not the raw id, for an account with no observed name", () => {
    // A former guild member, or one nobody has mirrored a profile onto yet - both real states,
    // neither an excuse to fall back to the identifier the whole rule exists to hide.
    render(<PersonIdentity discordId={DISCORD_ID} now={NOW} />)

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
        now={NOW}
      />,
    )

    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })
})

describe("PersonIdentity - system, steward/82", () => {
  it("shows Steward and never opens a popover, since there is no id behind it", () => {
    render(<PersonIdentity system now={NOW} />)

    expect(screen.getByText("Steward")).toBeTruthy()
    expect(screen.queryByRole("button")).toBeNull()
  })

  it("ignores a person's fields when system is set, rather than drawing them beside Steward", () => {
    render(
      <PersonIdentity
        system
        discordId={DISCORD_ID}
        discordDisplayName="Ally"
        now={NOW}
      />,
    )

    expect(screen.getByText("Steward")).toBeTruthy()
    expect(screen.queryByText("Ally")).toBeNull()
    expect(document.body.textContent).not.toContain(DISCORD_ID)
  })
})

describe("PersonIdentity - opened, the popover is the one place to copy from", () => {
  it("reveals the Discord id once the popover is opened", async () => {
    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" now={NOW} />)

    fireEvent.click(screen.getByRole("button"))

    const field = await screen.findByLabelText("Discord-ID")
    expect((field as HTMLInputElement).value).toBe(DISCORD_ID)
  })

  it("reveals the Minecraft uuid as well when the account is linked", async () => {
    render(
      <PersonIdentity
        discordId={DISCORD_ID}
        discordUsername="alice"
        mcUuid={MC_UUID}
        mcName="AliceMC"
        now={NOW}
      />,
    )

    fireEvent.click(screen.getByRole("button"))

    const field = await screen.findByLabelText("Minecraft-UUID")
    expect((field as HTMLInputElement).value).toBe(MC_UUID)
  })

  it("says plainly that nothing is linked, rather than offering a uuid field with nothing in it", async () => {
    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" now={NOW} />)

    fireEvent.click(screen.getByRole("button"))

    await screen.findByText(/no minecraft account linked/i)
    expect(screen.queryByLabelText("Minecraft-UUID")).toBeNull()
  })

  it("copies the id it is asked to copy, and not the other one", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal("navigator", { ...navigator, clipboard: { writeText } })

    render(
      <PersonIdentity
        discordId={DISCORD_ID}
        discordUsername="alice"
        mcUuid={MC_UUID}
        mcName="AliceMC"
        now={NOW}
      />,
    )
    fireEvent.click(screen.getByRole("button"))
    await screen.findByLabelText("Minecraft-UUID")

    fireEvent.click(screen.getByRole("button", { name: "Copy Minecraft-UUID" }))

    expect(writeText).toHaveBeenCalledExactlyOnceWith(MC_UUID)
  })

  it("leaves the id selectable when the clipboard API is unavailable, rather than failing silently", async () => {
    // steward/45's footnote: `navigator.clipboard` needs a secure context, which developing over
    // http://127.0.0.1 does not have. The button must not be the only way to get the value out.
    vi.stubGlobal("navigator", { ...navigator, clipboard: undefined })

    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" now={NOW} />)
    fireEvent.click(screen.getByRole("button"))

    const field = (await screen.findByLabelText("Discord-ID")) as HTMLInputElement
    expect(field.readOnly).toBe(true)
    expect(field.value).toBe(DISCORD_ID)
    // The button is still there and still clickable - it must not throw with no clipboard.
    expect(() =>
      fireEvent.click(screen.getByRole("button", { name: "Copy Discord-ID" })),
    ).not.toThrow()
  })
})

describe("PersonIdentity - a name that has not been reconfirmed says so", () => {
  it("marks a stale display name rather than presenting it as current", () => {
    const staleSince = new Date(NOW - STALE_AFTER_MS - 1000).toISOString()
    render(
      <PersonIdentity
        discordId={DISCORD_ID}
        discordDisplayName="Ally"
        discordDisplayNameUpdated={staleSince}
        now={NOW}
      />,
    )

    const name = screen.getByText("Ally", { exact: false })
    expect(name.getAttribute("data-stale")).toBe("true")
  })

  it("does not mark a name confirmed within the threshold", () => {
    const recentlySince = new Date(NOW - 1000).toISOString()
    render(
      <PersonIdentity
        discordId={DISCORD_ID}
        discordDisplayName="Ally"
        discordDisplayNameUpdated={recentlySince}
        now={NOW}
      />,
    )

    const name = screen.getByText("Ally", { exact: false })
    expect(name.getAttribute("data-stale")).toBeNull()
  })
})

describe("isStale", () => {
  it("treats a field that was never observed as stale", () => {
    expect(isStale(undefined, NOW)).toBe(true)
    expect(isStale(null, NOW)).toBe(true)
  })

  it("is the threshold, exactly", () => {
    const justUnder = new Date(NOW - STALE_AFTER_MS + 1000).toISOString()
    const justOver = new Date(NOW - STALE_AFTER_MS - 1000).toISOString()
    expect(isStale(justUnder, NOW)).toBe(false)
    expect(isStale(justOver, NOW)).toBe(true)
  })
})

describe("minecraftHeadUrl", () => {
  /**
   * steward/111, Till on 2026-09-18: the fetch was failing at the service, not in this code, so
   * the head now comes from mc-heads.net - and the uuid goes out without its hyphens, which is the
   * form he measured being accepted for certain. The stripping happens here
   * rather than in the configuration, so a base pointing at any service gets the form that every
   * one of them accepts.
   */
  const UNDASHED = MC_UUID.replace(/-/g, "")

  it("composes the uuid onto the configured base, without its hyphens", () => {
    expect(minecraftHeadUrl("https://mc-heads.net/avatar", MC_UUID)).toBe(
      `https://mc-heads.net/avatar/${UNDASHED}`,
    )
  })

  it("strips a trailing slash rather than doubling it", () => {
    expect(minecraftHeadUrl("https://mc-heads.net/avatar/", MC_UUID)).toBe(
      `https://mc-heads.net/avatar/${UNDASHED}`,
    )
  })

  it("leaves a uuid that already came without hyphens alone", () => {
    expect(minecraftHeadUrl("https://mc-heads.net/avatar", UNDASHED)).toBe(
      `https://mc-heads.net/avatar/${UNDASHED}`,
    )
  })

  it("answers nothing when no base is configured, rather than a broken relative path", () => {
    expect(minecraftHeadUrl(undefined, MC_UUID)).toBeNull()
  })
})

describe("MinecraftFace - the name and the head, never the uuid", () => {
  it("shows the name without ever drawing the uuid", () => {
    render(<MinecraftFace mcUuid={MC_UUID} mcName="AliceMC" now={NOW} />)

    expect(screen.getByText("AliceMC")).toBeTruthy()
    expect(document.body.textContent).not.toContain(MC_UUID)
  })

  it("says a name has not been observed yet, rather than showing nothing", () => {
    render(<MinecraftFace mcUuid={MC_UUID} now={NOW} />)

    // Shortened from "no name observed yet" on 2026-09-17 (steward/103): the long form was drawn
    // as `no name observed ye` at 390px. It now truncates properly as well, but a fallback label
    // that has to truncate to fit is a label chosen too long.
    expect(screen.getByText(/no name yet/i)).toBeTruthy()
  })
})
