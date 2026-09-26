import { act, renderHook } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { QUIET_FAILURES, backoff, useLogStream } from "@/lib/use-log-stream"
import type { LogStream } from "@/lib/use-log-stream"

/**
 * The contract of the console's stream: entries in the log's order and bounded at the
 * chosen limit, applied a frame at a time, refilled - not stitched - on every new connection, and a
 * broken connection retried on its own with a backoff, silently for the first
 * {@link QUIET_FAILURES} failures and with the server's sentence after them.
 *
 * jsdom has no `EventSource`, so the tests install one. It is deliberately dumb - it records
 * listeners, lets a test push an event through them and counts `close()` - because a clever fake
 * tests itself instead of the hook.
 */

type Listener = (event: Event) => void

class FakeEventSource {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2

  /** Every source the hook has constructed, in order. StrictMode makes two of them on mount. */
  static opened: FakeEventSource[] = []

  readonly url: string
  readonly withCredentials: boolean
  readyState: number = FakeEventSource.CONNECTING
  closeCalls = 0
  onerror: Listener | null = null

  private readonly listeners = new Map<string, Listener[]>()

  constructor(url: string, init?: { withCredentials?: boolean }) {
    this.url = url
    this.withCredentials = init?.withCredentials ?? false
    FakeEventSource.opened.push(this)
  }

  addEventListener(type: string, listener: Listener): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  removeEventListener(type: string, listener: Listener): void {
    this.listeners.set(
      type,
      (this.listeners.get(type) ?? []).filter((one) => one !== listener),
    )
  }

  close(): void {
    this.readyState = FakeEventSource.CLOSED
    this.closeCalls++
  }

  /** What the server sends. Text rides on a `MessageEvent.data`, exactly as the real one delivers it. */
  emit(type: string, data?: string): void {
    if (type === "open") this.readyState = FakeEventSource.OPEN
    const event = data === undefined ? new Event(type) : new MessageEvent(type, { data })
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }

  /** An `error` with the ready state the browser would be in - flaky retry, or given up for good. */
  fail(readyState: number): void {
    this.readyState = readyState
    this.onerror?.(new Event("error"))
  }
}

/**
 * The source the hook is currently listening on. Always the newest: a StrictMode mount and a
 * `reconnect` both leave a closed one behind, and a test that talked to it would test nothing.
 */
function live(): FakeEventSource {
  const source = FakeEventSource.opened.at(-1)
  if (!source) throw new Error("the hook never constructed an EventSource")
  return source
}

function mount(limit = 3) {
  const rendered = renderHook(
    ({ service, limit }: { service: string; limit: number }) => useLogStream(service, limit),
    { initialProps: { service: "smp", limit } },
  )
  act(() => live().emit("open"))
  return rendered
}

const texts = (result: { current: LogStream }) => result.current.entries.map((entry) => entry.text)

/** Lets the frame that applies a batch run. */
function frame(): void {
  act(() => vi.advanceTimersByTime(20))
}

function send(...lines: string[]): void {
  act(() => {
    for (const line of lines) live().emit("line", line)
  })
  frame()
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "requestAnimationFrame", "cancelAnimationFrame"] })
  FakeEventSource.opened = []
  ;(globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource
})

afterEach(() => {
  vi.useRealTimers()
})

describe("useLogStream", () => {
  it("asks for the chosen number of lines", () => {
    mount(5000)
    expect(live().url).toBe("/api/services/smp/logs?tail=5000")
    expect(live().withCredentials).toBe(true)
  })

  it("keeps the log's order and the three kinds, and drops the oldest past the limit", () => {
    const { result } = mount(3)
    act(() => {
      live().emit("end", "Nothing older")
      live().emit("run", "Earlier run, 22 Sep 19:44")
      live().emit("line", "a")
    })
    frame()
    expect(result.current.entries.map((entry) => entry.kind)).toEqual(["end", "run", "line"])

    send("b")
    expect(texts(result)).toEqual(["Earlier run, 22 Sep 19:44", "a", "b"])
  })

  it("applies what arrives within one frame as one update", () => {
    const { result } = mount(10)
    act(() => {
      live().emit("line", "a")
      live().emit("line", "b")
    })
    expect(texts(result)).toEqual([])
    frame()
    expect(texts(result)).toEqual(["a", "b"])
  })

  it("reconnects on its own after a break, backing off, and refills instead of stitching", () => {
    const { result } = mount(10)
    send("old1", "old2")
    const first = live()

    act(() => first.fail(FakeEventSource.CLOSED))
    expect(first.closeCalls).toBe(1)
    expect(texts(result)).toEqual(["old1", "old2"])
    expect(FakeEventSource.opened).toHaveLength(1)

    act(() => vi.advanceTimersByTime(backoff(1)))
    expect(FakeEventSource.opened).toHaveLength(2)
    act(() => live().emit("open"))
    send("old2", "new")
    expect(texts(result)).toEqual(["old2", "new"])
  })

  it("replaces the old lines with an empty answer once the new connection stays silent", () => {
    const { result } = mount(10)
    send("old")
    act(() => live().fail(FakeEventSource.CLOSED))
    act(() => vi.advanceTimersByTime(backoff(1)))
    act(() => live().emit("open"))
    frame()
    expect(texts(result)).toEqual(["old"])
    act(() => vi.advanceTimersByTime(600))
    expect(texts(result)).toEqual([])
  })

  it("backs off 1, 2, 4 ... and never beyond 30 seconds", () => {
    expect([1, 2, 3, 4, 5, 6, 9].map(backoff)).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000])
  })

  it("says nothing for the first failures and the server's sentence after them, and goes on trying", () => {
    const { result } = mount()
    send("still here")
    for (let attempt = 1; attempt < QUIET_FAILURES; attempt++) {
      act(() => live().emit("gone", "no running container for smp"))
      expect(result.current.failure).toBeNull()
      act(() => vi.advanceTimersByTime(backoff(attempt)))
    }
    expect(texts(result)).toEqual(["still here"])

    act(() => live().fail(FakeEventSource.CLOSED))
    expect(result.current.failure).toBe("no running container for smp")

    const before = FakeEventSource.opened.length
    act(() => vi.advanceTimersByTime(30_000))
    expect(FakeEventSource.opened.length).toBe(before + 1)
    act(() => live().emit("open"))
    send("back")
    expect(result.current.failure).toBeNull()
    expect(texts(result)).toEqual(["back"])
  })

  it("opens a new stream with the new number when the limit changes", () => {
    const { rerender } = mount(1000)
    const first = live()
    rerender({ service: "smp", limit: 5000 })
    expect(first.closeCalls).toBe(1)
    expect(live().url).toBe("/api/services/smp/logs?tail=5000")
  })

  it("closes the source and retries nothing once the window goes away", () => {
    const { unmount } = mount()
    const source = live()
    unmount()
    expect(source.closeCalls).toBeGreaterThanOrEqual(1)
    act(() => vi.advanceTimersByTime(60_000))
    expect(FakeEventSource.opened).toHaveLength(1)
  })

  it("shows only the new service's lines when the service it watches changes", () => {
    const { result, rerender } = mount(10)
    send("alpha")
    rerender({ service: "proxy", limit: 10 })
    act(() => live().emit("open"))
    send("beta")
    expect(live().url).toBe("/api/services/proxy/logs?tail=10")
    expect(texts(result)).toEqual(["beta"])
  })
})
