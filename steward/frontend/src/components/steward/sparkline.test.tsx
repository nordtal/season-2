import { cleanup, render } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { Sparkline } from "@/components/steward/sparkline"
import { asElement } from "@/lib/test-elements"

/**
 * The chart must not throw on the empty first render, and must reach for the SVG once there is data.
 *
 * jsdom measures every box as zero, which `ResponsiveContainer` refuses to draw into; the stub below fills that gap.
 */
const rect = (width: number, height: number): DOMRect => ({
  width,
  height,
  top: 0,
  left: 0,
  right: width,
  bottom: height,
  x: 0,
  y: 0,
  toJSON: () => ({}),
})

beforeEach(() => {
  vi.spyOn(Element.prototype, "getBoundingClientRect").mockReturnValue(rect(200, 28))
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe("Sparkline", () => {
  it("draws an empty, fixed-height strip rather than an empty chart when there are no points", () => {
    const { container } = render(<Sparkline points={[]} height={28} />)

    expect(container.querySelector("svg")).toBeNull()
    expect(asElement(container.firstElementChild).style.height).toBe("28px")
  })

  it("draws the curve once there is at least one point", () => {
    const { container } = render(<Sparkline points={[{ at: "2026-09-15T00:00:00Z", value: 12, resolution: "RAW" }]} />)

    expect(container.querySelector("svg")).not.toBeNull()
  })

  it("is decorative - a screen reader gets nothing from it, the number beside it already said it", () => {
    const { container } = render(<Sparkline points={[{ at: "2026-09-15T00:00:00Z", value: 12, resolution: "RAW" }]} />)

    // No jest-dom here, so the DOM API rather than a matcher.
    expect(container.firstElementChild?.hasAttribute("aria-hidden")).toBe(true)
  })
})
