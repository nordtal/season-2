import { describe, expect, it } from "vitest"

import type { MessageEntry, MessageSurface } from "@/lib/api"
import { groupOf, pillsOf, previewPlacesOf } from "@/lib/text-places"

const PLACES: Record<string, MessageSurface> = {
  CHAT: "GAME",
  GUI: "GAME",
  DISCORD_MESSAGE: "DISCORD",
  DISCORD_EMBED: "DISCORD",
  STEWARD: "STEWARD",
  PUSH: "STEWARD",
}

function entry(over: Partial<MessageEntry>): MessageEntry {
  return {
    bundle: "smp",
    key: "welcome",
    inBundle: true,
    texts: {},
    overrides: {},
    args: [],
    section: [],
    shown: [],
    limit: 0,
    ...over,
  }
}

describe("the pills of a text", () => {
  it("are one per place, in the key's order", () => {
    expect(pillsOf(entry({ shown: ["GUI", "DISCORD_MESSAGE", "STEWARD"] }), PLACES)).toEqual([
      { kind: "place", place: "GUI" },
      { kind: "place", place: "DISCORD_MESSAGE" },
      { kind: "place", place: "STEWARD" },
    ])
  })

  it("are one for a whole surface whose every place shows the text", () => {
    const everywhere = entry({ shown: ["CHAT", "STEWARD", "GUI", "DISCORD_MESSAGE", "PUSH", "DISCORD_EMBED"] })
    expect(pillsOf(everywhere, PLACES)).toEqual([
      { kind: "surface", surface: "GAME" },
      { kind: "surface", surface: "STEWARD" },
      { kind: "surface", surface: "DISCORD" },
    ])
  })
})

describe("the group and the previews of a text", () => {
  it("go by its first place", () => {
    expect(groupOf(entry({ shown: ["DISCORD_EMBED", "STEWARD"] }), PLACES)).toBe("DISCORD")
    expect(previewPlacesOf(entry({ shown: ["DISCORD_EMBED", "STEWARD"] }))).toEqual(["DISCORD_EMBED", "STEWARD"])
  })

  it("make a value a building block with one plain preview, though every place shows it", () => {
    const value = entry({ bundle: "values", shown: ["CHAT", "GUI", "DISCORD_MESSAGE"] })
    expect(groupOf(value, PLACES)).toBe("VALUES")
    expect(previewPlacesOf(value)).toEqual([undefined])
  })
})
