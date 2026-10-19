import { ArrowRightIcon, CheckIcon, ShieldWarningIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Link, useParams } from "@tanstack/react-router"
import { toast } from "sonner"

import type { ReportChange, ReportLine, Run } from "@/lib/api"
import { dateTime, parseInstant, relative } from "@/lib/format"
import { useAskForRun, useCancelRun, useRun, useServices } from "@/lib/queries"
import { useRunLock } from "@/lib/run-lock"
import { type MessageRef, choice, message, span, t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Stat } from "@/components/steward/stat"
import { Actor } from "@/components/steward/entity"
import { RunNotes } from "@/components/steward/run-notes"
import { RunStatus, StatusBadge, runKind, type Tone } from "@/components/steward/status"
import { Empty, Loading, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/steward/data-table"

/**
 * The vocabulary of a run, shared by Updates, Backups and a service's page. A run is a row in Steward's inbox and never a call to a container.
 */

/** The stages in `UpdateReport.Stage` order, without the four endings. */
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

/** Its word is the admin bundle's, so a state reads the same here and in Discord. */
const LINE_TONE: Record<string, Tone> = {
  UNCHANGED: "idle",
  PLANNED: "idle",
  STOPPED: "warn",
  INSTALLED: "warn",
  SAVED: "ok",
  RESTORED: "ok",
  STARTING: "warn",
  HEALTHY: "ok",
  FAILED: "down",
}

function LineState({ state }: { state: string }) {
  /** An unknown state keeps its enum name, so a newer Steward is noticed. */
  return <StatusBadge tone={LINE_TONE[state] ?? "idle"}>{t("run.state", { state: choice(state) })}</StatusBadge>
}

/**
 * One artefact, and what is happening to it. It branches on UNSUPPORTED and on `told`, whose `to` is `"-"`.
 */
export function Change({ change }: { change: ReportChange }) {
  if (change.state === "UNSUPPORTED") {
    return (
      <span className="text-muted-foreground">
        <code className="text-xs">{change.artefact}</code> - {t("steward.operations.no-build")}
      </span>
    )
  }
  if (change.told) {
    return (
      <span className="flex flex-wrap items-center gap-1.5">
        <code className="text-xs">{change.artefact}</code>
        <span className="text-muted-foreground">{message(change.told)}</span>
      </span>
    )
  }
  return (
    /** `flex-wrap`, so a long jar name does not leave a phone's edge. */
    <span className="flex flex-wrap items-center gap-1.5">
      <code className="text-xs">{change.artefact}</code>
      {change.from ? (
        <>
          <Was value={change.from} />
          <ArrowRightIcon className="size-3 shrink-0 text-muted-foreground" aria-hidden />
        </>
      ) : (
        <span className="text-muted-foreground">{t("steward.operations.added")}</span>
      )}
      <span className="tnum">{change.to}</span>
    </span>
  )
}

/** A fingerprint, as the resource pack reports its SHA-1. */
const FINGERPRINT = /^[0-9a-f]{32,64}$/i

/**
 * Shows what was there before: a version, or the first eight characters of a fingerprint. The whole hash is on the title.
 */
export function Was({ value }: { value: string }) {
  if (!FINGERPRINT.test(value)) {
    return <span className="text-muted-foreground tnum">{value}</span>
  }
  return (
    <span className="font-mono text-xs text-muted-foreground" title={value}>
      {value.slice(0, 8)}
    </span>
  )
}

/**
 * Returns whether a change row is a file actually moving. Steward omits the field for MOVING and a Gson round trip puts it back, so `state === "MOVING"` fails.
 */
function isMoving(change: ReportChange): boolean {
  return change.state !== "UNSUPPORTED"
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

/** Returns what a run did, one fact per line, counted out of the report. */
export function summaryOf(run: Run): string[] {
  if (run.resultText) return [t("steward.operations.report-unreadable")]
  const report = run.report
  if (!report) return [run.status === "PENDING" ? t("steward.operations.nothing-written") : "\u2013"]
  if (report.stage === "NOTHING_TO_DO") return [t("steward.operations.nothing-to-do")]

  const parts: string[] = []
  const saved = report.services.filter((line) => line.state === "SAVED")
  if (saved.length > 0) parts.push(t("steward.operations.saved", { count: saved.length }))

  const moving = report.services.filter((line) => line.changes.some(isMoving))
  if (moving.length > 0) {
    const artefacts = moving.reduce((sum, line) => sum + line.changes.filter(isMoving).length, 0)
    parts.push(t("steward.operations.moved", { services: moving.length, artefacts }))
  }

  const failed = report.services.filter((line) => line.state === "FAILED")
  if (failed.length > 0) parts.push(t("steward.operations.failed", { count: failed.length }))

  if (parts.length > 0) return parts
  return [report.services.length === 0 ? t("steward.operations.no-line") : t("steward.operations.no-change")]
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
 * One run, drawn and not dumped, for every kind but BACKUP. `useRun` polls while the run is unfinished, so the report grows on screen.
 */
export function UpdateRunPage() {
  const { id } = useParams({ from: "/operations/updates/$id" })
  const numeric = /^\d+$/.test(id)
  const run = useRun(id, numeric)

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
  const report = run?.report
  const finished = !run
    ? false
    : report
      ? ENDINGS.has(report.stage)
      : run.status !== "PENDING" && run.status !== "RUNNING"

  return (
    <>
      <div className="flex flex-wrap items-start gap-x-8 gap-y-4">
        <div className="flex flex-col gap-2">
          <span className="font-heading text-xs font-medium text-muted-foreground">
            {t("steward.operations.status")}
          </span>
          <div className="flex items-center gap-2">
            {report ? (
              <StageBadge stage={report.stage} />
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

      {report?.stage === "NOTHING_TO_DO" ? (
        <div className="flex items-start gap-3 rounded-md border border-border bg-secondary/40 px-4 py-3" role="status">
          <TERM_ICON.nothingToDo className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />
          <div className="flex flex-col gap-1">
            <p className="text-sm font-medium">{t("steward.operations.nothing-to-do-title")}</p>
          </div>
        </div>
      ) : null}

      {run?.savedSomething === false && run.kind === "BACKUP" && finished ? (
        <div
          className="flex items-start gap-3 rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3"
          role="alert"
        >
          <ShieldWarningIcon className="mt-0.5 size-5 shrink-0 text-destructive" aria-hidden />
          <div className="flex flex-col gap-1">
            <p className="text-sm font-medium">{t("steward.operations.saved-nothing")}</p>
          </div>
        </div>
      ) : null}

      {report && run ? (
        <Panel title={t("steward.operations.stages")}>
          <StageTrail stage={report.stage} kind={run.kind} />
        </Panel>
      ) : null}

      <Panel title={t("steward.operations.report")}>
        {!run ? (
          /** The size of the Report card, so the page does not jump when it loads. */
          <Loading rows={4} />
        ) : run.resultText ? (
          <>
            <p className="flex items-start gap-2 text-sm text-warning">
              <TERM_ICON.warning className="mt-0.5 size-4 shrink-0" aria-hidden />
              {t("steward.operations.raw-report")}
            </p>
            <pre className="max-h-96 overflow-auto rounded-md border border-border bg-card p-3 text-card-foreground font-mono text-xs leading-5 whitespace-pre-wrap">
              {run.resultText}
            </pre>
          </>
        ) : !report ? (
          <Empty title={t("steward.operations.no-report")} />
        ) : (
          <ReportLines lines={report.services} said={run.said} />
        )}
      </Panel>

      {report ? <RunNotes notes={report.notes} /> : null}
    </>
  )
}

/**
 * The stages that a finished run of each kind walked, because its report keeps only the last one. A run that did not end DONE gets no ticks.
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
          <li key={step} className="flex items-center gap-2">
            <span
              className={
                now
                  ? "flex items-center gap-1.5 rounded-full border border-warning/30 bg-warning/12 px-2 py-0.5 text-xs font-medium text-warning"
                  : past
                    ? "flex items-center gap-1.5 text-xs text-foreground"
                    : "flex items-center gap-1.5 text-xs text-muted-foreground"
              }
            >
              {past && !now ? <CheckIcon className="size-3 shrink-0" aria-hidden /> : null}
              {stageLabel(step)}
            </span>
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

function ReportLines({ lines, said }: { lines: ReportLine[]; said: Record<string, MessageRef> }) {
  if (lines.length === 0) {
    return <Empty title={t("steward.operations.no-line-title")} />
  }
  return (
    <DataTable
      rows={lines}
      rowKey={(line) => line.service}
      columns={[
        {
          head: t("steward.operations.service"),
          phone: { line: 1, side: "start" },
          className: "align-top font-medium",
          render: (line) => line.service,
        },
        {
          head: t("steward.operations.state"),
          phone: { line: 1, side: "end" },
          className: "align-top",
          render: (line) => <LineState state={line.state} />,
        },
        {
          head: t("steward.operations.changes"),
          grow: true,
          phone: { line: 2, side: "wide" },
          className: "align-top",
          render: (line) => {
            const words = said[line.service]
            return line.changes.length === 0 && !words ? (
              <span className="text-muted-foreground">{"\u2013"}</span>
            ) : (
              <div className="flex flex-col gap-1 py-1.5">
                {line.changes.map((change) => (
                  <Change key={`${line.service}-${change.artefact}`} change={change} />
                ))}
                {words ? (
                  <span
                    className={line.state === "FAILED" ? "text-xs text-destructive" : "text-xs text-muted-foreground"}
                  >
                    {message(words)}
                  </span>
                ) : null}
              </div>
            )
          },
        },
      ]}
    />
  )
}
