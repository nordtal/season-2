import { cleanup, render } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { Sparkline } from "@/components/steward/sparkline"

/**
 * The one thing worth pinning about a decorative chart: it must not throw on the input the metric
 * row actually hands it, which is "nothing yet" on every first render - and, once there is data,
 * that it actually reaches for the SVG rather than silently drawing nothing.
 *
 * jsdom does no layout at all, so `getBoundingClientRect` answers all zeroes for every element
 * regardless of CSS - recharts' `ResponsiveContainer` measures its wrapper on mount and refuses to
 * draw into a zero-sized box (`SeriesChart` would hit the exact same wall; nothing in this
 * repository has rendered a real chart under vitest before this file). The stub below is a test-only
 * fix for a jsdom gap, not a Sparkline concern - `initialDimension` on the component itself is the
 * real-browser safeguard against the first-paint flash, and it does not need faking here.
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
    expect((container.firstElementChild as HTMLElement).style.height).toBe("28px")
  })

  it("draws the curve once there is at least one point", () => {
    const { container } = render(<Sparkline points={[{ at: "2026-09-15T00:00:00Z", value: 12 }]} />)

    expect(container.querySelector("svg")).not.toBeNull()
  })

  it("is decorative - a screen reader gets nothing from it, the number beside it already said it", () => {
    const { container } = render(<Sparkline points={[{ at: "2026-09-15T00:00:00Z", value: 12 }]} />)

    // No jest-dom in this project (see recreate.test.tsx) - the DOM API rather than a matcher.
    expect(container.firstElementChild?.hasAttribute("aria-hidden")).toBe(true)
  })
})
