import { describe, expect, it } from "vitest"

import type { MessageArg } from "@/lib/api"

import { exampleOf } from "./examples"

const what: MessageArg = {
  name: "what",
  kind: "message",
  global: false,
  example: "restart.what.network",
  action: false,
  exampleWords: { en: "The network", fr: "Le réseau" },
}

describe("exampleOf", () => {
  it("reads a nested message as its words in the language asked for, else in English", () => {
    expect(exampleOf("what", [what], undefined, "fr")).toBe("Le réseau")
    expect(exampleOf("what", [what], undefined, "nl")).toBe("The network")
  })

  it("keeps a nested message's key where no language is asked for", () => {
    expect(exampleOf("what", [what], undefined)).toBe("restart.what.network")
  })
})
