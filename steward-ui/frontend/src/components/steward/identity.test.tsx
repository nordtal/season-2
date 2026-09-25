import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import {
  IDENTIFIER_PATTERN,
  PersonIdentity,
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
    render(
      <PersonIdentity discordId={DISCORD_ID} discordUsername="alice" />,
    )

    expect(screen.getByText("alice")).toBeTruthy()
    expect(document.body.textContent).not.toContain(DISCORD_ID)
  })

  it("falls back to readable text, not the raw id, for an account with no observed name", () => {
    // A former guild member, or one nobody has mirrored a profile onto yet - both real states,
    // neither an excuse to fall back to the identifier the whole rule exists to hide.
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

describe("PersonIdentity - system, steward/82", () => {
  it("shows Steward and never opens a popover, since there is no id behind it", () => {
    render(<PersonIdentity system />)

    expect(screen.getByText("Steward")).toBeTruthy()
    expect(screen.queryByRole("button")).toBeNull()
  })

  it("ignores a person's fields when system is set, rather than drawing them beside Steward", () => {
    render(
      <PersonIdentity
        system
        discordId={DISCORD_ID}
        discordDisplayName="Ally"
      />,
    )

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
    expect((field as HTMLInputElement).value).toBe(DISCORD_ID)
  })

  it("reveals the Minecraft uuid as well when the account is linked", async () => {
    render(
      <PersonIdentity
        discordId={DISCORD_ID}
        discordUsername="alice"
        mcUuid={MC_UUID}
        mcName="AliceMC"
      />,
    )

    fireEvent.click(screen.getByRole("button"))

    const field = await screen.findByLabelText("Minecraft-UUID")
    expect((field as HTMLInputElement).value).toBe(MC_UUID)
  })

  it("says plainly that nothing is linked, rather than offering a uuid field with nothing in it", async () => {
    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" />)

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

    render(<PersonIdentity discordId={DISCORD_ID} discordUsername="alice" />)
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

describe("PersonIdentity - nothing half-written, and no mark nobody can see", () => {
  /**
   * steward/123, Till on 2026-09-19: the popover said "last confirmed -" under the Discord name
   * and "last seen -" under the Minecraft name whenever there was no timestamp, and both names
   * carried a ` *` when the timestamp was old.
   *
   * The asterisk was the only thing that ANNOUNCED the staleness mark - the `title` tooltip behind
   * it is invisible until hovered and unreachable on a phone, and the `data-stale` attribute was
   * read by no stylesheet. So all of it went together rather than leaving an attribute and a
   * tooltip pretending to be a quieter version of a feature Till removed.
   */
  it("writes no line under the Discord name, whether the name is fresh or ancient", () => {
    render(<PersonIdentity discordId={DISCORD_ID} discordDisplayName="Ally" />)
    fireEvent.click(screen.getByRole("button"))

    expect(screen.queryByText(/confirmed/i)).toBeNull()
    // The en dash `format.ts` writes when it has nothing to format. In a table cell it means "no
    // value" and everybody reads it that way; behind a word it is a half-written sentence.
    expect(document.body.textContent).not.toContain("\u2013")
  })

  it("writes no line under the Minecraft name either", () => {
    render(
      <PersonIdentity discordId={DISCORD_ID} discordDisplayName="Ally" mcUuid={MC_UUID} mcName="AllyMC" />,
    )
    fireEvent.click(screen.getByRole("button"))

    expect(screen.queryByText(/\bseen\b/i)).toBeNull()
  })

  it("draws no asterisk after either name, and no mark for a stylesheet to find", () => {
    render(
      <PersonIdentity discordId={DISCORD_ID} discordDisplayName="Ally" mcUuid={MC_UUID} mcName="AllyMC" />,
    )
    fireEvent.click(screen.getByRole("button"))

    expect(document.body.textContent).not.toContain("*")
    expect(document.querySelector("[data-stale]")).toBeNull()
    expect(document.querySelector("[title*='Not confirmed']")).toBeNull()
  })

  it("still says so when there is genuinely nothing on record - that is a sentence, not a stub", () => {
    // The rule is not "never write anything", it is "never write half of something". "Never
    // observed" is a complete answer and stays; "last confirmed -" was not one and went.
    render(<PersonIdentity discordId={DISCORD_ID} />)
    fireEvent.click(screen.getByRole("button"))

    expect(screen.getAllByText("no Discord name on record").length).toBeGreaterThan(0)
    expect(screen.getByText(/never observed/i)).toBeTruthy()
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
   * steward/122, Till on 2026-09-19: api.mineatar.io is the standard endpoint for Minecraft
   * avatars from now on. Two things about that were measured against the real service on the same
   * day rather than inherited from the mc-heads era:
   *
   * - **Both uuid spellings answer 200**, on mineatar, mc-heads and crafatar alike. The hyphens
   *   still come out, but the reason is no longer compatibility - it is that one spelling has to
   *   be picked and a stable URL is a cached one.
   * - **The blank endpoint serves 32x32.** A head is drawn at up to 32 CSS pixels, which is 96
   *   real ones on a 3x phone, so the configured default carries `?scale=16` (128x128, 472 bytes).
   *   That is the whole reason the base can have a query at all, and the reason the uuid has to be
   *   inserted BEFORE it.
   */
  const UNDASHED = MC_UUID.replace(/-/g, "")
  const MINEATAR = "https://api.mineatar.io/face"

  it("composes the uuid onto the configured base, without its hyphens", () => {
    expect(minecraftHeadUrl(MINEATAR, MC_UUID)).toBe(`${MINEATAR}/${UNDASHED}`)
  })

  it("puts the uuid in the path and keeps the query behind it", () => {
    // The default base. Appending blindly would give `…/face?scale=16/<uuid>` - a URL that is
    // still a URL, still fetched, and never an image of this player.
    expect(minecraftHeadUrl(`${MINEATAR}?scale=16`, MC_UUID)).toBe(
      `${MINEATAR}/${UNDASHED}?scale=16`,
    )
  })

  it("keeps a query with several parameters whole", () => {
    expect(minecraftHeadUrl(`${MINEATAR}?scale=16&overlay=true`, MC_UUID)).toBe(
      `${MINEATAR}/${UNDASHED}?scale=16&overlay=true`,
    )
  })

  it("strips a trailing slash rather than doubling it, query or no query", () => {
    expect(minecraftHeadUrl(`${MINEATAR}/`, MC_UUID)).toBe(`${MINEATAR}/${UNDASHED}`)
    expect(minecraftHeadUrl(`${MINEATAR}/?scale=16`, MC_UUID)).toBe(
      `${MINEATAR}/${UNDASHED}?scale=16`,
    )
  })

  it("leaves a uuid that already came without hyphens alone", () => {
    expect(minecraftHeadUrl(MINEATAR, UNDASHED)).toBe(`${MINEATAR}/${UNDASHED}`)
  })

  it("still works for a base pointing anywhere else, because it is configuration", () => {
    // mc-heads stood here until 2026-09-19 and a deployment whose steward-ui.yml predates the
    // change still says so - jcore preserves a written file, so the default is the NEW installation
    // and never the running one.
    expect(minecraftHeadUrl("https://mc-heads.net/avatar", MC_UUID)).toBe(
      `https://mc-heads.net/avatar/${UNDASHED}`,
    )
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

    // Shortened from "no name observed yet" on 2026-09-17 (steward/103): the long form was drawn
    // as `no name observed ye` at 390px. It now truncates properly as well, but a fallback label
    // that has to truncate to fit is a label chosen too long.
    expect(screen.getByText(/no name yet/i)).toBeTruthy()
  })
})
