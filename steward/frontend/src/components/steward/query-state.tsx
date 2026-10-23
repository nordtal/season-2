import { PlugsIcon, TrayIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import type { ReactNode } from "react"

import { ApiError, errorMessage } from "@/lib/api"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { t } from "@/lib/texts"
export { Skeleton, SkeletonText } from "@/components/ui/skeleton"

/** A failure names the service that is down, taken from `ApiError.where`. */

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

/** `write` is set where an action failed rather than a read, so the heading does not speak of loading. */
export function Failure({ error, onRetry, write }: { error: unknown; onRetry?: () => void; write?: boolean }) {
  const api = error instanceof ApiError ? error : null
  const docker = api?.where === "docker"
  const agent = api?.where === "steward-agent"
  const Icon = docker || agent ? PlugsIcon : TERM_ICON.warning

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
              ? t("steward.failure.docker-silent")
              : agent
                ? t("steward.failure.agent-silent")
                : write
                  ? t("steward.failure.not-done")
                  : t("steward.failure.not-loaded")}
          </p>
          <p className="text-sm text-muted-foreground">
            {errorMessage(error)}
            {api?.status ? ` ${t("steward.failure.http", { status: api.status })}` : null}
          </p>
          {api?.detail ? (
            /**
             * `whitespace-pre-wrap` is set because a server's detail is one long line that a phone would cut off.
             */
            <pre className="mt-1 max-h-32 overflow-auto rounded-sm bg-muted px-2 py-1 text-xs break-words whitespace-pre-wrap text-muted-foreground">
              {api.detail}
            </pre>
          ) : null}
        </div>
      </div>
      {onRetry ? (
        <div>
          <Button type="button" variant="outline" size="sm" onClick={onRetry}>
            {t("steward.failure.try-again")}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

type QueryLike<T> = {
  data: T | undefined
  error: unknown
  isPending: boolean
  fetchStatus?: "fetching" | "paused" | "idle"
  refetch?: () => void
  dataUpdatedAt?: number
}

export const TRANSIENT_GRACE_MS = 60_000

/** Those errors usually come from a redeploy or restart. After a minute the failure shows. */
export function transient(query: QueryLike<unknown>, now = Date.now()): boolean {
  const error = query.error
  if (!(error instanceof ApiError) || ![502, 503, 504].includes(error.status)) return false
  if (query.data === undefined || !query.dataUpdatedAt) return false
  return now - query.dataUpdatedAt < TRANSIENT_GRACE_MS
}

/**
 * Both branches render the child bare, because a wrapper in only one of them would remount the child when the answer arrives.
 */
export function QueryState<T>(
  props: {
    query: QueryLike<T>
    empty?: { title: string; note?: string }
    isEmpty?: (data: T) => boolean
  } & (
    | {
        rows: number
        children: (data: T) => ReactNode
      }
    | {
        rows?: undefined
        children: (data: T | undefined) => ReactNode
      }
  ),
) {
  const { query, empty, isEmpty } = props

  if (query.isPending && query.fetchStatus === "idle") {
    return <Empty title={t("steward.failure.not-requested")} />
  }
  if (query.error && !transient(query)) return <Failure error={query.error} onRetry={query.refetch} />
  if (query.isPending || query.data === undefined) {
    if (props.rows === undefined) return <>{props.children(undefined)}</>
    return <Loading rows={props.rows} />
  }
  if (empty && isEmpty?.(query.data)) return <Empty title={empty.title} note={empty.note} />
  return <>{props.children(query.data)}</>
}
