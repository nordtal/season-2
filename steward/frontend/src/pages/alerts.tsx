import { Link } from "@tanstack/react-router"

import type { Alert, AlertLevel, RecentAlert } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import { useAlerts } from "@/lib/queries"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState } from "@/components/steward/query-state"
import { StatusBadge } from "@/components/steward/status"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"

/** What is wrong now, as steward last judged it, and every alert raised lately, newest first. */
export function AlertsPage() {
  const alerts = useAlerts()
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Alerts" />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2 xl:items-start">
        <Card>
          <CardHeader>
            <CardTitle>Now</CardTitle>
          </CardHeader>
          <CardContent>
            <QueryState
              query={alerts}
              rows={2}
              isEmpty={(data) => data.alerts.length === 0 && data.unreadable === undefined}
              empty={{ title: "All clear." }}
            >
              {(data) => (
                <ul className="flex flex-col divide-y divide-border">
                  {data.unreadable ? (
                    <AlertLine level="warn" title="The stack could not be read" detail={data.unreadable} />
                  ) : null}
                  {data.alerts.map((alert) => (
                    <AlertLine key={`${alert.type}-${alert.subject}`} {...alert} />
                  ))}
                </ul>
              )}
            </QueryState>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Recent</CardTitle>
          </CardHeader>
          <CardContent>
            <QueryState
              query={alerts}
              rows={4}
              isEmpty={(data) => data.recent.length === 0}
              empty={{ title: "Nothing raised yet." }}
            >
              {(data) => (
                <ul className="flex flex-col divide-y divide-border">
                  {data.recent.map((alert) => (
                    <AlertLine key={alert.id} {...alert} raised={alert} />
                  ))}
                </ul>
              )}
            </QueryState>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

const WORD: Record<AlertLevel, string> = { down: "down", warn: "warning", ok: "ok" }

function AlertLine({
  level,
  title,
  detail,
  path,
  raised,
}: Pick<Alert, "level" | "title" | "detail"> & { path?: string; raised?: RecentAlert }) {
  return (
    <li className="flex flex-col gap-1 py-3 first:pt-0 last:pb-0" data-alert={level}>
      <div className="flex min-w-0 items-center gap-2">
        <StatusBadge tone={level} className="shrink-0">
          {WORD[level]}
        </StatusBadge>
        {path ? (
          <Link to={path} className="min-w-0 truncate text-sm font-medium underline-offset-4 hover:underline">
            {title}
          </Link>
        ) : (
          <span className="min-w-0 truncate text-sm font-medium">{title}</span>
        )}
      </div>
      {detail ? <p className="text-sm break-words text-muted-foreground">{detail}</p> : null}
      {raised ? (
        <p className="text-xs text-muted-foreground">
          <time dateTime={raised.raised} title={dateTime(raised.raised)} className="tabular-nums">
            {relative(raised.raised)}
          </time>{" "}
          by {raised.raisedBy}
        </p>
      ) : null}
    </li>
  )
}
