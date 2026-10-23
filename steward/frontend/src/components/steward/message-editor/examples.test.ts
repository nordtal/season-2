import { describe, expect, it } from "vitest"

import type { MessageArg } from "@/lib/api"

import { argsOf } from "@/components/steward/document-fields"

import { fillOf, sentOf } from "./examples"

const what: MessageArg = {
  name: "what",
  kind: "message",
  global: false,
  example: "restart.what.network",
  action: false,
  exampleWords: { en: "The network", fr: "Le réseau" },
  exampleValue: { kind: "message", value: { key: "restart.what.network", args: {} } },
}

const took: MessageArg = {
  name: "took",
  kind: "duration",
  global: false,
  example: "PT2M",
  action: false,
  exampleWords: {},
  exampleValue: { kind: "duration", value: 120 },
}

const subjects: MessageArg = {
  name: "subjects",
  kind: "list",
  global: false,
  example: "database dump,disk",
  action: false,
  exampleWords: {},
  exampleValue: {
    kind: "list",
    value: [
      { kind: "text", value: "database dump" },
      { kind: "text", value: "disk" },
    ],
  },
}

describe("fillOf", () => {
  it("reads a nested message as its words in the language asked for, else in English", () => {
    expect(fillOf([what], undefined, "fr")("what")).toBe("Le réseau")
    expect(fillOf([what], undefined, "nl")("what")).toBe("The network")
  })

  it("shows an example as the game and the bot render its kind, in the style the text asks for", () => {
    const fill = fillOf([took, subjects], undefined, "en")
    expect(fill("took")).toBe("2 minutes")
    expect(fill("took", "clock")).toBe("2:00")
    expect(fill("subjects")).toBe("database dump and disk")
    expect(fill("subjects", "or")).toBe("database dump or disk")
  })
})

describe("a document's values", () => {
  it("show each example as its kind renders it, as a message's do", () => {
    const args = argsOf({ left: { kind: "duration", example: "PT2M", exampleValue: { kind: "duration", value: 120 } } })
    expect(fillOf(args, undefined, "en")("left")).toBe("2 minutes")
  })
})

describe("sentOf", () => {
  it("sends the server each example as written, a nested message as its key", () => {
    const sent = sentOf([what, took], undefined)
    expect(sent("what")).toBe("restart.what.network")
    expect(sent("took")).toBe("PT2M")
  })
})
