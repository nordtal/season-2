import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"

afterEach(() => {
  cleanup()
  window.innerWidth = 1024
})

function draw() {
  render(
    <ResponsiveDialog open>
      <ResponsiveDialogContent>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Which one is this</ResponsiveDialogTitle>
        </ResponsiveDialogHeader>
      </ResponsiveDialogContent>
    </ResponsiveDialog>,
  )
  return screen.getByText("Which one is this")
}

/** Which primitive mounts, read from `data-slot` since jsdom has no layout; its window is 1024 wide, a desktop. */
describe("one dialog, two shapes", () => {
  it("is a centred dialog on a desktop", async () => {
    window.innerWidth = 1024
    const title = draw()

    expect(title.closest("[data-slot='dialog-content']")).not.toBeNull()
    expect(title.closest("[data-slot='drawer-content']")).toBeNull()
  })

  it("is a bottom sheet on a phone", async () => {
    window.innerWidth = 390
    const title = draw()

    expect(title.closest("[data-slot='drawer-content']")).not.toBeNull()
    expect(title.closest("[data-slot='dialog-content']")).toBeNull()
  })

  it("keeps the title a title in both, so a screen reader is told the same thing", async () => {
    window.innerWidth = 390
    expect(draw().getAttribute("data-slot")).toBe("drawer-title")
    cleanup()

    window.innerWidth = 1024
    expect(draw().getAttribute("data-slot")).toBe("dialog-title")
  })
})
