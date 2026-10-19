import { useMemo, useState } from "react"
import { Link, useNavigate, useParams } from "@tanstack/react-router"
import { DownloadIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import { toast } from "sonner"

import type { Backup, Run } from "@/lib/api"
import { archived } from "@/lib/backup-name"
import { bytes, count, dateTime, parseInstant, relative } from "@/lib/format"
import { useBackups, useRestore, useRuns, useSchedule } from "@/lib/queries"
import { span, t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Actor } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Stat } from "@/components/steward/stat"
import { RunStatus, runKind } from "@/components/steward/status"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { AskButton } from "@/pages/operations"
import { DataTable } from "@/components/steward/data-table"
import { entryAt, useStewardConfig } from "@/components/steward/group-form"
import { ScheduleDialog } from "@/pages/backup-dialogs"

/**
 * Everything about the backups. A secret is never drawn. A backup and a restore are runs like every other.
 */
export function BackupsPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={t("steward.backups.title")}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <ScheduleDialog />
            <RestoreDialog />
            <AskButton kind="BACKUP" variant="default" label={t("steward.backups.back-up-now")} size="sm" />
          </div>
        }
      />

      <Summary />

      <Runs />
    </div>
  )
}

/**
 * Three numbers, about two to a row on a phone. Nothing queries the Storage Box yet, so "Storage available" says so.
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
        label={t("steward.backups.latest")}
        value={newest ? relative(newest.modified) : backups.data ? t("steward.backups.none") : "\u2013"}
        tone={backups.data && !newest ? "down" : undefined}
        hint={newest ? newest.human : backups.data ? t("steward.backups.none-finished") : "\u2013"}
      />
      {/* Neutral and not warn: "not tracked" is a fact about the feature. */}
      <Stat label={t("steward.backups.storage")} value={t("steward.backups.not-tracked")} />
      <Stat
        label={t("steward.backups.next")}
        value={schedule.data?.nextBackupAt ? relative(schedule.data.nextBackupAt) : "\u2013"}
        hint={
          schedule.data?.nextBackupAt
            ? `${at ?? schedule.data.backupAt} ${schedule.data.zone}`
            : schedule.isPending
              ? "\u2013"
              : t("steward.backups.no-clock")
        }
      />
    </div>
  )
}

function backupRuns(runs: Run[] | undefined): Run[] {
  return (runs ?? []).filter((run) => run.kind === "BACKUP")
}

function saved(run: Run): { saved: number; total: number } {
  const lines = run.report?.services ?? []
  return { saved: lines.filter((line) => line.state === "SAVED").length, total: lines.length }
}

function ran(run: Run): string {
  const started = parseInstant(run.started)
  const finished = parseInstant(run.finished)
  if (!started || !finished) return "\u2013"
  return span((finished.getTime() - started.getTime()) / 1000)
}

function Runs() {
  const navigate = useNavigate()
  const runs = useRuns(40)
  const rows = backupRuns(runs.data).slice(0, 8)

  return (
    <Panel title={t("steward.backups.runs")}>
      <QueryState query={runs} isEmpty={() => rows.length === 0} empty={{ title: t("steward.backups.no-run") }}>
        {(answer) => (
          <DataTable
            rows={answer ? rows : undefined}
            waiting={4}
            rowKey={(run) => run.id}
            onRowClick={(run) => navigate({ to: "/operations/backups/$id", params: { id: String(run.id) } })}
            columns={[
              {
                head: t("steward.backups.run"),
                phone: { line: 1, side: "start" },
                className: "font-medium tnum",
                waiting: <SkeletonText width="short" />,
                render: (run) => (
                  <Link
                    to="/operations/backups/$id"
                    params={{ id: String(run.id) }}
                    className="underline-offset-4 tap-target hover:text-primary hover:underline"
                    onClick={(event) => event.stopPropagation()}
                  >
                    #{run.id}
                  </Link>
                ),
              },
              {
                head: t("steward.backups.when"),
                phone: { line: 2, side: "start" },
                render: (run) => dateTime(run.started || run.requested),
              },
              {
                head: t("steward.backups.status"),
                phone: { line: 1, side: "end" },
                waiting: <Skeleton className="h-5 w-20 rounded-full" />,
                render: (run) => <RunStatus status={run.status} />,
              },
              {
                head: t("steward.backups.archives"),
                end: true,
                phone: { line: 3, side: "end", labelled: true },
                className: "tnum",
                render: (run) => {
                  const archives = saved(run)
                  return archives.total === 0 ? "\u2013" : count(archives.saved)
                },
              },
              {
                head: t("steward.backups.took"),
                end: true,
                phone: { line: 2, side: "end", labelled: true },
                className: "tnum",
                render: ran,
              },
              {
                head: t("steward.backups.initiated-by"),
                grow: true,
                phone: { line: 3, side: "start" },
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

/**
 * Asks for a restore of one finished archive, once what it replaces is typed back. The run backs up first, counts down and stops what the archive replaces.
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
          <TERM_ICON.restore />
          {t("steward.backups.restore")}
        </Button>
      }
      title={t("steward.backups.restore")}
      action={t("steward.backups.restore")}
      destructive
      disabled={!replaces || typed !== replaces}
      act={() =>
        restore
          .mutateAsync({ archive: chosen, confirm: typed })
          .then((run) => toast.success(t("steward.operations.entered", { kind: runKind("RESTORE"), run: run.id })))
      }
    >
      {backups.isPending || backups.error ? (
        <QueryState query={backups} rows={2}>
          {() => null}
        </QueryState>
      ) : restorable.length === 0 ? (
        <Empty
          title={t("steward.backups.nothing-to-restore")}
          note={
            (backups.data ?? []).length > 0 ? t("steward.backups.all-partial") : t("steward.backups.empty-directory")
          }
        />
      ) : (
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="restore-archive">{t("steward.backups.archive")}</Label>
            <Select
              value={chosen}
              onValueChange={(value) => {
                setChosen(value)
                setTyped("")
              }}
            >
              <SelectTrigger id="restore-archive" className="w-full">
                <SelectValue placeholder={t("steward.backups.choose-archive")} />
              </SelectTrigger>
              <SelectContent>
                {restorable.map((backup) => (
                  <SelectItem key={backup.name} value={backup.name}>
                    {backup.name} ({bytes(backup.bytes)}, {relative(backup.modified)}
                    {backup.inBackup ? "" : `, ${t("steward.backups.left")}`})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {replaces ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="restore-confirm">
                {t("steward.backups.type")} <span className="font-mono">{replaces}</span>
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
            {replaces === "nordtal" ? t("steward.backups.database-replaced") : t("steward.backups.volume-replaced")}
          </p>
        </div>
      )}
    </AskThenAct>
  )
}

/**
 * Returns the archives one run wrote: those modified within a minute of its start and finish. A run that cannot be dated matches nothing.
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

function holds(backup: Backup): string {
  const what = archived(backup.name)
  const subject =
    what.kind === "database" ? t("steward.backups.database") : (what.subject ?? t("steward.backups.unknown"))
  return t("steward.backups.holds", { subject, partial: backup.partial })
}

export function BackupRunDetailPage() {
  const { id } = useParams({ from: "/operations/backups/$id" })
  const runs = useRuns(80)
  const backups = useBackups()
  const run = runs.data?.find((candidate) => String(candidate.id) === id)
  const files = run ? archivesOf(run, backups.data ?? []) : []

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.backups.backup", { id })} />

      <QueryState query={runs} isEmpty={() => !run} empty={{ title: t("steward.backups.no-such-run") }}>
        {(answer) => (
          <>
            <div className="grid grid-cols-2 gap-x-4 gap-y-5 lg:grid-cols-4">
              <Stat label={t("steward.backups.status")} value={run ? <RunStatus status={run.status} /> : undefined} />
              <Stat
                label={t("steward.backups.when")}
                value={run ? dateTime(run.started || run.requested) : undefined}
              />
              <Stat label={t("steward.backups.took")} value={run ? ran(run) : undefined} />
              <Stat
                label={t("steward.backups.archives")}
                value={answer && backups.data ? count(files.length) : undefined}
              />
            </div>

            <Panel title={t("steward.backups.archives")}>
              {answer && backups.data && files.length === 0 ? (
                <Empty title={t("steward.backups.no-archive")} />
              ) : (
                <DataTable
                  rows={answer && backups.data ? files : undefined}
                  waiting={3}
                  rowKey={(backup) => backup.name}
                  columns={[
                    {
                      head: t("steward.backups.archive"),
                      grow: true,
                      phone: { line: 1, side: "wide" },
                      waiting: <SkeletonText className="text-xs" width="long" />,
                      render: (backup) => <code className="text-xs break-all">{backup.name}</code>,
                    },
                    {
                      head: t("steward.backups.holds-column"),
                      phone: { line: 2, side: "start" },
                      render: holds,
                    },
                    {
                      head: t("steward.backups.written"),
                      phone: { line: 3, side: "start" },
                      className: "text-muted-foreground",
                      render: (backup) => dateTime(backup.modified),
                    },
                    {
                      head: t("steward.backups.size"),
                      end: true,
                      phone: { line: 2, side: "end" },
                      className: "tnum",
                      render: (backup) => bytes(backup.bytes),
                    },
                    {
                      head: "",
                      phone: "action",
                      render: (backup) =>
                        backup.partial ? null : (
                          <a
                            href={`/api/backups/${encodeURIComponent(backup.name)}/download`}
                            download={backup.name}
                            aria-label={t("steward.backups.download-file", { file: backup.name })}
                            className="inline-flex text-muted-foreground tap-target hover:text-foreground"
                          >
                            <DownloadIcon className="size-4" />
                          </a>
                        ),
                    },
                  ]}
                />
              )}
            </Panel>
          </>
        )}
      </QueryState>
    </div>
  )
}
