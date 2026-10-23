import { useState } from "react"
import type { ReactNode } from "react"
import { Link } from "@tanstack/react-router"
import { CheckIcon } from "@phosphor-icons/react"
import { cn } from "cn"

import type { Icon } from "@phosphor-icons/react"

import type { Alert, AlertLevel, AlertType, JournalItem, JournalTerm, Measure } from "@/lib/api"
import { LOCALE, relative, time } from "@/lib/format"
import { useAlerts, useJournal } from "@/lib/queries"
import { choice, message, t } from "@/lib/texts"
import { useNow } from "@/lib/use-now"
import { actionIcon } from "@/components/steward/actions"
import { Actor, Entity } from "@/components/steward/entity"
import { FilterBar, SearchField } from "@/components/steward/filter-bar"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { RunStatus, StatusBadge, runKind, runStatus } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { When } from "@/components/steward/when"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"

const EDGE_LEFT: Record<AlertLevel, string> = {
  ok: "border-l-success",
  warn: "border-l-warning",
  down: "border-l-destructive",
}
const FILL: Record<AlertLevel, string> = { ok: "bg-success", warn: "bg-warning", down: "bg-destructive" }

const ALERT_ICON: Record<AlertType, Icon> = {
  service: TERM_ICON.down,
  backup: TERM_ICON.backup,
  disk: TERM_ICON.warning,
  memory: TERM_ICON.warning,
  drift: TERM_ICON.recreate,
  pack: TERM_ICON.pack,
  run: TERM_ICON.failed,
  payment: TERM_ICON.payments,
  bot: TERM_ICON.warning,
}

function actionLabel(action: string): string {
  return t("journal.action", { action: choice(action) })
}

function worst(levels: Iterable<AlertLevel>): AlertLevel | undefined {
  const all = [...levels]
  return all.includes("down") ? "down" : all.includes("warn") ? "warn" : undefined
}

/**
 * Returns each item's left edge: an alert's own level, and on every other item the worst alert open at that moment.
 * An all-clear closes every alert of its type.
 * An all-clear whose alert opened before the oldest loaded item colours the items it spans as a warning.
 */
export function edges(items: readonly JournalItem[]): (AlertLevel | undefined)[] {
  const oldest = Date.parse(items.at(-1)?.occurred ?? "")
  const open = new Map<AlertType, AlertLevel>()
  for (const item of items) {
    if (item.alert?.level === "ok" && item.opened && Date.parse(item.opened) < oldest) open.set(item.alert.type, "warn")
  }
  const result: (AlertLevel | undefined)[] = []
  for (let index = items.length - 1; index >= 0; index--) {
    const alert = items[index].alert
    if (alert?.level === "ok") open.delete(alert.type)
    else if (alert) open.set(alert.type, alert.level)
    result[index] = alert ? alert.level : worst(open.values())
  }
  return result
}

/** Returns how long an alert was open, as the all-clear says it. */
export function lasted(opened: string, cleared: string): string {
  const minutes = Math.max(0, Math.round((Date.parse(cleared) - Date.parse(opened)) / 60_000))
  return minutes >= 60
    ? t("steward.journal.after-hours", { hours: Math.floor(minutes / 60), minutes: minutes % 60 })
    : t("steward.journal.after-minutes", { minutes })
}

const DAY = new Intl.DateTimeFormat(LOCALE, { weekday: "long", day: "numeric", month: "long" })

function dayKey(at: Date): string {
  return `${at.getFullYear()}-${at.getMonth()}-${at.getDate()}`
}

function dayTitle(iso: string, now: number): string {
  const at = new Date(iso)
  const today = new Date(now)
  if (dayKey(at) === dayKey(today)) return t("steward.journal.today")
  if (dayKey(at) === dayKey(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1)))
    return t("steward.journal.yesterday")
  return DAY.format(at)
}

/** A bar filled to the value with the limit as a tick; percent gauges run to 100, the rest to half again the limit. */
function Meter({ measure, level }: { measure: Measure; level: AlertLevel }) {
  if (measure.value === undefined || measure.limit === undefined) return null
  const max = measure.gauge.endsWith("PERCENT") ? 100 : Math.max(measure.limit * 1.5, measure.value * 1.1)
  return (
    <div
      className="relative h-1.5 w-full max-w-xs rounded-full bg-secondary"
      role="meter"
      aria-valuenow={measure.value}
      aria-valuemax={max}
      aria-label={t("steward.journal.meter", {
        subject: measure.subject,
        value: Math.round(measure.value),
        limit: Math.round(measure.limit),
      })}
    >
      <div
        className={cn("h-full rounded-full", FILL[level])}
        style={{ width: `${Math.min(100, (measure.value / max) * 100)}%` }}
      />
      <div
        className="absolute -top-1 h-3.5 w-0.5 rounded-full bg-foreground"
        style={{ left: `${Math.min(100, (measure.limit / max) * 100)}%` }}
      />
    </div>
  )
}

/** An alert's lines say what it measured; a measure with a limit also draws its meter. */
function AlertLines({ alert, meters }: { alert: Alert; meters: boolean }) {
  return (
    <>
      {alert.detail.map((line, index) => (
        <p key={index} className="text-sm break-words whitespace-pre-line text-muted-foreground">
          {message(line)}
        </p>
      ))}
      {meters
        ? alert.measured.map((measure) => (
            <Meter key={measure.gauge + measure.subject} measure={measure} level={alert.level} />
          ))
        : null}
    </>
  )
}

/** The alerts open now: the level shows in the badge and the left edge alone, the rule between them stays neutral. */
function OpenNow() {
  const alerts = useAlerts()
  const now = useNow()
  return (
    <Panel title={t("steward.journal.open-now")}>
      <QueryState query={alerts} rows={2}>
        {(data) =>
          data.alerts.length === 0 && !data.unreadable ? (
            <p className="flex items-center gap-2 text-sm">
              <CheckIcon aria-hidden className="size-4 text-success" weight="bold" />
              <span className="font-medium">{t("steward.journal.nothing-open")}</span>
              {data.checkedAt ? (
                <span className="text-muted-foreground">
                  {t("steward.journal.checked", { when: relative(data.checkedAt, now) })}
                </span>
              ) : null}
            </p>
          ) : (
            <ul className="flex flex-col divide-y divide-border">
              {data.unreadable ? (
                <li className={cn("flex flex-col gap-2 border-l-2 py-3 pl-3", EDGE_LEFT.warn)}>
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <StatusBadge tone="warn">{t("alert.level", { level: "warn" })}</StatusBadge>
                    <span className="text-sm font-medium">{t("steward.journal.unreadable")}</span>
                  </div>
                  <p className="text-sm break-words text-muted-foreground">{data.unreadable}</p>
                </li>
              ) : null}
              {data.alerts.map((alert) => (
                <li
                  key={`${alert.type} ${alert.subject}`}
                  data-alert={alert.level}
                  className={cn("flex flex-col gap-2 border-l-2 py-3 pl-3", EDGE_LEFT[alert.level])}
                >
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <StatusBadge tone={alert.level}>{t("alert.level", { level: alert.level })}</StatusBadge>
                    <Link
                      to={alert.path}
                      className="min-w-0 text-sm font-medium break-words underline-offset-4 tap-target hover:underline"
                    >
                      {message(alert.title)}
                    </Link>
                  </div>
                  <AlertLines alert={alert} meters />
                </li>
              ))}
            </ul>
          )
        }
      </QueryState>
    </Panel>
  )
}

function TermBadge({ term }: { term: JournalTerm }) {
  const value = term.values[0] ?? term.word
  if (term.kind === "DISCORD") return <Entity id={value} kind="discord" />
  if (term.kind === "UUID") return <Entity id={value} kind="minecraft" />
  const [icon, label] = ((): [Icon | undefined, string] => {
    switch (term.kind) {
      case "PAYMENT":
        return [TERM_ICON.payments, value]
      case "DAYS":
        return [TERM_ICON.seasonDates, t("steward.journal.days", { days: Number(value) })]
      case "SERVICE":
        return [TERM_ICON.service, value]
      case "LEVEL":
        return [TERM_ICON.alert, t("alert.level", { level: value.toLowerCase() })]
      case "RUN_KIND":
        return [actionIcon(value), runKind(value)]
      case "RUN_STATUS":
        return [TERM_ICON.update, runStatus(value)]
      case "GAUGE":
        return [TERM_ICON.alert, term.word]
      case "ACTION":
        return term.values.length === 1
          ? [actionIcon(value), actionLabel(value)]
          : [TERM_ICON.journal, t("steward.journal.quoted", { word: term.word })]
      default:
        return [undefined, t("steward.journal.quoted", { word: term.word })]
    }
  })()
  const icons = { Icon: icon }
  return (
    <Badge variant="outline" className="gap-1 font-normal">
      {icons.Icon ? <icons.Icon aria-hidden className="size-3.5" /> : null}
      {label}
    </Badge>
  )
}

function itemTitle(item: JournalItem): string {
  if (item.run) return runKind(item.run.kind)
  if (item.alert) return message(item.alert.title)
  return item.entry ? actionLabel(item.entry.action) : ""
}

function itemIcon(item: JournalItem) {
  if (item.run) return actionIcon(item.run.kind)
  if (item.alert) return ALERT_ICON[item.alert.type]
  return actionIcon(item.entry?.action ?? "")
}

/** One item: its time, what happened with its facts below, and who did it at the end. */
function JournalRow({
  item,
  edge,
  person,
  stamp,
}: {
  item: JournalItem
  edge: AlertLevel | undefined
  person?: string
  stamp: (iso: string) => ReactNode
}) {
  const icons = { Icon: itemIcon(item) }
  const entry = item.entry
  const alert = item.alert
  const run = item.run
  const concerns =
    entry?.subject && entry.subject !== person && entry.subject !== entry.actor.person ? entry.subject : undefined
  return (
    <li
      data-key={item.key}
      data-edge={edge}
      className={cn(
        "grid grid-cols-[3.25rem_1fr] gap-x-3 border-b border-l-2 border-b-border/60 border-l-transparent py-2.5 pl-2 last:border-b-0 @xl:grid-cols-[3.5rem_1fr_auto]",
        edge && EDGE_LEFT[edge],
      )}
    >
      <span className="pt-0.5 text-sm text-muted-foreground tnum">{stamp(item.occurred)}</span>
      <div className="flex min-w-0 flex-col gap-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <icons.Icon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
          {run ? (
            <Link
              to="/operations/updates/$id"
              params={{ id: String(run.id) }}
              className="text-sm font-medium underline-offset-4 tap-target hover:underline"
            >
              {itemTitle(item)}
            </Link>
          ) : (
            <span className="text-sm font-medium break-words">{itemTitle(item)}</span>
          )}
          {run ? <RunStatus status={run.status} /> : null}
          {alert ? <StatusBadge tone={alert.level}>{t("alert.level", { level: alert.level })}</StatusBadge> : null}
          {alert?.level === "ok" && item.opened ? (
            <span className="text-xs text-muted-foreground tnum">{lasted(item.opened, item.occurred)}</span>
          ) : null}
        </div>
        {run && run.services.length > 0 ? (
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            {run.services.map((name) => (
              <Entity key={name} id={name} kind="service" />
            ))}
          </div>
        ) : null}
        {run?.why.map((line, index) => (
          <p key={index} className="text-sm break-words text-muted-foreground">
            {message(line)}
          </p>
        ))}
        {alert && alert.level !== "ok" ? <AlertLines alert={alert} meters={false} /> : null}
        {entry && (concerns || entry.mcUuid || Object.keys(entry.line.args ?? {}).length > 0) ? (
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            {concerns ? <Entity id={concerns} kind="discord" /> : null}
            {!concerns && entry.mcUuid ? <Entity id={entry.mcUuid} kind="minecraft" /> : null}
            {Object.keys(entry.line.args ?? {}).length > 0 ? (
              <span className="text-sm break-words text-muted-foreground">{message(entry.line)}</span>
            ) : null}
          </div>
        ) : null}
      </div>
      <div className="col-start-2 min-w-0 @xl:col-start-3 @xl:row-start-1 @xl:pt-0.5">
        <Actor kind={item.actor.kind} id={item.actor.person ?? ""} className="text-muted-foreground" />
      </div>
    </li>
  )
}

function WaitingRows({ count }: { count: number }) {
  return (
    <ul className="flex flex-col" aria-hidden>
      {Array.from({ length: count }, (_, index) => (
        <li
          key={index}
          className="grid grid-cols-[3.25rem_1fr] gap-x-3 border-b border-border/60 py-2.5 pl-2.5 last:border-b-0"
        >
          <SkeletonText className="w-10 text-sm" />
          <div className="flex items-center gap-2">
            <Skeleton className="size-4 rounded-full" />
            <SkeletonText className="w-48 text-sm" />
          </div>
        </li>
      ))}
    </ul>
  )
}

/**
 * The journal's items under `q`, newest first. With `days`, each day is its own panel and a row shows the time;
 * without, the rows sit in the caller's panel and show how long ago they happened.
 */
export function JournalStream({
  q,
  days,
  limit = 50,
  paged = true,
  person,
  empty,
}: {
  q: string
  days: boolean
  limit?: number
  /** `false` where the first page alone is shown, with a link to the journal instead. */
  paged?: boolean
  /** The person the caller is about, whom no row names again. */
  person?: string
  empty: string
}) {
  const journal = useJournal(q, limit)
  const now = useNow()
  const items = journal.data?.pages.flatMap((page) => page.items)
  const stamp = days
    ? (iso: string) => <time dateTime={iso}>{time(iso)}</time>
    : (iso: string) => <When at={iso} now={now} />
  const list = items ?? []
  const edge = edges(list)
  const rows = (from: number, to: number) => (
    <ul className="@container flex flex-col">
      {list.slice(from, to).map((item, index) => (
        <JournalRow key={item.key} item={item} edge={edge[from + index]} person={person} stamp={stamp} />
      ))}
    </ul>
  )
  const groups: { title: string; from: number; to: number }[] = []
  list.forEach((item, index) => {
    const title = dayTitle(item.occurred, now)
    if (groups.at(-1)?.title === title) groups[groups.length - 1].to = index + 1
    else groups.push({ title, from: index, to: index + 1 })
  })
  const more =
    paged && journal.hasNextPage ? (
      <Button
        variant="outline"
        size="sm"
        className="w-fit"
        disabled={journal.isFetchingNextPage}
        onClick={() => void journal.fetchNextPage()}
      >
        {t("steward.journal.older")}
      </Button>
    ) : null

  return (
    <QueryState
      query={journal}
      isEmpty={(data) => data.pages.every((page) => page.items.length === 0)}
      empty={{ title: empty }}
    >
      {(data) =>
        data === undefined ? (
          <WaitingRows count={Math.min(limit, 6)} />
        ) : days ? (
          <>
            {groups.map((group) => (
              <Panel key={group.title} title={group.title}>
                {rows(group.from, group.to)}
              </Panel>
            ))}
            {more}
          </>
        ) : (
          <>
            {rows(0, list.length)}
            {more}
          </>
        )
      }
    </QueryState>
  )
}

/** The words the server recognised in the query, each as one badge. */
function Terms({ q }: { q: string }) {
  const journal = useJournal(q)
  const terms = journal.data?.pages[0]?.terms ?? []
  if (terms.length === 0) return null
  return (
    <div className="flex flex-wrap items-center gap-1.5" aria-label={t("steward.journal.read-as")}>
      {terms.map((term, index) => (
        <TermBadge key={index} term={term} />
      ))}
    </div>
  )
}

/** The journal: what is open now, one search field, and the stream of entries, alerts and runs by day. */
export function JournalPage() {
  const [typed, setTyped] = useState("")
  const [q, setQ] = useState("")

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.journal.title")} />
      <div className="flex flex-col gap-2">
        <FilterBar>
          <SearchField
            value={typed}
            onValueChange={setTyped}
            onSubmit={setQ}
            label={t("steward.journal.search")}
            placeholder={t("steward.journal.search-hint")}
          />
        </FilterBar>
        <Terms q={q} />
      </div>
      <OpenNow />
      <JournalStream q={q} days empty={t("steward.journal.no-entry")} />
    </div>
  )
}
