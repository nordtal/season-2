import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

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
 */

afterEach(cleanup)

const ROLES: GuildList = {
  available: true,
  entries: [
    { id: "100000000000000001", name: "Admin", type: null },
    { id: "100000000000000002", name: "Donor", type: null },
  ],
}

function entry(over: Partial<ConfigEntry>): ConfigEntry {
  return {
    path: "roles.admin",
    key: "admin",
    label: "Admin",
    comments: [],
    filled: true,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    line: 1,
    editable: true,
    secret: false,
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
})

describe("discordId", () => {
  it("offers a picker for the keys that hold a role or a channel", () => {
    expect(discordId(entry({ path: "roles.admin", key: "admin" }))).toBe("role")
    expect(discordId(entry({ path: "languages.role", key: "role" }))).toBe("role")
    expect(discordId(entry({ path: "channels.admin", key: "admin" }))).toBe("channel")
    expect(discordId(entry({ path: "languages.link-channel", key: "link-channel" }))).toBe("channel")
    expect(discordId(entry({ path: "languages.status-channel", key: "status-channel" })))
      .toBe("channel")
  })

  it("decides on the key and not on the value, because an empty key is the one needing help", () => {
    expect(discordId(entry({ path: "roles.donor", key: "donor", value: "", filled: false })))
      .toBe("role")
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
