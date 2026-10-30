import type { ReactNode } from "react"
import { ArrowRightIcon, ShieldWarningIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Link, Navigate, useParams } from "@tanstack/react-router"
import { toast } from "sonner"

import type { Run } from "@/lib/api"
import { dateTime, parseInstant, relative, NO_VALUE } from "@/lib/format"
import { useAskForRun, useCancelRun, useRun, useServices } from "@/lib/queries"
import { useRunLock } from "@/lib/run-lock"
import { runPath } from "@/lib/run-path"
import { choice, span, t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Actor } from "@/components/steward/entity"
import { RunRail } from "@/components/steward/run-rail"
import { Hint, runKind, RunStatus, StatusBadge, type Tone } from "@/components/steward/status"
import { Empty, Loading, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Alert, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"

/**
 * The vocabulary of a run, shared by Updates, Backups and a service's page. A run is a row in Steward's inbox and never a call to a container.
 */

function stageLabel(stage: string): string {
  return t("steward.operations.step", { stage: choice(stage) })
}

/** The four stages a run stops at. `NOTHING_TO_DO` is not a kind of "done". */
export const ENDINGS = new Set(["DONE", "NOTHING_TO_DO", "FAILED", "CANCELLED"])

/** Grey for `NOTHING_TO_DO`, so a run that changed nothing never looks like a finished update. */
function stageTone(stage: string): Tone {
  if (stage === "DONE") return "ok"
  if (stage === "FAILED") return "down"
  if (stage === "NOTHING_TO_DO" || stage === "CANCELLED") return "idle"
  return "warn"
}

export function StageBadge({ stage }: { stage: string }) {
  return <StatusBadge tone={stageTone(stage)}>{stageLabel(stage)}</StatusBadge>
}

export function runSeconds(run: Run): number | null {
  const started = parseInstant(run.started)
  if (started == null) return null
  const finished = parseInstant(run.finished)
  return ((finished ?? new Date()).getTime() - started.getTime()) / 1000
}

/**
 * Returns whether the backend would still take this row back, as `UpdateDirectory#cancelCountdown` does.
 */
export function cancellable(run: Run, now = new Date()): boolean {
  if (!CANCELLABLE_KINDS.has(run.kind)) return false
  const moment =
    run.status === "PENDING"
      ? parseInstant(run.scheduledFor)
      : run.status === "RUNNING"
        ? parseInstant(run.countdownEnd)
        : null
  return moment !== null && moment.getTime() > now.getTime()
}

/** The kinds a countdown runs for, as in Steward's SQL. */
const CANCELLABLE_KINDS = new Set(["RESTART", "UPDATE", "BACKUP", "DOWN"])

/** Returns the archives a run saved, one per finished save step, which holds before the catalogue too. */
export function savedCount(run: Run): number {
  return run.steps.filter(
    (step) => (step.step === "DUMP_DATABASE" || step.step === "SAVE_VOLUME") && step.outcome === "DONE",
  ).length
}

/** Returns what a run did, one fact per line, counted out of its steps. */
export function summaryOf(run: Run): string[] {
  if (run.steps.length === 0) return [run.status === "PENDING" ? t("steward.operations.nothing-written") : NO_VALUE]
  if (run.stage === "NOTHING_TO_DO") return [t("steward.operations.nothing-to-do")]

  const parts: string[] = []
  const saved = savedCount(run)
  if (saved > 0) parts.push(t("steward.operations.saved", { count: saved }))

  const moved = run.steps.filter((step) => step.step === "INSTALL" && step.changes.length > 0)
  if (moved.length > 0) {
    const artefacts = moved.reduce((sum, step) => sum + step.changes.length, 0)
    parts.push(t("steward.operations.moved", { services: moved.length, artefacts }))
  }

  const failed = run.steps.filter((step) => step.outcome === "FAILED")
  if (failed.length > 0) parts.push(t("steward.operations.failed", { count: failed.length }))

  return parts.length > 0 ? parts : [t("steward.operations.no-change")]
}

export type Kind = "UPDATE" | "BACKUP" | "RESTART" | "DOWN" | "START"

export const ASKS: Record<Kind, typeof TERM_ICON.recreate> = {
  /** Its own symbol, so it is not mistaken for Recreate. */
  UPDATE: TERM_ICON.update,
  BACKUP: TERM_ICON.backup,
  RESTART: TERM_ICON.restart,
  DOWN: TERM_ICON.down,
  START: TERM_ICON.start,
}

/**
 * One button and one confirmation: Now or Cancel. Every run stops servers, so the dialog names what will happen in at most two short lines.
 */
export function AskButton({
  kind,
  variant = "outline",
  services,
  label,
  size,
  labelClassName,
  className,
  open,
  onOpenChange,
  trigger = true,
}: {
  kind: Kind
  variant?: "default" | "outline"
  /** Left off for the whole network. */
  services?: string[]
  /** Overrides the button's word where "Update" alone would be ambiguous. */
  label?: string
  size?: "default" | "sm"
  /** Hides the word below a breakpoint. The button keeps it as its accessible name. */
  labelClassName?: string
  className?: string
  /** So the service page's ⋯ menu opens the same confirmation. */
  open?: boolean
  onOpenChange?: (open: boolean) => void
  /** `false` draws the dialog alone. */
  trigger?: boolean
}) {
  const ask = useAskForRun()
  const lock = useRunLock()
  const Icon = ASKS[kind]
  const title = t("steward.operations.ask", { kind: choice(kind) })
  const warning = t("steward.operations.ask-warning", { kind: choice(kind) })
  const scoped = services !== undefined && services.length > 0
  const table = useServices(kind === "UPDATE")
  const localBuilds =
    kind === "UPDATE"
      ? (table.data?.services ?? [])
          .filter((row) => row.localBuild && (!scoped || services.includes(row.service)))
          .map((row) =>
            row.localBuild?.jars.length ? `${row.service} (${row.localBuild.jars.join(", ")})` : row.service,
          )
      : []

  const submit = () =>
    ask
      .mutateAsync({ kind, services, ...(localBuilds.length > 0 ? { replaceLocal: true } : {}) })
      .then((run) => toast.success(t("steward.operations.entered", { kind: runKind(kind), run: run.id })))

  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      trigger={
        trigger ? (
          <Hint tip={lock.title}>
            <Button
              type="button"
              variant={variant}
              size={size}
              className={className}
              disabled={ask.isPending || lock.locked}
              aria-label={labelClassName ? (label ?? runKind(kind)) : undefined}
            >
              <Icon aria-hidden />
              <span className={labelClassName}>{label ?? runKind(kind)}</span>
            </Button>
          </Hint>
        ) : null
      }
      title={scoped ? t("steward.operations.scoped", { ask: title, services }) : title}
      action={t("steward.operations.now")}
      destructive={kind === "RESTART" || kind === "DOWN"}
      act={submit}
    >
      {warning ? (
        <p className="flex items-start gap-2 text-sm text-warning">
          <TERM_ICON.warning className="mt-0.5 size-4 shrink-0" aria-hidden />
          {warning}
        </p>
      ) : null}
      {localBuilds.length > 0 ? (
        <p className="flex items-start gap-2 text-sm text-warning">
          <TERM_ICON.localBuild className="mt-0.5 size-4 shrink-0" aria-hidden />
          {t("steward.operations.local-builds", { builds: localBuilds })}
        </p>
      ) : null}
    </AskThenAct>
  )
}

/**
 * Takes a run back from its row, with no confirmation because it undoes one. A 409 means that the countdown ran out first, and the backend's sentence is shown as it came.
 */
export function CancelButton({ run }: { run: Run }) {
  const cancel = useCancelRun()

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      disabled={cancel.isPending}
      onClick={() =>
        cancel.mutate(undefined, {
          onSuccess: (cancelled) => {
            toast.success(t("steward.operations.cancelled", { run: cancelled.id }), {
              description: t("steward.operations.cancelled-note"),
            })
          },
          onError: (error) => {
            toast.error(t("steward.operations.not-cancelled", { run: run.id }), { description: error.message })
          },
        })
      }
    >
      <TERM_ICON.cancel aria-hidden />
      {t("steward.operations.cancel")}
    </Button>
  )
}

/** The page of one update run. A backup run goes to its page under Backups. */
export function UpdateRunPage() {
  const { id } = useParams({ from: "/operations/updates/$id" })
  return (
    <RunPage
      id={id}
      title={t("steward.operations.run", { id })}
      list={{ to: "/operations/updates", label: t("steward.operations.all-updates") }}
    />
  )
}

/**
 * One run, drawn and not dumped, under the page of its kind, which `runPath` names; a run of another kind goes to its own page. `useRun` polls while the run is unfinished, so its steps grow on screen.
 */
export function RunPage({
  id,
  title,
  list,
  children,
}: {
  id: string
  title: string
  /** The page of the kind's runs, which the button leads back to. */
  list: { to: "/operations/updates" | "/operations/backups"; label: string }
  /** What the kind adds, drawn before the report. The run is undefined while it loads. */
  children?: (run: Run | undefined) => ReactNode
}) {
  const numeric = /^\d+$/.test(id)
  const run = useRun(id, numeric)

  // A Discord card or a journal line can name a run under the other kind's page.
  if (run.data && runPath(run.data).to !== `${list.to}/$id`) return <Navigate {...runPath(run.data)} replace />

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={numeric ? title : t("steward.operations.a-run")}
        actions={
          <Button asChild variant="outline">
            <Link to={list.to}>
              <ArrowRightIcon aria-hidden />
              {list.label}
            </Link>
          </Button>
        }
      />

      {!numeric ? (
        <Empty title={t("steward.operations.not-a-number")} />
      ) : (
        /** A missing row arrives as a 404 failure. */
        <QueryState query={run}>{(data) => <RunDetail run={data}>{children?.(data)}</RunDetail>}</QueryState>
      )}
    </div>
  )
}

function RunDetail({ run, children }: { run?: Run; children?: ReactNode }) {
  const stage = run?.stage
  const finished = !run ? false : stage ? ENDINGS.has(stage) : run.status !== "PENDING" && run.status !== "RUNNING"

  return (
    <>
      <RunHeader run={run} finished={finished} />

      {run?.kind === "BACKUP" && finished && savedCount(run) === 0 ? (
        <Alert variant="destructive">
          <ShieldWarningIcon aria-hidden />
          <AlertTitle>{t("steward.operations.saved-nothing")}</AlertTitle>
        </Alert>
      ) : null}

      <Panel title={t("steward.operations.stages")}>
        {!run ? (
          <Loading rows={4} />
        ) : run.steps.length === 0 ? (
          <Empty title={t("steward.operations.nothing-written")} />
        ) : (
          <RunRail steps={run.steps} />
        )}
      </Panel>

      {children}
    </>
  )
}

const KIND_ICON: Partial<Record<string, typeof TERM_ICON.recreate>> = {
  ...ASKS,
  RECREATE: TERM_ICON.recreate,
  RESTORE: TERM_ICON.restore,
  INSTALL: TERM_ICON.update,
}

/** The run in one glance: its kind, its outcome and what it was for, then who asked, when it ran and how long. */
function RunHeader({ run, finished }: { run?: Run; finished: boolean }) {
  if (!run) {
    return (
      <div className="flex flex-col gap-3">
        <Skeleton className="h-6 w-40" />
        <SkeletonText width="long" />
      </div>
    )
  }
  const Icon = KIND_ICON[run.kind]
  const requested = parseInstant(run.requested)
  const scheduled = parseInstant(run.scheduledFor)
  // Steward does not pick the row up before this, which is worth a line only when someone asked for later.
  const later = requested && scheduled && Math.abs(scheduled.getTime() - requested.getTime()) >= 1000

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        {Icon ? <Icon aria-hidden className="size-5 shrink-0 text-muted-foreground" /> : null}
        <span className="font-heading text-base font-medium">{runKind(run.kind)}</span>
        {run.stage ? <StageBadge stage={run.stage} /> : <RunStatus status={run.status} />}
        {run.scope.map((service) => (
          <Badge key={service} variant="outline" className="font-normal">
            <TERM_ICON.service aria-hidden className="size-3 text-muted-foreground" />
            {service}
          </Badge>
        ))}
      </div>
      <dl className="flex flex-wrap gap-x-8 gap-y-3">
        <Fact label={t("steward.operations.requested-by")} hint={dateTime(run.requested)}>
          <Actor kind={run.actorKind} id={run.actorId} />
        </Fact>
        {later ? <Fact label={t("steward.operations.no-earlier-than")}>{dateTime(run.scheduledFor)}</Fact> : null}
        <Fact label={t("steward.operations.started")} hint={run.started ? relative(run.started) : undefined}>
          {dateTime(run.started)}
        </Fact>
        <Fact
          label={t("steward.operations.duration")}
          hint={finished ? dateTime(run.finished) : run.started ? t("steward.operations.still-running") : undefined}
        >
          {span(runSeconds(run))}
        </Fact>
      </dl>
    </div>
  )
}

function Fact({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <dt className="font-heading text-xs font-medium text-muted-foreground">{label}</dt>
      <dd className="flex min-w-0 flex-col text-sm tnum">
        {children}
        {hint ? <span className="text-xs text-muted-foreground">{hint}</span> : null}
      </dd>
    </div>
  )
}
