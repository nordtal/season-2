import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, renderHook, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError, currentCsrf, rememberCsrf, type ConfigDocument } from "@/lib/api"
import { RECONCILE } from "@/lib/live"
import { keys, useCommandRun, useMe, useSaveConfig } from "@/lib/queries"

/**
 * When a poll stops, and what a save sends.
 *
 * A poll must stop once its query errors; a save must drop the cached document on a 409 and only then.
 */

/** A response the way the browser hands it to `api()`. */
function answer(status: number, body: unknown): Response {
  return new Response(body === undefined ? "" : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

/** A client with retries off, so a failing test does not wait for a second attempt. */
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

/** The refetch interval TanStack would use, read from the cached query rather than re-implemented. */
function pollOf(queryClient: QueryClient, key: readonly unknown[]) {
  const query = queryClient.getQueryCache().find({ queryKey: key })
  if (!query) throw new Error(`nothing in the cache under ${JSON.stringify(key)}`)
  /** `refetchInterval` exists at runtime but not in `QueryOptions`; the `typeof` below is the assertion. */
  const options: object = query.options
  const interval = "refetchInterval" in options ? options.refetchInterval : undefined
  if (typeof interval !== "function") {
    throw new Error(`refetchInterval is ${String(interval)}, not a function of the query`)
  }
  return interval(query)
}

/** Narrows a mutation's `error` to the `ApiError` every path under test throws. */
function asApiError(error: Error | null): ApiError {
  if (!(error instanceof ApiError)) throw new Error(`expected an ApiError, got ${String(error)}`)
  return error
}

/** The query's state in the cache, which is what `refetchInterval` sees, unlike the hook's render result. */
function stateOf(queryClient: QueryClient, key: readonly unknown[]) {
  const state = queryClient.getQueryState(key)
  if (!state) throw new Error(`nothing in the cache under ${JSON.stringify(key)}`)
  return state
}

let fetched: ReturnType<typeof vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>>

beforeEach(() => {
  fetched = vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>()
  vi.stubGlobal("fetch", fetched)
})

afterEach(() => {
  vi.unstubAllGlobals()
  /** Unmounts every tree, so no polling hook leaves a live interval for the next test file. */
  cleanup()
})

describe("useCommandRun - when the reconciliation stops", () => {
  it("keeps reading once a minute while the row is unsettled", async () => {
    const queryClient = client()
    for (const status of ["PENDING", "RUNNING"] as const) {
      fetched.mockResolvedValue(answer(200, { id: "7", status }))
      renderHook(() => useCommandRun("7"), { wrapper: wrap(queryClient) })
      await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).data).toBeDefined())

      expect(pollOf(queryClient, keys.commandRun("7"))).toBe(RECONCILE)
      queryClient.clear()
    }
  })

  it("keeps asking while nothing has come back yet, so a spinner is never left sitting", async () => {
    /** Enabled, in flight, no data and no error: a slow first answer must not end the polling. */
    const queryClient = client()
    fetched.mockImplementation(() => new Promise<Response>(() => {}))
    renderHook(() => useCommandRun("7"), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(fetched).toHaveBeenCalled())

    expect(pollOf(queryClient, keys.commandRun("7"))).toBe(RECONCILE)
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
    /** The row said RUNNING, then steward stopped answering; without the guard the poll would go on. */
    const queryClient = client()
    fetched.mockResolvedValueOnce(answer(200, { id: "7", status: "RUNNING" }))
    const { result } = renderHook(() => useCommandRun("7"), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).data).toBeDefined())

    fetched.mockImplementation(async () => answer(502, { error: "Docker is not answering.", where: "docker" }))
    await result.current.refetch()
    await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).error).toBeInstanceOf(ApiError))

    /** The stale RUNNING is still cached, and the poll is off anyway. */
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
    /** A successful retry restarts the poll. */
    const queryClient = client()
    fetched.mockRejectedValueOnce(new TypeError("Failed to fetch"))
    const { result } = renderHook(() => useCommandRun("7"), { wrapper: wrap(queryClient) })
    await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).error).toBeInstanceOf(ApiError))

    fetched.mockImplementation(async () => answer(200, { id: "7", status: "RUNNING" }))
    await result.current.refetch()
    await waitFor(() => expect(stateOf(queryClient, keys.commandRun("7")).error).toBeNull())

    expect(pollOf(queryClient, keys.commandRun("7"))).toBe(RECONCILE)
  })
})

describe("useSaveConfig - the revision travels with the change", () => {
  const FILE = "steward/web"
  const SAVED: ConfigDocument = {
    service: "steward",
    name: "web",
    path: FILE,
    label: "",
    live: true,
    readable: true,
    writable: true,
    revision: "rev-2",
    restartRequired: false,
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
    /** The revision is sent, and the path keeps its slashes so the route matches. */
    await save(client(), answer(200, SAVED))

    const [url, init] = fetched.mock.calls[0]
    expect(url).toBe("/api/setting-groups/steward/web")
    expect(init?.method).toBe("PUT")
    expect(JSON.parse(init?.body ?? "")).toEqual({
      revision: "rev-1",
      changes: { "alerts.disk-percent": "70" },
    })
  })

  it("redraws the form from the answer, so the next save carries the new revision", async () => {
    /** The answer is the file as it now reads, so the cache takes its new revision. */
    const queryClient = client()
    await save(queryClient, answer(200, SAVED))

    expect(queryClient.getQueryData(keys.config(FILE))).toEqual(SAVED)
  })

  it("drops the cached document on 409, because its revision is provably stale", async () => {
    /** Somebody else was faster, so the stale copy is dropped, not only the error shown. */
    const queryClient = client()
    queryClient.setQueryData(keys.config(FILE), { ...SAVED, revision: "rev-1", restartRequired: false })
    const invalidated = vi.spyOn(queryClient, "invalidateQueries")

    const result = await save(queryClient, answer(409, { error: "The file has changed in the meantime." }))

    expect(asApiError(result.current.error).status).toBe(409)
    expect(invalidated).toHaveBeenCalledExactlyOnceWith({ queryKey: keys.config(FILE) })
  })

  it("keeps the cached document on every other failure", async () => {
    /** Any other failure keeps the cached copy, so the form keeps what was typed. */
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
    /** An unreachable interface is status 0, which is not 409. */
    const queryClient = client()
    const invalidated = vi.spyOn(queryClient, "invalidateQueries")

    const result = await save(queryClient, new TypeError("Failed to fetch"))

    expect(asApiError(result.current.error).status).toBe(0)
    expect(invalidated).not.toHaveBeenCalled()
  })
})

describe("useMe - the token every write in the interface needs", () => {
  beforeEach(() => rememberCsrf(null))

  it("puts the token where api() can read it synchronously", async () => {
    /** The CSRF token arrives only with /api/me, which is why the shell refuses to draw without it. */
    const queryClient = client()
    fetched.mockResolvedValue(answer(200, { signedIn: true, id: "1", name: "ally", csrf: "t0ken", webauthn: "x" }))
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
