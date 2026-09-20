import { PlugsIcon, TrayIcon, WarningIcon } from "@phosphor-icons/react"
import type { ReactNode } from "react"

import { ApiError } from "@/lib/api"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
export { Skeleton, SkeletonText } from "@/components/ui/skeleton"

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

/**
 * The fallback shape, for the places whose own layout is a single block anyway.
 *
 * **This is not the normal way to wait any more** (steward/120). A component that carries data
 * draws its own skeleton, because a generic grey row is not what replaces it and the difference
 * shows as a jump the moment the answer lands. `rows` stays for the handful of views whose loading
 * state genuinely is n bars of the same height - and for the ones where the real layout cannot be
 * drawn without the data, which is a case the ticket foresaw and asked to be written down where it
 * happens rather than smuggled in.
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

/**
 * <h2>There is no wrapper around a waiting child, and that is load-bearing</h2>
 * The obvious shape for the waiting branch below is a `<div role="status" aria-busy>` around the
 * child. It was written that way first and it is wrong, for a reason that cost an afternoon: React
 * reconciles by position, so a child that sits inside a wrapper in one render and directly in the
 * fragment in the next is **unmounted and mounted again** the moment the answer lands. Everything
 * the DOM was holding goes with it - focus, scroll position, the text in an uncontrolled field, an
 * open popover - and `season.test.tsx` caught it as a button that stayed disabled for ever,
 * because the node the test was holding had been thrown away a millisecond after it found it.
 *
 * Keeping the wrapper in *both* branches fixes the remount and buys a second problem: the div then
 * sits inside every `<TableBody>` a call site wraps, where it is not valid markup. So both branches
 * render `<>{children(...)}</>` and nothing else.
 *
 * What that costs is the announcement. A screen reader is told "loading" only on the `rows` path,
 * where {@link Loading} is a box of its own and can carry `role="status"` without being in
 * anybody's layout. On the shaped path the skeletons are `aria-hidden` and the surrounding page -
 * headings, labels, the table's own header - is already on screen and already readable, which is
 * the whole point of drawing the shape rather than a grey block.
 */

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
  const worker = api?.where === "steward-worker"
  const deployer = api?.where === "steward-deployer"
  const Icon = worker || deployer ? PlugsIcon : WarningIcon

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
 * <h2>One layout expression per call site (steward/120)</h2>
 * `children` is called **twice**: once with `undefined` while the answer is on its way, and again
 * with the data. So a call site names its layout once
 *
 * ```tsx
 * <QueryState query={services}>{(data) => <ServiceList services={data} />}</QueryState>
 * ```
 *
 * and `ServiceList` takes `services?: Service[]`, drawing its own rows with `Skeleton` inside them
 * when it has none. That is the whole rule, and everything else here follows from it: a separate
 * `ServiceListSkeleton` would be a second layout, and two layouts drift - which is visible exactly
 * once, as a jump, on the day somebody adds a column to one of them.
 *
 * `rows` opts back out, into the flat grey bars `Loading` draws. It is for the views whose real
 * shape is n bars of one height anyway, and for the few that cannot be drawn without their data.
 *
 * **The skeleton appears immediately and only on the first load.** No delay and no minimum
 * duration - Till named the Minecraft heads as the case that made the point, where an image
 * arriving into nothing is worse than anything a delay would save. `isPending` is false as soon as
 * there is anything to show,
 * so a refetch leaves the old data standing rather than greying the page out on every poll.
 *
 * **A disabled query is `isPending` for ever**, and that is the trap this component fell into.
 * Measured on 2026-09-14 on `/configuration`: skeletons, and nothing after them, because the page
 * had mounted `useConfig("")` and `enabled: Boolean(file)` had switched it off. There is no data
 * and none is coming, so "loading" was a lie the interface told indefinitely. `fetchStatus` tells
 * the two apart - `"idle"` beside `isPending` is switched off, `"fetching"` is on its way - and it
 * is optional here because some callers hand in a plain object rather than a query result.
 *
 * **A failure is never a skeleton that keeps pulsing.** `Failure` takes the same place in the page
 * and says what is wrong, because a surface that goes on shimmering is a promise that something is
 * coming.
 */
type QueryLike<T> = {
  data: T | undefined
  error: unknown
  isPending: boolean
  fetchStatus?: "fetching" | "paused" | "idle"
  refetch?: () => void
}

export function QueryState<T>(
  props: {
    query: QueryLike<T>
    empty?: { title: string; note?: string }
    isEmpty?: (data: T) => boolean
  } & (
    | {
        /**
         * Flat grey bars instead of the child's own shape. Read the note above before reaching for
         * it - and note what it buys in exchange: the child is then only ever called with data, so
         * a view that opts out does not pay for the optional prop everywhere inside it.
         */
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
  const draw = props.children as (data: T | undefined) => ReactNode

  if (query.isPending && query.fetchStatus === "idle") {
    return (
      <Empty
        title="Nothing was requested here."
        note="This query is switched off, so no answer is on its way. That is a fault in the page rather than in the service - the skeletons below it would otherwise never end."
      />
    )
  }
  if (query.error) return <Failure error={query.error} onRetry={query.refetch} />
  if (query.isPending || query.data === undefined) {
    return props.rows === undefined ? <>{draw(undefined)}</> : <Loading rows={props.rows} />
  }
  if (empty && isEmpty?.(query.data)) return <Empty title={empty.title} note={empty.note} />
  return <>{draw(query.data)}</>
}
