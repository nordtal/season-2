import { PlugsIcon, TrayIcon, WarningIcon } from "@phosphor-icons/react"
import type { ReactNode } from "react"

import { ApiError } from "@/lib/api"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
export { Skeleton, SkeletonText } from "@/components/ui/skeleton"

/**
 * Loading, empty and failed: the three states every list here must have, so none is silently missing.
 *
 * A failure names which service is down, from `ApiError.where`.
 */

/** Flat grey bars, for views whose loading state really is n bars of one height. */
export function Loading({ rows = 5, label }: { rows?: number; label?: string }) {
  return (
    <div className="flex flex-col gap-2" role="status" aria-busy="true">
      <span className="sr-only">{label ?? "Loading…"}</span>
      {Array.from({ length: rows }, (_, index) => (
        <Skeleton key={index} className="h-row w-full" />
      ))}
    </div>
  )
}

export function Empty({ title, note, action }: { title: string; note?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-md border border-dashed border-border px-6 py-10 text-center">
      <TrayIcon className="size-5 text-muted-foreground" aria-hidden />
      <div className="flex flex-col gap-1">
        <p className="text-sm font-medium">{title}</p>
        {note ? <p className="max-w-prose text-sm text-muted-foreground">{note}</p> : null}
      </div>
      {action}
    </div>
  )
}

export function Failure({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const api = error instanceof ApiError ? error : null
  const docker = api?.where === "docker"
  const deployer = api?.where === "steward-deployer"
  const Icon = docker || deployer ? PlugsIcon : WarningIcon

  return (
    <div
      role="alert"
      className="flex flex-col gap-3 rounded-md border border-destructive/40 bg-destructive/5 px-4 py-4"
    >
      <div className="flex items-start gap-3">
        <Icon className="mt-0.5 size-4 shrink-0 text-destructive" aria-hidden />
        <div className="flex min-w-0 flex-col gap-1">
          <p className="text-sm font-medium">
            {docker
              ? "Docker is not answering."
              : deployer
                ? "steward-deployer is not answering."
                : "This information could not be loaded."}
          </p>
          <p className="text-sm text-muted-foreground">
            {api ? api.message : String(error)}
            {api?.status ? ` (HTTP ${api.status})` : null}
          </p>
          {docker ? (
            <p className="max-w-prose text-sm text-muted-foreground">
              Everything about a container - status, log, console - comes from the Docker daemon. The interface itself
              is running; this list is not empty because of that.
            </p>
          ) : null}
          {deployer ? (
            <p className="max-w-prose text-sm text-muted-foreground">
              Only this service may create containers. While it stays silent nothing can be recreated - the stack keeps
              running regardless.
            </p>
          ) : null}
          {api?.detail ? (
            /** `whitespace-pre-wrap`, since a server's detail is one long line that a phone would cut off. */
            <pre className="mt-1 max-h-32 overflow-auto rounded-sm bg-muted px-2 py-1 text-xs break-words whitespace-pre-wrap text-muted-foreground">
              {api.detail}
            </pre>
          ) : null}
        </div>
      </div>
      {onRetry ? (
        <div>
          <Button type="button" variant="outline" size="sm" onClick={onRetry}>
            Try again
          </Button>
        </div>
      ) : null}
    </div>
  )
}

/** The parts of a query this reads; `fetchStatus` is optional, for callers handing in a plain object. */
type QueryLike<T> = {
  data: T | undefined
  error: unknown
  isPending: boolean
  fetchStatus?: "fetching" | "paused" | "idle"
  refetch?: () => void
  dataUpdatedAt?: number
}

/** How long an answer outlives a gateway error before the error is shown instead of it. */
export const TRANSIENT_GRACE_MS = 60_000

/**
 * A 502, 503 or 504 over data that is still recent, so the answer stays standing.
 *
 * Those are usually a redeploy or restart that the next poll recovers from; after a minute the failure shows.
 */
export function transient(query: QueryLike<unknown>, now = Date.now()): boolean {
  const error = query.error
  if (!(error instanceof ApiError) || ![502, 503, 504].includes(error.status)) return false
  if (query.data === undefined || !query.dataUpdatedAt) return false
  return now - query.dataUpdatedAt < TRANSIENT_GRACE_MS
}

/**
 * The states around one query, with `children` called with `undefined` while waiting and with the data after.
 *
 * Both branches render the child bare, since a wrapper in only one would remount it when the answer lands.
 */
export function QueryState<T>(
  props: {
    query: QueryLike<T>
    empty?: { title: string; note?: string }
    isEmpty?: (data: T) => boolean
  } & (
    | {
        /** Flat grey bars instead of the child's own shape; the child is then only called with data. */
        rows: number
        children: (data: T) => ReactNode
      }
    | {
        rows?: undefined
        /** Called with `undefined` while waiting, and with the data once it is here. */
        children: (data: T | undefined) => ReactNode
      }
  ),
) {
  const { query, empty, isEmpty } = props

  if (query.isPending && query.fetchStatus === "idle") {
    return (
      <Empty
        title="Nothing was requested here."
        note="This query is switched off, so no answer is on its way. That is a fault in the page rather than in the service - the skeletons below it would otherwise never end."
      />
    )
  }
  if (query.error && !transient(query)) return <Failure error={query.error} onRetry={query.refetch} />
  if (query.isPending || query.data === undefined) {
    if (props.rows === undefined) return <>{props.children(undefined)}</>
    return <Loading rows={props.rows} />
  }
  if (empty && isEmpty?.(query.data)) return <Empty title={empty.title} note={empty.note} />
  return <>{props.children(query.data)}</>
}
