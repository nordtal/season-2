import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { standsAlone, UsageBar } from "@/components/steward/stat"
import { asElement } from "@/lib/test-elements"

afterEach(cleanup)

const meter = () => screen.getByRole("meter")
const share = () => Number(meter().getAttribute("aria-valuenow"))
const fill = () => asElement(meter().firstElementChild)

/** `total` is zero until measured, and a bar must not read full because nothing is known. */
describe("UsageBar - the arithmetic", () => {
  it("reports the share of the ceiling", () => {
    render(<UsageBar used={25} total={100} />)
    expect(share()).toBe(25)
  })

  it("rounds for the ARIA value and keeps the exact share for the width", () => {
    render(<UsageBar used={1} total={3} />)
    expect(share()).toBe(33)
    // The style keeps six significant digits, as a browser does, so 33.3333 % and not the rounded 33 %.
    expect(fill().style.width).toMatch(/%$/)
    expect(Number.parseFloat(fill().style.width)).toBeCloseTo(100 / 3, 3)
  })

  it.each([0, -1])("answers 0 rather than NaN or Infinity when the ceiling is %s", (total) => {
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
    render(<UsageBar used={5} total={0} />)
    expect(fill().className).toContain("bg-success")
  })
})

describe("standsAlone - which value takes the mono face", () => {
  it.each(["3", "0", "1.234", "12,5", "-2", "1\u202f234", 42])("takes it for the bare number %s", (value) => {
    expect(standsAlone(value)).toBe(true)
  })

  it.each(["2 hours ago", "12 %", "1.8 GB", "€12.00", "\u2013", "", "en_GB", "12.05.2026", "1.2.3", undefined])(
    "keeps tnum for %s",
    (value) => {
      expect(standsAlone(value)).toBe(false)
    },
  )
})
