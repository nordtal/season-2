import { StrictMode } from "react"
import type { ReactNode } from "react"
import { act, renderHook } from "@testing-library/react"
import { beforeEach, describe, expect, it } from "vitest"
import { LIMIT, useLogStream } from "@/lib/use-log-stream"
import type { LogStream } from "@/lib/use-log-stream"

/**
 * The contract of the live log window, as its own doc comment states it: the buffer is bounded at
 * {@link LIMIT} - while paused as well - `dropped` counts what fell out, pausing holds lines rather
 * than losing them, resuming flushes them in arrival order, `clear` resets both, `reconnect` opens
 * a new source.
 *
 * Two of these tests run the hook inside `<StrictMode>` and that is not decoration. React invokes a
 * `useState` updater twice per update in development StrictMode, which is the only environment where
 * a counter raised from inside another updater is *guaranteed* to count twice rather than merely
 * allowed to. Measured against a replica of the old two-state shape: StrictMode reported 2 dropped
 * lines for one trim, the same hook outside StrictMode reported 1. A test written without the
 * wrapper would therefore have passed against the defect it was written for.
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
    this.listeners.set(type, (this.listeners.get(type) ?? []).filter((one) => one !== listener))
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

const strictMode = ({ children }: { children: ReactNode }) => <StrictMode>{children}</StrictMode>

function mount(options?: { strict?: boolean }) {
  const rendered = renderHook(({ service }: { service: string }) => useLogStream(service), {
    initialProps: { service: "paper-survival" },
    wrapper: options?.strict ? strictMode : undefined,
  })
  act(() => live().emit("open"))
  return rendered
}

const now = (result: { current: LogStream }) => result.current

/** `count` lines named `${prefix}0`, `${prefix}1`, ... so that order is readable in a failure. */
function send(count: number, prefix: string): void {
  act(() => {
    for (let index = 0; index < count; index++) live().emit("line", `${prefix}${index}`)
  })
}

beforeEach(() => {
  FakeEventSource.opened = []
  ;(globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource
})

describe("useLogStream", () => {
  it("reports connecting until the server answers, and open once it has", () => {
    const { result } = renderHook(() => useLogStream("paper-survival"))

    expect(now(result).state).toBe("connecting")
    expect(live().url).toBe("/api/services/paper-survival/logs?tail=200")
    // The cookie is the session; without it the stream is anonymous and the server refuses it.
    expect(live().withCredentials).toBe(true)

    act(() => live().emit("open"))

    expect(now(result).state).toBe("open")
    expect(now(result).error).toBeNull()
  })

  it("keeps every line and drops nothing when exactly LIMIT lines arrive", () => {
    const { result } = mount()

    send(LIMIT, "l")

    expect(now(result).lines).toHaveLength(LIMIT)
    expect(now(result).dropped).toBe(0)
    expect(now(result).lines[0].text).toBe("l0")
    expect(now(result).lines.at(-1)?.text).toBe(`l${LIMIT - 1}`)
  })

  it("drops the oldest line and counts exactly one when LIMIT + 1 lines arrive", () => {
    const { result } = mount()

    send(LIMIT + 1, "l")

    expect(now(result).lines).toHaveLength(LIMIT)
    expect(now(result).dropped).toBe(1)
    expect(now(result).lines[0].text).toBe("l1")
  })

  it("counts each trim on top of the last when the window overflows twice", () => {
    const { result } = mount()

    send(LIMIT + 1, "a")
    expect(now(result).dropped).toBe(1)

    // A second, separate update: the counter has to carry the first trim rather than restart.
    act(() => {
      live().emit("line", "b0")
      live().emit("line", "b1")
      live().emit("line", "b2")
    })

    expect(now(result).dropped).toBe(4)
    expect(now(result).lines).toHaveLength(LIMIT)
    expect(now(result).lines.at(-1)?.text).toBe("b2")
  })

  it("holds the lines that arrive while paused and flushes them in arrival order on resume", () => {
    const { result } = mount()

    send(2, "before")
    act(() => now(result).setPaused(true))
    send(3, "during")

    // Pausing is for reading: the window must not move, and nothing may be lost either.
    expect(now(result).lines.map((line) => line.text)).toEqual(["before0", "before1"])
    expect(now(result).dropped).toBe(0)

    act(() => now(result).setPaused(false))

    expect(now(result).lines.map((line) => line.text))
      .toEqual(["before0", "before1", "during0", "during1", "during2"])
    expect(now(result).dropped).toBe(0)
  })

  it("bounds the held lines while paused and reports the loss before the window is resumed", () => {
    const { result } = mount()

    act(() => now(result).setPaused(true))
    send(LIMIT + 50, "held")

    // The point of the bound is the memory of a tab left paused overnight, so it has to hold *while*
    // paused. Waiting for the resume to trim would make `dropped` right and the browser dead.
    expect(now(result).dropped).toBe(50)
    expect(now(result).lines).toHaveLength(0)

    act(() => now(result).setPaused(false))

    expect(now(result).lines).toHaveLength(LIMIT)
    expect(now(result).dropped).toBe(50)
    expect(now(result).lines[0].text).toBe("held50")
    expect(now(result).lines.at(-1)?.text).toBe(`held${LIMIT + 49}`)
  })

  it("trims and counts what the flush itself pushes out of the window", () => {
    const { result } = mount()

    send(100, "old")
    act(() => now(result).setPaused(true))
    send(LIMIT, "new")
    act(() => now(result).setPaused(false))

    // The flush alone overflows: 100 + LIMIT arrive, so the 100 lines read before the pause go.
    expect(now(result).lines).toHaveLength(LIMIT)
    expect(now(result).dropped).toBe(100)
    expect(now(result).lines[0].text).toBe("new0")
  })

  it("throws the held lines away too when the window is cleared while paused", () => {
    const { result } = mount()

    send(2, "before")
    act(() => now(result).setPaused(true))
    send(3, "during")
    act(() => now(result).clear())

    expect(now(result).lines).toHaveLength(0)

    // Resuming must not resurrect what the reader has just cleared.
    act(() => now(result).setPaused(false))

    expect(now(result).lines).toHaveLength(0)
    expect(now(result).dropped).toBe(0)
  })

  it("resets the lines and the dropped counter together when cleared", () => {
    const { result } = mount()

    send(LIMIT + 7, "l")
    expect(now(result).dropped).toBe(7)

    act(() => now(result).clear())

    expect(now(result).lines).toHaveLength(0)
    expect(now(result).dropped).toBe(0)
  })

  it("gives every line a key of its own, across a pause and a trim", () => {
    const { result } = mount()

    send(3, "a")
    act(() => now(result).setPaused(true))
    send(3, "b")
    act(() => now(result).setPaused(false))

    // React keys the rows on `seq`, so a repeat would silently drop a row from the DOM.
    const sequences = now(result).lines.map((line) => line.seq)
    expect(new Set(sequences).size).toBe(sequences.length)
    expect([...sequences].sort((a, b) => a - b)).toEqual(sequences)
  })

  it("closes the source and reports the server's own sentence when the stream is gone", () => {
    const { result } = mount()
    const source = live()

    act(() => source.emit("gone", "This service does not exist any more."))

    expect(now(result).state).toBe("closed")
    expect(now(result).error).toBe("This service does not exist any more.")
    // `gone` is final, so the hook must hang up rather than let EventSource retry forever.
    expect(source.closeCalls).toBe(1)
  })

  it("says nothing while EventSource is only retrying, and reports the break once it gives up", () => {
    const { result } = mount()

    act(() => live().fail(FakeEventSource.CONNECTING))

    // A flaky connection retries itself. Telling the reader about it would cry wolf on every blip.
    expect(now(result).state).toBe("open")
    expect(now(result).error).toBeNull()

    act(() => live().fail(FakeEventSource.CLOSED))

    expect(now(result).state).toBe("closed")
    expect(now(result).error).toBe("The connection to the log stream was lost.")
  })

  it("keeps the server's sentence when the connection then dies underneath it", () => {
    const { result } = mount()

    act(() => live().emit("gone", "The container was removed."))
    act(() => live().fail(FakeEventSource.CLOSED))

    // The server's own reason is more use to a reader than the generic one.
    expect(now(result).error).toBe("The container was removed.")
  })

  it("opens a second source and goes back to connecting when reconnect is called", () => {
    const { result } = mount()
    const first = live()

    act(() => first.emit("gone", "it ended"))
    act(() => now(result).reconnect())

    expect(FakeEventSource.opened).toHaveLength(2)
    expect(live()).not.toBe(first)
    expect(now(result).state).toBe("connecting")
    expect(now(result).error).toBeNull()

    act(() => live().emit("open"))
    expect(now(result).state).toBe("open")
  })

  it("closes the source when the window goes away", () => {
    const { unmount } = mount()
    const source = live()

    unmount()

    expect(source.closeCalls).toBeGreaterThanOrEqual(1)
  })

  /**
   * FAILS ON PURPOSE, and is reported rather than adjusted.
   *
   * The route is `/services/$name` with no `remountDeps`, so TanStack Router hands the same
   * `ServicePage` a new name instead of remounting it. The hook opens a stream for the new service
   * but keeps the old one's window, so paper-survival's lines sit above velocity's under
   * velocity's heading - and a line held over a pause on the old service is flushed into the new
   * one. Neither the lines, the held ref nor `sequence` is reset when `service` changes.
   */
  it("shows only the new service's lines when the service it watches changes", () => {
    const { result, rerender } = mount()

    send(2, "alpha")
    act(() => now(result).setPaused(true))
    send(1, "alphaHeld")

    rerender({ service: "velocity" })
    act(() => live().emit("open"))
    act(() => now(result).setPaused(false))
    act(() => live().emit("line", "beta0"))

    expect(live().url).toBe("/api/services/velocity/logs?tail=200")
    expect(now(result).lines.map((line) => line.text)).toEqual(["beta0"])
  })

  it("counts one dropped line once, not twice, when StrictMode invokes the updater twice", () => {
    const { result } = mount({ strict: true })

    send(LIMIT + 1, "l")

    expect(now(result).dropped).toBe(1)
    expect(now(result).lines).toHaveLength(LIMIT)
    expect(now(result).lines[0].text).toBe("l1")
  })

  it("counts two separate trims once each under StrictMode", () => {
    const { result } = mount({ strict: true })

    send(LIMIT + 4, "a")
    expect(now(result).dropped).toBe(4)

    act(() => {
      live().emit("line", "b0")
      live().emit("line", "b1")
    })

    expect(now(result).dropped).toBe(6)
    expect(now(result).lines).toHaveLength(LIMIT)
  })

  it("counts lines dropped from the held buffer once under StrictMode", () => {
    const { result } = mount({ strict: true })

    act(() => now(result).setPaused(true))
    send(LIMIT + 20, "held")

    // The paused path raises the counter through its own updater, so it doubles just as easily.
    expect(now(result).dropped).toBe(20)

    act(() => now(result).setPaused(false))

    expect(now(result).dropped).toBe(20)
    expect(now(result).lines).toHaveLength(LIMIT)
    expect(now(result).lines[0].text).toBe("held20")
  })
})
