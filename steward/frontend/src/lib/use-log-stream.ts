import { useEffect, useState } from "react"

import { followStream } from "@/lib/event-stream"

/** The steps the window offers; steward says which it can fill (`logCapacity`). */
export const STEPS = [1000, 5000, 10000] as const
export const DEFAULT_LIMIT = STEPS[0]

/** How long a new connection may stay silent before its empty answer replaces the old lines. */
const REFILL_GRACE = 500

export type LogEntry = {
  /** Monotonic within one hook, the key React needs, never the line's own text. */
  seq: number
  kind: "line" | "run" | "end"
  text: string
}

export type LogStream = {
  entries: LogEntry[]
  /** Set only once {@link QUIET_FAILURES} attempts in a row have failed: what the server last said. */
  failure: string | null
}

const LOST = "The connection to the log was lost."

const nextFrame: (callback: () => void) => () => void =
  typeof requestAnimationFrame === "function"
    ? (callback) => {
        const id = requestAnimationFrame(callback)
        return () => cancelAnimationFrame(id)
      }
    : (callback) => {
        const id = setTimeout(callback, 16)
        return () => clearTimeout(id)
      }

/**
 * The live log of one service, followed by steward over the Docker socket the browser never reaches.
 *
 * Entries arrive oldest first, applied once per frame; after a broken connection the window is refilled, not stitched.
 */
export function useLogStream(service: string, limit: number = DEFAULT_LIMIT): LogStream {
  const [entries, setEntries] = useState<LogEntry[]>([])
  const [failure, setFailure] = useState<string | null>(null)

  /** Cleared during render when the service changes, so its old lines never show under the new name. */
  const [lastService, setLastService] = useState(service)
  if (lastService !== service) {
    setLastService(service)
    setEntries([])
    setFailure(null)
  }

  useEffect(() => {
    if (!service) return undefined
    let grace: ReturnType<typeof setTimeout> | undefined
    let cancelFrame: (() => void) | null = null
    let sequence = 0
    let pending: LogEntry[] = []
    /** The next flush replaces the window instead of adding to it, for a new connection's first. */
    let refill = false

    const flush = () => {
      cancelFrame = null
      const batch = pending
      const replace = refill
      pending = []
      refill = false
      clearTimeout(grace)
      setEntries((previous) => {
        const next = replace ? batch : previous.concat(batch)
        return next.length > limit ? next.slice(next.length - limit) : next
      })
    }

    const queue = (kind: LogEntry["kind"]) => (text: string) => {
      pending.push({ seq: sequence++, kind, text })
      cancelFrame ??= nextFrame(flush)
    }

    const stop = followStream(`/api/services/${encodeURIComponent(service)}/logs?tail=${limit}`, {
      events: { line: queue("line"), run: queue("run"), end: queue("end") },
      opened: () => {
        pending = []
        refill = true
        grace = setTimeout(flush, REFILL_GRACE)
      },
      broke: () => clearTimeout(grace),
      failing: (said) => setFailure(said ?? LOST),
      recovered: () => setFailure(null),
    })
    return () => {
      stop()
      clearTimeout(grace)
      cancelFrame?.()
    }
  }, [service, limit])

  return { entries, failure }
}
