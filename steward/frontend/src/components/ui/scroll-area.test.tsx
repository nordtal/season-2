import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { ScrollArea } from "@/components/ui/scroll-area"

afterEach(cleanup)

/** The classes that make an element the containing block of an absolutely placed descendant. */
const POSITIONED = /(^|\s)(relative|absolute|fixed|sticky)(\s|$)/

/**
 * Only the viewport scrolls, so everything inside it is placed against it.
 *
 * An absolutely placed element (above all a `sr-only` label) whose containing block lies outside the viewport is not clipped by the viewport. It hangs below the page at its own spot, and the document gets a second scrollbar. jsdom lays nothing out, so the test checks the cause and not the scrollbar.
 */
describe("ScrollArea", () => {
  it("is the containing block of what it scrolls", () => {
    render(
      <ScrollArea>
        <p>
          <label className="sr-only">hidden label</label>
        </p>
      </ScrollArea>,
    )

    const label = screen.getByText("hidden label")
    const viewport = label.closest("[data-slot=scroll-area-viewport]")
    let block = label.parentElement
    while (block && !POSITIONED.test(block.className)) block = block.parentElement
    expect(viewport).not.toBeNull()
    expect(block !== null && viewport?.contains(block)).toBe(true)
  })
})
