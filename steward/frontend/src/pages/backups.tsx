import { useMemo, useState } from "react"
import { Link, useNavigate, useParams } from "@tanstack/react-router"
import { ArrowCounterClockwiseIcon, DownloadIcon } from "@phosphor-icons/react"
import { toast } from "sonner"

import type { Backup, Run } from "@/lib/api"
import { archived } from "@/lib/backup-name"
import { bytes, count, dateTime, duration, parseInstant, relative } from "@/lib/format"
import { useBackups, useRestore, useRuns, useSchedule } from "@/lib/queries"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Actor } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Stat } from "@/components/steward/stat"
import { RunStatus } from "@/components/steward/status"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { AskButton } from "@/pages/operations"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { entryAt, useStewardConfig } from "@/components/steward/group-form"
import { DestinationDialog, ScheduleDialog } from "@/pages/backup-dialogs"

/**
 * Everything about the backups: the numbers, the runs, the offsite target and the schedule.
 *
 * A secret is never drawn; a backup and a restore are runs like every other.
 */
export function BackupsPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Backups"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <DestinationDialog />
            <ScheduleDialog />
            <RestoreDialog />
            <AskButton kind="BACKUP" variant="default" label="Back up now" size="sm" />
          </div>
        }
      />

      <Summary />

      <Runs />
    </div>
  )
}

/**
 * Three numbers, roughly two to a row on a phone.
 *
 * Nothing queries the Storage Box yet, so "Storage available" says so instead of drawing a number.
 */
function Summary() {
  const backups = useBackups()
  const schedule = useSchedule()
  const { document } = useStewardConfig()
  const at = entryAt(document, "backup.at")?.value

  const files = backups.data ?? []
  const finished = files.filter((backup) => !backup.partial)
  const newest = finished[0]

  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-5 lg:grid-cols-3">
      <Stat
        label="Latest backup"
        value={newest ? relative(newest.modified) : backups.data ? "none" : "\u2013"}
        tone={backups.data && !newest ? "down" : undefined}
        hint={newest ? newest.human : backups.data ? "no finished backup" : "\u2013"}
      />
      {/* Neutral rather than warn, since "not tracked" is a fact about the feature, not an alarm. */}
      <Stat label="Storage available" value="not tracked" hint="steward does not query the Storage Box yet" />
      <Stat
        label="Next"
        value={schedule.data?.nextBackupAt ? relative(schedule.data.nextBackupAt) : "\u2013"}
        hint={
          schedule.data?.nextBackupAt
            ? `${at ?? schedule.data.backupAt} ${schedule.data.zone}`
            : schedule.isPending
              ? "\u2013"
              : "no nightly clock"
        }
      />
    </div>
  )
}

/** The `BACKUP` rows of steward's inbox, newest first. */
function backupRuns(runs: Run[] | undefined): Run[] {
  return (runs ?? []).filter((run) => run.kind === "BACKUP")
}

/** How many of a run's report lines were saved, out of how many. */
function saved(run: Run): { saved: number; total: number } {
  const lines = run.report?.services ?? []
  return { saved: lines.filter((line) => line.state === "SAVED").length, total: lines.length }
}

function ran(run: Run): string {
  const started = parseInstant(run.started)
  const finished = parseInstant(run.finished)
  if (!started || !finished) return "\u2013"
  return duration((finished.getTime() - started.getTime()) / 1000)
}

/** Four waiting runs; the panel shows eight at most. */
const WAITING_BACKUP_RUNS = Array.from({ length: 4 }, () => undefined)

/** The last backup runs, each row a link to its detail page. */
function Runs() {
  const navigate = useNavigate()
  const runs = useRuns(40)
  const rows = backupRuns(runs.data).slice(0, 8)

  return (
    <Panel title="Runs">
      <QueryState
        query={runs}
        isEmpty={() => rows.length === 0}
        empty={{
          title: "No backup run yet",
          note: "Steward's clock and the Back up now button both land here once one has run.",
        }}
      >
        {(answer) => (
          <Table className="steward-table">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[5rem]">Run</TableHead>
                <TableHead className="w-[12rem]">When</TableHead>
                <TableHead className="w-[9rem]">Status</TableHead>
                <TableHead className="w-[7rem] text-right">Archives</TableHead>
                <TableHead className="w-[7rem] text-right">Took</TableHead>
                <TableHead>Initiated by</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(answer ? rows : WAITING_BACKUP_RUNS).map((run, index) => {
                const archives = run ? saved(run) : undefined
                return (
                  <TableRow
                    key={run?.id ?? index}
                    className={run ? "cursor-pointer" : undefined}
                    onClick={() => run && navigate({ to: "/operations/backups/$id", params: { id: String(run.id) } })}
                  >
                    <TableCell data-label="Run" className="font-medium tnum">
                      {run ? (
                        <Link
                          to="/operations/backups/$id"
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
                    <TableCell data-label="Status">
                      {run ? <RunStatus status={run.status} /> : <Skeleton className="h-5 w-20 rounded-full" />}
                    </TableCell>
                    <TableCell data-label="Archives" className="text-right tnum">
                      {archives ? (
                        archives.total === 0 ? (
                          "\u2013"
                        ) : (
                          count(archives.saved)
                        )
                      ) : (
                        <SkeletonText width="short" className="ml-auto" />
                      )}
                    </TableCell>
                    <TableCell data-label="Took" className="text-right tnum">
                      {run ? ran(run) : <SkeletonText width="short" className="ml-auto" />}
                    </TableCell>
                    <TableCell data-label="Initiated by" className="text-muted-foreground">
                      {!run ? <SkeletonText width="medium" /> : <Actor kind={run.actorKind} id={run.actorId} />}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        )}
      </QueryState>
    </Panel>
  )
}

/**
 * Asks for a restore of one finished archive, once what it replaces is typed back.
 *
 * The run backs up first, counts down and stops what the archive replaces; `deploy/restore.sh` is for a host without steward-agent.
 */
function RestoreDialog() {
  const backups = useBackups()
  const restore = useRestore()
  const [open, setOpen] = useState(false)
  const [chosen, setChosen] = useState<string>("")
  const [typed, setTyped] = useState("")
  const restorable = useMemo(
    () => (backups.data ?? []).filter((backup) => !backup.partial && backup.restoresInto),
    [backups.data],
  )
  const replaces = restorable.find((backup) => backup.name === chosen)?.restoresInto ?? ""

  return (
    <AskThenAct
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        setChosen("")
        setTyped("")
      }}
      trigger={
        <Button variant="outline" size="sm">
          <ArrowCounterClockwiseIcon />
          Restore
        </Button>
      }
      title="Restore"
      description="A run: a backup first, then a countdown."
      action="Restore"
      destructive
      disabled={!replaces || typed !== replaces}
      act={() =>
        restore
          .mutateAsync({ archive: chosen, confirm: typed })
          .then((run) => toast.success(`Restore entered as run #${run.id}`))
      }
    >
      {backups.isPending || backups.error ? (
        <QueryState query={backups} rows={2}>
          {() => null}
        </QueryState>
      ) : restorable.length === 0 ? (
        <Empty
          title="No archive to restore"
          note={
            (backups.data ?? []).length > 0
              ? "Every file still carries the .partial suffix."
              : "The backup directory is empty."
          }
        />
      ) : (
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="restore-archive">Archive</Label>
            <Select
              value={chosen}
              onValueChange={(value) => {
                setChosen(value)
                setTyped("")
              }}
            >
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
          {replaces ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="restore-confirm">
                Type <span className="font-mono">{replaces}</span>
              </Label>
              <Input
                id="restore-confirm"
                value={typed}
                autoComplete="off"
                spellCheck={false}
                onChange={(event) => setTyped(event.target.value)}
              />
            </div>
          ) : null}
          <p className="text-xs text-warning">
            {replaces === "nordtal"
              ? "The database is replaced - everything written since the backup is gone."
              : "A volume is overwritten, not added to - everything made since the backup is gone."}
          </p>
        </div>
      )}
    </AskThenAct>
  )
}

/**
 * The archives one run wrote: those modified within a minute of the run's start and finish.
 *
 * A run that cannot be dated matches nothing.
 */
function archivesOf(run: Run, backups: Backup[]): Backup[] {
  const started = parseInstant(run.started)?.getTime()
  const finished = parseInstant(run.finished)?.getTime()
  if (started === undefined || finished === undefined || started === null || finished === null) {
    return []
  }
  const from = started - 60_000
  const to = finished + 60_000
  return backups
    .filter((backup) => {
      const modified = parseInstant(backup.modified)?.getTime()
      return modified !== undefined && modified !== null && modified >= from && modified <= to
    })
    .toSorted((left, right) => left.name.localeCompare(right.name))
}

/** What one file is, from its name, with `.partial` said plainly. */
function holds(backup: Backup): string {
  const what = archived(backup.name)
  const subject = what.kind === "database" ? "database" : (what.subject ?? "unknown")
  return backup.partial ? `${subject}, partial` : subject
}

/** Three waiting archives, the usual number for one run. */
const WAITING_ARCHIVES = Array.from({ length: 3 }, () => undefined)

/** One backup run and the archives it wrote, each downloadable. */
export function BackupRunDetailPage() {
  const { id } = useParams({ from: "/operations/backups/$id" })
  const runs = useRuns(80)
  const backups = useBackups()
  const run = runs.data?.find((candidate) => String(candidate.id) === id)
  const files = run ? archivesOf(run, backups.data ?? []) : []

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={`Backup #${id}`} />

      <QueryState
        query={runs}
        isEmpty={() => !run}
        empty={{
          title: "No such run",
          note: "It may be older than this page's own window.",
        }}
      >
        {(answer) => (
          <>
            <div className="grid grid-cols-2 gap-x-4 gap-y-5 lg:grid-cols-4">
              {/* The four figures keep their places while the run is looked up. */}
              <Stat label="Status" value={run ? <RunStatus status={run.status} /> : undefined} />
              <Stat label="When" value={run ? dateTime(run.started || run.requested) : undefined} />
              <Stat label="Took" value={run ? ran(run) : undefined} />
              <Stat label="Archives" value={answer && backups.data ? count(files.length) : undefined} />
            </div>

            <Panel title="Archives">
              {answer && backups.data && files.length === 0 ? (
                <Empty title="No archive matched this run's own window" />
              ) : (
                <Table className="steward-table">
                  <TableHeader>
                    <TableRow>
                      <TableHead>Archive</TableHead>
                      <TableHead className="w-[9rem]">Holds</TableHead>
                      <TableHead className="w-[12rem]">Written</TableHead>
                      <TableHead className="w-[8rem] text-right">Size</TableHead>
                      <TableHead className="w-[3rem]" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {(answer && backups.data ? files : WAITING_ARCHIVES).map((backup, index) => (
                      <TableRow key={backup?.name ?? index}>
                        <TableCell data-label="Archive">
                          {backup ? (
                            <code className="text-xs">{backup.name}</code>
                          ) : (
                            <SkeletonText className="text-xs" width="long" />
                          )}
                        </TableCell>
                        <TableCell data-label="Holds">
                          {backup ? holds(backup) : <SkeletonText width="medium" />}
                        </TableCell>
                        <TableCell data-label="Written">
                          {backup ? dateTime(backup.modified) : <SkeletonText width="long" />}
                        </TableCell>
                        <TableCell data-label="Size" className="text-right tnum">
                          {backup ? bytes(backup.bytes) : <SkeletonText width="short" className="ml-auto" />}
                        </TableCell>
                        <TableCell data-label="Download">
                          {!backup || backup.partial ? null : (
                            <a
                              href={`/api/backups/${encodeURIComponent(backup.name)}/download`}
                              download={backup.name}
                              aria-label={`Download ${backup.name}`}
                              className="inline-flex text-muted-foreground hover:text-foreground"
                            >
                              <DownloadIcon className="size-4" />
                            </a>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </Panel>
          </>
        )}
      </QueryState>
    </div>
  )
}
