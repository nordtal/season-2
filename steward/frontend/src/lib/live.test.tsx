import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query"
import { act, cleanup, renderHook, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { FakeEventSource } from "@/lib/fake-event-source"
import { announce, live, useLiveStream } from "@/lib/live"

function wrap(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

function mountQueries(client: QueryClient) {
  const reads = { runs: 0, people: 0, plain: 0 }
  renderHook(
    () => {
      useQuery({ queryKey: ["runs"], queryFn: () => ++reads.runs, ...live("RUNS") })
      useQuery({ queryKey: ["people"], queryFn: () => ++reads.people, ...live("PEOPLE", "JOURNAL") })
      useQuery({ queryKey: ["plain"], queryFn: () => ++reads.plain })
    },
    { wrapper: wrap(client) },
  )
  return reads
}

beforeEach(() => {
  FakeEventSource.opened = []
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe("announce", () => {
  it("reads again exactly the queries that follow the topic", async () => {
    const client = new QueryClient()
    const reads = mountQueries(client)
    await waitFor(() => expect(reads).toEqual({ runs: 1, people: 1, plain: 1 }))

    act(() => announce(client, "JOURNAL"))

    await waitFor(() => expect(reads.people).toBe(2))
    expect(reads).toEqual({ runs: 1, people: 2, plain: 1 })
  })
})

describe("useLiveStream", () => {
  it("turns a change on the stream into a read of its queries, and a reconnect into a read of all", async () => {
    vi.stubGlobal("EventSource", FakeEventSource)
    const client = new QueryClient()
    const reads = mountQueries(client)
    renderHook(() => useLiveStream(true), { wrapper: wrap(client) })
    await waitFor(() => expect(reads).toEqual({ runs: 1, people: 1, plain: 1 }))
    const stream = FakeEventSource.opened.at(-1)!
    expect(stream.url).toBe("/api/live")
    act(() => stream.emit("open"))

    act(() => stream.emit("change", JSON.stringify({ topic: "RUNS", version: "a1" })))
    await waitFor(() => expect(reads.runs).toBe(2))
    expect(reads).toEqual({ runs: 2, people: 1, plain: 1 })

    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] })
    act(() => stream.fail(FakeEventSource.CLOSED))
    act(() => {
      vi.advanceTimersByTime(1_000)
    })
    const again = FakeEventSource.opened.at(-1)!
    expect(again).not.toBe(stream)
    act(() => again.emit("open"))
    vi.useRealTimers()

    await waitFor(() => expect(reads).toEqual({ runs: 3, people: 2, plain: 1 }))
  })

  it("opens nothing while nobody is signed in", () => {
    vi.stubGlobal("EventSource", FakeEventSource)
    renderHook(() => useLiveStream(false), { wrapper: wrap(new QueryClient()) })
    expect(FakeEventSource.opened).toHaveLength(0)
  })
})
