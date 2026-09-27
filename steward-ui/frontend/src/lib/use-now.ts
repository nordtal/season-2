import { useEffect, useState } from "react"

/** How often a relative time on screen moves to the next minute worth noticing. */
const TICK_MS = 30_000

/** The current time, read once into state and ticked every {@link TICK_MS}, so one render sees one instant. */
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), TICK_MS)
    return () => clearInterval(timer)
  }, [])

  return now
}
