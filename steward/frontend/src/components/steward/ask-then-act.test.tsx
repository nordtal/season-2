import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { ApiError } from "@/lib/api"
import { AskThenAct } from "@/components/steward/ask-then-act"

afterEach(() => {
  cleanup()
  window.innerWidth = 1024
})

/** The confirmation open from the start, closing through `closed`. */
function draw(change: () => Promise<unknown> | void, closed: { count: number } = { count: 0 }) {
  render(
    <AskThenAct
      open
      onOpenChange={(open) => {
        if (!open) closed.count += 1
      }}
      title="Delete it"
      action="Delete"
      acting="Deleting…"
      act={change}
    />,
  )
  return screen.getByText("Delete it")
}

describe("AskThenAct - one shape per width", () => {
  it("is an alert dialog on a desktop", () => {
    window.innerWidth = 1024
    const title = draw(() => {})

    expect(title.closest("[data-slot='alert-dialog-content']")).not.toBeNull()
    expect(title.closest("[data-slot='drawer-content']")).toBeNull()
  })

  it("is a bottom sheet on a phone, and still acts there", () => {
    window.innerWidth = 390
    let acted = 0
    const title = draw(() => {
      acted += 1
    })

    expect(title.closest("[data-slot='drawer-content']")).not.toBeNull()
    fireEvent.click(screen.getByRole("button", { name: "Delete" }))
    expect(acted).toBe(1)
  })
})

describe("AskThenAct - what the answer does to the dialog", () => {
  it("closes at once for a change that returns nothing", () => {
    const closed = { count: 0 }
    draw(() => {}, closed)

    fireEvent.click(screen.getByRole("button", { name: "Delete" }))

    expect(closed.count).toBe(1)
  })

  it("stays open and refuses to close while the change is on its way, then closes", async () => {
    const closed = { count: 0 }
    let finish: (() => void) | undefined
    draw(() => new Promise<void>((resolve) => (finish = resolve)), closed)

    fireEvent.click(screen.getByRole("button", { name: "Delete" }))
    const busy = await screen.findByRole("button", { name: "Deleting…" })
    expect(busy.hasAttribute("disabled")).toBe(true)
    expect(screen.getByRole("button", { name: "Cancel" }).hasAttribute("disabled")).toBe(true)
    fireEvent.keyDown(screen.getByRole("alertdialog"), { key: "Escape" })
    expect(closed.count).toBe(0)

    await act(async () => finish?.())
    await waitFor(() => expect(closed.count).toBe(1))
  })

  it("keeps a refusal inside, under the question, and stays open", async () => {
    const closed = { count: 0 }
    draw(() => Promise.reject(new ApiError(409, "a run is already open", "steward")), closed)

    fireEvent.click(screen.getByRole("button", { name: "Delete" }))

    expect((await screen.findByRole("alert")).textContent).toContain("a run is already open")
    expect(closed.count).toBe(0)
  })
})
