import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import type { NpcMenu } from "@/lib/api"
import { blankNpc } from "@/lib/npc"
import { ThenPicker } from "@/components/steward/npc/step-panel"

const menus: NpcMenu[] = [
  { key: "objectives", hint: { en: "Open objectives", de: "German objectives hint" } },
  { key: "wheel", hint: { en: "Open wheel", de: "German wheel hint" } },
]

function opened(extraSteps: { id: string; text: string }[] = []): string[] {
  const npc = {
    ...blankNpc("00000000-0000-0000-0000-000000000001"),
    dialogue: {
      start: "a",
      steps: [
        { id: "a", x: 0, y: 0, pages: [{ en: "Hello", de: "Hallo" }], answers: [] },
        ...extraSteps.map((each) => ({
          id: each.id,
          x: 0,
          y: 0,
          pages: [{ en: each.text, de: "" }],
          answers: [],
        })),
      ],
    },
  }
  render(
    <ThenPicker
      label="Then"
      then={{ to: "end" }}
      npc={npc}
      menus={menus}
      except="a"
      onThen={() => undefined}
      onNew={() => undefined}
    />,
  )
  fireEvent.keyDown(screen.getByRole("combobox", { name: "Then" }), { key: "Enter" })
  return screen.getAllByRole("option").map((option) => option.textContent?.trim() ?? "")
}

describe("the Then picker", () => {
  afterEach(cleanup)

  it("names each menu once, by its English hint", () => {
    expect(opened()).toEqual(["End the dialogue", "Open objectives", "Open wheel", "Go to a new step"])
  })

  it("shows no German hint, since the editor names menus in English", () => {
    const options = opened()
    expect(options).not.toContain("German objectives hint")
    expect(options).not.toContain("German wheel hint")
  })

  it("names steps by reading-order number and text, and empty steps as Step N", () => {
    const options = opened([
      { id: "b", text: "Trade goods" },
      { id: "c", text: "" },
    ])
    expect(options).toContain("Go to “Step 2: Trade goods”")
    expect(options).toContain("Go to “Step 3”")
    expect(options.join(" ")).not.toContain("No text")
  })
})
