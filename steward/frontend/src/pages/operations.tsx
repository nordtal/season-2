import {
  ArchiveIcon,
  ArrowCounterClockwiseIcon,
  ArrowCircleUpIcon,
  ArrowRightIcon,
  ArrowsClockwiseIcon,
  CheckIcon,
  CopyIcon,
  PlayIcon,
  ProhibitInsetIcon,
  ShieldWarningIcon,
  PowerIcon,
  WarningIcon,
  XCircleIcon,
} from "@phosphor-icons/react"
import { useState } from "react"
import { Link, useParams } from "@tanstack/react-router"
import { toast } from "sonner"

import type { ReportChange, ReportLine, Run } from "@/lib/api"
import { dateTime, parseInstant, relative } from "@/lib/format"
import { useAskForRun, useCancelRun, useRun } from "@/lib/queries"
import { useRunLock } from "@/lib/run-lock"
import { choice, message, span, t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Stat } from "@/components/steward/stat"
import { Actor } from "@/components/steward/entity"
import { RunStatus, StatusBadge, runKind, type Tone } from "@/components/steward/status"
import { Empty, Loading, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/**
 * The vocabulary of a run, shared by Updates, Backups and a service's own page.
 *
 * A run is a row in steward's inbox, never a call to a container; steward claims it.
 */

/** The stages in `UpdateReport.Stage` order, without the four endings, shown in full for every kind. */
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

/** A stage as the trail names it, shorter than the admin bundle's sentence for it. */
function stageLabel(stage: string): string {
  return t("steward.operations.step", { stage: choice(stage) })
}

/** The four stages a run stops at; `NOTHING_TO_DO` is not a kind of "done". */
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

/** A report line's tone; its word is the admin bundle's, so a state reads the same here and in Discord. */
const LINE_TONE: Record<string, Tone> = {
  UNCHANGED: "idle",
  PLANNED: "idle",
  STOPPED: "warn",
  INSTALLED: "warn",
  SAVED: "ok",
  STARTING: "warn",
  HEALTHY: "ok",
  FAILED: "down",
}

function LineState({ state }: { state: string }) {
  /** An unknown state keeps its enum name, so a newer steward is noticed rather than hidden. */
  return <StatusBadge tone={LINE_TONE[state] ?? "idle"}>{t("run.state", { state: choice(state) })}</StatusBadge>
}

/**
 * One artefact, and what is happening to it.
 *
 * Branches on UNSUPPORTED and on `told`, whose `to` is `"-"`, and shows the raw words of a row stored before a change
 * was told in a message.
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
    /** `flex-wrap`, so a long jar name and both versions wrap instead of leaving a phone's edge. */
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

/** A fingerprint rather than a version, as the resource pack reports its SHA-1. */
const FINGERPRINT = /^[0-9a-f]{32,64}$/i

/**
 * What was there before this change: a version, or the first eight characters of a fingerprint.
 *
 * The whole hash is on the title.
 */
function Was({ value }: { value: string }) {
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
 * Whether a change row is a file actually moving.
 *
 * Not `state === "MOVING"`, since steward omits the field for MOVING and a Gson round trip puts it back.
 */
function isMoving(change: ReportChange): boolean {
  return change.state !== "UNSUPPORTED"
}

/** How long a run took, or how long it has been going so far. */
export function runSeconds(run: Run): number | null {
  const started = parseInstant(run.started)
  if (started == null) return null
  const finished = parseInstant(run.finished)
  return ((finished ?? new Date()).getTime() - started.getTime()) / 1000
}

/**
 * Whether the backend would still take this row back.
 *
 * Must match `UpdateDirectory#cancelCountdown`: a cancellable kind, PENDING before its schedule or RUNNING before
 * its countdown's end.
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

/** The kinds a countdown runs for, mirroring the list in steward's SQL. */
const CANCELLABLE_KINDS = new Set(["RESTART", "UPDATE", "BACKUP", "DOWN"])

/** What a run did, one fact per line, counted out of the report since steward writes no sentence. */
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

type Kind = "UPDATE" | "BACKUP" | "RESTART" | "DOWN" | "START"

/** Each kind's symbol; its title, what it does and what it costs are the bundle's. */
const ASKS: Record<Kind, typeof ArrowsClockwiseIcon> = {
  /** Its own symbol, so it is not mistaken for Recreate beside it on a service page. */
  UPDATE: ArrowCircleUpIcon,
  BACKUP: ArchiveIcon,
  RESTART: ArrowCounterClockwiseIcon,
  DOWN: PowerIcon,
  START: PlayIcon,
}

/**
 * One button, one confirmation: Now or Cancel.
 *
 * Every run stops servers, so the dialog names what will happen in at most two short lines.
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
  /** The compose services this run is for; left off for the whole network. */
  services?: string[]
  /** Overrides the button's own word, for a page where "Update" alone would be ambiguous. */
  label?: string
  size?: "default" | "sm"
  /** Lets a page hide the word below a breakpoint; the button keeps it as its accessible name. */
  labelClassName?: string
  className?: string
  /** The dialog's open state, so the service page's ⋯ menu opens the same confirmation. */
  open?: boolean
  onOpenChange?: (open: boolean) => void
  /** `false` draws the dialog alone, for a caller that opens it from somewhere else. */
  trigger?: boolean
}) {
  const ask = useAskForRun()
  const lock = useRunLock()
  const Icon = ASKS[kind]
  const title = t("steward.operations.ask", { kind: choice(kind) })
  const warning = t("steward.operations.ask-warning", { kind: choice(kind) })
  const scoped = services !== undefined && services.length > 0

  const submit = () =>
    ask
      .mutateAsync({ kind, services })
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
      description={t("steward.operations.ask-what", { kind: choice(kind) })}
      action={t("steward.operations.now")}
      destructive={kind === "RESTART" || kind === "DOWN"}
      act={submit}
    >
      {warning ? (
        <p className="flex items-start gap-2 text-sm text-warning">
          <WarningIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
          {warning}
        </p>
      ) : null}
    </AskThenAct>
  )
}

/**
 * Taking a run back, on its row, without a confirmation since it undoes one.
 *
 * A 409 means the countdown ran out first; the backend's sentence is shown as it came.
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
      <XCircleIcon aria-hidden />
      {t("steward.operations.cancel")}
    </Button>
  )
}

/**
 * One run, drawn rather than dumped, for every kind but BACKUP.
 *
 * `useRun` polls while the run is unfinished, so the report grows on screen.
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
        <Empty title={t("steward.operations.not-a-number")} note={t("steward.operations.not-a-number-note", { id })} />
      ) : (
        /** A missing row arrives as a 404 failure, and an answered query always has a body. */
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
      <div className="flex flex-wrap items-start gap-6">
        <div className="flex flex-col gap-2">
          <span className="text-xs font-medium text-muted-foreground">{t("steward.operations.status")}</span>
          <div className="flex items-center gap-2">
            {run ? <RunStatus status={run.status} /> : <Skeleton className="h-5 w-20 rounded-full" />}
            {report ? <StageBadge stage={report.stage} /> : null}
          </div>
          <span className="flex flex-col text-xs text-muted-foreground">
            {run ? (
              <>
                <span>{runKind(run.kind)}</span>
              </>
            ) : (
              <>
                <SkeletonText className="text-xs" width="medium" />
                <SkeletonText className="text-xs" width="short" />
              </>
            )}
          </span>
        </div>

        <Separator orientation="vertical" className="h-14" />

        <Stat
          label={t("steward.operations.requested-by")}
          value={run ? <Actor kind={run.actorKind} id={run.actorId} /> : undefined}
          hint={run ? dateTime(run.requested) : undefined}
        />
        <Stat
          label={t("steward.operations.no-earlier-than")}
          value={run ? dateTime(run.scheduledFor) : undefined}
          hint={t("steward.operations.no-earlier-than-hint")}
        />
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
          <ProhibitInsetIcon className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />
          <div className="flex flex-col gap-1">
            <p className="text-sm font-medium">{t("steward.operations.nothing-to-do-title")}</p>
            <p className="max-w-prose text-sm text-muted-foreground">{t("steward.operations.nothing-to-do-note")}</p>
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
            <p className="max-w-prose text-sm text-muted-foreground">{t("steward.operations.saved-nothing-note")}</p>
          </div>
        </div>
      ) : null}

      {report && run ? (
        <Panel title={t("steward.operations.stages")}>
          <StageTrail stage={report.stage} kind={run.kind} />
          {!finished ? (
            <p className="flex items-center gap-2 text-xs text-muted-foreground">
              <span className="size-2 animate-pulse rounded-full bg-warning" aria-hidden />
              {t("steward.operations.growing")}
            </p>
          ) : null}
        </Panel>
      ) : null}

      <Panel title={t("steward.operations.report")}>
        {!run ? (
          /** A placeholder the size of the Report card, so the page does not jump when it loads. */
          <Loading rows={4} />
        ) : run.resultText ? (
          <>
            <p className="flex items-start gap-2 text-sm text-warning">
              <WarningIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
              {t("steward.operations.raw-report")}
            </p>
            <pre className="max-h-96 overflow-auto rounded-md border border-border bg-[#0a0a0a] p-3 font-mono text-xs leading-5 whitespace-pre-wrap">
              {run.resultText}
            </pre>
          </>
        ) : !report ? (
          <Empty title={t("steward.operations.no-report")} note={t("steward.operations.no-report-note")} />
        ) : (
          <>
            <ReportLines lines={report.services} />
            <Notes notes={report.notes.map(message)} />
          </>
        )}
      </Panel>
    </>
  )
}

/**
 * The stages a finished run of each kind walked, since its report keeps only the last one.
 *
 * A run that did not end DONE gets no ticks, because where it stopped is no longer known.
 */
const WALKED: Record<string, ReadonlySet<string>> = {
  UPDATE: new Set(TRAIL),
  BACKUP: new Set(["PLANNED", "COUNTDOWN", "STOPPING", "BACKING_UP", "STARTING", "VERIFYING"]),
  RESTART: new Set(["PLANNED", "COUNTDOWN", "STOPPING", "STARTING", "VERIFYING"]),
  // A DOWN ends at STOPPING and never walks the starting half.
  DOWN: new Set(["PLANNED", "COUNTDOWN", "STOPPING"]),
  START: new Set(["STARTING", "VERIFYING"]),
}

function StageTrail({ stage, kind }: { stage: string; kind: string }) {
  const trail: readonly string[] = TRAIL
  const reached = trail.indexOf(stage)
  const ending = ENDINGS.has(stage)
  const walked = stage === "DONE" ? WALKED[kind] : undefined

  return (
    <ol className="flex flex-wrap items-center gap-x-2 gap-y-3">
      {TRAIL.map((step, index) => {
        /** A running run has walked everything before its stage; a finished one what its kind walks, if DONE. */
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

function ReportLines({ lines }: { lines: ReportLine[] }) {
  if (lines.length === 0) {
    return <Empty title={t("steward.operations.no-line-title")} note={t("steward.operations.no-line-note")} />
  }
  return (
    <Table className="steward-table">
      <TableHeader>
        <TableRow>
          <TableHead className="w-[14rem]">{t("steward.operations.service")}</TableHead>
          <TableHead className="w-[10rem]">{t("steward.operations.state")}</TableHead>
          <TableHead>{t("steward.operations.changes")}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {lines.map((line) => (
          <TableRow key={line.service} className="align-top">
            <TableCell data-label={t("steward.operations.service")} className="font-medium">
              {line.service}
            </TableCell>
            <TableCell data-label={t("steward.operations.state")}>
              <LineState state={line.state} />
            </TableCell>
            {/* `whitespace-normal`, so a long list of changes wraps instead of widening the table. */}
            <TableCell data-label={t("steward.operations.changes")} className="whitespace-normal">
              {line.changes.length === 0 && !line.detail ? (
                <span className="text-muted-foreground">{"\u2013"}</span>
              ) : (
                <div className="flex flex-col gap-1 py-1.5">
                  {line.changes.map((change) => (
                    <Change key={`${line.service}-${change.artefact}`} change={change} />
                  ))}
                  {line.detail ? (
                    <span
                      className={line.state === "FAILED" ? "text-xs text-destructive" : "text-xs text-muted-foreground"}
                    >
                      {message(line.detail)}
                    </span>
                  ) : null}
                </div>
              )}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

/** What the report says that belongs to no service: the pack, the migration, the reason. */
export function Notes({ notes }: { notes: string[] }) {
  if (notes.length === 0) return null
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-xs font-medium text-muted-foreground">{t("steward.operations.notes")}</span>
      <ul className="flex flex-col gap-1">
        {notes.map((note) => (
          <li key={note} className="text-sm text-muted-foreground">
            {note}
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Copy to clipboard, with a failure spelled out, since `navigator.clipboard` needs a secure context. */
export function CopyButton({ text, disabled }: { text: string; disabled?: boolean }) {
  const [copied, setCopied] = useState(false)

  return (
    <Button
      type="button"
      variant="outline"
      disabled={disabled}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text)
          setCopied(true)
          window.setTimeout(() => setCopied(false), 2000)
          toast.success(t("steward.operations.command-copied"))
        } catch {
          toast.error(t("steward.operations.cannot-copy"), { description: t("steward.operations.cannot-copy-note") })
        }
      }}
    >
      {copied ? <CheckIcon aria-hidden /> : <CopyIcon aria-hidden />}
      {copied ? t("steward.operations.copied") : t("steward.operations.copy")}
    </Button>
  )
}
