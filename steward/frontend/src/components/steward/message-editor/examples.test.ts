import { describe, expect, it } from "vitest"

import type { MessageArg, MessageEntry } from "@/lib/api"
import { wordsOf } from "@/lib/message-text"

import { argsOf } from "@/components/steward/document-fields"

import { fillOf, sentOf } from "./examples"

const what: MessageArg = {
  name: "what",
  kind: "message",
  global: false,
  example: "restart.what.network",
  action: false,
  exampleValue: { kind: "message", value: { key: "restart.what.network", args: {} } },
}

const took: MessageArg = {
  name: "took",
  kind: "duration",
  global: false,
  example: "PT2M",
  action: false,
  exampleValue: { kind: "duration", value: 120 },
}

const subjects: MessageArg = {
  name: "subjects",
  kind: "list",
  global: false,
  example: "database dump,disk",
  action: false,
  exampleValue: {
    kind: "list",
    value: [
      { kind: "text", value: "database dump" },
      { kind: "text", value: "disk" },
    ],
  },
}

describe("fillOf", () => {
  it("shows an example as the game and the bot render its kind, in the style the text asks for", () => {
    const fill = fillOf([took, subjects], undefined)
    expect(fill("took")).toBe("2 minutes")
    expect(fill("took", "clock")).toBe("2:00")
    expect(fill("subjects")).toBe("database dump and disk")
    expect(fill("subjects", "or")).toBe("database dump or disk")
  })
})

describe("a document's values", () => {
  it("show each example as its kind renders it, as a message's do", () => {
    const args = argsOf({ left: { kind: "duration", example: "PT2M", exampleValue: { kind: "duration", value: 120 } } })
    expect(fillOf(args, undefined)("left")).toBe("2 minutes")
  })
})

const values = (key: string, sv: string, en: string): MessageEntry => ({
  key,
  bundle: "values",
  texts: { sv: [sv], en: [en] },
  overrides: {},
  inBundle: true,
  args: [],
  section: [],
  shown: [],
  limit: 0,
})

describe("a preview in another language", () => {
  const entries = [
    values(
      "values.duration.minutes",
      "{n, plural, one {# minut} other {# minuter}}",
      "{n, plural, one {# minute} other {# minutes}}",
    ),
    values("values.list.and", "{rest} och {last}", "{rest} and {last}"),
    { ...values("restart.what.network", "Databasen", "The network"), bundle: "restart" },
    { ...values("restart.what.server", "Servern", "The server"), bundle: "restart" },
  ]
  const day: MessageArg = {
    ...took,
    name: "day",
    kind: "instant",
    example: "2026-10-23T12:00:00Z",
    exampleValue: { kind: "instant", value: "2026-10-23T12:00:00Z" },
  }

  it("writes each example in the words and formats of the language it previews", () => {
    const fill = fillOf([took, subjects, day], undefined, wordsOf(entries, "sv"))
    expect(fill("took")).toBe("2 minuter")
    expect(fill("subjects")).toBe("database dump och disk")
    expect(fill("day", "date")).toBe("23 okt. 2026")
  })

  it("reads a nested message, alone or in a list, in the language it previews", () => {
    const changes: MessageArg = {
      ...what,
      name: "changes",
      kind: "list",
      exampleValue: {
        kind: "list",
        value: [
          { kind: "message", value: { key: "restart.what.network", args: {} } },
          { kind: "message", value: { key: "restart.what.server", args: {} } },
        ],
      },
    }
    const fill = fillOf([what, changes], undefined, wordsOf(entries, "sv"))
    expect(fill("what")).toBe("Databasen")
    expect(fill("changes")).toBe("Databasen och Servern")
    expect(fillOf([what], undefined, wordsOf(entries, "fr"))("what")).toBe("The network")
  })

  it("takes English words where the language has none, and still its own formats", () => {
    const fill = fillOf([took, day], undefined, wordsOf(entries, "fr"))
    expect(fill("took")).toBe("2 minutes")
    expect(fill("day", "date")).toBe("23 oct. 2026")
  })
})

describe("sentOf", () => {
  it("sends the server each example as written, a nested message as its key", () => {
    const sent = sentOf([what, took], undefined)
    expect(sent("what")).toBe("restart.what.network")
    expect(sent("took")).toBe("PT2M")
  })
})
