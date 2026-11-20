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
    const { container } = render(<PlateSketch npc={npc({ plate: { style: "PILL", colour: "RED", icon: "SHIELD" } })} />)
    expect(symbols(container)).toEqual(["49"])
  })

  it("draws a badge without a symbol with the star", () => {
    const { container } = render(<PlateSketch npc={npc({ plate: { style: "BADGE", colour: "TEAL" } })} />)
    expect(symbols(container)).toEqual(["0"])
  })

  it("draws a plate without a symbol in any other style without one", () => {
    const { container } = render(<PlateSketch npc={npc({ plate: { style: "DARK", colour: "TEAL" } })} />)
    expect(symbols(container)).toEqual([])
  })

  it("draws the tab in the plate's colour and symbol", () => {
    const container = preview(npc({ plate: { style: "PILL", colour: "RED", icon: "HEART" } }))
    expect(container.querySelector("rect[fill='#A83232']")).not.toBeNull()
    expect(symbols(container)).toEqual(["91"])
  })

  it("shows the uploaded picture in the portrait instead of the skin's face", () => {
    const container = preview(npc({ artwork: ARTWORK, skin: "00000000-0000-0000-0000-0000000000bb" }))
    const pictures = [...container.querySelectorAll("image")].map((image) => image.getAttribute("href"))
    expect(pictures).toContain(`/api/npc-artworks/${ARTWORK}`)
    expect(container.querySelector("svg[viewBox='0 0 40 40']"), "no bust drawn from the skin").toBeNull()
  })

  it("draws the skin's face without an uploaded picture", () => {
    const container = preview(npc({ skin: "00000000-0000-0000-0000-0000000000bb" }))
    expect(container.querySelector("svg[viewBox='0 0 40 40']")).not.toBeNull()
  })
})
