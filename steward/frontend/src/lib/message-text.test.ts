import { describe, expect, it } from "vitest"

import type { MessageEntry } from "@/lib/api"
import { unknownPlaceholders } from "@/lib/message-text"

const won: MessageEntry = {
  key: "duel.won",
  inBundle: true,
  name: "Duel won",
  args: [
    { name: "winner.name", component: false, type: "player", global: false },
    { name: "server.name", component: false, type: "service", global: true },
  ],
  section: [],
}

describe("unknownPlaceholders", () => {
  it("knows a role's properties and the globals, and names a property the role does not have", () => {
    expect(unknownPlaceholders(won, "{winner.name} on {server.name}")).toEqual([])
    expect(unknownPlaceholders(won, "{winner.nope} {winner}")).toEqual(["{winner.nope}", "{winner}"])
  })
})
