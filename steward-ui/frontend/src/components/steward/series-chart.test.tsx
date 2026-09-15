import { afterEach, describe, expect, it, vi } from "vitest"

import { tooltipTimestamp } from "@/components/steward/series-chart"

/**
 * The fourth of `steward/04`'s five findings: null values in the chart tooltip.
 *
 * Recharts hands the label formatter whatever it currently holds, and that is not always a point.
 *
 * **Most of these cases were already handled before this file existed**, and saying otherwise would
 * make the tests look like they caught something they did not: the inline expression read
 * `Number(payload?.[0]?.payload?.at ?? Date.now())`, so every *missing* shape already fell back.
 * What was missing was any way to check it - the expression sat in a JSX prop three components deep
 * - and one case it really did get wrong, `at: "not a date"`, which survives `??`, becomes `NaN`,
 * and prints "Invalid Date". So: six of the seven rows below pass against the old expression too,
 * and they are here to pin behaviour rather than to claim a fix; the seventh is the fix.
 *
 * `Date.now()` is frozen here rather than allowed a tolerance: a test that asserts "within 50 ms of
 * now" is a test that measures how busy the machine is, which is the same trap `vitest.setup.ts`
 * documents for Testing Library's timeouts.
 */
const NOW = 1_763_000_000_000

afterEach(() => vi.useRealTimers())

function atNow() {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
}

describe("tooltipTimestamp - the point the heading names", () => {
  it("takes the timestamp of the first entry when there is one", () => {
    expect(tooltipTimestamp([{ payload: { at: 1_700_000_000_000 } }])).toBe(1_700_000_000_000)
  })

  it("reads only the first entry, because a tooltip names one instant", () => {
    expect(
      tooltipTimestamp([{ payload: { at: 1 } }, { payload: { at: 2 } }]),
    ).toBe(1)
  })

  it.each([
    ["undefined", undefined],
    ["null", null],
    ["an empty list, which is the first frame of a hover", []],
    ["an entry with no payload at all", [{}]],
    ["a payload with no timestamp in it", [{ payload: {} }]],
    ["a timestamp that is explicitly null", [{ payload: { at: null } }]],
    ["a timestamp that is not a number", [{ payload: { at: "not a date" } }]],
  ] as const)("falls back to now for %s", (_name, payload) => {
    atNow()
    expect(tooltipTimestamp(payload as never)).toBe(NOW)
  })

  it("never answers NaN, which is what Intl renders as Invalid Date", () => {
    atNow()
    for (const payload of [undefined, null, [], [{}], [{ payload: { at: undefined } }]]) {
      expect(Number.isNaN(tooltipTimestamp(payload as never))).toBe(false)
    }
  })

  it("takes a timestamp of 0 rather than treating it as missing", () => {
    // The epoch is a real instant and falsy; `at ?? now` gets this right and `at || now` does not.
    atNow()
    expect(tooltipTimestamp([{ payload: { at: 0 } }])).toBe(0)
  })
})
