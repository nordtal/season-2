import { cleanup, render } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { Preview } from "@/components/steward/message-editor/preview"
import { parse } from "@/lib/rich-text"

/** The game's places draw a text line by line, as the client does: spaces kept, lines stacked, no extra wrapping. */

afterEach(cleanup)

const TONES = { muted: "#AAAAAA", emphasis: "#FFFFFF" }

function draw(source: string, shown: string, fill: Record<string, string> = {}) {
  const runs = parse(source, "MINIMESSAGE", Object.keys(TONES))
  if (runs === null) throw new Error(`unreadable: ${source}`)
  const { container } = render(
    <Preview runs={runs} shown={shown} surface="GAME" fill={(name) => fill[name] ?? name} glyphs={[]} tones={TONES} />,
  )
  return [...container.querySelectorAll<HTMLElement>("[data-slot=mc-line]")]
}

describe("a game place's preview", () => {
  it("keeps the spaces between runs on a screen, each line laid out as text rather than as flex items", () => {
    const lines = draw(
      "<muted>The network opens in </muted><emphasis>{countdown}</emphasis><muted>.</muted>",
      "KICK_SCREEN",
      {
        countdown: "3 days 3 hours",
      },
    )
    expect(lines.map((line) => line.textContent)).toEqual(["The network opens in 3 days 3 hours."])
    expect(lines[0].style.whiteSpace).toMatch(/^pre/)
    expect(lines[0].style.display).toBe("block")
  })

  it("stacks the lines of a text one under the other", () => {
    const lines = draw("<muted>first line</muted><newline><emphasis>second</emphasis> line", "KICK_SCREEN")
    expect(lines.map((line) => line.textContent)).toEqual(["first line", "second line"])
    expect(lines.every((line) => line.style.display === "block")).toBe(true)
    expect(lines[0].parentElement).toBe(lines[1].parentElement)
  })

  it("does not wrap a title, an action bar or a boss bar, which the game draws on one line however long", () => {
    for (const shown of ["TITLE", "SUBTITLE", "ACTION_BAR", "BOSS_BAR"]) {
      const lines = draw("A line far longer than any preview is wide, which the game draws on one line", shown)
      expect(lines).toHaveLength(1)
      expect(lines[0].style.whiteSpace).toBe("pre")
      cleanup()
    }
  })

  it("draws a line break as a space where the game holds one line, as the game's renderer joins them", () => {
    for (const shown of ["TITLE", "SUBTITLE", "ACTION_BAR", "BOSS_BAR"]) {
      const lines = draw("<muted>first</muted><newline>second", shown)
      expect(lines.map((line) => line.textContent)).toEqual(["first second"])
      cleanup()
    }
  })
})
