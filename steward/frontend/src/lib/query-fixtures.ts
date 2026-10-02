import type { QueryObserverSuccessResult, UseQueryResult } from "@tanstack/react-query"
import type { NetworkMap } from "@/lib/api"

/** The URL `fetch` was actually asked for, whichever of its three argument shapes carried it. */
export function urlOf(input: RequestInfo | URL): string {
  if (typeof input === "string") return input
  if (input instanceof URL) return input.toString()
  return input.url
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null
}

/** A fetch mock's parsed request body, narrowed to `{ changes }` without trusting the caller's cast. */
export function changesOf(body: unknown): Record<string, unknown> {
  const changes = isRecord(body) && "changes" in body ? body.changes : undefined
  if (!isRecord(changes)) throw new Error("expected a { changes } payload")
  return changes
}

/** {@link changesOf}, for the callers that know every one of their changes is a plain string. */
export function stringChangesOf(body: unknown): Record<string, string> {
  const changes = changesOf(body)
  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(changes)) {
    if (typeof value !== "string") throw new Error(`expected ${key} to be a string change`)
    result[key] = value
  }
  return result
}

/** A settled, successful TanStack Query result carrying the given data, for mocking a query hook in a test. */
export function queryResult<T>(data: T): UseQueryResult<T> {
  const result: QueryObserverSuccessResult<T> = {
    data,
    dataUpdatedAt: 0,
    error: null,
    errorUpdateCount: 0,
    errorUpdatedAt: 0,
    failureCount: 0,
    failureReason: null,
    fetchStatus: "idle",
    isEnabled: true,
    isError: false,
    isFetched: true,
    isFetchedAfterMount: true,
    isFetching: false,
    isInitialLoading: false,
    isLoading: false,
    isLoadingError: false,
    isPaused: false,
    isPending: false,
    isPlaceholderData: false,
    isRefetchError: false,
    isRefetching: false,
    isStale: false,
    isSuccess: true,
    refetch: () => Promise.resolve(result),
    status: "success",
  }
  return result
}

/** `/api/topology` as `compose.yml`'s labels answer it, in compose's order, which is not the drawn one. */
export const NETWORK_MAP: NetworkMap = {
  services: [
    { name: "postgres", section: "Database", entry: false, reaches: [], storesIn: [] },
    { name: "discord-bot", section: "Discord", entry: false, reaches: [], storesIn: ["postgres"] },
    { name: "proxy", section: "Entry", entry: true, reaches: ["smp", "hunger-games", "limbo"], storesIn: ["postgres"] },
    { name: "limbo", section: "Paper", entry: false, reaches: [], storesIn: ["postgres"] },
    { name: "hunger-games", section: "Paper", entry: false, reaches: [], storesIn: ["postgres"] },
    { name: "smp", section: "Paper", entry: false, reaches: [], storesIn: ["postgres"] },
    {
      name: "steward",
      section: "Steward",
      entry: false,
      reaches: ["steward-agent", "steward-bunq"],
      storesIn: ["postgres"],
    },
    { name: "steward-agent", section: "Steward", entry: false, reaches: [], storesIn: [] },
    { name: "steward-bunq", section: "Steward", entry: false, reaches: [], storesIn: [] },
    { name: "caddy", section: "Entry", entry: true, reaches: ["steward"], storesIn: [] },
  ],
}
