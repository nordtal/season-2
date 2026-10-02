/**
 * Formats every number this interface prints, in one locale and one base.
 *
 * Bytes are base 1000, matching `df` and `statvfs`, so a card and the host's own tools agree.
 */

/** The one locale this interface formats in: `en-GB`, for the 24-hour clock and day-first dates the logs use. */
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
const DATE_ONLY = new Intl.DateTimeFormat(LOCALE, { dateStyle: "medium" })

const UNITS = ["B", "kB", "MB", "GB", "TB", "PB"] as const

/** A byte count, base 1000, one decimal from kB upwards. */
export function bytes(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return "\u2013"
  if (value < 1000) return `${NUMBER.format(Math.round(value))} B`
  let scaled = value
  let unit = 0
  while (scaled >= 1000 && unit < UNITS.length - 1) {
    scaled /= 1000
    unit += 1
  }
  /** The carry is checked on the rounded value, so 999 999 prints as "1.0 MB" rather than "1,000.0 kB". */
  if (Math.round(scaled * 10) >= 10_000 && unit < UNITS.length - 1) {
    scaled /= 1000
    unit += 1
  }
  return `${ONE_DECIMAL.format(scaled)} ${UNITS[unit]}`
}

/** A percentage that is already 0 to 100, with exactly `decimals` decimals. */
export function percent(value: number | null | undefined, decimals = 1): string {
  if (value == null || !Number.isFinite(value)) return "\u2013"
  return `${percentFormat(decimals).format(value)} %`
}

/** One `Intl.NumberFormat` per decimal count, cached since they are costly to construct. */
const PERCENT_FORMATS = new Map<number, Intl.NumberFormat>()

function percentFormat(decimals: number): Intl.NumberFormat {
  /** Intl throws outside 0..20, so the count is clamped rather than thrown in a dashboard. */
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

/** A plain count. */
export function count(value: number | null | undefined): string {
  return value == null || !Number.isFinite(value) ? "\u2013" : NUMBER.format(value)
}

/** Two decimals, for a load average. */
export function load(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return "\u2013"
  return new Intl.NumberFormat(LOCALE, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

/** Cents, as the bunq rows carry them. */
export function euros(cents: number | null | undefined): string {
  if (cents == null || !Number.isFinite(cents)) return "\u2013"
  return new Intl.NumberFormat(LOCALE, { style: "currency", currency: "EUR" }).format(cents / 100)
}

/** An instant as the server sends it, or null when it is absent or no instant. */
export function parseInstant(value: string | null | undefined): Date | null {
  if (value == null || value === "") return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

/** Date and time, in full. */
export function dateTime(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? "\u2013" : DATE_TIME.format(parsed)
}

/** The day only, without the clock, which fits the access badge on a phone; a period always ends at midnight. */
export function date(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? "\u2013" : DATE_ONLY.format(parsed)
}

/** The clock only, for a log line. */
export function clock(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? "\u2013" : TIME.format(parsed)
}

/** "3 minutes ago", "in 2 hours", with the grammar from `Intl.RelativeTimeFormat`. */
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

/** A span in at most two units: "3 d 4 h", "12 min"; for uptime and run durations. */
export function duration(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return "\u2013"
  const whole = Math.floor(seconds)
  if (whole < 60) return `${whole} s`
  const minutes = Math.floor(whole / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    const rest = minutes % 60
    return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
  }
  const days = Math.floor(hours / 24)
  const rest = hours % 24
  return rest === 0 ? `${days} d` : `${days} d ${rest} h`
}

/**
 * Play time in days, hours and minutes, the units the dialog asks for: "1 d 6 h 30 min".
 *
 * Seconds are dropped so a value round-trips; zero parts are left out, but all zero is "0 min".
 */
export function playtime(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return "\u2013"
  const { days, hours, minutes } = splitPlaytime(seconds)
  const parts: string[] = []
  if (days > 0) parts.push(`${days} d`)
  if (hours > 0) parts.push(`${hours} h`)
  if (minutes > 0 || parts.length === 0) parts.push(`${minutes} min`)
  return parts.join(" ")
}

/** The same three numbers unjoined, for the dialog's fields; the form carries overflow on save. */
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

/** How long ago an instant was, as a span rather than as "… ago". */
export function since(value: string | Date | null | undefined, now = Date.now()): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? "\u2013" : duration((now - parsed.getTime()) / 1000)
}
