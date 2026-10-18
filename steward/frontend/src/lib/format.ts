/**
 * Formats every number that this interface prints, in one locale and one base.
 *
 * Bytes are base 1000, matching `df` and `statvfs`, so a card and the host's own tools agree.
 */

/**
 * The one locale that this interface formats in: `en-GB`, for the 24-hour clock and the day-first dates that the logs use.
 */
export const LOCALE = "en-GB"

const NUMBER = new Intl.NumberFormat(LOCALE)
const ONE_DECIMAL = new Intl.NumberFormat(LOCALE, {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
})
const DATE_TIME = new Intl.DateTimeFormat(LOCALE, {
  dateStyle: "medium",
  timeStyle: "short",
})
const TIME = new Intl.DateTimeFormat(LOCALE, { timeStyle: "medium" })
const SHORT_TIME = new Intl.DateTimeFormat(LOCALE, { timeStyle: "short" })
const DATE_ONLY = new Intl.DateTimeFormat(LOCALE, { dateStyle: "medium" })

const UNITS = ["B", "kB", "MB", "GB", "TB", "PB"] as const

/** Formats a byte count in base 1000, with one decimal from kB upwards. */
export function bytes(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return "\u2013"
  if (value < 1000) return `${NUMBER.format(Math.round(value))} B`
  let scaled = value
  let unit = 0
  while (scaled >= 1000 && unit < UNITS.length - 1) {
    scaled /= 1000
    unit += 1
  }
  /**
   * The carry is checked on the rounded value, so 999 999 prints as "1.0 MB" and not as "1,000.0 kB".
   */
  if (Math.round(scaled * 10) >= 10_000 && unit < UNITS.length - 1) {
    scaled /= 1000
    unit += 1
  }
  return `${ONE_DECIMAL.format(scaled)} ${UNITS[unit]}`
}

/** Formats a percentage that is already 0 to 100, with exactly `decimals` decimals. */
export function percent(value: number | null | undefined, decimals = 1): string {
  if (value == null || !Number.isFinite(value)) return "\u2013"
  return `${percentFormat(decimals).format(value)} %`
}

/** One `Intl.NumberFormat` per decimal count, cached because constructing it is costly. */
const PERCENT_FORMATS = new Map<number, Intl.NumberFormat>()

function percentFormat(decimals: number): Intl.NumberFormat {
  /** Intl throws outside 0..20, so the count is clamped and a dashboard never throws. */
  const wanted = Math.min(Math.max(Math.trunc(decimals) || 0, 0), 20)
  let format = PERCENT_FORMATS.get(wanted)
  if (!format) {
    format = new Intl.NumberFormat(LOCALE, {
      minimumFractionDigits: wanted,
      maximumFractionDigits: wanted,
    })
    PERCENT_FORMATS.set(wanted, format)
  }
  return format
}

/** Formats a plain count. */
export function count(value: number | null | undefined): string {
  return value == null || !Number.isFinite(value) ? "\u2013" : NUMBER.format(value)
}

/** Formats a load average with two decimals. */
export function load(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return "\u2013"
  return new Intl.NumberFormat(LOCALE, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

/** Formats cents, as the bunq rows carry them. */
export function euros(cents: number | null | undefined): string {
  if (cents == null || !Number.isFinite(cents)) return "\u2013"
  return money(cents, "EUR")
}

/** Formats an amount in its currency's smallest unit, as a money value of a text carries it. */
export function money(minor: number, currency: string): string {
  const format = new Intl.NumberFormat(LOCALE, { style: "currency", currency })
  return format.format(minor / 10 ** (format.resolvedOptions().maximumFractionDigits ?? 2))
}

/** Parses an instant as the server sends it, or returns null when it is absent or no instant. */
export function parseInstant(value: string | null | undefined): Date | null {
  if (value == null || value === "") return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

/** Formats date and time in full. */
export function dateTime(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? "\u2013" : DATE_TIME.format(parsed)
}

/**
 * Formats the day only, without the clock. That fits the access badge on a phone, and a period always ends at midnight.
 */
export function date(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? "\u2013" : DATE_ONLY.format(parsed)
}

/** Formats hours and minutes, for a time of day in a text. */
export function time(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? "\u2013" : SHORT_TIME.format(parsed)
}

/** Formats the clock only, for a log line. */
export function clock(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? "\u2013" : TIME.format(parsed)
}

/**
 * Formats a time as "3 minutes ago" or "in 2 hours", with the grammar from `Intl.RelativeTimeFormat`.
 */
const RELATIVE = new Intl.RelativeTimeFormat(LOCALE, { numeric: "auto" })

const STEPS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ["second", 60],
  ["minute", 60],
  ["hour", 24],
  ["day", 7],
  ["week", 4.348],
  ["month", 12],
  ["year", Number.POSITIVE_INFINITY],
]

export function relative(value: string | Date | null | undefined, now = Date.now()): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  if (parsed == null) return "\u2013"
  let delta = (parsed.getTime() - now) / 1000
  for (const [unit, size] of STEPS) {
    if (Math.abs(delta) < size) {
      return RELATIVE.format(Math.round(delta), unit)
    }
    delta /= size
  }
  return DATE_TIME.format(parsed)
}

/**
 * Splits play time into the dialog's three fields: days, hours and minutes. The form sends the overflow on save.
 */
export function splitPlaytime(seconds: number | null | undefined): {
  days: number
  hours: number
  minutes: number
} {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) {
    return { days: 0, hours: 0, minutes: 0 }
  }
  const whole = Math.floor(seconds / 60)
  return {
    days: Math.floor(whole / (24 * 60)),
    hours: Math.floor(whole / 60) % 24,
    minutes: whole % 60,
  }
}
