/**
 * Every number this interface prints goes through here.
 *
 * Two reasons it is one file. Formatting a byte count in six places is six chances to disagree
 * about whether a gigabyte has 1000 or 1024 megabytes - and an operator comparing a card against a
 * table has to be able to trust that "1.4 GB" in one place is the same quantity as "1.4 GB" in the
 * other. And the locale is one constant, {@link LOCALE}, rather than fourteen string literals: the
 * interface used to carry LOCALE in fourteen places, and a page that formats a date differently
 * from the table beside it reads as translated rather than written.
 *
 * Base 1000, not 1024, and the unit says so. Docker reports memory in bytes and the disk numbers
 * come from `statvfs`; both are counts of bytes, and the host's own `df` prints base-1000 for the
 * same file system. Matching what the machine's own tools say is worth more here than the
 * pedantically correct GiB, because the two disagreeing is what makes somebody think a disk is
 * fuller than it is.
 */

/**
 * The one locale this interface formats in.
 *
 * `en-GB` rather than `en-US`: the operators are European, and a 24-hour clock and a day-first date
 * are what every other surface of this deployment prints - the worker's logs, `docker ps`, the
 * archive names. A page that says 09/14 beside a log line that says 14/09 makes somebody check
 * twice.
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
const DATE_ONLY = new Intl.DateTimeFormat(LOCALE, { dateStyle: "medium" })

const UNITS = ["B", "kB", "MB", "GB", "TB", "PB"] as const

/** A byte count, base 1000, one decimal from kB upwards. */
export function bytes(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return "–"
  if (value < 1000) return `${NUMBER.format(Math.round(value))} B`
  let scaled = value
  let unit = 0
  while (scaled >= 1000 && unit < UNITS.length - 1) {
    scaled /= 1000
    unit += 1
  }
  // The loop scales the raw number, and the rounding to one decimal happens after it - so 999 999
  // came out as "1.000,0 kB", a quantity this function never means to print and the header's own
  // "base 1000" says it does not. The carry is checked on the rounded value, where it happens.
  if (Math.round(scaled * 10) >= 10_000 && unit < UNITS.length - 1) {
    scaled /= 1000
    unit += 1
  }
  return `${ONE_DECIMAL.format(scaled)} ${UNITS[unit]}`
}

/**
 * A percentage that is already 0-100.
 *
 * `decimals` is honoured, all of it. It used to pick between two formatters - "no decimals" and
 * "one" - and the first of those was `Intl.NumberFormat(LOCALE)` with nothing set, whose own
 * default is THREE. So `percent(87.4567, 0)` printed "87,457 %" in the traffic light's own sentence about
 * a full disk. And because the choice was `decimals === 0 ? … : …`, every other number the
 * signature accepts quietly meant one - `percent(x, 2)` type-checked and gave one decimal, which
 * is the kind of wrong nobody can see at the call site.
 */
export function percent(value: number | null | undefined, decimals = 1): string {
  if (value == null || !Number.isFinite(value)) return "–"
  return `${percentFormat(decimals).format(value)} %`
}

/** One `Intl.NumberFormat` per decimal count, built once - they are not cheap to construct. */
const PERCENT_FORMATS = new Map<number, Intl.NumberFormat>()

function percentFormat(decimals: number): Intl.NumberFormat {
  // Intl throws outside 0..20, and a caller asking for 21 decimals of a percentage has made a
  // mistake that must not become an exception in a dashboard.
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
  return value == null || !Number.isFinite(value) ? "–" : NUMBER.format(value)
}

/** Two decimals, for a load average - the one number where the third digit is noise. */
export function load(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return "–"
  return new Intl.NumberFormat(LOCALE, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

/** Cents, as the bunq rows carry them. */
export function euros(cents: number | null | undefined): string {
  if (cents == null || !Number.isFinite(cents)) return "–"
  return new Intl.NumberFormat(LOCALE, { style: "currency", currency: "EUR" }).format(cents / 100)
}

/**
 * An instant as the server sends it.
 *
 * The backend prints `String.valueOf(instant)` for the timestamps of a run, so a column that is
 * SQL NULL arrives as the four characters "null" rather than as JSON null. Both are handled here
 * rather than at every call site, because forgetting once puts the word "null" on the screen.
 */
export function parseInstant(value: string | null | undefined): Date | null {
  if (value == null || value === "null" || value === "") return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

/** Date and time, in full. */
export function dateTime(value: string | Date | null | undefined): string {
  const date = value instanceof Date ? value : parseInstant(value)
  return date == null ? "–" : DATE_TIME.format(date)
}

/**
 * The day only, without the clock - for a phone (steward/103).
 *
 * `active until 1 Dec 2026, 00:00` does not fit the access badge on a 390px screen, and a badge
 * clips rather than wraps, so it was drawn as `active until 1 Dec 2026, 00:0` - a date that looks
 * like a number and is not one. Cutting the clock is the answer rather than an ellipsis: a period
 * always ends at midnight, so the four characters that did not fit were the four that carried no
 * information. An ellipsis would have been honest about being cut and still unreadable.
 */
export function date(value: string | Date | null | undefined): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? "\u2013" : DATE_ONLY.format(parsed)
}

/** The clock only - for a log line, where the date is the same for every line on screen. */
export function clock(value: string | Date | null | undefined): string {
  const date = value instanceof Date ? value : parseInstant(value)
  return date == null ? "–" : TIME.format(date)
}

/**
 * "3 minutes ago", "in 2 hours".
 *
 * Intl.RelativeTimeFormat does the grammar, which is the part that is easy to get wrong by hand
 * and impossible to get wrong with the platform's own table.
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
  const date = value instanceof Date ? value : parseInstant(value)
  if (date == null) return "–"
  let delta = (date.getTime() - now) / 1000
  for (const [unit, size] of STEPS) {
    if (Math.abs(delta) < size) {
      return RELATIVE.format(Math.round(delta), unit)
    }
    delta /= size
  }
  return DATE_TIME.format(date)
}

/**
 * A span, spelled out: "3 d 4 h", "12 min".
 *
 * Used for uptime and for how long a run took. Deliberately two units at most - "3 days, 4 hours,
 * 11 minutes and 6 seconds" is a sentence nobody reads to the end.
 */
export function duration(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return "–"
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

/** How long ago an instant was, as a span rather than as "… ago". */
export function since(value: string | Date | null | undefined, now = Date.now()): string {
  const date = value instanceof Date ? value : parseInstant(value)
  return date == null ? "–" : duration((now - date.getTime()) / 1000)
}
