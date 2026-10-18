import { useEffect, useState } from "react"

import { followStream } from "@/lib/event-stream"
import { t } from "@/lib/texts"

export const STEPS = [1000, 5000, 10000] as const
export const DEFAULT_LIMIT = STEPS[0]

/** How long a new connection may stay silent before its empty answer replaces the old lines. */
const REFILL_GRACE = 500

export type LogEntry = {
  /** React needs it as a key, and the line's own text never serves. */
  seq: number
  kind: "line" | "run" | "end"
  text: string
}

export type LogStream = {
  entries: LogEntry[]
  failure: string | null
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
 * Follows the live log of one service, which Steward reads over the Docker socket. Entries apply once per frame, and a broken connection refills the window and does not stitch it.
 */
export function useLogStream(service: string, limit: number = DEFAULT_LIMIT): LogStream {
  const [entries, setEntries] = useState<LogEntry[]>([])
  const [failure, setFailure] = useState<string | null>(null)

  /** Cleared during render, so the old lines never show under a new name. */
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
    /** Set for a new connection's first flush, which replaces the window. */
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
      failing: (said) => setFailure(said ?? t("steward.failure.log-lost")),
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
