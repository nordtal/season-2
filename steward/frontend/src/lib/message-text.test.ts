import { describe, expect, it } from "vitest"

import type { MessageEntry } from "@/lib/api"
import { unknownPlaceholders } from "@/lib/message-text"

const won: MessageEntry = {
  bundle: "smp",
  key: "duel.won",
  inBundle: true,
  name: "Duel won",
  args: [
    { name: "winner.name", kind: "name", type: "player", global: false, action: false },
    { name: "server.name", kind: "text", type: "service", global: true, action: false },
  ],
  section: [],
}

describe("unknownPlaceholders", () => {
  it("knows a role's properties and the globals, and names a property the role does not have", () => {
    expect(unknownPlaceholders(won, "{winner.name} on {server.name}")).toEqual([])
    expect(unknownPlaceholders(won, "{winner.nope} {winner}")).toEqual(["{winner.nope}", "{winner}"])
  })
})
