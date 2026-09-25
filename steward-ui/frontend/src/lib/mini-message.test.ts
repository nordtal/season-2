import { describe, expect, it } from "vitest"

import type { MessageArg } from "@/lib/api"
import { previewSegments } from "@/lib/mini-message"

/** The segments as `text|colour|flags`, which is what a reader of the preview actually sees. */
function seen(source: string, args: MessageArg[] = []) {
  return previewSegments(source, args).map((segment) =>
    segment.kind === "placeholder"
      ? `{${segment.name}}`
      : [segment.text, segment.colour ?? "", [segment.bold && "b", segment.italic && "i", segment.underlined && "u"].filter(Boolean).join("")].join("|"),
  )
}

describe("previewSegments", () => {
  it("colours text by name and by hex, and closes back to the colour outside", () => {
    expect(seen("<gray>The balloon sets you down in <white>here</white>.</gray>")).toEqual([
      "The balloon sets you down in |#AAAAAA|",
      "here|#FFFFFF|",
      ".|#AAAAAA|",
    ])
    expect(seen("<#a8888b>Locked</#a8888b> <color:#8ba888>open</color>")).toEqual([
      "Locked|#a8888b|",
      " ||",
      "open|#8ba888|",
    ])
  })

  it("keeps decorations until they are closed or negated", () => {
    expect(seen("<bold>a<italic>b</italic><!bold>c</bold>")).toEqual(["a||b", "b||bi", "c||"])
    expect(seen("<b><u>x</u></b>y")).toEqual(["x||bu", "y||"])
  })

  it("draws the declared placeholders, braced and component, as placeholders", () => {
    expect(seen("<white>{world}</white> by <_player>", [{ name: "world", component: false }, { name: "_player", component: true }])).toEqual(["{world}", " by ||", "{player}"])
  })

  it("leaves an undeclared brace as text", () => {
    expect(seen("{nope} left", [])).toEqual(["{nope} left||"])
  })

  it("drops tags it does not draw but keeps what they wrap, and flattens line breaks", () => {
    expect(seen("<click:run_command:/spawn><hover:show_text:'x'>Go</hover></click><newline>on")).toEqual(["Go on||"])
    expect(seen("<gradient:#fff:#000>fade</gradient><reset>plain")).toEqual(["fadeplain||"])
  })

  it("reads an escaped angle bracket as text", () => {
    expect(seen("a \\<b> c")).toEqual(["a <b> c||"])
  })

  it("resets everything on reset", () => {
    expect(seen("<red><bold>x<reset>y")).toEqual(["x|#FF5555|b", "y||"])
  })
})
