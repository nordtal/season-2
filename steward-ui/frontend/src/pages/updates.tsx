import { useState } from "react"
import { Link, useNavigate } from "@tanstack/react-router"
import { ArrowRightIcon, ArrowsClockwiseIcon, ClockIcon, ProhibitInsetIcon, WarningIcon } from "@phosphor-icons/react"
import { cn } from "cn"
import { toast } from "sonner"

import type { AvailableChange, ConfigChanges, Run } from "@/lib/api"
import { ApiError } from "@/lib/api"
import { LOCALE, count, dateTime, relative } from "@/lib/format"
import { versionJump } from "@/lib/version-jump"
import { useAvailable, useRefreshAvailable, useRuns, useSaveConfig, useSchedule } from "@/lib/queries"
import { ScalarControl } from "@/components/steward/config-controls"
import { Actor } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Stat } from "@/components/steward/stat"
import { AvailableBadge, RUN_KIND, RunStatus } from "@/components/steward/status"
import { Empty, Failure, Loading, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
  ResponsiveDialogTrigger,
} from "@/components/ui/responsive-dialog"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { AskButton, CancelButton, ENDINGS, Notes, StageBadge, cancellable, summaryOf } from "@/pages/operations"
import { DayPicker, chosenDays, entryAt, useConfigDraft, useWorkerConfig } from "@/pages/backups"

/**
 * The updates page: a header, three numbers and the lists, built like Backups.
 *
 * `update.at`, empty by default, schedules the same `update_request` row the button writes.
 */
export function UpdatesPage() {
  const refresh = useRefreshAvailable()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Updates"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <ScheduleDialog />
            <Button
              type="button"
              variant="outline"
              size="sm"
              /** The worker holds a reading for six hours; disabled rather than hidden while it asks. */
              title="Ask the sources again. This takes a moment - it really asks them."
              disabled={refresh.isPending}
              onClick={() =>
                refresh.mutate(undefined, {
                  onError: (failure) => toast.error("Could not check again", { description: failure.message }),
                })
              }
            >
              <ArrowsClockwiseIcon aria-hidden className={refresh.isPending ? "animate-spin" : undefined} />
              Check again
            </Button>
            <AskButton kind="RESTART" label="Restart everything" size="sm" />
            <AskButton kind="UPDATE" variant="default" label="Update everything" size="sm" />
          </div>
        }
      />

      <Summary />

      <Available />

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
        label="Available"
        value={waiting === undefined ? undefined : count(waiting)}
        tone={available.data?.hasFailures ? "warn" : undefined}
        hint={available.data?.hasFailures ? "a source did not answer" : undefined}
      />
      <Stat
        label="Checked"
        value={available.data ? relative(available.data.checkedAt) : undefined}
        hint={available.data ? dateTime(available.data.checkedAt) : undefined}
      />
      <Stat
        label="Next"
        value={
          schedule.data?.nextUpdateAt
            ? relative(schedule.data.nextUpdateAt)
            : schedule.data
              ? "not scheduled"
              : undefined
        }
        hint={schedule.data?.nextUpdateAt ? `${schedule.data.updateAt} ${schedule.data.zone}` : undefined}
      />
    </div>
  )
}

/** Rank of each resolve outcome: a source that could not be asked sorts above ordinary work. */
const AVAILABLE_RANK: Record<string, number> = {
  UNRESOLVED: 0,
  MOUNT_MISSING: 0,
  OUTDATED: 1,
  MISSING: 2,
  UNSUPPORTED: 4,
  UP_TO_DATE: 5,
}

/** Six absent rows, about what a resolve of this stack answers with. */
const WAITING_CHANGES = Array.from({ length: 6 }, () => undefined)

/** The rows this card draws: work, failures, and artefacts with no build for this platform, failures first. */
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
 * One row's change in as few characters as possible, or `nothing → 1.6.0` for a fresh volume.
 *
 * An artefact with no pair gets a dash, with its long note as the title.
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
      {change.installed ?? "\u2013"}
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
 * What a run would install: rows with work, failures, and `UNSUPPORTED` artefacts.
 *
 * Hiding a failure would read as nothing to do; `UNSUPPORTED` answers why a plugin is missing.
 */
function Available() {
  const available = useAvailable()
  const refresh = useRefreshAvailable()

  return (
    <Panel title="Available">
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
                  A source could not be asked, so this list is incomplete.
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
                  Claimed by nothing: {plan.unclaimed.map((one) => `${one.service}/${one.fileName}`).join(", ")}
                </p>
              ) : null}
              {plan ? <Notes notes={plan.notes} /> : null}
            </>
          )
        }}
      </QueryState>
    </Panel>
  )
}

/** Every row of `update_request` that is not a backup's and not a single service's Down or Start. */
export function updateRuns(runs: Run[] | undefined): Run[] {
  return (runs ?? []).filter((run) => run.kind === "UPDATE" || run.kind === "RESTART")
}

/** Four absent runs; the panel shows eight at most. */
const WAITING_UPDATE_RUNS = Array.from({ length: 4 }, () => undefined)

function Runs() {
  const navigate = useNavigate()
  const runs = useRuns(40)
  const rows = updateRuns(runs.data).slice(0, 8)

  return (
    <Panel title="Runs">
      <QueryState
        query={runs}
        isEmpty={() => rows.length === 0}
        empty={{
          title: "No update run yet",
          note: "The schedule and the Update everything button both land here once one has run.",
        }}
      >
        {(answer) => (
          <Table className="steward-table">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[5rem]">Run</TableHead>
                <TableHead className="w-[12rem]">When</TableHead>
                <TableHead className="w-[7rem]">Kind</TableHead>
                <TableHead className="w-[12rem]">Status</TableHead>
                <TableHead>Result</TableHead>
                <TableHead className="w-[12rem]">Initiated by</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(answer ? rows : WAITING_UPDATE_RUNS).map((run, index) => (
                <TableRow
                  key={run?.id ?? index}
                  className={run ? "cursor-pointer" : undefined}
                  onClick={() => run && navigate({ to: "/operations/updates/$id", params: { id: String(run.id) } })}
                >
                  <TableCell data-label="Run" className="font-medium tnum">
                    {run ? (
                      <Link
                        to="/operations/updates/$id"
                        params={{ id: String(run.id) }}
                        className="underline-offset-4 hover:text-primary hover:underline"
                        onClick={(event) => event.stopPropagation()}
                      >
                        #{run.id}
                      </Link>
                    ) : (
                      <SkeletonText width="short" />
                    )}
                  </TableCell>
                  <TableCell data-label="When">
                    {run ? dateTime(run.started || run.requested) : <SkeletonText width="long" />}
                  </TableCell>
                  <TableCell data-label="Kind">
                    {run ? (RUN_KIND[run.kind] ?? run.kind) : <SkeletonText width="medium" />}
                  </TableCell>
                  <TableCell data-label="Status">
                    {run ? (
                      <div className="flex items-center gap-1.5" onClick={(event) => event.stopPropagation()}>
                        <RunStatus status={run.status} />
                        {run.report && !ENDINGS.has(run.report.stage) ? <StageBadge stage={run.report.stage} /> : null}
                        {cancellable(run) ? <CancelButton run={run} /> : null}
                      </div>
                    ) : (
                      <Skeleton className="h-5 w-20 rounded-full" />
                    )}
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
                        {summaryOf(run).map((part, partIndex) => (
                          <span key={partIndex} className="truncate">
                            {part}
                          </span>
                        ))}
                      </div>
                    )}
                  </TableCell>
                  <TableCell data-label="Initiated by" className="text-muted-foreground">
                    {run ? <Actor kind={run.actorKind} id={run.actorId} /> : <SkeletonText width="medium" />}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </QueryState>
    </Panel>
  )
}

/** `update.at`, the one scalar of the update schedule. */
const SCHEDULE_KEYS = ["update.at"] as const

/** The `update.days` key, a list kept apart from the scalar draft as on Backups. */
const DAYS_KEY = "update.days"

/**
 * When an update runs on its own; an empty time, the default, is no schedule.
 *
 * The worker re-reads its file after a save, so no restart is needed.
 */
function ScheduleDialog() {
  const { file, document, pending } = useWorkerConfig()
  const save = useSaveConfig(file ?? "")
  const { entries, draft, setDraft, changes, changed } = useConfigDraft(document, SCHEDULE_KEYS)

  const [pickedDays, setPickedDays] = useState<string[] | undefined>(undefined)
  const [lastDocument, setLastDocument] = useState(document)
  if (lastDocument !== document) {
    setLastDocument(document)
    setPickedDays(undefined)
  }

  const daysEntry = entryAt(document, DAYS_KEY)
  const fileDays = chosenDays(daysEntry?.items)
  const days = pickedDays ?? fileDays
  const daysChanged = pickedDays !== undefined && pickedDays.join() !== fileDays.join()
  const allChanges: ConfigChanges = daysChanged ? { ...changes, [DAYS_KEY]: days } : changes
  const allChanged = changed + (daysChanged ? 1 : 0)
  const at = draft["update.at"] ?? entryAt(document, "update.at")?.value ?? ""

  return (
    <ResponsiveDialog>
      <ResponsiveDialogTrigger asChild>
        <Button variant="outline" size="sm">
          <ClockIcon />
          Schedule
        </Button>
      </ResponsiveDialogTrigger>
      <ResponsiveDialogContent>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Schedule</ResponsiveDialogTitle>
          <ResponsiveDialogDescription>When an update runs on its own. Empty is never.</ResponsiveDialogDescription>
        </ResponsiveDialogHeader>

        {pending ? (
          <Loading rows={3} />
        ) : !document || entries.length === 0 ? (
          <Empty
            title="This worker's config has no update section"
            note="The file in the volume predates it. A worker that has started since the section was added writes it in."
          />
        ) : (
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <Label>Days</Label>
              <DayPicker
                days={days}
                disabled={!daysEntry || !document.writable || save.isPending}
                onChange={setPickedDays}
              />
              {at.trim() !== "" && days.length === 0 ? (
                <p className="text-xs text-destructive">No day is picked, so no update runs.</p>
              ) : null}
            </div>

            {entries.map((entry) => (
              <div key={entry.path} className="flex flex-col gap-1.5">
                <Label htmlFor={entry.path}>{entry.label || entry.key}</Label>
                <ScalarControl
                  id={entry.path}
                  entry={entry}
                  value={draft[entry.path] ?? entry.value ?? ""}
                  edited={draft[entry.path] !== undefined}
                  disabled={!document.writable || save.isPending}
                  roles={undefined}
                  channels={undefined}
                  onChange={(value) => setDraft((was) => ({ ...was, [entry.path]: value }))}
                />
              </div>
            ))}

            <div className="flex items-center gap-3">
              <Button
                disabled={allChanged === 0 || !document.writable || save.isPending}
                onClick={() =>
                  save.mutate(
                    { revision: document.revision, changes: allChanges },
                    {
                      onSuccess: () => toast.success("Schedule saved."),
                      onError: (failure) =>
                        toast.error(
                          failure instanceof ApiError && failure.status === 409
                            ? "The file changed while this was open. It has been read again."
                            : String(failure),
                        ),
                    },
                  )
                }
              >
                Save
              </Button>
              {allChanged > 0 ? <span className="text-sm text-muted-foreground tnum">{allChanged} changed</span> : null}
            </div>
          </div>
        )}
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
