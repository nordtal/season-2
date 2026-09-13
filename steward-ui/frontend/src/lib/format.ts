/**
 * Every number this interface prints goes through here.
 *
 * Two reasons it is one file. Formatting a byte count in six places is six chances to disagree
 * about whether a gigabyte has 1000 or 1024 megabytes - and an operator comparing a card against a
 * table has to be able to trust that "1,4 GB" in one place is the same quantity as "1,4 GB" in the
 * other. And the locale is German everywhere: a decimal point where a comma belongs is the kind of
 * detail that makes a page read as translated rather than written.
 *
 * Base 1000, not 1024, and the unit says so. Docker reports memory in bytes and the disk numbers
 * come from `statvfs`; both are counts of bytes, and the host's own `df` prints base-1000 for the
 * same file system. Matching what the machine's own tools say is worth more here than the
 * pedantically correct GiB, because the two disagreeing is what makes somebody think a disk is
 * fuller than it is.
 */

const NUMBER = new Intl.NumberFormat("de-DE")
const ONE_DECIMAL = new Intl.NumberFormat("de-DE", {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
})
const DATE_TIME = new Intl.DateTimeFormat("de-DE", {
  dateStyle: "medium",
  timeStyle: "short",
})
const TIME = new Intl.DateTimeFormat("de-DE", { timeStyle: "medium" })

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
 * "one" - and the first of those was `Intl.NumberFormat("de-DE")` with nothing set, whose own
 * default is THREE. So `percent(87.4567, 0)` printed "87,457 %" in the Ampel's own sentence about
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
    format = new Intl.NumberFormat("de-DE", {
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
  return new Intl.NumberFormat("de-DE", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

/** Cents, as the bunq rows carry them. */
export function euros(cents: number | null | undefined): string {
  if (cents == null || !Number.isFinite(cents)) return "–"
  return new Intl.NumberFormat("de-DE", { style: "currency", currency: "EUR" }).format(cents / 100)
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

/** The clock only - for a log line, where the date is the same for every line on screen. */
export function clock(value: string | Date | null | undefined): string {
  const date = value instanceof Date ? value : parseInstant(value)
  return date == null ? "–" : TIME.format(date)
}

/**
 * "vor 3 Minuten", "in 2 Stunden".
 *
 * Intl.RelativeTimeFormat does the grammar, which is the part that is easy to get wrong in German
 * and impossible to get wrong with the platform's own table.
 */
const RELATIVE = new Intl.RelativeTimeFormat("de-DE", { numeric: "auto" })

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
 * A span, spelled out: "3 T 4 Std", "12 Min".
 *
 * Used for uptime and for how long a run took. Deliberately two units at most - "3 Tage, 4 Stunden,
 * 11 Minuten und 6 Sekunden" is a sentence nobody reads to the end.
 */
export function duration(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return "–"
  const whole = Math.floor(seconds)
  if (whole < 60) return `${whole} s`
  const minutes = Math.floor(whole / 60)
  if (minutes < 60) return `${minutes} Min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    const rest = minutes % 60
    return rest === 0 ? `${hours} Std` : `${hours} Std ${rest} Min`
  }
  const days = Math.floor(hours / 24)
  const rest = hours % 24
  return rest === 0 ? `${days} T` : `${days} T ${rest} Std`
}

/** How long ago an instant was, as a span rather than as "vor …". */
export function since(value: string | Date | null | undefined, now = Date.now()): string {
  const date = value instanceof Date ? value : parseInstant(value)
  return date == null ? "–" : duration((now - date.getTime()) / 1000)
}
