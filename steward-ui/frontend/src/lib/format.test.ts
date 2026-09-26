import { describe, expect, it } from "vitest"

import {
  bytes,
  clock,
  count,
  dateTime,
  duration,
  euros,
  load,
  parseInstant,
  percent,
  playtime,
  relative,
  since,
  splitPlaytime,
} from "@/lib/format"

/**
 * The numbers this interface prints, held against what the file promises they look like.
 *
 * One character in here is not the one a keyboard produces and is written as an escape on purpose:
 * the placeholder for "nothing to show" is an en dash, not a hyphen - an assertion typed with the
 * ordinary character passes nowhere and fails for a reason nobody can see in the diff.
 */
const NOTHING = "\u2013"

/** Every formatter takes null, undefined, NaN and both infinities, because the API sends all four. */
const NOT_A_NUMBER = [null, undefined, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]

describe("bytes", () => {
  it("prints a count below a kilobyte as bytes, with no decimal at all", () => {
    expect(bytes(0)).toBe("0 B")
    expect(bytes(512)).toBe("512 B")
    expect(bytes(999)).toBe("999 B")
  })

  it("treats a kilobyte as a thousand bytes, because that is what df on this host says", () => {
    // The header of the file argues this explicitly: matching the machine's own tools beats the
    // pedantically correct GiB, so 1024 B is 1.0 kB and not 1.0 KiB.
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
    // A value larger than the largest unit must still be a sentence, not "undefined".
    expect(bytes(1e15)).toBe("1.0 PB")
    expect(bytes(1e18)).toBe("1,000.0 PB")
  })

  it("shows a dash for anything that is not a number, including both infinities", () => {
    for (const value of NOT_A_NUMBER) expect(bytes(value as number)).toBe(NOTHING)
  })

  it("never prints a thousand of one unit, because that is the next unit", () => {
    // FINDING - fails today. The scaling loop looks at the raw value and the rounding happens
    // afterwards, so 999 999 B is scaled to 999.999 kB and then printed as "1,000.0 kB". Every
    // byte count in [999 950, 999 999] reads as a thousand kilobytes rather than as a megabyte,
    // and these are integers the host really reports.
    expect(bytes(999_999)).toBe("1.0 MB")
    expect(bytes(999_999_999)).toBe("1.0 GB")
  })
})

describe("percent", () => {
  it("prints a whole number when no decimals were asked for", () => {
    // The regression this test exists for: Intl's own default is three fraction digits, so the
    // zero-decimal call printed "87.457 %" where the page had asked for "87 %".
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
    // FINDING - fails today. The signature takes `decimals: number`, so this call type-checks, but
    // the implementation is `decimals === 0 ? NO_DECIMAL : ONE_DECIMAL` and every value that is not
    // 0 silently means 1. Either the formatter is chosen by the argument or the type says `0 | 1`;
    // accepting a number and ignoring it is the one option that cannot be seen at the call site.
    expect(percent(12.3456, 2)).toBe("12.35 %")
  })

  it("shows a dash for anything that is not a number", () => {
    for (const value of NOT_A_NUMBER) expect(percent(value as number)).toBe(NOTHING)
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
    for (const value of NOT_A_NUMBER) expect(count(value as number)).toBe(NOTHING)
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
    for (const value of NOT_A_NUMBER) expect(load(value as number)).toBe(NOTHING)
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
    for (const value of NOT_A_NUMBER) expect(euros(value as number)).toBe(NOTHING)
  })
})

describe("parseInstant", () => {
  it("reads the four characters null as SQL NULL rather than as a date", () => {
    // The backend prints String.valueOf(instant), so a NULL column arrives as the word. Handled
    // here once, because forgetting once puts "null" on the screen.
    expect(parseInstant("null")).toBeNull()
    expect(parseInstant("")).toBeNull()
    expect(parseInstant(null)).toBeNull()
    expect(parseInstant(undefined)).toBeNull()
  })

  it("returns null for a string that is not a date, never an Invalid Date", () => {
    // An Invalid Date would survive a null check and then throw inside Intl at the call site.
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
  // These two are the only assertions in this file that must not name a time zone: the tests run
  // wherever they run, so what is pinned is the en-GB shape - day first, a named month, and a
  // 24-hour clock - rather than the digits, which depend on the machine.
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

describe("duration", () => {
  it("prints seconds below a minute and drops the fraction instead of rounding it up", () => {
    expect(duration(0)).toBe("0 s")
    expect(duration(59.9)).toBe("59 s")
  })

  it("switches unit exactly at sixty seconds and again at sixty minutes", () => {
    expect(duration(60)).toBe("1 min")
    expect(duration(3_599)).toBe("59 min")
    expect(duration(3_600)).toBe("1 h")
  })

  it("leaves out the smaller unit when it is zero", () => {
    expect(duration(3_600)).toBe("1 h")
    expect(duration(86_400)).toBe("1 d")
  })

  it("says at most two units, because nobody reads the fourth one", () => {
    expect(duration(3 * 86_400 + 4 * 3_600 + 11 * 60 + 6)).toBe("3 d 4 h")
    expect(duration(2 * 3_600 + 3 * 60 + 9)).toBe("2 h 3 min")
  })

  it("shows a dash for a negative span, which is not a span", () => {
    expect(duration(-1)).toBe(NOTHING)
  })

  it("shows a dash for anything that is not a number", () => {
    for (const value of NOT_A_NUMBER) expect(duration(value as number)).toBe(NOTHING)
  })
})

describe("since", () => {
  const NOW = Date.UTC(2026, 8, 12, 12, 0, 0)

  it("says how long ago an instant was as a span rather than as a sentence", () => {
    expect(since(new Date(NOW - (3 * 3_600_000 + 5 * 60_000)), NOW)).toBe("3 h 5 min")
    expect(since(new Date(NOW - 30_000), NOW)).toBe("30 s")
  })

  it("shows a dash for an instant in the future", () => {
    // Pinned rather than endorsed: "how long ago" has no answer for the future, but the browser
    // clock and the worker's clock are two clocks, and a timestamp two seconds ahead reads as
    // "nothing here" rather than as "0 s". See the findings.
    expect(since(new Date(NOW + 2_000), NOW)).toBe(NOTHING)
  })

  it("shows a dash for nothing and for SQL NULL", () => {
    expect(since(null, NOW)).toBe(NOTHING)
    expect(since("null", NOW)).toBe(NOTHING)
  })
})

/**
 * steward/126. Till, 2026-09-20: play time is asked for in days, hours and minutes, so it has to be
 * answered in them too - a column that says "1 d 6 h" to something entered as 1 d 6 h 30 min looks
 * like a save that lost the minutes.
 */
describe("playtime", () => {
  it("spells out all three units, leaving out the empty ones", () => {
    expect(playtime(86_400 + 6 * 3_600 + 30 * 60)).toBe("1 d 6 h 30 min")
    expect(playtime(2 * 86_400)).toBe("2 d")
    expect(playtime(6 * 3_600 + 30 * 60)).toBe("6 h 30 min")
    expect(playtime(30 * 60)).toBe("30 min")
  })

  it("keeps the minutes `duration` would have dropped", () => {
    // THE WHOLE DIFFERENCE between the two, in one line: duration stops at two units because an
    // uptime does not need a third, and this one cannot.
    expect(duration(86_400 + 6 * 3_600 + 30 * 60)).toBe("1 d 6 h")
    expect(playtime(86_400 + 6 * 3_600 + 30 * 60)).toBe("1 d 6 h 30 min")
  })

  it("answers zero with a zero and nothing at all with a dash", () => {
    // Two different people: one has been online and has almost no time, the other has never been
    // online. "0 min" and the dash are the two answers and they must not be the same one.
    expect(playtime(0)).toBe("0 min")
    expect(playtime(59)).toBe("0 min")
    expect(playtime(-1)).toBe(NOTHING)
    for (const value of NOT_A_NUMBER) expect(playtime(value as number)).toBe(NOTHING)
  })

  it("drops seconds rather than rounding, so a value read back is the value written", () => {
    expect(playtime(3_659)).toBe("1 h")
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
