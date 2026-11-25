import { cleanup, render } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import type { Npc } from "@/lib/api"
import { blankNpc } from "@/lib/npc"
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

function preview(shown: Npc): HTMLElement {
  const step = shown.dialogue.steps[0]
  return render(<DialoguePreview npc={shown} step={step} language="en" measured={undefined} />).container
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
})
