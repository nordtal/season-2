import { ArrowRightIcon, CheckIcon, ShieldWarningIcon } from "@phosphor-icons/react"
import { cn } from "cn"
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
import { Stat } from "@/components/steward/stat"
import { Actor } from "@/components/steward/entity"
import { RunSteps } from "@/components/steward/run-steps"
import { RunStatus, StatusBadge, runKind, type Tone } from "@/components/steward/status"
import { Empty, Loading, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Alert, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"

/**
 * The vocabulary of a run, shared by Updates, Backups and a service's page. A run is a row in Steward's inbox and never a call to a container.
 */

/** The stages in `RunStage` order, without the four endings. */
const TRAIL = [
  "RESOLVING",
  "PLANNED",
  "COUNTDOWN",
  "STOPPING",
  "BACKING_UP",
  "INSTALLING",
  "STARTING",
  "VERIFYING",
] as const

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
          <Button
            type="button"
            variant={variant}
            size={size}
            className={className}
            disabled={ask.isPending || lock.locked}
            title={lock.title}
            aria-label={labelClassName ? (label ?? runKind(kind)) : undefined}
          >
            <Icon aria-hidden />
            <span className={labelClassName}>{label ?? runKind(kind)}</span>
          </Button>
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

/**
 * One run, drawn and not dumped, for every kind but BACKUP, which it sends to its page under Backups. `useRun` polls while the run is unfinished, so its steps grow on screen.
 */
export function UpdateRunPage() {
  const { id } = useParams({ from: "/operations/updates/$id" })
  const numeric = /^\d+$/.test(id)
  const run = useRun(id, numeric)

  // A Discord card posted earlier can name a backup run under Updates.
  if (run.data?.kind === "BACKUP") return <Navigate {...runPath(run.data)} replace />

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={numeric ? t("steward.operations.run", { id }) : t("steward.operations.a-run")}
        actions={
          <Button asChild variant="outline">
            <Link to="/operations/updates">
              <ArrowRightIcon aria-hidden />
              {t("steward.operations.all-updates")}
            </Link>
          </Button>
        }
      />

      {!numeric ? (
        <Empty title={t("steward.operations.not-a-number")} />
      ) : (
        /** A missing row arrives as a 404 failure. */
        <QueryState query={run}>{(data) => <RunDetail run={data} />}</QueryState>
      )}
    </div>
  )
}

function RunDetail({ run }: { run?: Run }) {
  const stage = run?.stage
  const finished = !run ? false : stage ? ENDINGS.has(stage) : run.status !== "PENDING" && run.status !== "RUNNING"

  return (
    <>
      <div className="flex flex-wrap items-start gap-x-8 gap-y-4">
        <div className="flex flex-col gap-2">
          <span className="font-heading text-xs font-medium text-muted-foreground">
            {t("steward.operations.status")}
          </span>
          <div className="flex items-center gap-2">
            {stage ? (
              <StageBadge stage={stage} />
            ) : run ? (
              <RunStatus status={run.status} />
            ) : (
              <Skeleton className="h-5 w-20 rounded-full" />
            )}
          </div>
          <span className="text-xs text-muted-foreground">
            {run ? runKind(run.kind) : <SkeletonText className="text-xs" width="medium" />}
          </span>
        </div>

        <Stat
          label={t("steward.operations.requested-by")}
          value={run ? <Actor kind={run.actorKind} id={run.actorId} /> : undefined}
          hint={run ? dateTime(run.requested) : undefined}
        />
        {/* Steward does not pick the row up before this. */}
        <Stat label={t("steward.operations.no-earlier-than")} value={run ? dateTime(run.scheduledFor) : undefined} />
        <Stat
          label={t("steward.operations.started")}
          value={run ? dateTime(run.started) : undefined}
          hint={run ? relative(run.started) : undefined}
        />
        <Stat
          label={t("steward.operations.duration")}
          value={run ? span(runSeconds(run)) : undefined}
          hint={!run ? undefined : finished ? dateTime(run.finished) : t("steward.operations.still-running")}
        />
      </div>

      {stage === "NOTHING_TO_DO" ? (
        <Alert role="status">
          <TERM_ICON.nothingToDo aria-hidden />
          <AlertTitle>{t("steward.operations.nothing-to-do-title")}</AlertTitle>
        </Alert>
      ) : null}

      {run?.kind === "BACKUP" && finished && savedCount(run) === 0 ? (
        <Alert variant="destructive">
          <ShieldWarningIcon aria-hidden />
          <AlertTitle>{t("steward.operations.saved-nothing")}</AlertTitle>
        </Alert>
      ) : null}

      {stage && run ? (
        <Panel title={t("steward.operations.stages")}>
          <StageTrail stage={stage} kind={run.kind} />
        </Panel>
      ) : null}

      {!run ? (
        <Panel title={t("steward.operations.report")}>
          {/* The size of the Report card, so the page does not jump when it loads. */}
          <Loading rows={4} />
        </Panel>
      ) : run.steps.length === 0 ? (
        <Panel title={t("steward.operations.report")}>
          <Empty title={t("steward.operations.no-report")} />
        </Panel>
      ) : (
        <RunSteps steps={run.steps} title={t("steward.operations.report")} />
      )}
    </>
  )
}

/**
 * The stages that a finished run of each kind walked, because a run keeps only the last one. A run that did not end DONE gets no ticks.
 */
const WALKED: Record<string, ReadonlySet<string>> = {
  UPDATE: new Set(TRAIL),
  BACKUP: new Set(["PLANNED", "COUNTDOWN", "STOPPING", "BACKING_UP", "STARTING", "VERIFYING"]),
  RESTART: new Set(["PLANNED", "COUNTDOWN", "STOPPING", "STARTING", "VERIFYING"]),
  // A DOWN ends at STOPPING.
  DOWN: new Set(["PLANNED", "COUNTDOWN", "STOPPING"]),
  START: new Set(["STARTING", "VERIFYING"]),
  // Written once it is done, on a start with no server running yet.
  INSTALL: new Set(["PLANNED", "INSTALLING"]),
}

function StageTrail({ stage, kind }: { stage: string; kind: string }) {
  const trail: readonly string[] = TRAIL
  const reached = trail.indexOf(stage)
  const ending = ENDINGS.has(stage)
  const walked = stage === "DONE" ? WALKED[kind] : undefined

  return (
    <ol className="flex flex-wrap items-center gap-x-2 gap-y-3">
      {TRAIL.map((step, index) => {
        /**
         * A running run has walked everything before its stage. A finished one has walked what its kind walks, if it is DONE.
         */
        const past = ending ? (walked?.has(step) ?? false) : reached >= 0 && index < reached
        const now = index === reached
        return (
          <li key={step} aria-current={now ? "step" : undefined} className="flex items-center gap-2">
            {now ? (
              <Badge>{stageLabel(step)}</Badge>
            ) : (
              <span
                className={cn("flex items-center gap-1.5 text-xs", past ? "text-foreground" : "text-muted-foreground")}
              >
                {past ? <CheckIcon className="size-3 shrink-0" aria-hidden /> : null}
                {stageLabel(step)}
              </span>
            )}
            {index < TRAIL.length - 1 ? (
              <ArrowRightIcon className="size-3 shrink-0 text-muted-foreground" aria-hidden />
            ) : null}
          </li>
        )
      })}
      {ending ? (
        <li className="flex items-center gap-2">
          <ArrowRightIcon className="size-3 shrink-0 text-muted-foreground" aria-hidden />
          <StageBadge stage={stage} />
        </li>
      ) : null}
    </ol>
  )
}
