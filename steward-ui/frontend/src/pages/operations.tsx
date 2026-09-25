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
import {
  count,
  dateTime,
  duration,
  parseInstant,
  relative,
} from "@/lib/format"
import {
  useAskForRun,
  useCancelRun,
  useRun,
} from "@/lib/queries"
import { useRunLock } from "@/lib/run-lock"
import { PageHeader } from "@/components/steward/page-header"
import { Stat } from "@/components/steward/stat"
import { Actor } from "@/components/steward/entity"
import {
  RUN_KIND,
  RunStatus,
  StatusBadge,
  type Tone,
} from "@/components/steward/status"
import {
  Empty,
  Loading,
  QueryState,
  Skeleton,
  SkeletonText,
} from "@/components/steward/query-state"
import {
  ResponsiveAlertDialog,
  ResponsiveAlertDialogAction,
  ResponsiveAlertDialogCancel,
  ResponsiveAlertDialogContent,
  ResponsiveAlertDialogDescription,
  ResponsiveAlertDialogFooter,
  ResponsiveAlertDialogHeader,
  ResponsiveAlertDialogTitle,
  ResponsiveAlertDialogTrigger,
} from "@/components/ui/responsive-dialog"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

/**
 * The vocabulary of a run, shared by Updates, Backups and a service's own page (concept §10a).
 *
 * **A run is a row in `update_request`, never a call to a container.** Every button here writes one
 * and stops; the worker claims it once `not_before` has passed. That is what makes a run
 * schedulable and what puts a countdown in front of every player before anything is stopped - and
 * it is why the toasts below speak of a row being written rather than of an update having started.
 */

// --- the vocabulary of a report -----------------------------------------------------------------

/**
 * The stages in the order `UpdateReport.Stage` declares them, without the four endings.
 *
 * Shown in full for every kind rather than filtered per kind. Which stages a BACKUP walks and which
 * an UPDATE walks is the worker's decision, and a trail that guessed it would be quietly wrong on
 * the day the worker changes - so the trail shows all of them and the line underneath says that not
 * every run passes every one.
 */
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

const STAGE_LABEL: Record<string, string> = {
  RESOLVING: "Resolving",
  PLANNED: "Planned",
  COUNTDOWN: "Countdown",
  STOPPING: "Stopping",
  BACKING_UP: "Backing up",
  INSTALLING: "Installing",
  STARTING: "Starting",
  VERIFYING: "Verifying",
  DONE: "Done",
  NOTHING_TO_DO: "Nothing to do",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
}

/** The four stages a run stops at. `NOTHING_TO_DO` is one of them and is not a kind of "done". */
export const ENDINGS = new Set(["DONE", "NOTHING_TO_DO", "FAILED", "CANCELLED"])

/**
 * `NOTHING_TO_DO` is grey, not green, and that is the whole point of this function.
 *
 * A run that found nothing to do stopped no server and installed nothing. Painting it in the same
 * colour as a finished update makes the two indistinguishable in a list - and the difference
 * between "it was updated" and "there was nothing to update" is exactly what somebody scanning
 * this table is looking for.
 */
function stageTone(stage: string): Tone {
  if (stage === "DONE") return "ok"
  if (stage === "FAILED") return "down"
  if (stage === "NOTHING_TO_DO" || stage === "CANCELLED") return "idle"
  return "warn"
}

export function StageBadge({ stage }: { stage: string }) {
  return <StatusBadge tone={stageTone(stage)}>{STAGE_LABEL[stage] ?? stage}</StatusBadge>
}

const LINE_STATE: Record<string, { label: string; tone: Tone }> = {
  UNCHANGED: { label: "unchanged", tone: "idle" },
  PLANNED: { label: "waiting", tone: "idle" },
  STOPPED: { label: "stopped", tone: "warn" },
  INSTALLED: { label: "installed", tone: "warn" },
  SAVED: { label: "saved", tone: "ok" },
  STARTING: { label: "starting", tone: "warn" },
  HEALTHY: { label: "healthy", tone: "ok" },
  FAILED: { label: "failed", tone: "down" },
}

function LineState({ state }: { state: string }) {
  const known = LINE_STATE[state]
  // An unknown state keeps its enum name rather than disappearing: a worker newer than this jar is
  // a thing to notice, not a thing to hide behind a neutral badge with no text.
  return <StatusBadge tone={known?.tone ?? "idle"}>{known?.label ?? state}</StatusBadge>
}

/**
 * One artefact, and what is happening to it.
 *
 * `UpdateReport.Change.State` has exactly two values, MOVING and UNSUPPORTED, and `to` carries the
 * sentinel `"-"` for the second one. (`src/lib/api.ts` calls the second value UNCHANGED in a
 * comment; the enum in `:common` has no such value. This branches on what the worker actually
 * writes and falls through to the raw words for anything a newer one might add.)
 */
function Change({ change }: { change: ReportChange }) {
  if (change.state === "UNSUPPORTED") {
    return (
      <span className="text-muted-foreground">
        <code className="text-xs">{change.artefact}</code> - no build for this Minecraft
        version
      </span>
    )
  }
  return (
    // `flex-wrap`: MEASURED 2026-09-14 at 390px on a run's own page. A jar name, the old
    // version, an arrow and the new one is 180px of unbreakable content more than the card has,
    // and a flex row with nowhere to break puts the last two off the right edge of the phone.
    <span className="flex flex-wrap items-center gap-1.5">
      <code className="text-xs">{change.artefact}</code>
      {change.from ? (
        <>
          <Was value={change.from} />
          <ArrowRightIcon className="size-3 shrink-0 text-muted-foreground" aria-hidden />
        </>
      ) : (
        <span className="text-muted-foreground">new:</span>
      )}
      <span className="tnum">{change.to}</span>
    </span>
  )
}

/**
 * A fingerprint rather than a version: forty hex characters and nothing else.
 *
 * The resource pack is the artefact this exists for (season-2-ops/142). It has no version - its
 * hash IS its version, as the ticket puts it - so the worker reports the SHA-1 that was installed
 * before the run, and forty characters of it used to sit in the middle of a table cell on a phone.
 */
const FINGERPRINT = /^[0-9a-f]{32,64}$/i

/**
 * What was there before this change: a version, or the first eight characters of a fingerprint.
 *
 * Shortened rather than dropped. Which pack was on the proxy is the one fact a report can offer
 * about a pack, and it is what tells two runs of the same release apart; the whole hash is on the
 * title, where somebody comparing it against `pack.yml` can still reach it.
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
 * Deliberately not `state === "MOVING"`. `UpdateReports` omits the field entirely when it is MOVING
 * and writes it only for UNSUPPORTED - measured against runs 22 and 24 in this host's database on
 * 2026-09-13 - while steward-ui parses that JSON into the record and re-writes it through Gson,
 * which puts the field back. "Anything that is not UNSUPPORTED" is true of both shapes; a literal
 * comparison is true of one of them, and silently counts nothing on the other.
 */
function isMoving(change: ReportChange): boolean {
  return change.state !== "UNSUPPORTED"
}

/** How long a run took - or, while it is still going, how long it has been going. */
export function runSeconds(run: Run): number | null {
  const started = parseInstant(run.started)
  if (started == null) return null
  const finished = parseInstant(run.finished)
  return ((finished ?? new Date()).getTime() - started.getTime()) / 1000
}

/**
 * Whether this row is one the backend would still take back (steward/131).
 *
 * THE SAME CONDITION AS THE SQL, and it has to stay that way: `UpdateDirectory#cancelCountdown`
 * takes `status IN ('PENDING','RUNNING') AND kind IN ('RESTART','UPDATE','BACKUP','DOWN') AND
 * not_before > now()`. A button drawn under any wider rule is a button that answers "too late" -
 * which is honest, but it is a tap somebody made for nothing.
 *
 * The moment is the row's own `not_before`, not its age: a run entered for tonight sits PENDING
 * for hours and is cancellable the whole time, while one whose countdown has run out is RUNNING
 * with its moment in the past and is not. Those two look the same in every other column.
 *
 * `now` is a parameter because this is the piece with arithmetic in it and the page has to draw
 * the same answer as the backend a second later.
 */
export function cancellable(run: Run, now = new Date()): boolean {
  if (run.status !== "PENDING" && run.status !== "RUNNING") return false
  if (!CANCELLABLE_KINDS.has(run.kind)) return false
  const moment = parseInstant(run.notBefore)
  return moment !== null && moment.getTime() > now.getTime()
}

/**
 * The kinds a countdown is run for, and therefore the only ones there is a window to cancel in.
 *
 * REPORT and START stop nothing and are over before anybody could press anything; APPLY was
 * retired. The worker's SQL lists these four by name, so this list is a mirror rather than a
 * judgement - if one is ever added there, it is added here.
 */
const CANCELLABLE_KINDS = new Set(["RESTART", "UPDATE", "BACKUP", "DOWN"])

/**
 * What a run did, one fact per line in the table's Result cell.
 *
 * Counted out of the report rather than taken from a sentence, because there is no sentence: the
 * worker writes structure and every surface renders its own summary (`UpdateReport`'s own comment
 * says so).
 *
 * Used to be one line joined with a middle dot; Till, 2026-09-16, ruled that separator out of the
 * UI entirely, so each fact now gets its own line instead of a shared one (steward/84).
 */
export function summaryOf(run: Run): string[] {
  if (run.resultText) return ["report unreadable"]
  const report = run.report
  if (!report) return [run.status === "PENDING" ? "nothing written yet" : "–"]
  if (report.stage === "NOTHING_TO_DO") return ["nothing to do"]

  const parts: string[] = []
  const saved = report.services.filter((line) => line.state === "SAVED")
  if (saved.length > 0) parts.push(`${count(saved.length)} saved`)

  const moving = report.services.filter((line) => line.changes.some(isMoving))
  if (moving.length > 0) {
    const artefacts = moving.reduce(
      (sum, line) => sum + line.changes.filter(isMoving).length,
      0,
    )
    parts.push(
      `${count(moving.length)} ${moving.length === 1 ? "service" : "services"}, ` +
        `${count(artefacts)} ${artefacts === 1 ? "artefact" : "artefacts"}`,
    )
  }

  const failed = report.services.filter((line) => line.state === "FAILED")
  if (failed.length > 0) parts.push(`${count(failed.length)} failed`)

  if (parts.length > 0) return parts
  return [report.services.length === 0 ? "no line in the report" : "no change"]
}

const SOURCE_LABEL: Record<string, string> = {
  DISCORD: "Discord",
  GAME: "in game",
  CONSOLE: "Interface/console",
}

// --- asking for a run ----------------------------------------------------------------------------

type Kind = "UPDATE" | "BACKUP" | "RESTART" | "DOWN" | "START"

const ASKS: Record<
  Kind,
  { title: string; what: string; warning?: string; icon: typeof ArrowsClockwiseIcon }
> = {
  UPDATE: {
    title: "Update",
    what: "Stops what changes, swaps its jars and starts it again.",
    // Its own symbol, not Recreate's: the two sit side by side on a service page, and a button
    // that looks like another one is a risk on a phone.
    icon: ArrowCircleUpIcon,
  },
  BACKUP: {
    title: "Back up",
    what: "Dumps the database, then packs every volume.",
    warning: "The network is offline while it packs.",
    icon: ArchiveIcon,
  },
  RESTART: {
    title: "Restart",
    what: "Stops the network and starts it again.",
    warning: "Every player is thrown off.",
    icon: ArrowCounterClockwiseIcon,
  },
  DOWN: {
    title: "Take down",
    what: "Counts down and leaves it stopped.",
    warning: "It stays down until somebody presses Start.",
    icon: PowerIcon,
  },
  START: {
    title: "Start",
    what: "Takes the hold off and starts it again.",
    icon: PlayIcon,
  },
}

/**
 * One button, one confirmation: Now or Cancel.
 *
 * The dialog is not a formality - all of these stop servers - so it names what will happen in at
 * most two short lines before it happens. A run for later is the backend's `not_before`, which
 * carries the countdown; this page no longer offers one.
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
  /**
   * Which compose services this run is for (season-2-ops/127). Left off for the whole network,
   * which is what every button on /operations means.
   */
  services?: string[]
  /** Overrides the button's own word, for a page where "Update" alone would be ambiguous. */
  label?: string
  size?: "default" | "sm"
  /** Lets a page hide the word below a breakpoint; the button keeps it as its accessible name. */
  labelClassName?: string
  className?: string
  /**
   * The dialog, steerable from outside (steward/140): the service page's ⋯ menu opens the same
   * confirmation a button would, rather than a second copy of it.
   */
  open?: boolean
  onOpenChange?: (open: boolean) => void
  /** `false` draws the dialog alone, for a caller that opens it from somewhere else. */
  trigger?: boolean
}) {
  const ask = useAskForRun()
  const lock = useRunLock()
  const spec = ASKS[kind]
  const Icon = spec.icon
  const scoped = services !== undefined && services.length > 0

  const submit = () => {
    ask.mutate(
      { kind, services },
      {
        onSuccess: (run) => {
          toast.success(`${RUN_KIND[kind]} entered as run #${run.id}`)
        },
        onError: (error) => {
          toast.error(`${RUN_KIND[kind]} was not entered`, { description: error.message })
        },
      },
    )
  }

  return (
    <ResponsiveAlertDialog open={open} onOpenChange={onOpenChange}>
      {trigger ? (
        <ResponsiveAlertDialogTrigger asChild>
          <Button
            type="button"
            variant={variant}
            size={size}
            className={className}
            disabled={ask.isPending || lock.locked}
            title={lock.title}
            aria-label={labelClassName ? (label ?? RUN_KIND[kind]) : undefined}
          >
            <Icon aria-hidden />
            <span className={labelClassName}>{label ?? RUN_KIND[kind]}</span>
          </Button>
        </ResponsiveAlertDialogTrigger>
      ) : null}
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>
            {scoped ? `${spec.title} for ${services.join(", ")}` : spec.title}
          </ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>{spec.what}</ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>

        {spec.warning ? (
          <p className="flex items-start gap-2 text-sm text-warning">
            <WarningIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
            {spec.warning}
          </p>
        ) : null}

        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            variant={kind === "RESTART" || kind === "DOWN" ? "destructive" : "default"}
            onClick={submit}
          >
            Now
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/**
 * Taking a run back, on the row it belongs to (steward/131).
 *
 * **No confirmation in front of it.** Every other button on this page opens a dialog because every
 * other button starts something; this one undoes what a dialog already asked about. A second "are
 * you sure" in front of an undo is a countdown running out while somebody reads it.
 *
 * It sits beside the status badge rather than in a column of its own: the button is only there for
 * one row at a time, and an eighth column would be an empty cell on every other row - which on a
 * phone, where each row is a card and a cell is a line, is seven blank lines.
 *
 * The 409 is a normal answer, not a failure of this interface: between the tap and the request the
 * countdown can run out, and then nothing was cancelled and nothing was broken either. The backend
 * sends the sentence; it is shown as it came.
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
            toast.success(`Run #${cancelled.id} cancelled`, {
              description: "Nothing was stopped. The row is CANCELLED and names who took it back.",
            })
          },
          onError: (error) => {
            toast.error(`Run #${run.id} was not cancelled`, { description: String(error.message) })
          },
        })
      }
    >
      <XCircleIcon aria-hidden />
      Cancel
    </Button>
  )
}

// --- /operations/updates/$id ---------------------------------------------------------------------

/**
 * One run, drawn rather than dumped - every kind but BACKUP, which has its own page under Backups.
 *
 * `useRun` polls every two seconds while the run is unfinished and stops by itself, so the report
 * grows on screen without a socket or an interval of this page's own.
 */
export function UpdateRunPage() {
  const { id } = useParams({ from: "/operations/updates/$id" })
  const numeric = /^\d+$/.test(id)
  const run = useRun(id, numeric)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={numeric ? `Run #${id}` : "Run"}
        actions={
          <Button asChild variant="outline">
            <Link to="/operations/updates">
              <ArrowRightIcon aria-hidden />
              All updates
            </Link>
          </Button>
        }
      />

      {!numeric ? (
        <Empty
          title="Not a run number"
          note={`"${id}" is not a number. A run is addressed by the number of its row.`}
        />
      ) : (
        // The worker answers a missing row with a 404, which arrives as a failure with the number
        // in it, and an answered query with no body is not a state this route can produce.
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
      <Card>
        <CardContent className="flex flex-wrap items-start gap-6 pt-6">
          <div className="flex flex-col gap-2">
            <span className="text-xs font-medium text-muted-foreground">
              Status
            </span>
            <div className="flex items-center gap-2">
              {run ? <RunStatus status={run.status} /> : <Skeleton className="h-5 w-20 rounded-full" />}
              {report ? <StageBadge stage={report.stage} /> : null}
            </div>
            <span className="flex flex-col text-xs text-muted-foreground">
              {run ? (
                <>
                  <span>{RUN_KIND[run.kind] ?? run.kind}</span>
                  <span>{SOURCE_LABEL[run.source] ?? run.source}</span>
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
            label="Requested by"
            value={
              run ? (
                <Actor system={run.system} discordId={run.actorDiscordId} label={run.actorLabel} />
              ) : undefined
            }
            hint={run ? dateTime(run.requested) : undefined}
          />
          <Stat
            label="No earlier than"
            value={run ? dateTime(run.notBefore) : undefined}
            hint="the worker does not pick the row up before this"
          />
          <Stat
            label="Started"
            value={run ? dateTime(run.started) : undefined}
            hint={run ? relative(run.started) : undefined}
          />
          <Stat
            label="Duration"
            value={run ? duration(runSeconds(run)) : undefined}
            hint={!run ? undefined : finished ? dateTime(run.finished) : "still running"}
          />
        </CardContent>
      </Card>

      {report?.stage === "NOTHING_TO_DO" ? (
        <div
          className="flex items-start gap-3 rounded-md border border-border bg-secondary/40 px-4 py-3"
          role="status"
        >
          <ProhibitInsetIcon className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />
          <div className="flex flex-col gap-1">
            <p className="text-sm font-medium">Nothing to do.</p>
            <p className="max-w-prose text-sm text-muted-foreground">
              Nothing was stopped, nothing swapped and nothing backed up.
            </p>
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
            <p className="text-sm font-medium">This run saved nothing.</p>
            <p className="max-w-prose text-sm text-muted-foreground">
              No line of the report stands at "saved".
            </p>
          </div>
        </div>
      ) : null}

      {report && run ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm font-medium">Stages</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <StageTrail stage={report.stage} kind={run.kind} />
            {!finished ? (
              <p className="flex items-center gap-2 text-xs text-muted-foreground">
                <span className="size-2 animate-pulse rounded-full bg-warning" aria-hidden />
                The report is re-read every two seconds and grows while you watch.
              </p>
            ) : null}
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Report</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {!run ? (
            // The card keeps its place while the row is read: it is the tallest thing on the page,
            // and a Report card that appears after the head above it would move the whole page.
            <Loading rows={4} />
          ) : run.resultText ? (
            <>
              <p className="flex items-start gap-2 text-sm text-warning">
                <WarningIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
                The contents of the <code className="text-xs">result</code> column could not be
                read as a report - an old row, or one from a newer version than this. The raw text
                is therefore shown here.
              </p>
              <pre className="max-h-96 overflow-auto rounded-md border border-border bg-[#0a0a0a] p-3 font-mono text-xs leading-5 whitespace-pre-wrap">
                {run.resultText}
              </pre>
            </>
          ) : !report ? (
            <Empty
              title="No report yet"
              note="The result column is empty. Until the worker has picked the row up, nobody writes into it."
            />
          ) : (
            <>
              <ReportLines lines={report.services} />
              <Notes notes={report.notes} />
            </>
          )}
        </CardContent>
      </Card>
    </>
  )
}

/**
 * Which stations a finished run of each kind really walked.
 *
 * A finished run's report keeps only its last stage, so the trail behind it has to be derived -
 * and it used to be derived as "all", which drew a completed backup as having passed through
 * "Resolving" and "Installing". Neither is something a backup does: `RESOLVING` and `INSTALLING`
 * belong to an update (`Runner#update`), `BACKING_UP` to a backup (`Runner#backup`, the one caller
 * of `UpdateRun#save`), and a restart walks none of the three.
 *
 * A run that ended any other way than DONE gets no ticks at all. `NOTHING_TO_DO` stopped nothing
 * and installed nothing, and a `FAILED` or `CANCELLED` run stopped somewhere this report no longer
 * says - and a grey trail is the honest shape of "not known".
 */
const WALKED: Record<string, ReadonlySet<string>> = {
  UPDATE: new Set(TRAIL),
  BACKUP: new Set(["PLANNED", "COUNTDOWN", "STOPPING", "BACKING_UP", "STARTING", "VERIFYING"]),
  RESTART: new Set(["PLANNED", "COUNTDOWN", "STOPPING", "STARTING", "VERIFYING"]),
  // A DOWN ends at STOPPING and never walks the starting half - that is the whole of what it is.
  DOWN: new Set(["PLANNED", "COUNTDOWN", "STOPPING"]),
  START: new Set(["STARTING", "VERIFYING"]),
}

function StageTrail({ stage, kind }: { stage: string; kind: string }) {
  const reached = TRAIL.indexOf(stage as (typeof TRAIL)[number])
  const ending = ENDINGS.has(stage)
  const walked = stage === "DONE" ? WALKED[kind] : undefined

  return (
    <ol className="flex flex-wrap items-center gap-x-2 gap-y-3">
      {TRAIL.map((step, index) => {
        // A running run has walked everything before its current stage. A finished one has walked
        // what its kind walks - which is not all of them, and not anything at all unless it is DONE.
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
              {STAGE_LABEL[step]}
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
    return (
      <Empty
        title="No line in the report"
        note="The report is there but names no service - the run has touched none yet."
      />
    )
  }
  return (
    <Table className="steward-table">
      <TableHeader>
        <TableRow>
          <TableHead className="w-[14rem]">Service</TableHead>
          <TableHead className="w-[10rem]">State</TableHead>
          <TableHead>Changes</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {lines.map((line) => (
          <TableRow key={line.service} className="align-top">
            <TableCell data-label="Service" className="font-medium">{line.service}</TableCell>
            <TableCell data-label="State">
              <LineState state={line.state} />
            </TableCell>
            {/* `whitespace-normal`: the Table component puts `whitespace-nowrap` on every cell,
                which is right for a service name and wrong for a list of them. MEASURED
                2026-09-14 at 1440px: a failed run of proxy drew this table 1922px wide
                on a 1440px screen, because six artefact changes were one unbreakable line. */}
            <TableCell data-label="Changes" className="whitespace-normal">
              {line.changes.length === 0 && !line.detail ? (
                <span className="text-muted-foreground">–</span>
              ) : (
                <div className="flex flex-col gap-1 py-1.5">
                  {line.changes.map((change) => (
                    <Change key={`${line.service}-${change.artefact}`} change={change} />
                  ))}
                  {line.detail ? (
                    <span
                      className={
                        line.state === "FAILED"
                          ? "text-xs text-destructive"
                          : "text-xs text-muted-foreground"
                      }
                    >
                      {line.detail}
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

/** What the report has to say that hangs on no service: the pack, the migration, the reason. */
export function Notes({ notes }: { notes: string[] }) {
  if (notes.length === 0) return null
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-xs font-medium text-muted-foreground">
        Notes
      </span>
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

/**
 * Copy to clipboard, with the failure spelled out rather than swallowed.
 *
 * `navigator.clipboard` only exists in a secure context. Behind Caddy that is always true, but a
 * button that silently does nothing on the one day it is not would be worse than no button - so the
 * command stays selectable in the block beside it and a failure says why.
 */
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
          toast.success("Command copied")
        } catch {
          toast.error("Cannot copy", {
            description:
              "The clipboard is not available to this page. The command can be selected beside it.",
          })
        }
      }}
    >
      {copied ? <CheckIcon aria-hidden /> : <CopyIcon aria-hidden />}
      {copied ? "Copied" : "Copy"}
    </Button>
  )
}
