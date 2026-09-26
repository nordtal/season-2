import { useEffect, useLayoutEffect, useState } from "react"

/**
 * The live log of one service.
 *
 * It is an `EventSource` against steward-ui, which re-emits what steward-worker re-emits from the
 * Docker socket. Three hops and no redirect anywhere, deliberately: a redirect to the worker would
 * hand the browser the worker's address and its shared secret, and that is the single thing the
 * whole steward-ui/steward-worker split exists to prevent.
 *
 * **Three kinds of entry, in the log's own order, oldest first.** `line` is a line; `run` is the
 * grey line the worker puts above an earlier run it read out of the volume; `end` says there is
 * nothing older. The window turns the list round - newest on top - and nothing here does.
 *
 * **No pause, no clear, no reconnect button.** A broken connection is retried on its own, 1, 2, 4
 * … up to 30 seconds apart and without end. Every new connection fills the window afresh with the
 * chosen number of lines instead of stitching onto what is there: there is no matching of
 * duplicates, the same stance the LISTEN/NOTIFY loop takes. The first {@link QUIET_FAILURES}
 * failures in a row stay invisible and the old lines stay standing; only after them does
 * `failure` carry a sentence, and the attempts go on underneath it.
 *
 * **Lines are applied once per animation frame**, as one block, which is what lets the window slide
 * a block in rather than re-render for every line of a busy server.
 */

/** The steps the window offers. The worker says which of them it can fill (`logCapacity`). */
export const STEPS = [1000, 5000, 10000] as const
export const DEFAULT_LIMIT = STEPS[0]

/** Failed attempts in a row that stay invisible - about half a minute with the backoff below. */
export const QUIET_FAILURES = 5

/** How long to wait before attempt `failures + 1`: 1, 2, 4 … seconds, never over 30. */
export function backoff(failures: number): number {
  return Math.min(30_000, 1000 * 2 ** Math.max(0, failures - 1))
}

/**
 * How long a new connection may stay silent before its (empty) answer replaces the old lines.
 * The backlog arrives within milliseconds; waiting for it keeps the window from flashing empty.
 */
const REFILL_GRACE = 500

export type LogEntry = {
  /** Monotonic within one hook - the key React needs, and never the line's own text. */
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

export function useLogStream(service: string, limit: number = DEFAULT_LIMIT): LogStream {
  const [entries, setEntries] = useState<LogEntry[]>([])
  const [failure, setFailure] = useState<string | null>(null)

  // The window belongs to ONE service, and the page can change which one without remounting: a
  // layout effect so that the old service's lines are never painted under the new one's name.
  useLayoutEffect(() => {
    setEntries([])
    setFailure(null)
  }, [service])

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
    /** The next flush replaces the window instead of adding to it: a new connection's first. */
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
        opened.addEventListener(kind, (event) => queue(kind, (event as MessageEvent<string>).data))
      }
      // `gone` is the server's own sentence for why it stopped; an `error` has none. Either way
      // the source is closed here, because the browser's own retry would neither back off nor
      // count, and the counting is what decides when the window says something.
      opened.addEventListener("gone", (event) => fail((event as MessageEvent<string>).data))
      opened.onerror = () => fail(null)
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
