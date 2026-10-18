import { useEffect, useState } from "react"

/** How often a relative time on screen moves on. */
const TICK_MS = 30_000

/**
 * Returns the current time, read once into state and ticking every {@link TICK_MS}, so one render sees one instant.
 */
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), TICK_MS)
    return () => clearInterval(timer)
  }, [])

  return now
}
