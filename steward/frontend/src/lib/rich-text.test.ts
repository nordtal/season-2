import { describe, expect, it } from "vitest"

import {
  applyStyle,
  branchesOf,
  commonStyle,
  gradientAt,
  insert,
  parse,
  remove,
  serialize,
  totalLength,
  type Format,
  type Run,
} from "@/lib/rich-text"

const TONES = ["neutral", "good", "bad", "warn", "muted", "accent", "brand", "emphasis", "faint"]

function read(text: string, format: Format = "MINIMESSAGE"): Run[] {
  const runs = parse(text, format, TONES)
  if (runs === null) throw new Error(`unreadable: ${text}`)
  return runs
}

const roundTrip = (text: string, format: Format = "MINIMESSAGE") => serialize(read(text, format), format)

describe("a Minecraft text", () => {
  it("reads colours, decorations and placeholders into runs", () => {
    expect(read("<gray>Hi <bold>{player.name}</bold></gray>!")).toEqual([
      { kind: "text", text: "Hi ", style: { colour: "gray" } },
      { kind: "placeholder", name: "player.name", style: { colour: "gray", bold: true } },
      { kind: "text", text: "!", style: {} },
    ])
  })

  it("reads a tone, and a value's kind and style", () => {
    expect(read("<good>{left, duration, short}</good>")).toEqual([
      { kind: "placeholder", name: "left", k: "duration", s: "short", style: { tone: "good" } },
    ])
  })

  it("lets a colour replace the tone around it", () => {
    expect(read("<good><red>x</red></good>")).toEqual([{ kind: "text", text: "x", style: { colour: "red" } }])
  })

  it("writes back the same text it was read from, where that text was already minimal", () => {
    for (const text of [
      "<gray>The Discord</gray><newline><click:open_url:'{invite}'><#4a63d8><underlined>{invite}</underlined></#4a63d8></click>",
      "{sender} <#4e5668>|</#4e5668> <gradient:#ff0000:gold>rainbow <bold>ish</bold></gradient>",
      "<hover:show_text:'<red>it\\'s <glyph:admin></red>'>admin</hover> <italic:false>plain</italic>",
      "<hover:show_text:'{player.name}'>x</hover>",
      "<action:accept><good>Accept</good></action>",
      "a \\<b> c",
      "<rainbow>kept</rainbow>",
      "{n, plural, one {# file} other {# files}} left",
    ]) {
      expect(roundTrip(text)).toBe(text)
    }
  })

  it("keeps what no editor offers as one piece of source", () => {
    expect(read("{n, plural, one {#} other {# more}}<lang:x>")).toEqual([
      { kind: "raw", source: "{n, plural, one {#} other {# more}}", style: {} },
      { kind: "raw", source: "<lang:x>", style: {} },
    ])
  })

  it("reads nothing while the parser cannot", () => {
    expect(parse("Hi {name", "MINIMESSAGE", TONES)).toBeNull()
  })

  it("writes one tag around every run that shares a style, however the runs were split", () => {
    const runs = applyStyle(read("<red>abcdef</red>"), 2, 4, (style) => ({ ...style, bold: true }))
    expect(serialize(runs, "MINIMESSAGE")).toBe("<red>ab<bold>cd</bold>ef</red>")
  })

  it("keeps a hover's own text a message of its own", () => {
    const [run] = read("<hover:show_text:'<gold>more'>word</hover>")
    expect(run.style.hover).toEqual([{ kind: "text", text: "more", style: { colour: "gold" } }])
  })
})

/** The runs of `text` as a preview draws them, each value's example taken from `examples`. */
function drawn(text: string, examples: Record<string, string>, format: Format = "MINIMESSAGE"): Run[] {
  return branchesOf(read(text, format), format, TONES, (name) => examples[name] ?? name)
}

describe("a plural or a choice as a preview draws it", () => {
  it("draws the plural's case its example's number picks, an exact one before its category", () => {
    const text = "{n, plural, =0 {none} one {# file} other {# files}} left"
    expect(drawn(text, { n: "0" })).toEqual([{ kind: "text", text: "none left", style: {} }])
    expect(drawn(text, { n: "1" })).toEqual([
      { kind: "placeholder", name: "n", k: "number", style: {} },
      { kind: "text", text: " file left", style: {} },
    ])
    expect(drawn(text, { n: "3" })).toEqual([
      { kind: "placeholder", name: "n", k: "number", style: {} },
      { kind: "text", text: " files left", style: {} },
    ])
  })

  it("draws a plural's other case where its example is no number", () => {
    expect(drawn("{n, plural, one {one} other {many}}", { n: "a few" })).toEqual([
      { kind: "text", text: "many", style: {} },
    ])
  })

  it("draws the choice's case its example names, and its other case where the example names none", () => {
    const text = "{state, select, RUNNING {up} other {down}}"
    expect(drawn(text, { state: "RUNNING" })).toEqual([{ kind: "text", text: "up", style: {} }])
    expect(drawn(text, { state: "true" })).toEqual([{ kind: "text", text: "down", style: {} }])
  })

  it("keeps the style around the case and the tags inside it", () => {
    expect(drawn("<gray>{n, plural, one {<bold>#</bold> file} other {# files}}</gray>", { n: "1" })).toEqual([
      { kind: "placeholder", name: "n", k: "number", style: { colour: "gray", bold: true } },
      { kind: "text", text: " file", style: { colour: "gray" } },
    ])
  })

  it("draws a choice inside a case, where a # still stands for the plural's number", () => {
    const text = "{n, plural, one {one} other {{kind, select, log {# logs} other {# items}}}}"
    expect(drawn(text, { n: "4", kind: "log" })).toEqual([
      { kind: "placeholder", name: "n", k: "number", style: {} },
      { kind: "text", text: " logs", style: {} },
    ])
  })

  it("draws a Discord text's case with its markdown", () => {
    expect(drawn("{n, plural, one {**#** file} other {# files}}", { n: "1" }, "DISCORD_MARKDOWN")).toEqual([
      { kind: "placeholder", name: "n", k: "number", style: { bold: true } },
      { kind: "text", text: " file", style: {} },
    ])
  })

  it("keeps a tag no editor offers as its source", () => {
    expect(drawn("<lang:x>", {})).toEqual([{ kind: "raw", source: "<lang:x>", style: {} }])
  })
})

describe("a Discord text", () => {
  it("reads markdown and writes it back", () => {
    const text = "Team **{player.name}** is *complete* - see [the rules](https://nordtal.eu) and `code`"
    expect(roundTrip(text, "DISCORD_MARKDOWN")).toBe(text)
  })

  it("leaves a lone marker as text, and writes an escaped one back as it was", () => {
    expect(read("5 * 3", "DISCORD_MARKDOWN")).toEqual([{ kind: "text", text: "5 * 3", style: {} }])
    expect(roundTrip("5 \\* 3 \\{x\\}", "DISCORD_MARKDOWN")).toBe("5 \\* 3 \\{x\\}")
  })
})

describe("a plain text", () => {
  it("reads a line break and a value, and no tag", () => {
    expect(read("One\n<b>{count}", "PLAIN")).toEqual([
      { kind: "text", text: "One", style: {} },
      { kind: "break", style: {} },
      { kind: "text", text: "<b>", style: {} },
      { kind: "placeholder", name: "count", style: {} },
    ])
  })
})

describe("editing runs", () => {
  const runs = read("ab{invite}cd")

  it("counts a placeholder as one position", () => {
    expect(totalLength(runs)).toBe(5)
  })

  it("inserts in the style of what comes before", () => {
    const red = read("<red>ab</red>cd")
    expect(serialize(insert(red, 2, [{ kind: "text", text: "X", style: {} }]), "MINIMESSAGE")).toBe("<red>abX</red>cd")
  })

  it("removes across runs", () => {
    expect(serialize(remove(runs, 1, 4), "MINIMESSAGE")).toBe("ad")
  })

  it("tells the style a whole selection shares", () => {
    const mixed = read("<red><bold>ab</bold>cd</red>")
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
