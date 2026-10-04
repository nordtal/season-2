import type { ReactNode } from "react"
import { Link } from "@tanstack/react-router"
import { cn } from "cn"

import { bytes, count, percent, relative } from "@/lib/format"
import type { Alert } from "@/lib/api"
import { useNow } from "@/lib/use-now"
import { useActions, useAlerts, useBackups, useHost, useMetrics, useServices } from "@/lib/queries"
import { ActionRow } from "@/components/steward/actions"
import { NetworkPanel } from "@/components/steward/network/view"
import { OnlineLine, useOnline } from "@/components/steward/online"
import { Sparkline } from "@/components/steward/sparkline"
import { Stat, UsageBar } from "@/components/steward/stat"
import { QueryState, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { t } from "@/lib/texts"

/**
 * The landing page: is something wrong, how full is the box, where to go next.
 *
 * Laid out for a phone first. Every tile links to the page that can act on it; nothing here restarts anything.
 */
export function OverviewPage() {
  const online = useOnline()

  return (
    <div className="flex flex-col gap-6">
      <OnlineLine online={online} />

      <MetricRow />

      {/* On desktop the picture sits left of the actions; on a phone it goes last. */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="order-last lg:order-first lg:col-span-2">
          <NetworkPanel />
        </div>
        <div className="flex flex-col lg:col-start-3">
          <ActionsPanel />
        </div>
      </div>
    </div>
  )
}

/** The quiet line under a figure, while the figure is still out. */
const WAITING_HINT = <SkeletonText className="w-20 text-xs" />

/** CPU, memory, disk, latest backup, drift and issues; the detail behind each lives on the Operations page. */
function MetricRow() {
  const host = useHost()
  const cpu = useMetrics("host", "cpu_percent", 6)
  const services = useServices()
  const backups = useBackups()
  const alerts = useAlerts()

  const outdated = (services.data?.services ?? []).filter((service) => service.drift === "OUTDATED")
  const finishedBackups = (backups.data ?? []).filter((backup) => !backup.partial)
  const newest = finishedBackups[0]

  const unreadable = host.data?.unreadable
  const usedMemory = host.data?.memoryTotalBytes
    ? host.data.memoryTotalBytes - (host.data.memoryAvailableBytes ?? 0)
    : undefined

  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-5 min-[26rem]:grid-cols-3 lg:grid-cols-6">
      {/* CPU spans the row below `lg`, since its sparkline makes it taller than any row-mate. */}
      <MetricTile
        label={t("steward.service-page.cpu")}
        value={host.data ? percent(host.data.cpuPercent) : undefined}
        hint={host.data ? (unreadable ?? cores(host.data.cpus)) : WAITING_HINT}
        className="col-span-2 min-[26rem]:col-span-3 lg:col-span-1"
      >
        <UsageBar used={host.data?.cpuPercent ?? (host.data ? 0 : undefined)} total={host.data ? 100 : undefined} />
        <Sparkline points={cpu.data?.points} />
      </MetricTile>

      <MetricTile
        label={t("steward.overview.memory")}
        value={host.data ? memoryShare(host.data) : undefined}
        hint={
          host.data
            ? (unreadable ??
              (host.data.memoryTotalBytes
                ? t("steward.overview.used-of", { used: bytes(usedMemory), total: bytes(host.data.memoryTotalBytes) })
                : "\u2013"))
            : WAITING_HINT
        }
      >
        {!host.data ? (
          <UsageBar />
        ) : host.data.memoryTotalBytes ? (
          <UsageBar used={usedMemory ?? 0} total={host.data.memoryTotalBytes} />
        ) : null}
      </MetricTile>

      <MetricTile
        label={t("steward.service-page.disk")}
        value={
          !host.data
            ? undefined
            : host.data.diskTotalBytes
              ? percent(((host.data.diskUsedBytes ?? 0) / host.data.diskTotalBytes) * 100, 0)
              : "\u2013"
        }
        hint={
          host.data
            ? (unreadable ??
              (host.data.diskTotalBytes
                ? t("steward.overview.used-of", {
                    used: bytes(host.data.diskUsedBytes),
                    total: bytes(host.data.diskTotalBytes),
                  })
                : "\u2013"))
            : WAITING_HINT
        }
      >
        {!host.data ? (
          <UsageBar />
        ) : host.data.diskTotalBytes ? (
          <UsageBar used={host.data.diskUsedBytes ?? 0} total={host.data.diskTotalBytes} />
        ) : null}
      </MetricTile>

      {/* `/operations/backups` holds what this number summarises, as `/alerts` does the issues'. */}
      <Link to="/operations/backups" className="flex min-w-0 flex-col gap-1.5">
        <Stat
          label={t("steward.overview.latest-backup")}
          value={newest ? relative(newest.modified) : backups.data ? t("steward.overview.none") : undefined}
          tone={backups.data && !newest ? "down" : undefined}
          hint={newest ? newest.human : backups.data ? t("steward.overview.no-finished-backup") : WAITING_HINT}
        />
      </Link>

      <MetricTile
        label={t("steward.overview.behind")}
        value={services.data ? count(outdated.length) : undefined}
        tone={outdated.length > 0 ? "warn" : undefined}
        hint={
          !services.data
            ? WAITING_HINT
            : outdated.length === 0
              ? t("steward.service-page.up-to-date")
              : outdated.map((service) => service.service).join(", ")
        }
      />

      <Link to="/alerts" className="flex min-w-0 flex-col gap-1.5">
        <IssuesTile
          alerts={alerts.data?.alerts ?? []}
          waiting={alerts.isPending || (alerts.data !== undefined && alerts.data.checkedAt === undefined)}
          failed={Boolean(alerts.error ?? alerts.data?.unreadable)}
        />
      </Link>
    </div>
  )
}

/**
 * The issue count steward judged, drawn like `Behind`: a count, and beneath it the names it is about.
 *
 * A settled `0` appears only once steward has read the stack; a failed read shows the row's dash.
 */
function IssuesTile({ alerts, waiting, failed }: { alerts: Alert[]; waiting: boolean; failed: boolean }) {
  if (alerts.length === 0) {
    /** Neither a dash nor a zero: a tile with nothing in it yet. */
    if (waiting && !failed)
      return <MetricTile label={t("steward.overview.issues")} value={undefined} hint={WAITING_HINT} />
    if (failed) {
      return (
        <MetricTile
          label={t("steward.overview.issues")}
          value={"\u2013"}
          tone="warn"
          hint={t("steward.overview.unreadable")}
        />
      )
    }
    return <MetricTile label={t("steward.overview.issues")} value={count(0)} hint={t("steward.overview.all-clear")} />
  }

  const worst = alerts[0]
  const names = alerts.map((alert) => alert.subject).join(", ")
  const note = failed ? t("steward.overview.partly-unreadable") : null

  return (
    <MetricTile
      label={t("steward.overview.issues")}
      value={count(alerts.length)}
      tone={worst.level === "down" ? "down" : "warn"}
      hint={
        note ? (
          <span className="flex flex-col gap-0.5">
            <span>{names}</span>
            <span>{note}</span>
          </span>
        ) : (
          names
        )
      }
    />
  )
}

function MetricTile({
  label,
  value,
  hint,
  tone,
  className,
  children,
}: {
  label: string
  value: ReactNode
  hint?: ReactNode
  tone?: "ok" | "warn" | "down"
  className?: string
  children?: ReactNode
}) {
  return (
    <div className={cn("flex min-w-0 flex-col gap-1.5", className)}>
      <Stat label={label} value={value} hint={hint} tone={tone} />
      {children}
    </div>
  )
}

/** The host's core count, or the dash while the host has not said. */
function cores(cpus: number | undefined): string {
  return cpus === undefined ? "\u2013" : t("steward.overview.cores", { count: cpus })
}

function memoryShare(host: { memoryTotalBytes?: number; memoryAvailableBytes?: number } | undefined) {
  if (!host?.memoryTotalBytes) return "\u2013"
  const used = host.memoryTotalBytes - (host.memoryAvailableBytes ?? 0)
  return percent((used / host.memoryTotalBytes) * 100, 0)
}

/** Four absent rows, the length `useActions(5)` settles at once the season is running. */
const WAITING_ACTIONS = [undefined, undefined, undefined, undefined]

/** The latest actions from `/api/actions`; every actor is drawn through `Entity`, never as a raw snowflake. */
function ActionsPanel() {
  const actions = useActions(5)
  const now = useNow()

  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-lg font-semibold text-foreground">{t("steward.overview.latest-actions")}</h2>
      <QueryState
        query={actions}
        isEmpty={(list) => list.length === 0}
        empty={{
          title: t("steward.overview.nothing-recorded"),
          note: t("steward.overview.nothing-recorded-note"),
        }}
      >
        {(list) => (
          <ul className="flex flex-col">
            {/* Four while waiting, because that is what `useActions(5)` all but always answers. */}
            {(list ?? WAITING_ACTIONS).map((action, index) => (
              <ActionRow
                /** The feed has no id of its own, and position is stable because the list is never reordered here. */
                key={index}
                action={action}
                now={now}
              />
            ))}
          </ul>
        )}
      </QueryState>
      <Button asChild variant="ghost" size="sm" className="w-fit -ml-3">
        <Link to="/journal">{t("steward.overview.whole-journal")}</Link>
      </Button>
    </section>
  )
}
