import { useMemo, useState } from "react"
import { Link, useNavigate, useParams } from "@tanstack/react-router"
import { DownloadIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import { toast } from "sonner"

import type { Backup, Run, RunArchive } from "@/lib/api"
import { archived } from "@/lib/backup-name"
import { bytes, count, dateTime, parseInstant, relative, NO_VALUE } from "@/lib/format"
import { useBackups, useRestore, useRuns, useSchedule } from "@/lib/queries"
import { choice, span, t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Actor } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Stat } from "@/components/steward/stat"
import { When } from "@/components/steward/when"
import { RunStatus, runKind } from "@/components/steward/status"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { AskButton, RunPage, savedCount } from "@/pages/operations"
import { DataTable } from "@/components/steward/data-table"
import { ScheduleDialog, entryAt, useStewardConfig } from "@/components/steward/group-form"

/** steward-agent's four `backup.retention` keys, in the order the schedule dialog draws them. */
const RETENTION_KEYS = [
  "backup.retention.daily",
  "backup.retention.weekly",
  "backup.retention.monthly",
  "backup.retention.collapse-after-days",
] as const

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
            <ScheduleDialog
              job="backup"
              empty={t("steward.backup-settings.no-at")}
              noDay={t("steward.backup-settings.no-night")}
              also={{
                service: "steward-agent",
                name: "runs",
                keys: RETENTION_KEYS,
                saved: t("steward.backup-settings.retention-saved"),
              }}
            />
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
        value={newest ? <When at={newest.modified} /> : backups.data ? t("steward.backups.none") : NO_VALUE}
        tone={backups.data && !newest ? "down" : undefined}
        hint={newest ? newest.human : backups.data ? t("steward.backups.none-finished") : NO_VALUE}
      />
      {/* Neutral and not warn: "not tracked" is a fact about the feature. */}
      <Stat label={t("steward.backups.storage")} value={t("steward.backups.not-tracked")} />
      <Stat
        label={t("steward.backups.next")}
        value={schedule.data?.nextBackupAt ? relative(schedule.data.nextBackupAt) : NO_VALUE}
        hint={
          schedule.data?.nextBackupAt
            ? `${at ?? schedule.data.backupAt} ${schedule.data.zone}`
            : schedule.isPending
              ? NO_VALUE
              : t("steward.backups.no-clock")
        }
      />
    </div>
  )
}

function backupRuns(runs: Run[] | undefined): Run[] {
  return (runs ?? []).filter((run) => run.kind === "BACKUP")
}

/** Returns the archives the run saved, from the catalogue and not from the disk. */
function savedBy(run: Run): RunArchive[] {
  return run.steps.flatMap((step) => step.saved)
}

function ran(run: Run): string {
  const started = parseInstant(run.started)
  const finished = parseInstant(run.finished)
  if (!started || !finished) return NO_VALUE
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
                render: (run) => (run.steps.length === 0 ? NO_VALUE : count(savedCount(run))),
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

/** An archive the run saved or pruned, with the file on the disk if it is still there. */
type RunFile = RunArchive & { onDisk?: Backup }

/** Returns the archives the run saved, then those it pruned, each found on the disk by its name. */
function filesOf(run: Run, backups: Backup[]): RunFile[] {
  const disk = new Map(backups.map((backup) => [backup.name, backup]))
  return [...savedBy(run), ...run.steps.flatMap((step) => step.pruned)].map((archive) => ({
    ...archive,
    onDisk: disk.get(archive.file),
  }))
}

/** Returns where the file is: when it was written, pruned with its rule, or missing from the disk. */
function whereabouts(file: RunFile): string {
  if (file.onDisk) return dateTime(file.onDisk.modified)
  if (file.rule) return t("steward.backups.pruned", { rule: choice(file.rule) })
  return t("steward.backups.missing")
}

function holds(file: RunFile): string {
  const what = archived(file.file)
  const subject =
    what.kind === "database" ? t("steward.backups.database") : (what.subject ?? t("steward.backups.unknown"))
  return t("steward.backups.holds", { subject, partial: file.onDisk?.partial ?? false })
}

/** The page of one backup run: the update run's page with the archives the run saved and pruned. */
export function BackupRunDetailPage() {
  const { id } = useParams({ from: "/operations/backups/$id" })
  return (
    <RunPage
      id={id}
      title={t("steward.backups.backup", { id })}
      list={{ to: "/operations/backups", label: t("steward.backups.all-backups") }}
    >
      {(run) => <Archives run={run} />}
    </RunPage>
  )
}

function Archives({ run }: { run?: Run }) {
  const backups = useBackups()
  const files = run ? filesOf(run, backups.data ?? []) : []

  return (
    <Panel title={t("steward.backups.archives")}>
      {backups.error ? (
        <QueryState query={backups}>{() => null}</QueryState>
      ) : run && backups.data && files.length === 0 ? (
        <Empty title={t(savedCount(run) > 0 ? "steward.backups.not-catalogued" : "steward.backups.no-archive")} />
      ) : (
        <DataTable
          rows={run && backups.data ? files : undefined}
          waiting={3}
          rowKey={(file) => `${file.file}-${file.rule ?? ""}`}
          columns={[
            {
              head: t("steward.backups.archive"),
              grow: true,
              phone: { line: 1, side: "wide" },
              waiting: <SkeletonText className="text-xs" width="long" />,
              render: (file) => <code className="text-xs break-all">{file.file}</code>,
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
              render: (file) =>
                file.onDisk ? whereabouts(file) : <span className="text-warning">{whereabouts(file)}</span>,
            },
            {
              head: t("steward.backups.size"),
              end: true,
              phone: { line: 2, side: "end" },
              className: "tnum",
              render: (file) => bytes(file.bytes),
            },
            {
              head: "",
              phone: "action",
              render: (file) =>
                !file.onDisk || file.onDisk.partial ? null : (
                  <a
                    href={`/api/backups/${encodeURIComponent(file.file)}/download`}
                    download={file.file}
                    aria-label={t("steward.backups.download-file", { file: file.file })}
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
  )
}
