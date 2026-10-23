/**
 * Formats every number this interface prints, in one locale and one base. Bytes are base 1000, matching `df` and `statvfs`.
 */

/** `en-GB`, for the 24-hour clock and the day-first dates that the logs use. */
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

/** What every place that has no value shows. */
export const NO_VALUE = "\u2013"

const UNITS = ["B", "kB", "MB", "GB", "TB", "PB"] as const

export function bytes(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return NO_VALUE
  if (value < 1000) return `${NUMBER.format(Math.round(value))} B`
  let scaled = value
  let unit = 0
  while (scaled >= 1000 && unit < UNITS.length - 1) {
    scaled /= 1000
    unit += 1
  }
  /** The carry is checked on the rounded value, so 999 999 prints as "1.0 MB". */
  if (Math.round(scaled * 10) >= 10_000 && unit < UNITS.length - 1) {
    scaled /= 1000
    unit += 1
  }
  return `${ONE_DECIMAL.format(scaled)} ${UNITS[unit]}`
}

export function percent(value: number | null | undefined, decimals = 1): string {
  if (value == null || !Number.isFinite(value)) return NO_VALUE
  return `${percentFormat(decimals).format(value)} %`
}

/** Cached because constructing it is costly. */
const PERCENT_FORMATS = new Map<number, Intl.NumberFormat>()

function percentFormat(decimals: number): Intl.NumberFormat {
  /** Intl throws outside 0..20. */
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

export function count(value: number | null | undefined): string {
  return value == null || !Number.isFinite(value) ? NO_VALUE : NUMBER.format(value)
}

export function load(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return NO_VALUE
  return new Intl.NumberFormat(LOCALE, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

export function euros(cents: number | null | undefined): string {
  if (cents == null || !Number.isFinite(cents)) return NO_VALUE
  return money(cents, "EUR")
}

export function money(minor: number, currency: string): string {
  const format = new Intl.NumberFormat(LOCALE, { style: "currency", currency })
  return format.format(minor / 10 ** (format.resolvedOptions().maximumFractionDigits ?? 2))
}

export function parseInstant(value: string | null | undefined): Date | null {
  if (value == null || value === "") return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

export function dateTime(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? NO_VALUE : DATE_TIME.format(parsed)
}

/** The access badge on a phone shows the day only, and a period always ends at midnight. */
export function date(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? NO_VALUE : DATE_ONLY.format(parsed)
}

export function time(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? NO_VALUE : SHORT_TIME.format(parsed)
}

export function clock(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? NO_VALUE : TIME.format(parsed)
}

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
  if (parsed == null) return NO_VALUE
  let delta = (parsed.getTime() - now) / 1000
  for (const [unit, size] of STEPS) {
    if (Math.abs(delta) < size) {
      return RELATIVE.format(Math.round(delta), unit)
    }
    delta /= size
  }
  return DATE_TIME.format(parsed)
}

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
