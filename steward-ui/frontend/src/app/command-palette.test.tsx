import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { CommandPalette } from "@/app/command-palette"

/**
 * Ctrl+K, and who gets to keep it.
 *
 * The second of `steward/04`'s five findings: the comment said the browser keeps its own Ctrl+K
 * while somebody is typing, and the code did the opposite - every Ctrl+K in a console line or a
 * config field was swallowed and opened the search instead. It was fixed with `tsc` and thinking,
 * and this is the test that was missing.
 *
 * The palette's own input is the deliberate exception: there the shortcut is how you close it
 * again, so the rule is *not while typing, unless the palette is already open*. That "unless" is
 * why this cannot be a test of `isEditable` alone - the bug was the condition at the call site, and
 * an inverted condition passes every unit test of the predicate under it.
 */

// The palette navigates on select. Nothing here selects, but the hook must exist to render.
vi.mock("@tanstack/react-router", () => ({ useNavigate: () => vi.fn() }))

afterEach(cleanup)

/** The palette, identified by the one thing only the open dialog has. */
const searchInput = () => screen.queryByPlaceholderText("Search pages…")

function ctrlK(target: Element | Document) {
  fireEvent.keyDown(target, { key: "k", ctrlKey: true })
}

describe("CommandPalette - Ctrl+K", () => {
  it("opens on Ctrl+K when nobody is typing", async () => {
    render(<CommandPalette />)
    expect(searchInput()).toBeNull()
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())
  })

  it("opens on Cmd+K as well, so the label the interface prints is a courtesy", async () => {
    render(<CommandPalette />)
    fireEvent.keyDown(document.body, { key: "k", metaKey: true })
    await waitFor(() => expect(searchInput()).not.toBeNull())
  })

  it("leaves a plain k alone", () => {
    render(<CommandPalette />)
    fireEvent.keyDown(document.body, { key: "k" })
    expect(searchInput()).toBeNull()
  })

  it.each(["INPUT", "TEXTAREA", "SELECT"])(
    "does not open while somebody is typing into a %s - the console line keeps its Ctrl+K",
    (tag) => {
      render(<CommandPalette />)
      const field = document.createElement(tag.toLowerCase())
      document.body.append(field)
      ctrlK(field)
      expect(searchInput()).toBeNull()
      field.remove()
    },
  )

  it("does not open from a contenteditable either", () => {
    render(<CommandPalette />)
    const field = document.createElement("div")
    field.contentEditable = "true"
    // jsdom does not derive isContentEditable from the attribute, so it is set directly.
    Object.defineProperty(field, "isContentEditable", { value: true })
    document.body.append(field)
    ctrlK(field)
    expect(searchInput()).toBeNull()
    field.remove()
  })

  it("closes again on Ctrl+K in its own input, which is the one typing that counts", async () => {
    render(<CommandPalette />)
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())
    const input = searchInput()
    expect(input).not.toBeNull()
    ctrlK(input as Element)
    await waitFor(() => expect(searchInput()).toBeNull())
  })
})
