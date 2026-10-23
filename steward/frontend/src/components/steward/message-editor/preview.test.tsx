import { cleanup, render } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { DiscordRuns, Preview } from "@/components/steward/message-editor/preview"
import { markdownRuns, parse } from "@/lib/rich-text"

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

  it("wraps a chat text, a tab list, a menu tooltip and a kick screen within the preview, as the client wraps them", () => {
    for (const shown of ["CHAT", "TAB_LIST", "GUI", "KICK_SCREEN"]) {
      const [line] = draw("A line far longer than any preview is wide, which the client wraps onto the next", shown)
      expect(line.style.whiteSpace).toBe("pre-wrap")
      // A box as wide as its longest line would push the wrap past the preview's edge.
      for (let box = line.parentElement; box; box = box.parentElement) {
        expect(`${shown}: ${box.className}`).not.toMatch(/\bw-max\b/)
      }
      cleanup()
    }
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

  it("draws a title's subtitle under it, on one line", () => {
    const runs = (source: string) => parse(source, "MINIMESSAGE", Object.keys(TONES)) ?? []
    const { container } = render(
      <Preview
        runs={runs("<emphasis>One moment</emphasis>")}
        subtitle={runs("<muted>You will be</muted><newline>connected")}
        shown="TITLE"
        surface="GAME"
        fill={(name) => name}
        glyphs={[]}
        tones={TONES}
      />,
    )
    const lines = [...container.querySelectorAll<HTMLElement>("[data-slot=mc-line]")]
    expect(lines.map((line) => line.textContent)).toEqual(["One moment", "You will be connected"])
  })
})

describe("a Discord preview", () => {
  it("breaks a code span inside its column, keeping its frame on each line", () => {
    const { getByText } = render(
      <DiscordRuns runs={markdownRuns("Join `play.nordtal.eu` now")} fill={(name) => name} />,
    )
    // jsdom has no layout, so this pins the rule; a picture at a narrow column shows the wrap.
    expect(getByText("play.nordtal.eu").className).toMatch(/\bwrap-anywhere\b/)
    expect(getByText("play.nordtal.eu").className).toMatch(/\bbox-decoration-clone\b/)
  })
})
