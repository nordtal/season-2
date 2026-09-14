import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"

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
 * than pretending the list is complete. **The bound holds while paused too** - the held lines are
 * the same memory and a tab left paused overnight is the same dead browser.
 *
 * **The lines and the count are one state, and it is not tidiness.** They used to be two, with the
 * counter raised from inside the list's own updater. React may call an updater more than once for
 * one update and StrictMode does it on every render in development, so each trim counted its
 * dropped lines twice and the window printed a number that was simply wrong. An updater that only
 * returns the next state can be called as often as React likes.
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
  const [buffer, setBuffer] = useState<{ lines: LogLine[]; dropped: number }>({
    lines: [],
    dropped: 0,
  })
  const { lines, dropped } = buffer
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

  /** Appends, trims to {@link LIMIT} and counts what fell out - in one pure updater. */
  const push = useCallback((incoming: LogLine[]) => {
    if (incoming.length === 0) return
    setBuffer((previous) => {
      const next = [...previous.lines, ...incoming]
      if (next.length <= LIMIT) return { lines: next, dropped: previous.dropped }
      const cut = next.length - LIMIT
      return { lines: next.slice(cut), dropped: previous.dropped + cut }
    })
  }, [])

  /**
   * The window belongs to ONE service, and the page can change which one without remounting.
   *
   * `/services/$name` carries no `remountDeps`, and TanStack Router only keys a match when it has
   * some - so walking from smp to the proxy re-renders this hook with a new name instead of giving
   * it a new instance. Without this the new service's heading sat above the old one's lines, `seq`
   * carried on from the old stream, and a pause held over the navigation flushed lines from a
   * service nobody was looking at any more. A layout effect rather than a plain one, because the
   * point is that the wrong lines are never painted; and separate from the connect effect below,
   * because `reconnect` re-runs that one and must NOT throw away what is already on screen.
   */
  useLayoutEffect(() => {
    held.current = []
    sequence.current = 0
    setBuffer({ lines: [], dropped: 0 })
  }, [service])

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
        if (held.current.length > LIMIT) {
          const cut = held.current.length - LIMIT
          held.current = held.current.slice(cut)
          setBuffer((previous) => ({ ...previous, dropped: previous.dropped + cut }))
        }
        return
      }
      const incoming = held.current.length ? [...held.current, line] : [line]
      held.current = []
      push(incoming)
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
        setError((current) => current ?? "The connection to the log stream was lost.")
      }
    }

    return () => source.close()
  }, [service, tail, attempt, push])

  // Resuming flushes whatever arrived while paused, in arrival order.
  useEffect(() => {
    if (paused || held.current.length === 0) return
    const incoming = held.current
    held.current = []
    push(incoming)
  }, [paused, push])

  const clear = useCallback(() => {
    held.current = []
    setBuffer({ lines: [], dropped: 0 })
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
