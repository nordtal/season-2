/** Failed attempts in a row that stay invisible, about half a minute with the backoff below. */
export const QUIET_FAILURES = 5

/** How long to wait before attempt `failures + 1`: 1, 2, 4 and on, never over 30 seconds. */
export function backoff(failures: number): number {
  return Math.min(30_000, 1000 * 2 ** Math.max(0, failures - 1))
}

/** A connection that stayed open this long before it broke starts the count again, like a first failure. */
const LONG_LIVED = 30_000

export type StreamHandlers = {
  /** The named events to listen for, each handed its data. */
  events: Record<string, (data: string) => void>
  /** Each connection that opens, the first and every reconnect. */
  opened?: () => void
  /** Each connection that breaks, before the wait for the next. */
  broke?: () => void
  /** What the server last said, or null if nothing, once {@link QUIET_FAILURES} attempts in a row failed. */
  failing?: (message: string | null) => void
  /** The first event after {@link failing} was called. */
  recovered?: () => void
}

/** An SSE event actually carries the server's data, the way every named event here does. */
function isMessageEvent(event: Event): event is MessageEvent<string> {
  return "data" in event
}

/**
 * Follows one SSE route of steward until the returned stop is called, reconnecting with a backoff.
 *
 * The source is closed on `gone` or `error`, since the browser's own retry would neither back off nor count.
 */
export function followStream(url: string, handlers: StreamHandlers): () => void {
  let stopped = false
  let source: EventSource | null = null
  let retry: ReturnType<typeof setTimeout> | undefined
  let failures = 0
  let said: string | null = null
  let since = 0

  const delivered = () => {
    if (failures === 0) return
    const wasFailing = failures >= QUIET_FAILURES
    failures = 0
    said = null
    if (wasFailing) handlers.recovered?.()
  }

  const fail = (message: string | null) => {
    source?.close()
    source = null
    handlers.broke?.()
    if (stopped) return
    if (since > 0 && Date.now() - since >= LONG_LIVED) delivered()
    since = 0
    failures++
    said = message ?? said
    if (failures >= QUIET_FAILURES) handlers.failing?.(said)
    retry = setTimeout(connect, backoff(failures))
  }

  const connect = () => {
    if (stopped) return
    const opened = new EventSource(url, { withCredentials: true })
    source = opened
    opened.addEventListener("open", () => {
      since = Date.now()
      handlers.opened?.()
    })
    for (const [name, handle] of Object.entries(handlers.events)) {
      opened.addEventListener(name, (event) => {
        if (!isMessageEvent(event)) return
        delivered()
        handle(event.data)
      })
    }
    opened.addEventListener("gone", (event) => fail(isMessageEvent(event) ? event.data : null))
    opened.addEventListener("error", () => fail(null))
  }

  connect()
  return () => {
    stopped = true
    clearTimeout(retry)
    source?.close()
  }
}
