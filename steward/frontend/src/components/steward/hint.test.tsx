import { useState } from "react"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { Hint } from "@/components/steward/status"
import { tipOf } from "@/lib/test-elements"

afterEach(cleanup)

/** A field whose tip comes with its first keystroke, as a number field's problem does. */
function Field() {
  const [value, setValue] = useState("")
  return (
    <Hint tip={value === "" ? undefined : `not ${value}`}>
      <input aria-label="Amount" value={value} onChange={(event) => setValue(event.target.value)} />
    </Hint>
  )
}

/** A button that disables itself while its work runs, as a send button does. */
function Send() {
  const [busy, setBusy] = useState(false)
  return (
    <Hint tip={busy ? "Sending" : "Send"}>
      <button type="button" disabled={busy} onClick={() => setBusy(true)}>
        Send
      </button>
    </Hint>
  )
}

describe("Hint", () => {
  it("keeps the field and its focus when the tip comes and goes", () => {
    render(<Field />)
    const field = screen.getByLabelText("Amount")
    field.focus()
    fireEvent.change(field, { target: { value: "-" } })
    expect(screen.getByLabelText("Amount")).toBe(field)
    expect(document.activeElement).toBe(field)
    fireEvent.change(field, { target: { value: "" } })
    expect(screen.getByLabelText("Amount")).toBe(field)
    expect(document.activeElement).toBe(field)
  })

  it("keeps the button when it turns disabled, and still shows its tip", () => {
    render(<Send />)
    const button = screen.getByRole("button", { name: "Send" })
    fireEvent.click(button)
    expect(screen.getByRole("button", { name: "Send" })).toBe(button)
    expect(button.style.pointerEvents).toBe("auto")
    expect(tipOf(button)).toBe("Sending")
  })

  it("shows no tooltip without a tip", () => {
    render(
      <Hint>
        <button type="button">Plain</button>
      </Hint>,
    )
    expect(tipOf(screen.getByRole("button", { name: "Plain" }))).toBeNull()
  })
})
