import { useEffect, useState } from "react"

/** The steps the window offers; the worker says which it can fill (`logCapacity`). */
export const STEPS = [1000, 5000, 10000] as const
export const DEFAULT_LIMIT = STEPS[0]

/** Failed attempts in a row that stay invisible, about half a minute with the backoff below. */
export const QUIET_FAILURES = 5

/** How long to wait before attempt `failures + 1`: 1, 2, 4 and on, never over 30 seconds. */
export function backoff(failures: number): number {
  return Math.min(30_000, 1000 * 2 ** Math.max(0, failures - 1))
}

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

/** An SSE event actually carries the server's data, the way every named event here does. */
function isMessageEvent(event: Event): event is MessageEvent<string> {
  return "data" in event
}

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
 * The live log of one service, through steward-ui so the browser never learns the worker's address.
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
    let stopped = false
    let source: EventSource | null = null
    let retry: ReturnType<typeof setTimeout> | undefined
    let grace: ReturnType<typeof setTimeout> | undefined
    let cancelFrame: (() => void) | null = null
    let failures = 0
    let said: string | null = null
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

    const queue = (kind: LogEntry["kind"], text: string) => {
      if (failures > 0) {
        failures = 0
        said = null
        setFailure(null)
      }
      pending.push({ seq: sequence++, kind, text })
      cancelFrame ??= nextFrame(flush)
    }

    const fail = (message: string | null) => {
      source?.close()
      source = null
      clearTimeout(grace)
      if (stopped) return
      failures++
      said = message ?? said
      if (failures >= QUIET_FAILURES) setFailure(said ?? LOST)
      retry = setTimeout(connect, backoff(failures))
    }

    const connect = () => {
      if (stopped) return
      const opened = new EventSource(`/api/services/${encodeURIComponent(service)}/logs?tail=${limit}`, {
        withCredentials: true,
      })
      source = opened
      opened.addEventListener("open", () => {
        pending = []
        refill = true
        grace = setTimeout(flush, REFILL_GRACE)
      })
      for (const kind of ["line", "run", "end"] as const) {
        opened.addEventListener(kind, (event) => {
          if (isMessageEvent(event)) queue(kind, event.data)
        })
      }
      /** The source is closed on `gone` or `error`, since the browser's own retry would neither back off nor count. */
      opened.addEventListener("gone", (event) => fail(isMessageEvent(event) ? event.data : null))
      opened.addEventListener("error", () => fail(null))
    }

    connect()
    return () => {
      stopped = true
      clearTimeout(retry)
      clearTimeout(grace)
      cancelFrame?.()
      source?.close()
    }
  }, [service, limit])

  return { entries, failure }
}
