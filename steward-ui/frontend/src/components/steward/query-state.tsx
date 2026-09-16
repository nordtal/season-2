import type { ReactNode } from "react"
import { AlertTriangle, Inbox, ServerCrash } from "lucide-react"

import { ApiError } from "@/lib/api"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"

/**
 * Loading, empty and failed - the three states every list in this interface has to have.
 *
 * They are here rather than at each call site because a missing one is invisible until the day it
 * matters: a table that renders nothing while it loads and nothing when it is empty tells an
 * operator the same thing in two situations that could not be more different.
 *
 * **A failure names which service is down.** `ApiError.where` is one of the three the deployment
 * has, and the difference matters: "this page is broken", "the daemon is not answering, so nothing
 * on this host can be read", and "nothing can be deployed right now" are three different evenings.
 * An empty table would say none of them.
 */

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
      <Inbox className="size-5 text-muted-foreground" aria-hidden />
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
  const worker = api?.where === "steward-worker"
  const deployer = api?.where === "steward-deployer"
  const Icon = worker || deployer ? ServerCrash : AlertTriangle

  return (
    <div
      role="alert"
      className="flex flex-col gap-3 rounded-md border border-destructive/40 bg-destructive/5 px-4 py-4"
    >
      <div className="flex items-start gap-3">
        <Icon className="mt-0.5 size-4 shrink-0 text-destructive" aria-hidden />
        <div className="flex min-w-0 flex-col gap-1">
          <p className="text-sm font-medium">
            {worker
              ? "steward-worker is not answering."
              : deployer
                ? "steward-deployer is not answering."
                : "This information could not be loaded."}
          </p>
          <p className="text-sm text-muted-foreground">
            {api ? api.message : String(error)}
            {api?.status ? ` (HTTP ${api.status})` : null}
          </p>
          {worker ? (
            <p className="max-w-prose text-sm text-muted-foreground">
              Everything about a container - status, log, console - comes from this service. The
              interface itself is running; this list is not empty because of that.
            </p>
          ) : null}
          {deployer ? (
            <p className="max-w-prose text-sm text-muted-foreground">
              Only this service may create containers. While it stays silent nothing can be
              recreated - the stack keeps running regardless.
            </p>
          ) : null}
          {api?.detail ? (
            // `whitespace-pre-wrap`: a server's detail is one long line, and a box that only
            // scrolls sideways on a phone is a box that reads "There is no config file called
            // steward-w" and stops.
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

/**
 * The three states around one query, in one place - and the fourth, which is not a state of the
 * query at all.
 *
 * `data` is handed to the child only once it exists, so a page never writes `data?.` chains for a
 * value it has already waited for.
 *
 * **A disabled query is `isPending` for ever**, and that is the trap this component fell into.
 * Measured on 2026-09-14 on `/configuration`: skeletons, and nothing after them, because the page
 * had mounted `useConfig("")` and `enabled: Boolean(file)` had switched it off. There is no data
 * and none is coming, so "loading" was a lie the interface told indefinitely. `fetchStatus` tells
 * the two apart - `"idle"` beside `isPending` is switched off, `"fetching"` is on its way - and it
 * is optional here because some callers hand in a plain object rather than a query result.
 */
export function QueryState<T>({
  query,
  children,
  rows,
  empty,
  isEmpty,
}: {
  query: {
    data: T | undefined
    error: unknown
    isPending: boolean
    fetchStatus?: "fetching" | "paused" | "idle"
    refetch?: () => void
  }
  children: (data: T) => ReactNode
  rows?: number
  empty?: { title: string; note?: string }
  isEmpty?: (data: T) => boolean
}) {
  if (query.isPending && query.fetchStatus === "idle") {
    return (
      <Empty
        title="Nothing was requested here."
        note="This query is switched off, so no answer is on its way. That is a fault in the page rather than in the service - the skeletons below it would otherwise never end."
      />
    )
  }
  if (query.isPending) return <Loading rows={rows} />
  if (query.error) return <Failure error={query.error} onRetry={query.refetch} />
  if (query.data === undefined) return <Loading rows={rows} />
  if (empty && isEmpty?.(query.data)) return <Empty title={empty.title} note={empty.note} />
  return <>{children(query.data)}</>
}
