/** Failed attempts in a row that stay invisible, about half a minute with the backoff. */
export const QUIET_FAILURES = 5

/** Returns the wait before attempt `failures + 1`: 1, 2, 4 seconds and on, at most 30. */
export function backoff(failures: number): number {
  return Math.min(30_000, 1000 * 2 ** Math.max(0, failures - 1))
}

/** A connection open this long before it broke counts as a first failure. */
const LONG_LIVED = 30_000

export type StreamHandlers = {
  events: Record<string, (data: string) => void>
  opened?: () => void
  broke?: () => void
  /**
   * Called with the server's last message, or null, once {@link QUIET_FAILURES} attempts in a row failed.
   */
  failing?: (message: string | null) => void
  recovered?: () => void
}

function isMessageEvent(event: Event): event is MessageEvent<string> {
  return "data" in event
}

/**
 * Follows one SSE route until the returned function is called, and reconnects with a backoff. It closes the source on `gone` or `error`, because the browser's own retry would neither back off nor count.
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
