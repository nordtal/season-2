import { describe, expect, it } from "vitest"

import type { MessageArg } from "@/lib/api"
import { applyStyle, commonStyle, gradientAt, insert, parse, remove, serialize, totalLength } from "@/lib/rich-text"

const ARGS: MessageArg[] = [
  { name: "invite", component: false },
  { name: "_sender", component: true },
  { name: "player.name", component: false, type: "player" },
]

const roundTrip = (text: string, format: "MINIMESSAGE" | "DISCORD_MARKDOWN" | "PLAIN" = "MINIMESSAGE") =>
  serialize(parse(text, format, ARGS), format, ARGS)

describe("a MiniMessage text", () => {
  it("reads colours, decorations and placeholders into runs", () => {
    expect(parse("<gray>Hi <bold>{player.name}</bold></gray>!", "MINIMESSAGE", ARGS)).toEqual([
      { kind: "text", text: "Hi ", style: { colour: "gray" } },
      { kind: "placeholder", name: "player.name", style: { colour: "gray", bold: true } },
      { kind: "text", text: "!", style: {} },
    ])
  })

  it("writes back the same text it was read from, where that text was already minimal", () => {
    for (const text of [
      "<gray>The Discord</gray><newline><click:open_url:'{invite}'><#4a63d8><underlined>{invite}</underlined></#4a63d8></click>",
      "<_sender> <#4e5668>|</#4e5668> <gradient:#ff0000:gold>rainbow <bold>ish</bold></gradient>",
      "<hover:show_text:'<red>it\\'s <glyph:admin></red>'>admin</hover> <italic:false>plain</italic>",
      "a \\<b> c",
      "<rainbow>kept</rainbow>",
    ]) {
      expect(roundTrip(text)).toBe(text)
    }
  })

  it("writes one tag around every run that shares a style, however the runs were split", () => {
    const runs = applyStyle(parse("<red>abcdef</red>", "MINIMESSAGE", ARGS), 2, 4, (style) => ({ ...style, bold: true }))
    expect(serialize(runs, "MINIMESSAGE", ARGS)).toBe("<red>ab<bold>cd</bold>ef</red>")
  })

  it("keeps a hover's own text a message of its own", () => {
    const [run] = parse("<hover:show_text:'<gold>more'>word</hover>", "MINIMESSAGE", ARGS)
    expect(run.style.hover).toEqual([{ kind: "text", text: "more", style: { colour: "gold" } }])
  })
})

describe("a Discord text", () => {
  it("reads markdown and writes it back", () => {
    const text = "Team **{player.name}** is *complete* - see [the rules](https://nordtal.eu) and `code`"
    expect(roundTrip(text, "DISCORD_MARKDOWN")).toBe(text)
  })

  it("leaves a lone marker as text", () => {
    expect(parse("5 * 3", "DISCORD_MARKDOWN", ARGS)).toEqual([{ kind: "text", text: "5 * 3", style: {} }])
  })
})

describe("editing runs", () => {
  const runs = parse("ab{invite}cd", "MINIMESSAGE", ARGS)

  it("counts a placeholder as one position", () => {
    expect(totalLength(runs)).toBe(5)
  })

  it("inserts in the style of what comes before", () => {
    const red = parse("<red>ab</red>cd", "MINIMESSAGE", ARGS)
    expect(serialize(insert(red, 2, [{ kind: "text", text: "X", style: {} }]), "MINIMESSAGE", ARGS)).toBe("<red>abX</red>cd")
  })

  it("removes across runs", () => {
    expect(serialize(remove(runs, 1, 4), "MINIMESSAGE", ARGS)).toBe("ad")
  })

  it("tells the style a whole selection shares", () => {
    const mixed = parse("<red><bold>ab</bold>cd</red>", "MINIMESSAGE", ARGS)
    expect(commonStyle(mixed, 0, 4)).toEqual({ colour: "red" })
    expect(commonStyle(mixed, 0, 2)).toEqual({ colour: "red", bold: true })
  })
})

describe("a gradient", () => {
  it("runs from its first colour to its last", () => {
    expect(gradientAt(["#000000", "#FFFFFF"], 0)).toBe("#000000")
    expect(gradientAt(["#000000", "#FFFFFF"], 1)).toBe("#FFFFFF")
    expect(gradientAt(["red", "#000000", "#FFFFFF"], 0.5)).toBe("#000000")
  })
})
