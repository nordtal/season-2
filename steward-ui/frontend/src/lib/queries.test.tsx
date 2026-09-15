import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import type { Query } from "@tanstack/react-query"
import { renderHook, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError, currentCsrf, rememberCsrf, type ConfigDocument } from "@/lib/api"
import { keys, useCommandRun, useDeployerJob, useMe, useSaveConfig } from "@/lib/queries"

/**
 * The two decisions in this file that are not "which URL" - when a poll stops, and what a save
 * sends - held against the failures they were written for.
 *
 * **The polls.** `useCommandRun` and `useDeployerJob` both keep asking once a second while their
 * row is unsettled. The last answer they hold says PENDING or RUNNING, and it goes on saying so
 * after the service behind it has stopped answering - so without the `query.state.error` guard a
 * dead deployer is a failed request manufactured once a second, for as long as the dialog is open.
 * That guard is the thing under test, and it is tested through the options TanStack actually
 * resolved rather than through a copy of them: `refetchInterval` is fetched off the `Query` in the
 * cache and called with that same `Query`, which is exactly what the library does with it.
 *
 * **The save.** A PUT without a revision is refused by the backend, and a PUT with a stale one is
 * answered 409 - which is the whole of the protection against two open forms. 409 is therefore the
 * one failure that has to drop the cached document, revision and all; every other failure must
 * leave it alone, because the copy in the cache is still what the file says.
 */

/** A response the way the browser hands it to `api()`: a body, a status, and nothing clever. */
function answer(status: number, body: unknown): Response {
  return new Response(body === undefined ? "" : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

/**
 * A client with retries off.
 *
 * main.tsx ships `retry: 1`, which is what the comment on `useCommandRun` counts on - by the time
 * an error lands the query has already asked twice. Here it would only make every failing test
 * wait for a second attempt without changing what is being asserted.
 */
function client(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

function wrap(queryClient: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}

/**
 * The refetch interval TanStack would use, taken from the cache rather than from the source.
 *
 * Reading `query.options.refetchInterval` is the point: a hook that stopped passing a function -
 * or passed a plain number again - fails here at the `typeof` rather than silently passing a test
 * that re-implements the predicate.
 */
type Poll = (query: Query<unknown, Error, unknown, readonly unknown[]>) => number | false | undefined

function pollOf(queryClient: QueryClient, key: readonly unknown[]) {
  const query = queryClient.getQueryCache().find({ queryKey: key })
  if (!query) throw new Error(`nothing in the cache under ${JSON.stringify(key)}`)
  // `Query.options` is typed `QueryOptions`, which is the fetching half; `refetchInterval` belongs
  // to the observer's half and is present at runtime but not in that type. Narrowed by hand rather
  // than silently: the `typeof` below is the assertion, not the cast.
  const interval = (query.options as { refetchInterval?: Poll | number | false }).refetchInterval
  if (typeof interval !== "function") {
    throw new Error(`refetchInterval is ${String(interval)}, not a function of the query`)
  }
  return interval(query as Query<unknown, Error, unknown, readonly unknown[]>)
}

/**
 * The query's own state, which is what TanStack hands to `refetchInterval` - and not the hook's
 * React result.
 *
 * They part company exactly here: `notifyOnChangeProps` defaults to tracking the properties a
 * render touched, so a refetch that fails while `data` stays put notifies nobody, and a test
 * reading `result.current.error` waits for a re-render that correctly never comes. The cache is
 * the honest place to look, because it is the place the predicate under test looks.
 */
function stateOf(queryClient: QueryClient, key: readonly unknown[]) {
  const state = queryClient.getQueryState(key)
  if (!state) throw new Error(`nothing in the cache under ${JSON.stringify(key)}`)
  return state
}

let fetched: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetched = vi.fn()
  vi.stubGlobal("fetch", fetched)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("useCommandRun - when the polling stops", () => {
  it("keeps asking every second while the row is unsettled", async () => {
    const queryClient = client()
    for (const status of ["PENDING", "RUNNING"] as const) {
      fetched.mockResolvedValue(answer(200, { id: "7", status }))
      renderHook(() => useCommandRun("7"), { wrapper: wrap(queryClient) })
      await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).data).toBeDefined())

      expect(pollOf(queryClient, keys.commandRun("7"))).toBe(1000)
      queryClient.clear()
    }
  })

  it("keeps asking while nothing has come back yet, so a spinner is never left sitting", async () => {
    // Enabled, in flight, no data and no error. The row was written a moment ago and the service
    // that owns it has not claimed it; a first answer that is slow must not end the polling.
    const queryClient = client()
    fetched.mockImplementation(() => new Promise<Response>(() => {}))
    renderHook(() => useCommandRun("7"), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(fetched).toHaveBeenCalled())

    expect(pollOf(queryClient, keys.commandRun("7"))).toBe(1000)
  })

  it("stops for each of the three settled states", async () => {
    const queryClient = client()
    for (const status of ["DONE", "FAILED", "EXPIRED"] as const) {
      fetched.mockResolvedValue(answer(200, { id: "7", status }))
      renderHook(() => useCommandRun("7"), { wrapper: wrap(queryClient) })
      await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).data).toBeDefined())

      expect(pollOf(queryClient, keys.commandRun("7"))).toBe(false)
      queryClient.clear()
    }
  })

  it("stops on a failed poll even though the answer it still holds says RUNNING", async () => {
    // The case the guard exists for, and the only one where data and error are both set: the row
    // said RUNNING, then steward-ui stopped answering. Without the guard the interval would be
    // read off `data` - which is now a memory of a service that is gone - and the dialog would
    // manufacture one failed request a second for as long as it stays open.
    const queryClient = client()
    fetched.mockResolvedValueOnce(answer(200, { id: "7", status: "RUNNING" }))
    const { result } = renderHook(() => useCommandRun("7"), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).data).toBeDefined())

    fetched.mockImplementation(async () =>
      answer(502, { error: "steward-worker is not answering.", where: "steward-worker" }),
    )
    await result.current.refetch()
    await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).error).toBeInstanceOf(ApiError))

    // Both halves of the premise, so that a test passing for the wrong reason is visible: the
    // stale RUNNING is still in the cache, and the poll is off anyway.
    expect(stateOf(queryClient, keys.commandRun("7")).data).toMatchObject({ status: "RUNNING" })
    expect(pollOf(queryClient, keys.commandRun("7"))).toBe(false)
  })

  it("stops when the very first poll fails and there is no answer at all", async () => {
    const queryClient = client()
    fetched.mockRejectedValue(new TypeError("Failed to fetch"))
    renderHook(() => useCommandRun("7"), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).error).toBeInstanceOf(ApiError))

    expect(pollOf(queryClient, keys.commandRun("7"))).toBe(false)
  })

  it("starts asking again once a retry gets through", async () => {
    // The other direction, which is what makes the stop bearable: `Failure` offers "Try again",
    // and a poll that never came back after a successful retry would turn one bad second into a
    // dialog that has to be closed and reopened.
    const queryClient = client()
    fetched.mockRejectedValueOnce(new TypeError("Failed to fetch"))
    const { result } = renderHook(() => useCommandRun("7"), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).error).toBeInstanceOf(ApiError))

    fetched.mockImplementation(async () => answer(200, { id: "7", status: "RUNNING" }))
    await result.current.refetch()
    await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).error).toBeNull())

    expect(pollOf(queryClient, keys.commandRun("7"))).toBe(1000)
  })
})

describe("useDeployerJob - when the polling stops", () => {
  it("asks every second while compose is still working", async () => {
    const queryClient = client()
    fetched.mockResolvedValue(answer(200, { id: "j1", state: "RUNNING", lines: [] }))
    renderHook(() => useDeployerJob("j1"), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(stateOf(queryClient, keys.deployerJob("j1")).data).toBeDefined())

    expect(pollOf(queryClient, keys.deployerJob("j1"))).toBe(1000)
  })

  it("stops once the job has ended, in either direction", async () => {
    const queryClient = client()
    for (const state of ["DONE", "FAILED"] as const) {
      fetched.mockResolvedValue(answer(200, { id: "j1", state, lines: [], exitCode: 0 }))
      renderHook(() => useDeployerJob("j1"), { wrapper: wrap(queryClient) })
      await waitFor(() => expect(stateOf(queryClient, keys.deployerJob("j1")).data).toBeDefined())

      expect(pollOf(queryClient, keys.deployerJob("j1"))).toBe(false)
      queryClient.clear()
    }
  })

  it("stops on a failed poll while the job it remembers is still RUNNING", async () => {
    // steward-deployer going away mid-recreate is the realistic version of this: the container it
    // was recreating may be down, the last answer says RUNNING for ever, and the dialog is open.
    const queryClient = client()
    fetched.mockResolvedValueOnce(answer(200, { id: "j1", state: "RUNNING", lines: ["Container smp  Recreating"] }))
    const { result } = renderHook(() => useDeployerJob("j1"), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(stateOf(queryClient, keys.deployerJob("j1")).data).toBeDefined())

    fetched.mockImplementation(async () =>
      answer(502, { error: "steward-deployer is not answering.", where: "steward-deployer" }),
    )
    await result.current.refetch()
    await waitFor(() => expect(stateOf(queryClient, keys.deployerJob("j1")).error).toBeInstanceOf(ApiError))

    expect(stateOf(queryClient, keys.deployerJob("j1")).data).toMatchObject({ state: "RUNNING" })
    expect(pollOf(queryClient, keys.deployerJob("j1"))).toBe(false)
  })

  it("refreshes the service table once the job has ended and not while it runs", async () => {
    // The container is new, so its state, uptime and digest all are - but asked for while compose
    // is still working the answer would be a container in the middle of being taken down.
    const queryClient = client()
    const invalidated = vi.spyOn(queryClient, "invalidateQueries")

    fetched.mockResolvedValueOnce(answer(200, { id: "j1", state: "RUNNING", lines: [] }))
    const { result } = renderHook(() => useDeployerJob("j1"), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(stateOf(queryClient, keys.deployerJob("j1")).data).toBeDefined())
    expect(invalidated).not.toHaveBeenCalled()

    fetched.mockImplementation(async () => answer(200, { id: "j1", state: "DONE", lines: [], exitCode: 0 }))
    await result.current.refetch()
    await waitFor(() =>
      expect(stateOf(queryClient, keys.deployerJob("j1")).data).toMatchObject({ state: "DONE" }),
    )

    expect(invalidated.mock.calls.map(([argument]) => argument)).toEqual([
      { queryKey: keys.services },
      { queryKey: ["service"] },
    ])
  })
})

describe("useSaveConfig - the revision travels with the change", () => {
  const FILE = "steward/steward-ui.yml"
  const SAVED: ConfigDocument = {
    service: "steward-ui",
    name: "steward-ui.yml",
    path: FILE,
    readable: true,
    writable: true,
    revision: "rev-2",
    header: [],
    entries: [],
  }

  async function save(queryClient: QueryClient, response: Response | Error) {
    if (response instanceof Error) fetched.mockRejectedValue(response)
    else fetched.mockResolvedValue(response)
    const { result } = renderHook(() => useSaveConfig(FILE), { wrapper: wrap(queryClient) })
    result.current.mutate({ revision: "rev-1", changes: { "alerts.disk-percent": "70" } })
    await waitFor(() => expect(result.current.isPending).toBe(false))
    return result
  }

  it("puts the revision and the changes in the body, at the path the listing gave", async () => {
    // The revision is not optional - the backend refuses a PUT without one - and the path keeps
    // its slashes: encodeURIComponent over the whole string would turn them into %2F and the route
    // would stop matching.
    await save(client(), answer(200, SAVED))

    const [url, init] = fetched.mock.calls[0] as [string, RequestInit]
    expect(url).toBe("/api/config/steward/steward-ui.yml")
    expect(init.method).toBe("PUT")
    expect(JSON.parse(String(init.body))).toEqual({
      revision: "rev-1",
      changes: { "alerts.disk-percent": "70" },
    })
  })

  it("redraws the form from the answer, so the next save carries the new revision", async () => {
    // The answer IS the file as it now reads. Keeping the old document would leave rev-1 in the
    // cache, and the very next save would be answered 409 by a backend that is perfectly happy.
    const queryClient = client()
    await save(queryClient, answer(200, SAVED))

    expect(queryClient.getQueryData(keys.config(FILE))).toEqual(SAVED)
  })

  it("drops the cached document on 409, because its revision is provably stale", async () => {
    // Somebody else was faster. Nothing was written, and a second attempt with the revision in
    // this cache would be refused for exactly the same reason - so the copy has to go, not just
    // the error be shown.
    const queryClient = client()
    queryClient.setQueryData(keys.config(FILE), { ...SAVED, revision: "rev-1" })
    const invalidated = vi.spyOn(queryClient, "invalidateQueries")

    const result = await save(queryClient, answer(409, { error: "The file has changed in the meantime." }))

    expect((result.current.error as ApiError).status).toBe(409)
    expect(invalidated).toHaveBeenCalledExactlyOnceWith({ queryKey: keys.config(FILE) })
  })

  it("keeps the cached document on every other failure", async () => {
    // A 500, a refused value, a proxy that timed out: the file on disk is untouched and the copy
    // in this cache is still what it says. Throwing it away would make the form redraw and lose
    // what the operator typed, for a save that can simply be pressed again.
    for (const failure of [
      answer(500, { error: "Broken." }),
      answer(422, { error: "alerts.disk-percent is not a number." }),
      answer(403, { error: "No." }),
    ]) {
      const queryClient = client()
      const invalidated = vi.spyOn(queryClient, "invalidateQueries")

      const result = await save(queryClient, failure)

      expect(result.current.error).toBeInstanceOf(ApiError)
      expect(invalidated).not.toHaveBeenCalled()
    }
  })

  it("keeps it when the request never arrived anywhere either", async () => {
    // `api()` turns an unreachable interface into an ApiError with status 0. Zero is not 409, and
    // an `!== 200` test in place of the `=== 409` one would land here.
    const queryClient = client()
    const invalidated = vi.spyOn(queryClient, "invalidateQueries")

    const result = await save(queryClient, new TypeError("Failed to fetch"))

    expect((result.current.error as ApiError).status).toBe(0)
    expect(invalidated).not.toHaveBeenCalled()
  })
})

describe("useMe - the token every write in the interface needs", () => {
  beforeEach(() => rememberCsrf(null))

  it("puts the token where api() can read it synchronously", async () => {
    // This is the whole argument for the shell refusing to draw on a failed /api/me: the CSRF
    // token arrives with this answer and with no other, so a shell drawn without it would refuse
    // every write, one confusing page at a time.
    const queryClient = client()
    fetched.mockResolvedValue(answer(200, { signedIn: true, id: "1", name: "till", csrf: "t0ken", webauthn: "x" }))
    renderHook(() => useMe(), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(stateOf(queryClient, keys.me).data).toBeDefined())

    expect(currentCsrf()).toBe("t0ken")
  })

  it("leaves it empty when the answer was a refusal, rather than keeping the last one", async () => {
    const queryClient = client()
    fetched.mockResolvedValue(answer(200, { signedIn: false, webauthn: "x" }))
    renderHook(() => useMe(), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(stateOf(queryClient, keys.me).data).toBeDefined())

    expect(currentCsrf()).toBeNull()
  })
})
