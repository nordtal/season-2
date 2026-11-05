import { useEffect, useState } from "react"

/** How often a relative time on screen moves on. */
const TICK_MS = 30_000

/**
 * Returns the current time, read once into state and ticking every {@link TICK_MS}, so one render sees one instant.
 *
 * @param since when the data on screen was read, so a time the data names is never ahead of the clock it is shown by
 */
export function useNow(since = 0): number {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), TICK_MS)
    return () => clearInterval(timer)
  }, [])

  return Math.max(now, since)
}
