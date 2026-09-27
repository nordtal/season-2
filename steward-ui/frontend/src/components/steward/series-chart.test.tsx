import { afterEach, describe, expect, it, vi } from "vitest"

import { tooltipTimestamp } from "@/components/steward/series-chart"

/** Frozen rather than given a tolerance, which would measure how busy the machine is. */
const NOW = 1_763_000_000_000

afterEach(() => vi.useRealTimers())

function atNow() {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
}

/** Recharts hands the label formatter whatever it holds, so every shape that is not a point falls back to now. */
describe("tooltipTimestamp - the point the heading names", () => {
  it("takes the timestamp of the first entry when there is one", () => {
    expect(tooltipTimestamp([{ payload: { at: 1_700_000_000_000 } }])).toBe(1_700_000_000_000)
  })

  it("reads only the first entry, because a tooltip names one instant", () => {
    expect(tooltipTimestamp([{ payload: { at: 1 } }, { payload: { at: 2 } }])).toBe(1)
  })

  const CASES: Array<[string, Parameters<typeof tooltipTimestamp>[0]]> = [
    ["undefined", undefined],
    ["null", null],
    ["an empty list, which is the first frame of a hover", []],
    ["an entry with no payload at all", [{}]],
    ["a payload with no timestamp in it", [{ payload: {} }]],
    ["a timestamp that is explicitly null", [{ payload: { at: null } }]],
    ["a timestamp that is not a number", [{ payload: { at: "not a date" } }]],
  ]

  it.each(CASES)("falls back to now for %s", (_name, payload) => {
    atNow()
    expect(tooltipTimestamp(payload)).toBe(NOW)
  })

  it("never answers NaN, which is what Intl renders as Invalid Date", () => {
    atNow()
    const payloads: Parameters<typeof tooltipTimestamp>[0][] = [
      undefined,
      null,
      [],
      [{}],
      [{ payload: { at: undefined } }],
    ]
    for (const payload of payloads) {
      expect(Number.isNaN(tooltipTimestamp(payload))).toBe(false)
    }
  })

  it("takes a timestamp of 0 rather than treating it as missing", () => {
    // The epoch is a real instant and falsy; `at ?? now` gets this right and `at || now` does not.
    atNow()
    expect(tooltipTimestamp([{ payload: { at: 0 } }])).toBe(0)
  })
})
