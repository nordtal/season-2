import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { UsageBar } from "@/components/steward/stat"

/**
 * The fifth of `steward/04`'s five findings: the `value` on the usage bar.
 *
 * A bar for something with a ceiling is handed a pair of numbers off the host, and `total` is zero
 * whenever the answer has not arrived - a container without limits, a disk that has not been
 * measured yet. `used / 0` is `Infinity` or `NaN`, and a bar that is 100 % full because nothing is
 * known reads as an emergency. Checked with `tsc` at the time; never with a test.
 *
 * The bar is a `role="meter"` rather than shadcn's `Progress`, and that is deliberate - the colour
 * change at a threshold is the entire reason an operator glances at it, and `Progress` has no such
 * thing. The assertions below are therefore against the ARIA value and the class, which is what a
 * reader of the page actually gets.
 */
afterEach(cleanup)

const meter = () => screen.getByRole("meter")
const share = () => Number(meter().getAttribute("aria-valuenow"))
const fill = () => meter().firstElementChild as HTMLElement

describe("UsageBar - the arithmetic", () => {
  it("reports the share of the ceiling", () => {
    render(<UsageBar used={25} total={100} />)
    expect(share()).toBe(25)
  })

  it("rounds for the ARIA value and keeps the exact share for the width", () => {
    render(<UsageBar used={1} total={3} />)
    expect(share()).toBe(33)
    expect(fill().style.width).toBe("33.33333333333333%")
  })

  it.each([0, -1])("answers 0 rather than NaN or Infinity when the ceiling is %s", (total) => {
    // This is the finding. An unmeasured disk must not draw a full bar.
    render(<UsageBar used={5} total={total} />)
    expect(share()).toBe(0)
    expect(Number.isNaN(share())).toBe(false)
  })

  it("clamps at 100 rather than drawing past the end of the track", () => {
    render(<UsageBar used={300} total={100} />)
    expect(share()).toBe(100)
    expect(fill().style.width).toBe("100%")
  })

  it("carries the bounds an assistive reader needs", () => {
    render(<UsageBar used={10} total={100} />)
    expect(meter().getAttribute("aria-valuemin")).toBe("0")
    expect(meter().getAttribute("aria-valuemax")).toBe("100")
  })
})

describe("UsageBar - the colour, which is the reason it is not shadcn's Progress", () => {
  it.each([
    [0, "bg-success"],
    [79, "bg-success"],
    [80, "bg-warning"],
    [89, "bg-warning"],
    [90, "bg-destructive"],
    [100, "bg-destructive"],
  ])("is %s%% full and therefore %s", (used, expected) => {
    render(<UsageBar used={used} total={100} />)
    expect(fill().className).toContain(expected)
  })

  it("takes the thresholds it is given", () => {
    render(<UsageBar used={50} total={100} warnAt={40} dangerAt={60} />)
    expect(fill().className).toContain("bg-warning")
  })

  it("stays green for an unmeasured ceiling instead of drawing a red full bar", () => {
    // The two halves of the finding meet here: the guarded share is 0, so the tone is the calm one.
    render(<UsageBar used={5} total={0} />)
    expect(fill().className).toContain("bg-success")
  })
})
