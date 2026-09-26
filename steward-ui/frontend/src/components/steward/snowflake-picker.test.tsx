import { cleanup, fireEvent, render, screen, within } from "@testing-library/react"
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest"

import { SnowflakePicker, withUnknown } from "@/components/steward/snowflake-picker"
import { discordId } from "@/components/steward/configuration"
import type { ConfigEntry, GuildList } from "@/lib/api"

/**
 * Picking a Discord id by name, and the two ways that must never make things worse.
 *
 * The picker exists because an eighteen-digit id typed by hand is a transcription with no feedback:
 * the wrong one is still a valid snowflake, so nothing refuses it and the first sign of the mistake
 * is a message in a channel nobody meant. Both tests below are about what happens when the list of
 * names cannot be had - because a configuration page that loses the ability to set an id when
 * Discord is unreachable would be a worse page than the text field it replaced.
 *
 * steward/53 adds the other half of Till's rule (steward/45): identifiers stay out of this list's
 * rendering entirely, but a snowflake pasted from Discord's own "Copy ID" still has to find its
 * row - the rule is about what is drawn, never about what can be searched.
 */

// jsdom has neither method - the same gap `vitest.setup.ts` already patches for ResizeObserver and
// scrollIntoView. Radix Select's trigger calls both on pointer down, so without this an open()
// throws instead of drawing the popup, in every test below that needs the list open.
beforeAll(() => {
  if (!Element.prototype.hasPointerCapture) {
    Element.prototype.hasPointerCapture = () => false
  }
  if (!Element.prototype.setPointerCapture) {
    Element.prototype.setPointerCapture = () => {}
  }
  if (!Element.prototype.releasePointerCapture) {
    Element.prototype.releasePointerCapture = () => {}
  }
})

afterEach(cleanup)

const ROLES: GuildList = {
  available: true,
  entries: [
    { id: "100000000000000001", name: "Admin", type: null },
    { id: "100000000000000002", name: "Donor", type: null },
  ],
}

/** Opens the picker's popup the way a pointer does - see the `beforeAll` polyfill above. */
function open(trigger: HTMLElement) {
  fireEvent.pointerDown(trigger, { button: 0, ctrlKey: false })
  fireEvent.click(trigger)
}

function entry(over: Partial<ConfigEntry>): ConfigEntry {
  return {
    path: "roles.admin",
    key: "admin",
    label: "Admin",
    comments: [],
    explanation: "",
    noExplanationNeeded: false,
    filled: true,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    line: 1,
    editable: true,
    secret: false,
    inSchema: true,
    ...over,
  }
}

describe("SnowflakePicker", () => {
  it("falls back to a field that still writes the id when the guild cannot be listed", () => {
    // No bot token, an unreachable Discord, a rate limit - all of them arrive here the same way,
    // and none of them may take away the ability to configure the id.
    const onChange = vi.fn()
    render(
      <SnowflakePicker
        id="roles.admin"
        value="214906139328839681"
        directory={{ available: false, reason: "discord.bot-token is not set.", entries: [] }}
        what="role"
        disabled={false}
        onChange={onChange}
      />,
    )

    const field = screen.getByDisplayValue("214906139328839681") as HTMLInputElement
    expect(field.disabled).toBe(false)
    expect(screen.getByText(/discord\.bot-token is not set/)).toBeTruthy()
    // And it says where to get the id by hand, which is the thing nobody remembers.
    expect(screen.getByText(/Developer Mode/)).toBeTruthy()
  })

  it("keeps an id the guild did not list rather than silently dropping it", () => {
    // A channel the bot cannot see, or one deleted since it was configured, is still the value in
    // the file. Replacing it with "none" the moment the page draws is the exact failure this
    // component exists to prevent, only faster and without anybody pressing anything.
    const offered = withUnknown(ROLES.entries, "999999999999999999")

    expect(offered).toHaveLength(3)
    expect(offered[0].id).toBe("999999999999999999")
    expect(offered[0].name).toMatch(/not in this guild, or not visible to the bot/)
  })

  it("adds nothing when the id is one the guild listed, or when there is none", () => {
    expect(withUnknown(ROLES.entries, "100000000000000002")).toHaveLength(2)
    expect(withUnknown(ROLES.entries, "")).toHaveLength(2)
  })

  it("shows only the name in the open list, never the id sitting next to it", () => {
    render(
      <SnowflakePicker id="roles.admin" value="" directory={ROLES} what="role" disabled={false} onChange={vi.fn()} />,
    )
    open(screen.getByRole("combobox"))

    expect(screen.getByText("Admin")).toBeTruthy()
    expect(screen.getByText("Donor")).toBeTruthy()
    // Till's rule (steward/45, applied by steward/53): a known entry's row carries no snowflake at
    // all, not even in a muted corner - the whole popup's text is checked, not just one row's.
    expect(screen.queryByText("100000000000000001")).toBeNull()
    expect(screen.queryByText("100000000000000002")).toBeNull()
    expect(document.body.textContent).not.toMatch(/\b\d{17,20}\b/)
  })

  it("finds an entry by the id pasted from Discord's own 'Copy ID', though the id is never shown", () => {
    render(
      <SnowflakePicker id="roles.admin" value="" directory={ROLES} what="role" disabled={false} onChange={vi.fn()} />,
    )
    open(screen.getByRole("combobox"))

    const search = screen.getByRole("searchbox", { name: /search/i })
    fireEvent.change(search, { target: { value: "100000000000000002" } })

    expect(screen.getByText("Donor")).toBeTruthy()
    expect(screen.queryByText("Admin")).toBeNull()
  })

  it("still finds an entry by typing its name, unaffected by the id search", () => {
    render(
      <SnowflakePicker id="roles.admin" value="" directory={ROLES} what="role" disabled={false} onChange={vi.fn()} />,
    )
    open(screen.getByRole("combobox"))

    const search = screen.getByRole("searchbox", { name: /search/i })
    fireEvent.change(search, { target: { value: "admin" } })

    expect(screen.getByText("Admin")).toBeTruthy()
    expect(screen.queryByText("Donor")).toBeNull()
  })

  it("saves the snowflake, never the name, when an option is picked", () => {
    const onChange = vi.fn()
    render(
      <SnowflakePicker id="roles.admin" value="" directory={ROLES} what="role" disabled={false} onChange={onChange} />,
    )
    open(screen.getByRole("combobox"))
    fireEvent.click(screen.getByText("Donor"))

    expect(onChange).toHaveBeenCalledWith("100000000000000002")
  })

  it("marks an id the guild did not list as a missing name, not as an ordinary row", () => {
    render(
      <SnowflakePicker
        id="roles.admin"
        value="999999999999999999"
        directory={ROLES}
        what="role"
        disabled={false}
        onChange={vi.fn()}
      />,
    )
    open(screen.getByRole("combobox"))

    // This is the one honest exception in steward/53: the guild gave no name for this id, so the
    // id itself is the only thing there is to show - but it has to read as "a name is missing"
    // rather than sit among the named rows looking like one of them.
    const row = screen.getByRole("option", { name: /999999999999999999/ })
    expect(within(row).getByText(/name unavailable/i)).toBeTruthy()
  })
})

describe("discordId", () => {
  it("keeps the chosen row in the list while a search hides the others, so the control keeps its name", () => {
    // Orchestrator's acceptance of steward/53, and it found a real one. The filter unmounts every
    // row it does not match, and the first version made no exception for the chosen one - so
    // typing four letters emptied the control the user was looking at, and told them their setting
    // had no value. Radix reads the closed trigger's text off the mounted item; unmount it and the
    // name is gone.
    //
    // The fix is the behaviour a person would expect anyway: what is set now stays in the list, no
    // matter what is typed. Seen red first - `expect(trigger.textContent).toContain("Admin")`
    // failed with "Received - Admin", an empty trigger.
    const three: GuildList = {
      available: true,
      entries: [
        { id: "100000000000000001", name: "Admin", type: null },
        { id: "100000000000000002", name: "Donor", type: null },
        { id: "100000000000000003", name: "Moderator", type: null },
      ],
    }
    render(
      <SnowflakePicker
        id="roles.admin"
        value="100000000000000001"
        directory={three}
        what="role"
        disabled={false}
        onChange={vi.fn()}
      />,
    )
    const trigger = screen.getByRole("combobox")
    expect(trigger.textContent).toContain("Admin")

    open(trigger)
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Donor" } })

    expect(screen.getByRole("option", { name: /Donor/ })).toBeTruthy()
    expect(screen.queryByRole("option", { name: /Moderator/ })).toBeNull()
    expect(screen.getByRole("option", { name: /Admin/ })).toBeTruthy()
    expect(trigger.textContent).toContain("Admin")
  })

  it("offers a picker for the keys that hold a role or a channel", () => {
    expect(discordId(entry({ path: "roles.admin", key: "admin" }))).toBe("role")
    expect(discordId(entry({ path: "languages.role", key: "role" }))).toBe("role")
    expect(discordId(entry({ path: "channels.admin", key: "admin" }))).toBe("channel")
    expect(discordId(entry({ path: "languages.link-channel", key: "link-channel" }))).toBe("channel")
  })

  it("leaves status-channel alone, because it holds a channel NAME rather than an id (steward/61)", () => {
    // `key.endsWith("-channel")` used to catch this one too, and `SnowflakePicker` writes back an
    // id - so saving through it would have replaced the channel's name with a snowflake the bot
    // then searches for and never finds. Seen red first: this assertion failed with
    // `expected null to be 'channel'` before the exclusion was added.
    expect(discordId(entry({ path: "languages.status-channel", key: "status-channel" }))).toBeNull()
  })

  it("decides on the key and not on the value, because an empty key is the one needing help", () => {
    expect(discordId(entry({ path: "roles.donor", key: "donor", value: "", filled: false }))).toBe("role")
  })

  it("leaves guild-id alone, because the list is read from the guild", () => {
    // Offering to pick the guild out of a list that only exists once the guild is known is
    // circular, and would draw an empty select on the one field that has to be typed.
    expect(discordId(entry({ path: "guild-id", key: "guild-id" }))).toBeNull()
  })

  it("never replaces a secret, a list or a read-only row with a picker", () => {
    expect(discordId(entry({ path: "roles.admin", key: "admin", secret: true }))).toBeNull()
    expect(discordId(entry({ path: "roles.admin", key: "admin", kind: "LIST" }))).toBeNull()
    expect(discordId(entry({ path: "roles.admin", key: "admin", editable: false }))).toBeNull()
  })

  it("leaves an ordinary key as the field it was", () => {
    expect(discordId(entry({ path: "donation-cents", key: "donation-cents" }))).toBeNull()
    expect(discordId(entry({ path: "guild-id", key: "tag" }))).toBeNull()
  })
})
