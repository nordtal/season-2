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

/**
 * The one place that decides sheet or dialog (steward/98, Till 2026-09-18: every dialog switches
 * over, and at one common place).
 *
 * jsdom has no layout, so nothing here can say that a sheet sits at the bottom edge or that it can
 * be dragged away - `fits-on-a-phone.test.ts` says at length why a width is a number this
 * environment does not have. What it CAN say is which of the two primitives was mounted, which is
 * the whole of what this component decides: `data-slot` is on every shadcn part for exactly this
 * kind of question.
 *
 * The width is `window.innerWidth`, because that is what `useIsMobile` reads - the media query is
 * only how it learns that the number changed. jsdom's own window is 1024 wide, which is why the
 * desktop case needs no setup at all.
 */
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
          <ResponsiveAlertDialogAction onClick={onConfirm}>
            Delete
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>,
  )
  return screen.getByText("Delete it")
}

/**
 * steward/128: the confirmation switches over too, and that was the exception steward/98 named.
 *
 * The reason it was an exception was that a sheet can be flicked away and a confirmation has to be
 * answered. Till, 2026-09-20: flicking it away IS an answer, and the same one a click on the
 * overlay already gave. So there is nothing here asserting that it cannot be dismissed - that would
 * be the old decision written as a test.
 */
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
    drawConfirmation(() => { confirmed += 1 })

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
