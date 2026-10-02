import { cleanup, fireEvent, render, screen, within } from "@testing-library/react"
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest"

import { SnowflakePicker, withUnknown } from "@/components/steward/snowflake-picker"
import { discordId } from "@/components/steward/configuration"
import type { ConfigEntry, GuildList } from "@/lib/api"
import { asInput } from "@/lib/test-elements"

/**
 * Picking a Discord id by name, which must still work without the list and never draw an id.
 *
 * A snowflake pasted from "Copy ID" still finds its row, as the rule is about drawing, not searching.
 */

/** Radix Select's trigger calls pointer capture methods on pointer down, which jsdom lacks. */
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
    { id: "100000000000000001", name: "Admin" },
    { id: "100000000000000002", name: "Donor" },
  ],
}

/** Opens the popup the way a pointer does, relying on the polyfill above. */
function open(trigger: HTMLElement) {
  fireEvent.pointerDown(trigger, { button: 0, ctrlKey: false })
  fireEvent.click(trigger)
}

function entry(over: Partial<ConfigEntry>): ConfigEntry {
  return {
    path: "roles.admin",
    key: "admin",
    label: "Admin",
    explanation: "",
    noExplanationNeeded: false,
    filled: true,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    editable: true,
    secret: false,
    environmentOverridden: false,
    ...over,
  }
}

describe("SnowflakePicker", () => {
  it("falls back to a field that still writes the id when the guild cannot be listed", () => {
    /** Every way the list can fail arrives the same, and none may take away setting the id. */
    const onChange = vi.fn<(value: string) => void>()
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

    const field = asInput(screen.getByDisplayValue("214906139328839681"))
    expect(field.disabled).toBe(false)
    expect(screen.getByText(/discord\.bot-token is not set/)).toBeTruthy()
    // It says where to get the id by hand.
    expect(screen.getByText(/Developer Mode/)).toBeTruthy()
  })

  it("keeps an id the guild did not list rather than silently dropping it", () => {
    /** A channel the bot cannot see is still the file's value, and must not become "none" on drawing. */
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
      <SnowflakePicker
        id="roles.admin"
        value=""
        directory={ROLES}
        what="role"
        disabled={false}
        onChange={vi.fn<(value: string) => void>()}
      />,
    )
    open(screen.getByRole("combobox"))

    expect(screen.getByText("Admin")).toBeTruthy()
    expect(screen.getByText("Donor")).toBeTruthy()
    /** No known row carries a snowflake anywhere in the popup's text. */
    expect(screen.queryByText("100000000000000001")).toBeNull()
    expect(screen.queryByText("100000000000000002")).toBeNull()
    expect(document.body.textContent).not.toMatch(/\b\d{17,20}\b/)
  })

  it("finds an entry by the id pasted from Discord's own 'Copy ID', though the id is never shown", () => {
    render(
      <SnowflakePicker
        id="roles.admin"
        value=""
        directory={ROLES}
        what="role"
        disabled={false}
        onChange={vi.fn<(value: string) => void>()}
      />,
    )
    open(screen.getByRole("combobox"))

    const search = screen.getByRole("searchbox", { name: /search/i })
    fireEvent.change(search, { target: { value: "100000000000000002" } })

    expect(screen.getByText("Donor")).toBeTruthy()
    expect(screen.queryByText("Admin")).toBeNull()
  })

  it("still finds an entry by typing its name, unaffected by the id search", () => {
    render(
      <SnowflakePicker
        id="roles.admin"
        value=""
        directory={ROLES}
        what="role"
        disabled={false}
        onChange={vi.fn<(value: string) => void>()}
      />,
    )
    open(screen.getByRole("combobox"))

    const search = screen.getByRole("searchbox", { name: /search/i })
    fireEvent.change(search, { target: { value: "admin" } })

    expect(screen.getByText("Admin")).toBeTruthy()
    expect(screen.queryByText("Donor")).toBeNull()
  })

  it("saves the snowflake, never the name, when an option is picked", () => {
    const onChange = vi.fn<(value: string) => void>()
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
        onChange={vi.fn<(value: string) => void>()}
      />,
    )
    open(screen.getByRole("combobox"))

    /** An id the guild gave no name for is shown, but reads as a missing name. */
    const row = screen.getByRole("option", { name: /999999999999999999/ })
    expect(within(row).getByText(/name unavailable/i)).toBeTruthy()
  })
})

describe("discordId", () => {
  it("keeps the chosen row in the list while a search hides the others, so the control keeps its name", () => {
    /** What is set stays in the list whatever is typed, since the closed trigger reads its text off the item. */
    const three: GuildList = {
      available: true,
      entries: [
        { id: "100000000000000001", name: "Admin" },
        { id: "100000000000000002", name: "Donor" },
        { id: "100000000000000003", name: "Moderator" },
      ],
    }
    render(
      <SnowflakePicker
        id="roles.admin"
        value="100000000000000001"
        directory={three}
        what="role"
        disabled={false}
        onChange={vi.fn<(value: string) => void>()}
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

  it("leaves status-channel alone, because it holds a channel NAME rather than an id", () => {
    /** `-channel` keys that hold a name must not get a picker that writes back an id. */
    expect(discordId(entry({ path: "languages.status-channel", key: "status-channel" }))).toBeNull()
  })

  it("decides on the key and not on the value, because an empty key is the one needing help", () => {
    expect(discordId(entry({ path: "roles.donor", key: "donor", value: "", filled: false }))).toBe("role")
  })

  it("leaves guild-id alone, because the list is read from the guild", () => {
    /** The guild cannot be picked from a list that needs the guild first. */
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
