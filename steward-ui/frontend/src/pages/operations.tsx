import {
  ArchiveIcon,
  ArrowCounterClockwiseIcon,
  ArrowCircleUpIcon,
  ArrowRightIcon,
  ArrowsClockwiseIcon,
  CheckIcon,
  CopyIcon,
  DatabaseIcon,
  DownloadIcon,
  FileTextIcon,
  PlayIcon,
  ProhibitInsetIcon,
  ShieldWarningIcon,
  PowerIcon,
  WarningIcon,
  XCircleIcon,
} from "@phosphor-icons/react"
import { useMemo, useState } from "react"
import { cn } from "cn"
import { Link, useParams } from "@tanstack/react-router"
import { toast } from "sonner"

import type { AvailableChange, Backup, ReportChange, ReportLine, Run, ServiceTable } from "@/lib/api"
import { archived } from "@/lib/backup-name"
import { versionJump } from "@/lib/version-jump"
import {
  LOCALE,
  bytes,
  count,
  dateTime,
  duration,
  load,
  parseInstant,
  relative,
  since,
} from "@/lib/format"
import {
  useAskForRun,
  useAvailable,
  useCancelRun,
  useBackups,
  useHost,
  useRefreshAvailable,
  useRun,
  useRuns,
  useServices,
} from "@/lib/queries"
import { PageHeader } from "@/components/steward/page-header"
import { Stat } from "@/components/steward/stat"
import {
  AvailableBadge,
  DriftBadge,
  RUN_KIND,
  RunStatus,
  StatusBadge,
  held,
  type Tone,
} from "@/components/steward/status"
import { RecreateButton } from "@/components/steward/recreate"
import {
  Empty,
  Failure,
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
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
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
 * Operations - the five pages about runs, images, backups and the way back (concept §10a).
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
const ENDINGS = new Set(["DONE", "NOTHING_TO_DO", "FAILED", "CANCELLED"])

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

function StageBadge({ stage }: { stage: string }) {
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
    // `flex-wrap`: MEASURED 2026-09-14 at 390px on /operations/runs/latest. A jar name, the old
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
function runSeconds(run: Run): number | null {
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
function summaryOf(run: Run): string[] {
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
          toast.error(`${RUN_KIND[kind]} was not entered`, { description: String(error) })
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
            disabled={ask.isPending}
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

function AskBar() {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <AskButton kind="UPDATE" variant="default" />
      <AskButton kind="BACKUP" />
      <AskButton kind="RESTART" />
      <StartHeldButton />
    </div>
  )
}

/** Every service that is stopped and meant to be, by name. */
export function heldServices(table?: ServiceTable): string[] {
  return (table?.services ?? []).filter(held).map((service) => service.service)
}

/**
 * One way back up for everything that was put down (steward/134).
 *
 * `/update start` without an argument exists for the case Till named when he built it: after a
 * restart, not knowing any more which services you held. In Steward the only way back was the
 * service page of each one in turn, which is the same errand done N times.
 *
 * **It sends no scope**, which is the whole point and not a shortcut: the worker lifts every hold
 * in `service_hold`, including one on a service this browser's table does not know about because
 * its answer is a minute old. A list assembled here would be a second opinion about what is held.
 *
 * **Below two holds it does not draw.** For a single one the service's own page is the shorter way
 * and carries the since and the by with it; a button that is always there is one nobody reads.
 */
function StartHeldButton() {
  const services = useServices()
  const names = heldServices(services.data)
  if (names.length < 2) return null
  return <AskButton kind="START" label={`Start held (${names.length})`} />
}

// --- the drift table, shared by /operations and /operations/plan ---------------------------------------

/**
 * Whatever wants attention first: OUTDATED, then UNKNOWN, then LOCAL, then UP_TO_DATE, then by
 * name. LOCAL sorts ahead of UP_TO_DATE deliberately - it is not an alarm, but it is worth noticing
 * before a screenful of ordinary rows (steward/75).
 */
const DRIFT_RANK: Record<string, number> = { OUTDATED: 0, LOCAL: 2, UP_TO_DATE: 3 }

/** Ten absent services, because this stack has ten. */
const WAITING_SERVICES = Array.from({ length: 10 }, () => undefined)

function DriftCard() {
  const services = useServices()

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Images</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <QueryState
          query={services}
          empty={{
            title: "No container in the project",
            note: "steward-worker answered, but no container carries the compose project label.",
          }}
          isEmpty={(table: ServiceTable) => table.services.length === 0}
        >
          {(table) => {
            const rows = table
              ? [...table.services].sort(
                  (left, right) =>
                    (DRIFT_RANK[left.drift] ?? 1) - (DRIFT_RANK[right.drift] ?? 1) ||
                    left.service.localeCompare(right.service, LOCALE),
                )
              : // Ten, because the stack has ten services. The number is a fact about this
                // deployment and belongs beside the table that draws it, not in a shared constant
                // that would then be wrong for the next list (steward/120).
                WAITING_SERVICES
            return (
              <>
                {table ? (
                  <p className="text-xs text-muted-foreground">
                    {table.drift.checkedAt
                      ? `Registry last queried ${relative(table.drift.checkedAt)} (${dateTime(table.drift.checkedAt)}) - that is the age of this comparison, not of the row beside it.`
                      : "The registry has not been queried yet; no row below is a comparison."}
                  </p>
                ) : (
                  <SkeletonText className="text-xs" width="long" />
                )}
                {table?.drift.reached === false ? (
                  <p className="flex items-start gap-2 text-xs text-warning">
                    <WarningIcon className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                    The registry could not be reached
                    {table.drift.reason ? ` (${table.drift.reason})` : ""}.
                    {table.drift.message ? ` ${table.drift.message}` : ""}
                  </p>
                ) : null}

                <Table className="steward-table">
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-[14rem]">Service</TableHead>
                      <TableHead>Image</TableHead>
                      <TableHead className="w-[8rem]">Compared</TableHead>
                      <TableHead className="w-[10rem] text-right">Container</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((service, index) => (
                      <TableRow key={service?.service ?? index}>
                        <TableCell data-label="Service" className="font-medium">
                          {service ? (
                            <Link
                              to="/services/$name"
                              params={{ name: service.service }}
                              className="underline-offset-4 hover:text-primary hover:underline"
                            >
                              {service.service}
                            </Link>
                          ) : (
                            <SkeletonText width="medium" />
                          )}
                        </TableCell>
                        <TableCell data-label="Image" className="text-muted-foreground">
                          {service ? (
                            <code className="text-xs">{service.image}</code>
                          ) : (
                            <SkeletonText className="text-xs" width="long" />
                          )}
                        </TableCell>
                        <TableCell data-label="Compared">
                          {service ? <DriftBadge drift={service.drift} image={service.image}/> : <Skeleton className="h-5 w-16 rounded-full" />}
                        </TableCell>
                        <TableCell data-label="Container" className="text-right">
                          {service ? <RecreateButton service={service.service} /> : null}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>

                {table && table.drift.unverifiable.length > 0 ? (
                  <p className="text-xs text-muted-foreground">
                    Unchecked: {table.drift.unverifiable.join(", ")} - either the registry did not
                    answer, or this container's exact image is no longer on file locally (its tag
                    was rebuilt without recreating it). A build performed on this host and never
                    published is its own row above, marked "local build", not listed here: that is
                    a known answer, not an unanswered question.
                  </p>
                ) : null}
              </>
            )
          }}
        </QueryState>
      </CardContent>
    </Card>
  )
}

// --- 1. /operations ---------------------------------------------------------------------------------

/**
 * The overview: runs, images and backups on one page.
 *
 * Three questions, in this order: what happened last, is anything behind, and is there a backup I
 * could actually use. The third one comes last on screen and first in consequence - it is what
 * decides whether acting on the other two is safe.
 */
export function OperationsPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Operations"
        actions={<AskBar />}
      />

      <RunsCard />
      <HostCard />
      <DriftCard />
    </div>
  )
}

/**
 * The one number the front page dropped: `load1` (steward/80).
 *
 * Till asked what the CPU tile's "Load" figure meant and, once told it is the host's one-minute
 * load average and not a clock speed, decided it should not be on the front page at all - but the
 * number itself is not abolished, only moved to where there is room for the sentence that explains
 * it (steward/65 argued against dropping a number without a destination, and this is that
 * destination).
 */
function HostCard() {
  const host = useHost()

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Host</CardTitle>
      </CardHeader>
      <CardContent>
        <QueryState query={host}>
          {(data) => (
            <Stat
              label="Load"
              value={data ? load(data.load1) : undefined}
              // No cpu count, no hint - "across - cores" is a half sentence (steward/123).
              hint={
                data?.cpus == null
                  ? undefined
                  : `1-minute average across ${count(data.cpus)} cores`
              }
            />
          )}
        </QueryState>
      </CardContent>
    </Card>
  )
}

/** Eight absent runs: shorter than the twenty asked for, and taller than the card ever needs. */
const WAITING_RUNS = Array.from({ length: 8 }, () => undefined)

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
function CancelButton({ run }: { run: Run }) {
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

function RunsCard() {
  const runs = useRuns(20)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Runs</CardTitle>
        <CardAction>
          <div className="flex flex-wrap items-center gap-2">
            <Button asChild variant="outline" size="sm">
              <Link to="/operations/backups">
                <ArchiveIcon aria-hidden />
                Backups
              </Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link to="/operations/plan">
                <FileTextIcon aria-hidden />
                View the plan
              </Link>
            </Button>
          </div>
        </CardAction>
      </CardHeader>
      <CardContent>
        <QueryState
          query={runs}
          empty={{
            title: "No run yet",
            note: "There is no row in update_request - not from this interface, not from Discord, not from the worker's clock.",
          }}
          isEmpty={(rows: Run[]) => rows.length === 0}
        >
          {(rows) => (
            <Table className="steward-table">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[5rem]">Run</TableHead>
                  <TableHead className="w-[7rem]">Kind</TableHead>
                  <TableHead className="w-[9rem]">Status</TableHead>
                  <TableHead className="w-[14rem]">Requested by</TableHead>
                  <TableHead className="w-[10rem]">When</TableHead>
                  <TableHead className="w-[7rem] text-right">Duration</TableHead>
                  <TableHead>Result</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(rows ?? WAITING_RUNS).map((run, index) => (
                  <TableRow key={run?.id ?? index}>
                    <TableCell data-label="Run" className="font-medium tnum">
                      {run ? (
                        <Link
                          to="/operations/runs/$id"
                          params={{ id: String(run.id) }}
                          className="underline-offset-4 hover:text-primary hover:underline"
                        >
                          #{run.id}
                        </Link>
                      ) : (
                        <SkeletonText width="short" />
                      )}
                    </TableCell>
                    <TableCell data-label="Kind">
                      {run ? (RUN_KIND[run.kind] ?? run.kind) : <SkeletonText width="medium" />}
                    </TableCell>
                    <TableCell data-label="Status">
                      {run ? (
                        <div className="flex items-center gap-1.5">
                          <RunStatus status={run.status} />
                          {run.report && ENDINGS.has(run.report.stage) === false ? (
                            <StageBadge stage={run.report.stage} />
                          ) : null}
                          {cancellable(run) ? <CancelButton run={run} /> : null}
                        </div>
                      ) : (
                        <Skeleton className="h-5 w-20 rounded-full" />
                      )}
                    </TableCell>
                    <TableCell data-label="Requested by" className="truncate text-muted-foreground">
                      {run ? (
                        <>
                          {run.requestedBy}
                          <span className="ml-1 text-xs">
                            ({SOURCE_LABEL[run.source] ?? run.source})
                          </span>
                        </>
                      ) : (
                        <SkeletonText width="long" />
                      )}
                    </TableCell>
                    <TableCell data-label="When"
                      className="text-muted-foreground"
                      title={run ? dateTime(run.requested) : undefined}
                    >
                      {run ? relative(run.requested) : <SkeletonText width="medium" />}
                    </TableCell>
                    <TableCell data-label="Duration" className="text-right tnum text-muted-foreground">
                      {run ? duration(runSeconds(run)) : <SkeletonText width="short" className="ml-auto" />}
                    </TableCell>
                    <TableCell data-label="Result">
                      {!run ? (
                        <SkeletonText width="long" />
                      ) : run.report?.stage === "NOTHING_TO_DO" ? (
                        <span className="flex items-center gap-1.5 text-muted-foreground">
                          <ProhibitInsetIcon className="size-3.5 shrink-0" aria-hidden />
                          nothing to do
                        </span>
                      ) : (
                        <div className="flex flex-col gap-0.5">
                          {summaryOf(run).map((part, index) => (
                            <span key={index} className="truncate">
                              {part}
                            </span>
                          ))}
                        </div>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </QueryState>
      </CardContent>
    </Card>
  )
}

/**
 * What a run would do, resolved on demand and never run (season-2-ops/128).
 *
 * <h2>The order is the point</h2>
 * A row that could not be asked sorts first, above the ones that merely have work in them. "the
 * source did not answer" and "nothing has changed" produce the same silence, and this page exists
 * to break that tie - `hasFailures` is drawn as a line of its own above the table for the same
 * reason, because a reader who scans a column of green ticks will not notice one grey badge in it.
 *
 * <h2>Why the age is in the header</h2>
 * The worker holds a reading for six hours and refreshes it behind whoever opened the page, so what
 * is drawn here is regularly the previous answer. Saying when it was taken is the difference
 * between a cache and a claim, and the refresh button next to it is the only way to shorten the
 * six hours from here (season-2-ops/142).
 */
const AVAILABLE_RANK: Record<string, number> = {
  UNRESOLVED: 0,
  MOUNT_MISSING: 0,
  OUTDATED: 1,
  MISSING: 2,
  UNSUPPORTED: 4,
  UP_TO_DATE: 5,
}

/** Six absent rows - about what a resolve of this stack answers with. */
const WAITING_CHANGES = Array.from({ length: 6 }, () => undefined)

/**
 * The rows this card draws: work, failures, and the artefacts with no build for this platform.
 *
 * Sorted by the same rank as before, so a failure is read before an ordinary update.
 */
function worthShowing(changes: AvailableChange[]): AvailableChange[] {
  return changes
    .filter((change) => change.work || change.failure || change.status === "UNSUPPORTED")
    .sort(
      (left, right) =>
        (AVAILABLE_RANK[left.status] ?? 3) - (AVAILABLE_RANK[right.status] ?? 3) ||
        (left.service ?? "").localeCompare(right.service ?? "", LOCALE) ||
        left.artifact.localeCompare(right.artifact, LOCALE),
    )
}

/**
 * One row's change, in as few characters as it can honestly be said.
 *
 * `1.5.3 → 1.6.0` when the two filenames come apart into a pair, and the filename in a monospace
 * face when they do not - drawn as a filename so it reads as the stopgap it is. Nothing installed
 * is `nothing → 1.6.0`, which is what a fresh volume looks like and is worth saying rather than
 * leaving blank.
 *
 * **An artefact with no pair at all gets a dash, not its note.** The one that has none on this
 * network is CoreProtect, and its note is a hundred-word paragraph about stable releases and
 * platforms - true, useful, and not something a table cell can hold. It is on the dash as a title,
 * and the badge beside it already carries the short version.
 */
function Jump({ change }: { change: AvailableChange }) {
  const jump = versionJump(change.installed, change.fileName, change.version)
  if (jump) return <Pair from={jump.from} to={jump.to} exact={jump.exact} />

  const wanted = change.version ?? change.fileName
  if (wanted && !change.installed) {
    return <Pair from="nothing" to={wanted} exact={change.version !== undefined} />
  }
  return (
    <span className="text-xs" title={change.note}>
      {change.installed ?? "–"}
    </span>
  )
}

/** The jump itself: two versions and an arrow, or two filenames when that is all there is. */
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
 * What a run would install, and nothing else (season-2-ops/142).
 *
 * <h2>Only the rows with something in them</h2>
 * This card used to list every artefact the resolve touched, thirty-odd lines of "up to date" with
 * the one interesting row somewhere inside. The owner asked on 2026-09-20 for only the services
 * that have an update to show at all. So the filter is {@code change.work}, which is the worker's
 * own opinion of what a run would act on, plus the two kinds of row that are not work and still
 * have to be read:
 *
 * <ul>
 *   <li><b>A failure</b> - a source that could not be asked. Hiding it would turn "this list is
 *       incomplete" into "there is nothing to do", which is the one confusion the whole resolve
 *       exists to prevent.</li>
 *   <li><b>{@code UNSUPPORTED}</b> - CoreProtect has no build for 26.2. That is the ticket's own
 *       named exception, and it earns its place for the same reason: it is the answer to "why is
 *       this plugin not on the list", asked once a month, and a row that disappears when it is
 *       nothing to worry about cannot answer it.</li>
 * </ul>
 *
 * <h2>The jump, not the bookkeeping</h2>
 * One column instead of two, `1.5.3 → 1.6.0`, derived by {@link versionJump} from the two
 * filenames rather than parsed out of either - see that file for why a guess is refused.
 */
function AvailableCard() {
  const available = useAvailable()
  const refresh = useRefreshAvailable()

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between gap-2 space-y-0">
        <CardTitle className="text-sm font-medium">Available</CardTitle>
        <div className="flex items-center gap-2">
          {available.data ? (
            <span className="text-xs text-muted-foreground" title={dateTime(available.data.checkedAt)}>
              Last checked {relative(available.data.checkedAt)}
            </span>
          ) : (
            <SkeletonText className="w-32 text-xs" />
          )}
          <Button
            type="button"
            variant="ghost"
            size="icon"
            // The cache holds six hours and this is the only way to shorten it. Disabled while it
            // runs rather than hidden: the wait is the point, and a button that vanishes mid-press
            // looks like it failed.
            aria-label="Ask the sources again"
            title="Ask the sources again. This takes a moment - it really asks them."
            disabled={refresh.isPending}
            onClick={() => refresh.mutate()}
          >
            <ArrowsClockwiseIcon aria-hidden className={refresh.isPending ? "animate-spin" : undefined} />
          </Button>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {refresh.error ? <Failure error={refresh.error} /> : null}
        <QueryState
          query={available}
          empty={{
            title: "Nothing to install",
            note: "Every source answered and everything the network runs is what the source says is newest.",
          }}
          isEmpty={(plan) => worthShowing(plan.changes).length === 0}
        >
          {(plan) => {
            const rows = plan ? worthShowing(plan.changes) : WAITING_CHANGES
            return (
              <>
                {plan?.hasFailures ? (
                  <p className="flex items-start gap-2 text-xs text-destructive">
                    <WarningIcon className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                    A source could not be asked, so this list is incomplete. Read it as "unknown",
                    not as "nothing to do".
                  </p>
                ) : null}

                <Table className="steward-table">
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-[10rem]">Service</TableHead>
                      <TableHead>Plugin</TableHead>
                      <TableHead className="w-[16rem]">Change</TableHead>
                      <TableHead className="w-[9rem] text-right">State</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((change, index) => (
                      <TableRow key={change ? `${change.service ?? "-"}/${change.artifact}` : index}>
                        <TableCell data-label="Service" className="font-medium">
                          {!change ? (
                            <SkeletonText width="medium" />
                          ) : change.service ? (
                            <Link
                              to="/services/$name"
                              params={{ name: change.service }}
                              className="underline-offset-4 hover:text-primary hover:underline"
                            >
                              {change.service}
                            </Link>
                          ) : (
                            <span className="text-muted-foreground">resource pack</span>
                          )}
                        </TableCell>
                        <TableCell data-label="Plugin">
                          {change ? change.artifact : <SkeletonText width="long" />}
                        </TableCell>
                        <TableCell data-label="Change" className="text-muted-foreground">
                          {change ? <Jump change={change} /> : <SkeletonText width="long" />}
                        </TableCell>
                        <TableCell data-label="State" className="text-right">
                          {change ? (
                            <AvailableBadge status={change.status} />
                          ) : (
                            <Skeleton className="ml-auto h-5 w-20 rounded-full" />
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>

                {plan && plan.unclaimed.length > 0 ? (
                  <p className="text-xs text-muted-foreground">
                    Claimed by nothing:{" "}
                    {plan.unclaimed.map((one) => `${one.service}/${one.fileName}`).join(", ")} -
                    installed by hand, or the same plugin under a name its publisher has changed. A
                    run never touches these.
                  </p>
                ) : null}
                {plan ? <Notes notes={plan.notes} /> : null}
              </>
            )
          }}
        </QueryState>
      </CardContent>
    </Card>
  )
}

// --- 2. /operations/plan ----------------------------------------------------------------------------

/**
 * What a run would change - now asked directly, rather than inferred from a run that is happening.
 *
 * **There is a dry run now, and it is the first card** (season-2-ops/128).
 * `GET /api/updates/available` asks Modrinth, GitHub and the Fill API what is newest and compares
 * it with the jars in the volumes - the same `Runs#resolve` a run begins with, which writes
 * nothing. No row in `update_request`, no container touched. Starting one is still a button, and
 * still a row.
 *
 * This page used to say in as many words that there was no dry run and that an invented preview
 * would be a lie. That was true of an invented one. This one is not invented: it is the worker's
 * own resolve, read without acting on it.
 *
 * The two older sources are kept below it, because they answer different questions: "Last resolved"
 * is a run that is happening right now, and the image comparison is about containers rather than
 * jars - neither is replaced by knowing what is newest. A run stands in `RESOLVING` or `PLANNED`
 * for seconds only, so "no resolved run" never meant there was nothing to do; now the card above it
 * says what there is.
 */
export function OperationsPlanPage() {
  const runs = useRuns(20)
  const planned = (runs.data ?? []).find(
    (run) => run.report?.stage === "PLANNED" || run.report?.stage === "RESOLVING",
  )

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Plan"
        actions={<AskButton kind="UPDATE" variant="default" />}
      />

      <AvailableCard />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Last resolved</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <QueryState
            query={runs}
            isEmpty={() => planned === undefined}
            empty={{
              title: "No run is in the plan right now",
              note: `Among the last 20 rows there is none whose report still stands at "Resolving" or "Planned". What an update would do can therefore only be read here from the image comparison below.`,
            }}
            // `rows`: this card is a run's own report, and there is no report to lay out until it
            // is known whether there is a run at all - the empty state is the usual answer.
            rows={4}
          >
            {() => (
            <>
              <div className="flex flex-wrap items-center gap-4">
                <Stat
                  label="Run"
                  value={
                    <Link
                      to="/operations/runs/$id"
                      params={{ id: String(planned!.id) }}
                      className="underline-offset-4 hover:text-primary hover:underline"
                    >
                      #{planned!.id}
                    </Link>
                  }
                  hint={
                    <span className="flex flex-col gap-0.5">
                      <span>{RUN_KIND[planned!.kind] ?? planned!.kind}</span>
                      <span>{planned!.requestedBy}</span>
                    </span>
                  }
                />
                <Stat
                  label="Stage"
                  value={<StageBadge stage={planned!.report!.stage} />}
                  hint={`resolved ${relative(planned!.requested)}`}
                />
              </div>
              <ReportLines lines={planned!.report!.services} />
              <Notes notes={planned!.report!.notes} />
            </>
          )}
          </QueryState>
        </CardContent>
      </Card>

      <DriftCard />
    </div>
  )
}

// --- 3. /operations/runs/$id ------------------------------------------------------------------------

/**
 * One run, drawn rather than dumped.
 *
 * `useRun` polls every two seconds while the run is unfinished and stops by itself, so the report
 * grows on screen without a socket or an interval of this page's own.
 *
 * `$id` may also be the word `latest`, because the sidebar links there. It is resolved through
 * `useRuns(1)` before any run is asked for - without that, `GET /api/updates/latest` would die on
 * the backend's `Long.parseLong`.
 */
export function OperationsRunPage() {
  const { id } = useParams({ from: "/operations/runs/$id" })
  const wantsNewest = id === "latest"
  const newest = useRuns(1, wantsNewest)
  const resolved = wantsNewest ? (newest.data?.[0]?.id?.toString() ?? "") : id
  const numeric = /^\d+$/.test(resolved)
  const run = useRun(resolved, numeric)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={numeric ? `Run #${resolved}` : "Run"}
        actions={
          <Button asChild variant="outline">
            <Link to="/operations">
              <ArrowRightIcon aria-hidden />
              All runs
            </Link>
          </Button>
        }
      />

      {/* `rows`: this is the lookup that turns the word "latest" into a number, and until it
          answers there is not even a run to draw the shape of. */}
      {wantsNewest && (newest.isPending || newest.error) ? (
        <QueryState query={newest} rows={4}>{() => null}</QueryState>
      ) : wantsNewest && !numeric ? (
        <Empty
          title="No run yet"
          note={`There is no row in update_request for "latest" to point at.`}
        />
      ) : !numeric ? (
        <Empty
          title="Not a run number"
          note={`"${id}" is neither a number nor the word "latest". A run is addressed by the number of its row.`}
        />
      ) : (
        // "Unknown run" was an empty state here; the worker answers a missing row with a 404, which
        // arrives as a failure with the number in it, and an answered query with no body is not a
        // state this route can produce.
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
            value={run?.requestedBy}
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
function Notes({ notes }: { notes: string[] }) {
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

// --- 4. /operations/backups/$id -------------------------------------------------------------------

function ArchiveKind({ name }: { name: string }) {
  const what = archived(name)
  if (what.kind === "database") {
    return (
      <span className="flex items-center gap-1.5">
        <DatabaseIcon className="size-3.5 shrink-0" aria-hidden />
        Database
      </span>
    )
  }
  if (what.kind === "volume") {
    return (
      <span className="flex items-center gap-1.5">
        <ArchiveIcon className="size-3.5 shrink-0" aria-hidden />
        <span className="truncate">{what.subject}</span>
      </span>
    )
  }
  return <span className="text-muted-foreground">unknown</span>
}

/**
 * One backup.
 *
 * **The run beside it is inferred, not recorded.** No report carries the file name: a backup
 * run's line carries the volume name and a sentence like "saved 512.9 MiB in 4s". So this page
 * looks for a run that was running when the file was written and has a line for exactly that
 * volume - and says on screen that this is what it did. A confident "belongs to run #23" would be
 * wrong on the day two runs sit close together.
 */
export function OperationsBackupPage() {
  const { id } = useParams({ from: "/operations/backups/$id" })
  const backups = useBackups()
  const wantsNewest = id === "latest"

  const backup = useMemo(() => {
    const all = backups.data ?? []
    if (!wantsNewest) return all.find((entry) => entry.name === id)
    // "latest" means the newest FINISHED one: a .partial is not a backup, and sending the sidebar's
    // link to a half-written file would be the one case where the word is actively misleading.
    // NO FALLBACK TO all[0]. It used to be there, and it undid the line above it: a directory
    // holding nothing but a backup that is still being written answered "latest" with that file,
    // labelled "incomplete", on a page whose whole job is to say which backup there is.
    return all.find((entry) => !entry.partial)
  }, [backups.data, id, wantsNewest])

  const runs = useRuns(50)
  const match = useMemo(() => matchingRun(backup, runs.data ?? []), [backup, runs.data])

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={backup ? backup.name : "Backup"}
        actions={
          <Button asChild variant="outline">
            <Link to="/operations/restore">
              <DownloadIcon aria-hidden />
              Restore
            </Link>
          </Button>
        }
      />

      {backups.isPending || backups.error ? (
        // `rows`: this page is one file out of the directory listing, and until the listing is
        // here there is no telling whether there is a file to draw at all.
        <QueryState query={backups} rows={4}>{() => null}</QueryState>
      ) : backup === undefined ? (
        <Empty
          title={wantsNewest ? "No backup present" : "Unknown file"}
          note={
            wantsNewest
              ? backups.data && backups.data.length > 0
                ? "There is no finished file in the backup directory yet - what is there is being written or was aborted (.partial)."
                : "There is no file in the backup directory."
              : `"${id}" is not in the backup directory. The cleanup run may have taken it away by now.`
          }
        />
      ) : (
        <>
          <Card>
            <CardContent className="flex flex-wrap items-start gap-6 pt-6">
              <div className="flex flex-col gap-2">
                <span className="text-xs font-medium text-muted-foreground">
                  State
                </span>
                {backup.partial ? (
                  <StatusBadge tone="warn">incomplete</StatusBadge>
                ) : (
                  <StatusBadge tone="ok">complete</StatusBadge>
                )}
                <span className="max-w-xs text-xs text-muted-foreground">
                  {backup.partial
                    ? "The file still carries the .partial suffix. Either it is being written, or the run died doing it - it cannot be restored."
                    : "The file was read back once after writing, and only then renamed."}
                </span>
              </div>

              <Separator orientation="vertical" className="h-14" />

              <Stat
                label="Taken"
                value={dateTime(backup.modified)}
                hint={relative(backup.modified)}
              />
              <Stat label="Age" value={since(backup.modified)} />
              <Stat
                label="Size"
                value={bytes(backup.bytes)}
                hint={`the worker calls it ${backup.human}`}
              />
              <div className="flex min-w-48 flex-col gap-1">
                <span className="text-xs font-medium text-muted-foreground">
                  Contents
                </span>
                <span className="text-sm">
                  <ArchiveKind name={backup.name} />
                </span>
                <span className="text-xs text-muted-foreground">
                  {archived(backup.name).kind === "unknown"
                    ? "The name follows neither of the two patterns the worker writes."
                    : "Read from the file name - nobody looked inside the archive."}
                </span>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-sm font-medium">The run behind it</CardTitle>
            </CardHeader>
            <CardContent>
              <QueryState
                query={runs}
                isEmpty={() => match === undefined}
                empty={{
                  title: "No matching run found",
                  note: "Among the last 50 rows there is none that matches in time and name. That does not mean there was none - it means it is no longer among the last 50.",
                }}
              >
                {(answer) => (
                  <div className="flex flex-wrap items-center gap-4">
                    <Stat
                      label="Run"
                      value={
                        answer && match ? (
                          <Link
                            to="/operations/runs/$id"
                            params={{ id: String(match.id) }}
                            className="underline-offset-4 hover:text-primary hover:underline"
                          >
                            #{match.id}
                          </Link>
                        ) : undefined
                      }
                      hint={
                        answer && match ? (
                          <span className="flex flex-col gap-0.5">
                            <span>{RUN_KIND[match.kind] ?? match.kind}</span>
                            <span>{match.requestedBy}</span>
                          </span>
                        ) : undefined
                      }
                    />
                    <Stat
                      label="Status"
                      value={answer && match ? <RunStatus status={match.status} /> : undefined}
                    />
                    <Stat
                      label="Ran"
                      value={answer && match ? dateTime(match.started) : undefined}
                      hint={answer && match ? duration(runSeconds(match)) : undefined}
                    />
                    {answer && match ? (
                      <Button asChild variant="outline" size="sm" className="ml-auto">
                        <Link to="/operations/runs/$id" params={{ id: String(match.id) }}>
                          <PlayIcon aria-hidden />
                          View the report
                        </Link>
                      </Button>
                    ) : null}
                  </div>
                )}
              </QueryState>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}

/** A minute of slack on both ends, because the file's mtime is when the tar was closed. */
const SLACK = 60 * 1000

function matchingRun(backup: Backup | undefined, runs: Run[]): Run | undefined {
  if (!backup) return undefined
  const at = parseInstant(backup.modified)
  const subject = archived(backup.name).subject
  if (at == null || subject == null) return undefined

  return runs.find((run) => {
    const started = parseInstant(run.started)
    if (started == null) return false
    const ended = parseInstant(run.finished) ?? new Date()
    if (at.getTime() < started.getTime() - SLACK) return false
    if (at.getTime() > ended.getTime() + SLACK) return false
    return (run.report?.services ?? []).some((line) => line.service === subject)
  })
}

// --- 5. /operations/restore ----------------------------------------------------------------

/**
 * This page restores nothing - and that is the decision, not a gap in it (concept §10a,
 * 2026-09-12).
 *
 * A restore is needed exactly when the stack is broken. `steward-ui` is part of the stack it would
 * be restoring: a button here works in every situation except the one it would exist for. A path
 * that fails in the emergency is not a path. So the page builds the finished command and a person
 * runs it on the host - which is where they would have to run it in the emergency anyway.
 */
export function OperationsRestorePage() {
  const backups = useBackups()
  const [chosen, setChosen] = useState<string>("")
  // What can actually be restored. A .partial is a file being written or a run that died in the
  // middle of one, and restore.sh will not take it.
  const restorable = useMemo(
    () => (backups.data ?? []).filter((backup) => !backup.partial),
    [backups.data],
  )
  const command = `sudo bash deploy/restore.sh ${chosen || "<archive>"}`

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Restore"
      />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Choose an archive</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {backups.isPending || backups.error ? (
            // `rows`: the control below is one select, and a skeleton select that becomes an empty
            // state on most days would be a control offered and then withdrawn.
            <QueryState query={backups} rows={3}>{() => null}</QueryState>
          ) : restorable.length === 0 ? (
            // The empty state is about what can be RESTORED, not about what lies there. A
            // directory holding three .partial files used to draw a selector with three entries,
            // every one of them disabled - a control that cannot be used and does not say why.
            <Empty
              title="No archive on the disk"
              note={
                (backups.data ?? []).length > 0
                  ? "Every file in the backup directory still carries the .partial suffix: it is being written, or the run died doing it. None of them can be restored."
                  : "There is no file in the backup directory that could be restored."
              }
            />
          ) : (
            <div className="flex max-w-xl flex-col gap-1.5">
              <Label htmlFor="restore-archive">Backup</Label>
              <Select value={chosen} onValueChange={setChosen}>
                <SelectTrigger id="restore-archive" className="w-full">
                  <SelectValue placeholder="Choose an archive…" />
                </SelectTrigger>
                <SelectContent>
                  {restorable.map((backup) => (
                    <SelectItem key={backup.name} value={backup.name}>
                      {backup.name} ({bytes(backup.bytes)}, {relative(backup.modified)})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}

          <div className="flex flex-col gap-2">
            <span className="text-xs font-medium text-muted-foreground">
              Command
            </span>
            <div className="flex items-center gap-2">
              <code className="min-w-0 flex-1 overflow-x-auto rounded-md border border-border bg-[#0a0a0a] px-3 py-2 font-mono text-xs whitespace-pre">
                {command}
              </code>
              <CopyButton text={command} disabled={!chosen} />
            </div>
            {/* TWO SENTENCES STAY HERE, and they are different in kind (2026-09-14). The
                first is why there is no button, which is the ticket's named exception; the second
                names a real danger, which is the fourth case that exception allows for. */}
            <p className="max-w-prose text-xs text-muted-foreground">
              Steward does not run it: it is a container in the stack it would be restoring.
            </p>
            <p className="max-w-prose text-xs text-warning">
              A volume is overwritten, not added to - everything made since the backup is gone.
            </p>
          </div>
        </CardContent>
      </Card>

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
function CopyButton({ text, disabled }: { text: string; disabled?: boolean }) {
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
