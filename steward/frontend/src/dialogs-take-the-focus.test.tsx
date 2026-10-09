import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { useState } from "react"
import { afterEach, describe, expect, it } from "vitest"

import { Button } from "@/components/ui/button"
import {
  ResponsiveAlertDialog,
  ResponsiveAlertDialogCancel,
  ResponsiveAlertDialogContent,
  ResponsiveAlertDialogTitle,
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"

afterEach(() => {
  cleanup()
  window.innerWidth = 1024
})

function Page({ alert, field }: { alert: boolean; field: boolean }) {
  const [open, setOpen] = useState(false)
  const inside = (
    <>
      {field ? <input aria-label="Inside" /> : null}
      <Button type="button">Go on</Button>
    </>
  )
  return (
    <>
      <input aria-label="Console line" />
      {/* Opened without taking the focus first, as a refused request opens the step-up over the console's field. */}
      <button type="button" onClick={() => setOpen(true)}>
        Open
      </button>
      {alert ? (
        <ResponsiveAlertDialog open={open} onOpenChange={setOpen}>
          <ResponsiveAlertDialogContent aria-describedby={undefined}>
            <ResponsiveAlertDialogTitle>Sure?</ResponsiveAlertDialogTitle>
            {inside}
            <ResponsiveAlertDialogCancel>No</ResponsiveAlertDialogCancel>
          </ResponsiveAlertDialogContent>
        </ResponsiveAlertDialog>
      ) : (
        <ResponsiveDialog open={open} onOpenChange={setOpen}>
          <ResponsiveDialogContent aria-describedby={undefined}>
            <ResponsiveDialogTitle>Hold the key</ResponsiveDialogTitle>
            {inside}
          </ResponsiveDialogContent>
        </ResponsiveDialog>
      )}
    </>
  )
}

const CASES = [
  { shape: "sheet", width: 390 },
  { shape: "dialog", width: 1024 },
].flatMap((size) => [false, true].flatMap((alert) => [false, true].map((field) => ({ ...size, alert, field }))))

/**
 * Every dialog goes through `responsive-dialog.tsx`, so these four roots in both shapes are every dialog there is.
 *
 * On a phone a field that keeps the focus keeps the keyboard open over the sheet.
 */
describe("a dialog that opens over a focused field takes the focus", () => {
  it.each(CASES)("$shape, alert $alert, a field of its own $field", ({ width, alert, field }) => {
    window.innerWidth = width
    render(<Page alert={alert} field={field} />)
    const line = screen.getByRole("textbox", { name: "Console line" })
    line.focus()
    expect(document.activeElement).toBe(line)

    fireEvent.click(screen.getByRole("button", { name: "Open" }))

    const title = screen.getByText(alert ? "Sure?" : "Hold the key")
    const content = title.closest("[role=dialog], [role=alertdialog]")
    expect(document.activeElement).not.toBe(line)
    expect(content?.contains(document.activeElement)).toBe(true)
  })
})
