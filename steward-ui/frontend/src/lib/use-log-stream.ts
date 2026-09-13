import { useCallback, useEffect, useMemo, useRef, useState } from "react"

/**
 * The live log of one service.
 *
 * It is an `EventSource` against steward-ui, which re-emits what steward-worker re-emits from the
 * Docker socket. Three hops and no redirect anywhere, deliberately: a redirect to the worker would
 * hand the browser the worker's address and its shared secret, and that is the single thing the
 * whole steward-ui/steward-worker split exists to prevent.
 *
 * **The buffer is bounded and says so.** A busy Minecraft server writes faster than anybody reads,
 * and an unbounded array in a tab left open overnight is a browser that has to be killed. The oldest
 * lines fall out at {@link LIMIT}; `dropped` counts them, and the window prints that count rather
 * than pretending the list is complete.
 */

/** How many lines the window keeps. 5 000 lines of Minecraft log is roughly 500 kB in memory. */
export const LIMIT = 5000

export type LogLine = {
  /** Monotonic within one stream - the key React needs, and never the line's own text. */
  seq: number
  text: string
  /** When this end received it. Docker's own timestamps are inside the text, if at all. */
  at: number
}

export type LogStream = {
  lines: LogLine[]
  dropped: number
  /** connecting → open → closed. `error` carries the sentence the server sent, when it sent one. */
  state: "connecting" | "open" | "closed"
  error: string | null
  paused: boolean
  setPaused: (paused: boolean) => void
  clear: () => void
  /** Close and open again - what the "neu verbinden" button does. */
  reconnect: () => void
}

export function useLogStream(service: string, tail = 200): LogStream {
  const [lines, setLines] = useState<LogLine[]>([])
  const [dropped, setDropped] = useState(0)
  const [state, setState] = useState<LogStream["state"]>("connecting")
  const [error, setError] = useState<string | null>(null)
  const [paused, setPaused] = useState(false)
  const [attempt, setAttempt] = useState(0)

  const sequence = useRef(0)
  // Lines that arrive while the window is paused are kept, not dropped: pausing is for reading, not
  // for looking away. They land in the list the moment it resumes.
  const held = useRef<LogLine[]>([])
  const pausedRef = useRef(paused)
  pausedRef.current = paused

  useEffect(() => {
    if (!service) return undefined
    setState("connecting")
    setError(null)

    const source = new EventSource(
      `/api/services/${encodeURIComponent(service)}/logs?tail=${tail}`,
      { withCredentials: true },
    )

    const append = (text: string) => {
      const line: LogLine = { seq: sequence.current++, text, at: Date.now() }
      if (pausedRef.current) {
        held.current.push(line)
        return
      }
      setLines((previous) => {
        const next = held.current.length ? [...previous, ...held.current, line] : [...previous, line]
        held.current = []
        if (next.length <= LIMIT) return next
        const cut = next.length - LIMIT
        setDropped((count) => count + cut)
        return next.slice(cut)
      })
    }

    source.addEventListener("open", () => setState("open"))
    source.addEventListener("line", (event) => append((event as MessageEvent<string>).data))
    source.addEventListener("gone", (event) => {
      setError((event as MessageEvent<string>).data)
      setState("closed")
      source.close()
    })
    source.onerror = () => {
      // EventSource reconnects by itself while the connection is merely flaky. Only a source it has
      // given up on reports CLOSED, and that is the only case worth telling the reader about.
      if (source.readyState === EventSource.CLOSED) {
        setState("closed")
        setError((current) => current ?? "Die Verbindung zum Logstrom ist abgerissen.")
      }
    }

    return () => source.close()
  }, [service, tail, attempt])

  // Resuming flushes whatever arrived while paused, in arrival order.
  useEffect(() => {
    if (paused || held.current.length === 0) return
    setLines((previous) => {
      const next = [...previous, ...held.current]
      held.current = []
      if (next.length <= LIMIT) return next
      const cut = next.length - LIMIT
      setDropped((count) => count + cut)
      return next.slice(cut)
    })
  }, [paused])

  const clear = useCallback(() => {
    held.current = []
    setLines([])
    setDropped(0)
  }, [])

  const reconnect = useCallback(() => {
    setError(null)
    setAttempt((count) => count + 1)
  }, [])

  return useMemo(
    () => ({ lines, dropped, state, error, paused, setPaused, clear, reconnect }),
    [lines, dropped, state, error, paused, clear, reconnect],
  )
}
