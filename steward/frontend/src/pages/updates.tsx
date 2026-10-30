import { Link, useNavigate } from "@tanstack/react-router"
import { ArrowRightIcon } from "@phosphor-icons/react"
import { ServiceName } from "@/components/steward/service-name"
import { TERM_ICON } from "@/components/steward/term-icons"
import { cn } from "@/lib/utils"
import { toast } from "sonner"

import type { AvailableChange, Run } from "@/lib/api"
import { LOCALE, count, dateTime, relative, NO_VALUE } from "@/lib/format"
import { message, t } from "@/lib/texts"
import { versionJump } from "@/lib/version-jump"
import { useAvailable, useRefreshAvailable, useRuns, useSchedule } from "@/lib/queries"
import { Actor } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { RunRail } from "@/components/steward/run-rail"
import { Stat } from "@/components/steward/stat"
import { AvailableBadge, RunStatus, Tip, runKind } from "@/components/steward/status"
import { Failure, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/steward/data-table"
import { AskButton, CancelButton, ENDINGS, StageBadge, cancellable, summaryOf } from "@/pages/operations"
import { ScheduleDialog } from "@/components/steward/group-form"

/**
 * The updates page: a header, three numbers and the lists. `update.at` is empty by default and schedules the same request that the button writes.
 */
export function UpdatesPage() {
  const refresh = useRefreshAvailable()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={t("steward.updates.title")}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <ScheduleDialog job="update" empty={t("steward.updates.no-section")} noDay={t("steward.updates.no-day")} />
            <Button
              type="button"
              variant="outline"
              size="sm"
              /** Steward holds a reading for six hours and asks every source again here. */
              disabled={refresh.isPending}
              onClick={() =>
                refresh.mutate(undefined, {
                  onError: (failure) =>
                    toast.error(t("steward.updates.check-again-failed"), { description: failure.message }),
                })
              }
            >
              <TERM_ICON.checkForUpdates aria-hidden className={refresh.isPending ? "animate-spin" : undefined} />
              {t("steward.updates.check-again")}
            </Button>
            <AskButton kind="RESTART" label={t("steward.updates.restart-everything")} size="sm" />
            <AskButton kind="UPDATE" variant="default" label={t("steward.updates.update-everything")} size="sm" />
          </div>
        }
      />

      <Summary />

      <Available />

      <PlanNotes />

      <Runs />
    </div>
  )
}

function Summary() {
  const available = useAvailable()
  const schedule = useSchedule()
  const waiting = available.data ? available.data.changes.filter((change) => change.work).length : undefined

  return (
    <div className="grid grid-cols-1 gap-x-4 gap-y-5 sm:grid-cols-2 lg:grid-cols-3">
      <Stat
        label={t("steward.updates.available")}
        value={waiting === undefined ? undefined : count(waiting)}
        tone={available.data?.hasFailures ? "warn" : undefined}
        hint={available.data?.hasFailures ? t("steward.updates.source-silent") : undefined}
      />
      <Stat
        label={t("steward.updates.checked")}
        value={available.data ? relative(available.data.resolvedAt) : undefined}
        hint={available.data ? dateTime(available.data.resolvedAt) : undefined}
      />
      <Stat
        label={t("steward.updates.next")}
        value={
          schedule.data?.nextUpdateAt
            ? relative(schedule.data.nextUpdateAt)
            : schedule.data
              ? t("steward.updates.not-scheduled")
              : undefined
        }
        hint={schedule.data?.nextUpdateAt ? `${schedule.data.updateAt} ${schedule.data.zone}` : undefined}
      />
    </div>
  )
}

/** A source that could not be asked sorts above ordinary work. */
const AVAILABLE_RANK: Record<string, number> = {
  UNRESOLVED: 0,
  MOUNT_MISSING: 0,
  OUTDATED: 1,
  MISSING: 2,
  UNSUPPORTED: 4,
  UP_TO_DATE: 5,
}

/**
 * Returns the rows this card draws: work, failures, and artefacts with no build for this platform, failures first.
 */
function worthShowing(changes: AvailableChange[]): AvailableChange[] {
  return changes
    .filter((change) => change.work || change.failure || change.status === "UNSUPPORTED")
    .toSorted(
      (left, right) =>
        (AVAILABLE_RANK[left.status] ?? 3) - (AVAILABLE_RANK[right.status] ?? 3) ||
        (left.service ?? "").localeCompare(right.service ?? "", LOCALE) ||
        left.artifact.localeCompare(right.artifact, LOCALE),
    )
}

/**
 * Shows one row's change in as few characters as possible, or `nothing → 1.6.0` for a fresh volume. An artefact with no pair gets a dash, with its reason on a tap or a hover.
 */
function Jump({ change }: { change: AvailableChange }) {
  const jump = versionJump(change.installed, change.fileName, change.version)
  if (jump) return <Pair from={jump.from} to={jump.to} exact={jump.exact} />

  const wanted = change.version ?? change.fileName
  if (wanted && !change.installed) {
    return <Pair from={t("steward.updates.nothing")} to={wanted} exact={change.version !== undefined} />
  }
  const shown = <span className="text-xs">{change.installed ?? NO_VALUE}</span>
  return change.reason ? <Tip content={message(change.reason)}>{shown}</Tip> : shown
}

function Pair({ from, to, exact }: { from: string; to: string; exact: boolean }) {
  const face = exact ? "tnum" : "font-mono break-all"
  return (
    <span className="flex flex-wrap items-baseline gap-1 text-xs">
      <span className={face}>{from}</span>
      <ArrowRightIcon aria-hidden className="size-3 shrink-0 self-center text-muted-foreground" />
      <span className={cn(face, "text-foreground")}>{to}</span>
    </span>
  )
}

/**
 * Shows what a run would install: rows with work, failures, and `UNSUPPORTED` artefacts. Hiding a failure would read as nothing to do.
 */
function Available() {
  const available = useAvailable()
  const refresh = useRefreshAvailable()

  return (
    <Panel title={t("steward.updates.available")}>
      {refresh.error ? <Failure error={refresh.error} write /> : null}
      <QueryState
        query={available}
        empty={{ title: t("steward.updates.nothing-to-install") }}
        isEmpty={(plan) => worthShowing(plan.changes).length === 0}
      >
        {(plan) => {
          const rows = plan ? worthShowing(plan.changes) : undefined
          return (
            <>
              {plan?.hasFailures ? (
                <p className="flex items-start gap-2 text-xs text-destructive">
                  <TERM_ICON.warning className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                  {t("steward.updates.incomplete")}
                </p>
              ) : null}

              <DataTable
                rows={rows}
                waiting={6}
                rowKey={(change) => `${change.service ?? "-"}/${change.artifact}`}
                columns={[
                  {
                    head: t("steward.updates.service"),
                    phone: { line: 1, side: "start" },
                    className: "font-medium",
                    render: (change) =>
                      change.service ? (
                        <Link
                          to="/services/$name"
                          params={{ name: change.service }}
                          className="underline-offset-4 tap-target hover:text-primary hover:underline"
                        >
                          <ServiceName service={change.service} />
                        </Link>
                      ) : (
                        <span className="text-muted-foreground">{t("steward.updates.resource-pack")}</span>
                      ),
                  },
                  {
                    head: t("steward.updates.plugin"),
                    grow: true,
                    phone: { line: 2, side: "start" },
                    render: (change) => change.artifact,
                  },
                  {
                    head: t("steward.updates.change"),
                    phone: { line: 2, side: "end" },
                    className: "text-muted-foreground",
                    render: (change) => <Jump change={change} />,
                  },
                  {
                    head: t("steward.updates.state"),
                    end: true,
                    phone: { line: 1, side: "end" },
                    waiting: <Skeleton className="ml-auto h-5 w-20 rounded-full" />,
                    render: (change) => <AvailableBadge status={change.status} />,
                  },
                ]}
              />
            </>
          )
        }}
      </QueryState>
    </Panel>
  )
}

/** What a run would note. It has its own panel, because panels are not nested. */
function PlanNotes() {
  const available = useAvailable()
  if (!available.data || available.data.steps.length === 0) return null
  return (
    <Panel title={t("steward.operations.notes")}>
      <RunRail steps={available.data.steps} />
    </Panel>
  )
}

/** Returns the runs that install or restart. */
export function updateRuns(runs: Run[] | undefined): Run[] {
  return (runs ?? []).filter((run) => run.kind === "UPDATE" || run.kind === "RESTART" || run.kind === "INSTALL")
}

function Runs() {
  const navigate = useNavigate()
  const runs = useRuns(40)
  const rows = updateRuns(runs.data).slice(0, 8)

  return (
    <Panel title={t("steward.updates.runs")}>
      <QueryState query={runs} isEmpty={() => rows.length === 0} empty={{ title: t("steward.updates.no-run") }}>
        {(answer) => (
          <DataTable
            rows={answer ? rows : undefined}
            waiting={4}
            rowKey={(run) => run.id}
            onRowClick={(run) => navigate({ to: "/operations/updates/$id", params: { id: String(run.id) } })}
            columns={[
              {
                head: t("steward.updates.run"),
                phone: { line: 1, side: "start" },
                className: "font-medium tnum",
                waiting: <SkeletonText width="short" />,
                render: (run) => (
                  <Link
                    to="/operations/updates/$id"
                    params={{ id: String(run.id) }}
                    className="underline-offset-4 tap-target hover:text-primary hover:underline"
                    onClick={(event) => event.stopPropagation()}
                  >
                    #{run.id}
                  </Link>
                ),
              },
              {
                head: t("steward.updates.when"),
                phone: { line: 2, side: "start" },
                render: (run) => dateTime(run.started || run.requested),
              },
              {
                head: t("steward.updates.kind"),
                phone: { line: 2, side: "end" },
                render: (run) => runKind(run.kind),
              },
              {
                head: t("steward.updates.status"),
                phone: { line: 1, side: "end" },
                waiting: <Skeleton className="h-5 w-20 rounded-full" />,
                render: (run) => (
                  <div className="flex items-center gap-1.5" onClick={(event) => event.stopPropagation()}>
                    <RunStatus status={run.status} />
                    {run.stage && !ENDINGS.has(run.stage) ? <StageBadge stage={run.stage} /> : null}
                    {cancellable(run) ? <CancelButton run={run} /> : null}
                  </div>
                ),
              },
              {
                head: t("steward.updates.result"),
                grow: true,
                phone: { line: 3, side: "start" },
                render: (run) =>
                  run.stage === "NOTHING_TO_DO" ? (
                    <span className="flex items-center gap-1.5 text-muted-foreground">
                      <TERM_ICON.nothingToDo className="size-3.5 shrink-0" aria-hidden />
                      {t("steward.operations.nothing-to-do")}
                    </span>
                  ) : (
                    <div className="flex flex-col gap-0.5">
                      {summaryOf(run).map((part, partIndex) => (
                        <span key={partIndex}>{part}</span>
                      ))}
                    </div>
                  ),
              },
              {
                head: t("steward.updates.initiated-by"),
                phone: { line: 3, side: "end" },
                className: "text-muted-foreground",
                render: (run) => <Actor kind={run.actorKind} id={run.actorId} />,
              },
            ]}
          />
        )}
      </QueryState>
    </Panel>
  )
}
