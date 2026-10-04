import { describe, expect, it } from "vitest"

import {
  bytes,
  clock,
  count,
  dateTime,
  euros,
  load,
  parseInstant,
  percent,
  relative,
  splitPlaytime,
} from "@/lib/format"

/**
 * The formatters held against the shapes the file promises.
 *
 * `NOTHING` is an escaped en dash, since a typed hyphen would fail for a reason the diff hides.
 */
const NOTHING = "\u2013"

/** Every formatter takes null, undefined, NaN and both infinities, since the API sends all four. */
const NOT_A_NUMBER = [null, undefined, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]

describe("bytes", () => {
  it("prints a count below a kilobyte as bytes, with no decimal at all", () => {
    expect(bytes(0)).toBe("0 B")
    expect(bytes(512)).toBe("512 B")
    expect(bytes(999)).toBe("999 B")
  })

  it("treats a kilobyte as a thousand bytes, because that is what df on this host says", () => {
    /** Decimal units match the machine's own tools, so 1000 B is 1.0 kB and not a KiB. */
    expect(bytes(1000)).toBe("1.0 kB")
    expect(bytes(1024)).toBe("1.0 kB")
    expect(bytes(1500)).toBe("1.5 kB")
  })

  it("climbs one unit per factor of a thousand and names the unit it stopped at", () => {
    expect(bytes(2_500_000)).toBe("2.5 MB")
    expect(bytes(3_400_000_000)).toBe("3.4 GB")
    expect(bytes(1_000_000_000_000)).toBe("1.0 TB")
  })

  it("stops at petabytes rather than walking off the end of the unit list", () => {
    expect(bytes(1e15)).toBe("1.0 PB")
    expect(bytes(1e18)).toBe("1,000.0 PB")
  })

  it("shows a dash for anything that is not a number, including both infinities", () => {
    for (const value of NOT_A_NUMBER) expect(bytes(value)).toBe(NOTHING)
  })

  it("never prints a thousand of one unit, because that is the next unit", () => {
    /** Scaling looks at the rounded value, so 999 999 B is a megabyte and not "1,000.0 kB". */
    expect(bytes(999_999)).toBe("1.0 MB")
    expect(bytes(999_999_999)).toBe("1.0 GB")
  })
})

describe("percent", () => {
  it("prints a whole number when no decimals were asked for", () => {
    /** Intl defaults to three fraction digits, which a zero decimal call must not print. */
    expect(percent(87.4567, 0)).toBe("87 %")
    expect(percent(0, 0)).toBe("0 %")
    expect(percent(100, 0)).toBe("100 %")
  })

  it("rounds half away from zero rather than truncating", () => {
    expect(percent(86.5, 0)).toBe("87 %")
    expect(percent(87.5, 0)).toBe("88 %")
  })

  it("prints one decimal by default, padded when the value has none", () => {
    expect(percent(87.4567)).toBe("87.5 %")
    expect(percent(87)).toBe("87.0 %")
    expect(percent(0)).toBe("0.0 %")
  })

  it("honours a decimal count other than zero or one", () => {
    /** A decimal count other than 0 or 1 must be honoured, not read as 1. */
    expect(percent(12.3456, 2)).toBe("12.35 %")
  })

  it("shows a dash for anything that is not a number", () => {
    for (const value of NOT_A_NUMBER) expect(percent(value)).toBe(NOTHING)
    expect(percent(null, 0)).toBe(NOTHING)
  })
})

describe("count", () => {
  it("groups with the thousands separator of the locale", () => {
    expect(count(0)).toBe("0")
    expect(count(999)).toBe("999")
    expect(count(1_234_567)).toBe("1,234,567")
  })

  it("keeps the sign of a negative count", () => {
    expect(count(-5)).toBe("-5")
  })

  it("shows a dash for anything that is not a number", () => {
    for (const value of NOT_A_NUMBER) expect(count(value)).toBe(NOTHING)
  })
})

describe("load", () => {
  it("always prints two decimals, because a load average of 1 is 1,00", () => {
    expect(load(0)).toBe("0.00")
    expect(load(1.5)).toBe("1.50")
    expect(load(12)).toBe("12.00")
  })

  it("drops the third digit rather than showing it as noise", () => {
    expect(load(0.1234)).toBe("0.12")
  })

  it("shows a dash for anything that is not a number", () => {
    for (const value of NOT_A_NUMBER) expect(load(value)).toBe(NOTHING)
  })
})

describe("euros", () => {
  it("reads cents as the bunq rows carry them and prints euros", () => {
    expect(euros(0)).toBe("€0.00")
    expect(euros(150)).toBe("€1.50")
    expect(euros(123_456)).toBe("€1,234.56")
  })

  it("keeps the sign of a refund", () => {
    expect(euros(-150)).toBe("-€1.50")
  })

  it("shows a dash for anything that is not a number", () => {
    for (const value of NOT_A_NUMBER) expect(euros(value)).toBe(NOTHING)
  })
})

describe("parseInstant", () => {
  it("reads an absent value or a word that is no instant as null", () => {
    expect(parseInstant("null")).toBeNull()
    expect(parseInstant("")).toBeNull()
    expect(parseInstant(null)).toBeNull()
    expect(parseInstant(undefined)).toBeNull()
  })

  it("returns null for a string that is not a date, never an Invalid Date", () => {
    // An Invalid Date would pass a null check and throw inside Intl.
    expect(parseInstant("not a time")).toBeNull()
    expect(parseInstant("2026-13-45T99:99:99Z")).toBeNull()
  })

  it("parses an ISO instant to the moment it names", () => {
    expect(parseInstant("2026-09-12T04:45:00Z")?.getTime()).toBe(Date.UTC(2026, 8, 12, 4, 45, 0))
  })

  it("reads an offset as an offset and not as local time", () => {
    expect(parseInstant("2026-09-12T06:45:00+02:00")?.getTime()).toBe(Date.UTC(2026, 8, 12, 4, 45, 0))
  })
})

describe("dateTime and clock", () => {
  /** The en-GB shape is pinned rather than the digits, which depend on the machine's time zone. */
  const LOCALE_DATE_TIME = /^\d{1,2} \w+ \d{4}, \d{2}:\d{2}$/
  const LOCALE_CLOCK = /^\d{2}:\d{2}:\d{2}$/

  it("prints a date day first, with a named month and a 24-hour clock", () => {
    expect(dateTime("2026-09-12T04:45:00Z")).toMatch(LOCALE_DATE_TIME)
  })

  it("prints only the clock for a log line, down to the second", () => {
    expect(clock("2026-09-12T04:45:09Z")).toMatch(LOCALE_CLOCK)
  })

  it("takes a Date as it is and a string through parseInstant", () => {
    expect(dateTime(new Date("2026-09-12T04:45:00Z"))).toMatch(LOCALE_DATE_TIME)
    expect(clock(new Date("2026-09-12T04:45:09Z"))).toMatch(LOCALE_CLOCK)
  })

  it("shows a dash for nothing, for SQL NULL and for an unparseable string", () => {
    for (const value of [null, undefined, "null", "", "not a time"]) {
      expect(dateTime(value)).toBe(NOTHING)
      expect(clock(value)).toBe(NOTHING)
    }
  })
})

describe("relative", () => {
  const NOW = Date.UTC(2026, 8, 12, 12, 0, 0)
  const away = (ms: number) => new Date(NOW + ms)

  it("says now rather than in 0 seconds", () => {
    expect(relative(away(0), NOW)).toBe("now")
  })

  it("puts the past after the number and the future before it", () => {
    expect(relative(away(-3 * 60_000), NOW)).toBe("3 minutes ago")
    expect(relative(away(2 * 3_600_000), NOW)).toBe("in 2 hours")
  })

  it("uses the word for a day rather than the number, which is what numeric auto is for", () => {
    expect(relative(away(-24 * 3_600_000), NOW)).toBe("yesterday")
    expect(relative(away(24 * 3_600_000), NOW)).toBe("tomorrow")
  })

  it("steps up to the next unit exactly at sixty seconds", () => {
    expect(relative(away(-59_000), NOW)).toBe("59 seconds ago")
    expect(relative(away(-60_000), NOW)).toBe("1 minute ago")
  })

  it("keeps climbing units for a distance the page rarely shows", () => {
    expect(relative(away(-14 * 24 * 3_600_000), NOW)).toBe("2 weeks ago")
    expect(relative(away(-90 * 24 * 3_600_000), NOW)).toBe("3 months ago")
    expect(relative(away(-400 * 24 * 3_600_000), NOW)).toBe("last year")
  })

  it("shows a dash for nothing and for SQL NULL", () => {
    expect(relative(null, NOW)).toBe(NOTHING)
    expect(relative("null", NOW)).toBe(NOTHING)
  })
})

/** The dialog's three fields; showing the value is the duration kind's, in `texts`. */
describe("splitPlaytime", () => {
  it("drops seconds rather than rounding, so a value read back is the value written", () => {
    expect(splitPlaytime(3_659)).toEqual({ days: 0, hours: 1, minutes: 0 })
  })

  it("splits into the three fields the dialog shows", () => {
    expect(splitPlaytime(86_400 + 6 * 3_600 + 30 * 60)).toEqual({ days: 1, hours: 6, minutes: 30 })
    expect(splitPlaytime(0)).toEqual({ days: 0, hours: 0, minutes: 0 })
    expect(splitPlaytime(null)).toEqual({ days: 0, hours: 0, minutes: 0 })
    expect(splitPlaytime(-1)).toEqual({ days: 0, hours: 0, minutes: 0 })
  })

  it("is the inverse of what the dialog multiplies, for a round trip", () => {
    for (const seconds of [0, 60, 3_600, 86_400, 86_400 + 6 * 3_600 + 30 * 60, 50 * 3_600]) {
      const { days, hours, minutes } = splitPlaytime(seconds)
      expect(days * 86_400 + hours * 3_600 + minutes * 60).toBe(seconds)
    }
  })
})
