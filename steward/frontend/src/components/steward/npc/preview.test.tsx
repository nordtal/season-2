import { cleanup, render } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import type { Npc, NpcTextMeasurement } from "@/lib/api"
import { blankNpc } from "@/lib/npc"
import { measureKey } from "@/lib/queries-npcs"
import { DialoguePreview, PlateSketch } from "@/components/steward/npc/preview"

const ARTWORK = "00000000-0000-0000-0000-0000000000cc"

function npc(change: Partial<Npc>): Npc {
  const blank = blankNpc("00000000-0000-0000-0000-000000000001")
  return { ...blank, name: { en: "Smith", de: "Schmied" }, ...change }
}

/** The symbols drawn, by the column of the sheet each shows. */
function symbols(container: HTMLElement): string[] {
  return [...container.querySelectorAll("svg[viewBox$=' 0 7 7']")].map(
    (symbol) => symbol.getAttribute("viewBox")?.split(" ")[0] ?? "",
  )
}

function preview(shown: Npc, measured?: Map<string, NpcTextMeasurement>): HTMLElement {
  const step = shown.dialogue.steps[0]
  return render(<DialoguePreview npc={shown} step={step} language="en" measured={measured} />).container
}

describe("the NPC's plate and dialogue preview", () => {
  afterEach(cleanup)

  it("puts the chosen symbol on the plate, from the pack's sheet", () => {
    const { container } = render(<PlateSketch npc={npc({ plate: { colour: "RED", icon: "SHIELD" } })} />)
    expect(symbols(container)).toEqual(["49"])
  })

  it("draws a plate without a symbol without one", () => {
    const { container } = render(<PlateSketch npc={npc({ plate: { colour: "TEAL" } })} />)
    expect(symbols(container)).toEqual([])
  })

  it("draws the tab in the plate's colour and symbol", () => {
    const container = preview(npc({ plate: { colour: "RED", icon: "HEART" } }))
    expect(container.querySelector("rect[fill='#A83232']")).not.toBeNull()
    expect(symbols(container)).toEqual(["91"])
  })

  it("shows the uploaded picture in the portrait instead of the skin's face", () => {
    const container = preview(npc({ artwork: ARTWORK, skin: "00000000-0000-0000-0000-0000000000bb" }))
    const pictures = [...container.querySelectorAll("image")].map((image) => image.getAttribute("href"))
    expect(pictures).toContain(`/api/npc-artworks/${ARTWORK}`)
    expect(container.querySelector("svg[viewBox='0 0 36 36']"), "no bust drawn from the skin").toBeNull()
  })

  it("lays the uploaded picture on an empty well, so no default bust shows through it", () => {
    const container = preview(npc({ artwork: ARTWORK }))
    const well = container.querySelector("svg[aria-label] rect[fill='#8b8b8b']")
    const picture = container.querySelector(`image[href='/api/npc-artworks/${ARTWORK}']`)
    expect(well).not.toBeNull()
    expect(picture).not.toBeNull()
    expect(well!.compareDocumentPosition(picture!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("draws a skin's own bust on an empty well, outlined as the game outlines it", () => {
    const container = preview(npc({ skin: "00000000-0000-0000-0000-0000000000bb" }))
    const well = container.querySelector("svg[aria-label] rect[fill='#8b8b8b']")
    const bust = container.querySelector("svg[viewBox='0 0 36 36']")
    expect(well).not.toBeNull()
    expect(bust).not.toBeNull()
    expect(well!.compareDocumentPosition(bust!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(bust!.querySelector("feFlood[flood-color='#101014']"), "the bust's dark outline").not.toBeNull()
  })

  it("keeps the pack's default portrait for an NPC in the default skin", () => {
    expect(preview(npc({})).querySelector("rect[fill='#8b8b8b']")).toBeNull()
  })

  it("crops tightly around the dialogue panel without sky, grass or full-body figure", () => {
    const container = preview(npc({}))
    const svg = container.querySelector("svg[role='img']")
    expect(svg?.getAttribute("viewBox")).toBe("0 0 316 87")
    expect(container.querySelector("#npc-preview-sky")).toBeNull()
    expect(container.querySelector("rect[fill='#5d8f3f']")).toBeNull()
    expect(container.querySelector("image[href='/glyphs/npc/vignette_4.png']")).toBeNull()
    expect(container.querySelector("image[href='/glyphs/npc/band_4.png']")).not.toBeNull()
    expect(container.querySelector("image[href='/glyphs/npc/mouse_right.png']")).not.toBeNull()
  })

  it("draws fitted overflow lines with ellipsis for body, name and answer", () => {
    const base = npc({})
    const shown: Npc = {
      ...base,
      name: { en: "Very Long Speaker Name", de: "Speaker" },
      dialogue: {
        ...base.dialogue,
        steps: [
          {
            ...base.dialogue.steps[0],
            pages: [{ en: "overflow body text", de: "Body" }],
            answers: [{ text: { en: "Very Long Answer Label", de: "Answer" } }],
          },
        ],
      },
    }
    const measured = new Map<string, NpcTextMeasurement>([
      [
        measureKey({ text: "Very Long Speaker Name", kind: "NAME", answers: 0 }),
        {
          lines: ["Very Long Speaker Name"],
          fitted: ["Very Long Speaker…"],
          width: 240,
          room: 210,
          lineLimit: 1,
          fits: false,
        },
      ],
      [
        measureKey({ text: "overflow body text", kind: "BODY", answers: 1 }),
        {
          lines: ["Line 1", "Line 2", "Line 3", "Line 4", "Line 5"],
          fitted: ["Line 1", "Line 2", "Line 3", "Line 4 tail…"],
          width: 210,
          room: 226,
          lineLimit: 4,
          fits: false,
        },
      ],
      [
        measureKey({ text: "Very Long Answer Label", kind: "ANSWER", answers: 0 }),
        {
          lines: ["Very Long Answer Label"],
          fitted: ["Very Long Answer…"],
          width: 240,
          room: 213,
          lineLimit: 1,
          fits: false,
        },
      ],
    ])
    const container = preview(shown, measured)
    const texts = [...container.querySelectorAll("text")].map((node) => node.textContent)
    expect(texts).toContain("Very Long Speaker…")
    expect(texts).toContain("Line 4 tail…")
    expect(texts).toContain("Very Long Answer…")
    expect(texts).not.toContain("Line 5")
  })

  it("keeps the previous measurement while live edits wait for debounce and clears on empty text or page switch", () => {
    const base = npc({})
    const sixLines = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\nLine 6"
    const initial: Npc = {
      ...base,
      dialogue: {
        ...base.dialogue,
        steps: [
          {
            ...base.dialogue.steps[0],
            pages: [
              { en: sixLines, de: "" },
              { en: "Second page", de: "" },
            ],
          },
        ],
      },
    }
    const measured = new Map<string, NpcTextMeasurement>([
      [
        measureKey({ text: sixLines, kind: "BODY", answers: 0 }),
        {
          lines: ["Line 1", "Line 2", "Line 3", "Line 4", "Line 5", "Line 6"],
          fitted: ["Line 1", "Line 2", "Line 3", "Line 4", "Line 5", "Line 6"],
          width: 40,
          room: 226,
          lineLimit: 6,
          fits: true,
        },
      ],
    ])
    const { container, rerender } = render(
      <DialoguePreview
        npc={initial}
        step={initial.dialogue.steps[0]}
        pageIndex={0}
        language="en"
        measured={measured}
      />,
    )
    const beforeViewBox = container.querySelector("svg[role='img']")?.getAttribute("viewBox")
    expect(beforeViewBox).toBe("0 0 316 120")

    // Simulate a keystroke before the debounced /api/npc-text/measure response arrives.
    const edited: Npc = {
      ...initial,
      dialogue: {
        ...initial.dialogue,
        steps: [
          {
            ...initial.dialogue.steps[0],
            pages: [
              { en: `${sixLines}!`, de: "" },
              { en: "Second page", de: "" },
            ],
          },
        ],
      },
    }
    rerender(
      <DialoguePreview npc={edited} step={edited.dialogue.steps[0]} pageIndex={0} language="en" measured={measured} />,
    )
    expect(container.querySelector("svg[role='img']")?.getAttribute("viewBox")).toBe(beforeViewBox)

    // Clearing the text resets the kept measurement immediately.
    const cleared: Npc = {
      ...initial,
      dialogue: {
        ...initial.dialogue,
        steps: [
          {
            ...initial.dialogue.steps[0],
            pages: [
              { en: "", de: "" },
              { en: "Second page", de: "" },
            ],
          },
        ],
      },
    }
    rerender(
      <DialoguePreview
        npc={cleared}
        step={cleared.dialogue.steps[0]}
        pageIndex={0}
        language="en"
        measured={measured}
      />,
    )
    expect(container.querySelector("svg[role='img']")?.getAttribute("viewBox")).toBe("0 0 316 87")
  })

  it("clamps answer rows to MOST_ANSWERS", () => {
    const base = npc({})
    const shown: Npc = {
      ...base,
      dialogue: {
        ...base.dialogue,
        steps: [
          {
            ...base.dialogue.steps[0],
            pages: [{ en: "Pick one", de: "" }],
            answers: [
              { text: { en: "One", de: "" } },
              { text: { en: "Two", de: "" } },
              { text: { en: "Three", de: "" } },
              { text: { en: "Four", de: "" } },
            ],
          },
        ],
      },
    }
    const container = preview(shown)
    const texts = [...container.querySelectorAll("text")].map((node) => node.textContent)
    expect(texts).toContain("Three")
    expect(texts).not.toContain("Four")
  })
})
