import { Link } from "@tanstack/react-router"

import type { AlertLevel, RecentAlert } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import { useAlerts } from "@/lib/queries"
import { message, t } from "@/lib/texts"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState } from "@/components/steward/query-state"
import { StatusBadge } from "@/components/steward/status"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"

/** What is wrong now, as steward last judged it, and every alert raised lately, newest first. */
export function AlertsPage() {
  const alerts = useAlerts()
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.alerts.title")} />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2 xl:items-start">
        <Card>
          <CardHeader>
            <CardTitle>{t("steward.alerts.now")}</CardTitle>
          </CardHeader>
          <CardContent>
            <QueryState
              query={alerts}
              rows={2}
              isEmpty={(data) => data.alerts.length === 0 && data.unreadable === undefined}
              empty={{ title: t("steward.alerts.all-clear") }}
            >
              {(data) => (
                <ul className="flex flex-col divide-y divide-border">
                  {data.unreadable ? (
                    <AlertLine level="warn" title={t("steward.alerts.unreadable")} lines={[data.unreadable]} />
                  ) : null}
                  {data.alerts.map((alert) => (
                    <AlertLine
                      key={`${alert.type}-${alert.subject}`}
                      level={alert.level}
                      title={message(alert.title)}
                      lines={alert.detail.map(message)}
                      path={alert.path}
                    />
                  ))}
                </ul>
              )}
            </QueryState>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>{t("steward.alerts.recent")}</CardTitle>
          </CardHeader>
          <CardContent>
            <QueryState
              query={alerts}
              rows={4}
              isEmpty={(data) => data.recent.length === 0}
              empty={{ title: t("steward.alerts.none-raised") }}
            >
              {(data) => (
                <ul className="flex flex-col divide-y divide-border">
                  {data.recent.map((alert) => (
                    <AlertLine
                      key={alert.id}
                      level={alert.level}
                      title={message(alert.title)}
                      lines={alert.detail.map(message)}
                      path={alert.path}
                      raised={alert}
                    />
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

/** One alert: its level in words, its title rendered from the admin bundle, and the lines below it. */
function AlertLine({
  level,
  title,
  lines,
  path,
  raised,
}: {
  level: AlertLevel
  title: string
  lines: string[]
  path?: string
  raised?: RecentAlert
}) {
  return (
    <li className="flex flex-col gap-1 py-3 first:pt-0 last:pb-0" data-alert={level}>
      <div className="flex min-w-0 items-center gap-2">
        <StatusBadge tone={level} className="shrink-0">
          {t("alert.level", { level })}
        </StatusBadge>
        {path ? (
          <Link to={path} className="min-w-0 truncate text-sm font-medium underline-offset-4 hover:underline">
            {title}
          </Link>
        ) : (
          <span className="min-w-0 truncate text-sm font-medium">{title}</span>
        )}
      </div>
      {lines.map((line, index) => (
        <p key={index} className="text-sm break-words text-muted-foreground">
          {line}
        </p>
      ))}
      {raised ? (
        <p className="text-xs text-muted-foreground">
          <time dateTime={raised.raised} title={dateTime(raised.raised)} className="tabular-nums">
            {relative(raised.raised)}
          </time>{" "}
          {t("steward.alerts.raised-by", { who: raised.raisedBy })}
        </p>
      ) : null}
    </li>
  )
}
