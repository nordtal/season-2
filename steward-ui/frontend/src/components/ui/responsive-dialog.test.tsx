import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import {
  ResponsiveAlertDialog,
  ResponsiveAlertDialogAction,
  ResponsiveAlertDialogCancel,
  ResponsiveAlertDialogContent,
  ResponsiveAlertDialogFooter,
  ResponsiveAlertDialogHeader,
  ResponsiveAlertDialogTitle,
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

function drawConfirmation(onConfirm: () => void) {
  render(
    <ResponsiveAlertDialog open>
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>Delete it</ResponsiveAlertDialogTitle>
        </ResponsiveAlertDialogHeader>
        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction onClick={onConfirm}>Delete</ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>,
  )
  return screen.getByText("Delete it")
}

/** The confirmation takes both shapes; flicking a sheet away is an answer, like a click on the overlay. */
describe("the confirmation takes both shapes as well", () => {
  it("is an alert dialog on a desktop", () => {
    window.innerWidth = 1024
    const title = drawConfirmation(() => {})

    expect(title.closest("[data-slot='alert-dialog-content']")).not.toBeNull()
    expect(title.closest("[data-slot='drawer-content']")).toBeNull()
  })

  it("is a bottom sheet on a phone", () => {
    window.innerWidth = 390
    const title = drawConfirmation(() => {})

    expect(title.closest("[data-slot='drawer-content']")).not.toBeNull()
    expect(title.closest("[data-slot='alert-dialog-content']")).toBeNull()
  })

  it("still runs the action on a phone, which is the whole point of converting it", () => {
    window.innerWidth = 390
    let confirmed = 0
    drawConfirmation(() => {
      confirmed += 1
    })

    fireEvent.click(screen.getByRole("button", { name: "Delete" }))

    expect(confirmed).toBe(1)
  })

  it("keeps both buttons buttons in both shapes", () => {
    window.innerWidth = 390
    drawConfirmation(() => {})
    expect(screen.getByRole("button", { name: "Cancel" })).not.toBeNull()
    cleanup()

    window.innerWidth = 1024
    drawConfirmation(() => {})
    expect(screen.getByRole("button", { name: "Cancel" })).not.toBeNull()
  })
})
