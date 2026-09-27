import { useEffect, useState } from "react"

/** How often a relative time on screen moves to the next minute worth noticing. */
const TICK_MS = 30_000

/**
 * The current time, ticking - for a page that shows "3 min ago" beside a live table.
 *
 * `Date.now()` read straight into a render is impure: React may call a component more than once
 * for one commit, and two calls a moment apart would disagree about what "now" is. This hook reads
 * it once into state and moves it forward from an effect instead, so every read within one render
 * sees the same instant and the page still catches up every {@link TICK_MS}.
 */
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), TICK_MS)
    return () => clearInterval(timer)
  }, [])

  return now
}
