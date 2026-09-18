import { useEffect, useMemo, useState } from "react"
import { Link } from "@tanstack/react-router"
import { toast } from "sonner"

import type { Backup, ConfigChanges, ConfigEntry, ParsedConfigDocument, Run } from "@/lib/api"
import { ApiError } from "@/lib/api"
import { archived } from "@/lib/backup-name"
import { bytes, count, dateTime, duration, parseInstant, relative } from "@/lib/format"
import { useBackups, useConfig, useConfigs, useRuns, useSaveConfig, useSchedule } from "@/lib/queries"
import { ScalarControl } from "@/components/steward/config-controls"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Stat } from "@/components/steward/stat"
import { RunStatus } from "@/components/steward/status"
import { Empty, Failure, Loading } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

/**
 * Everything about the backups, in one place (steward/95).
 *
 * <h2>Why it is a page and not four halves of other pages</h2>
 * Till, 2026-09-17: the backups were spread over Operations (the archive list and the runs),
 * Settings (the age threshold the light turns on) and the start page (one tile), and the one thing
 * none of them carried was where an offsite copy would go. A copy that only exists on the disk it
 * is a copy of is the one fact an operator has to be able to read in a second, so the target is on
 * this page and is typed here rather than in `setup.sh`.
 *
 * <h2>What is on it, and where each number comes from</h2>
 * The runs are `update_request` rows of kind `BACKUP` - the same table `/operations` lists for
 * every kind. The volumes are the newest of those runs' own report lines: the worker writes one
 * line per volume, so "which volume was saved and which was not" needs no second endpoint and
 * cannot drift from what the run actually did. The archives are the files in
 * `backup.output-root`. Retention and the nightly clock are read out of `steward.yml` itself, the
 * same document the remote target is edited in - one read, and no copy of a config value in a
 * second place.
 *
 * <h2>The two rules this page is held to</h2>
 * A secret is never drawn: {@link RemoteTarget} sends the two keys the way every other credential
 * in this interface is sent, as "set" or "not set", and typing a new one does not require having
 * seen the old one. And nothing here starts or restores anything - asking for a run lives on
 * Operations, where the countdown and the confirmation already stand, and a restore is the host's
 * own script on purpose (a way back that runs inside the stack is no way back at all).
 */
export function BackupsPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Backups" />

      <Summary />

      <Runs />

      <Volumes />

      <Archives />

      <RemoteTarget />
    </div>
  )
}

// --- the file this page reads and writes ---------------------------------------------------------

/**
 * `steward-worker/steward.yml`, looked up rather than spelled out.
 *
 * The id is the path out of `/api/config`, and that listing is what the backend actually found
 * under the mount - a constant here would be a path this page believes in and the worker may not
 * have. A deployment whose worker config is missing or unreadable gets no form rather than a form
 * that cannot save.
 */
function useWorkerConfig() {
  const configs = useConfigs()
  const file = configs.data?.find(
    (location) =>
      location.service === "steward-worker" && location.name.split("/").pop() === "steward.yml",
  )
  const document = useConfig(file?.path ?? "", Boolean(file))
  const parsed = document.data && !document.data.raw ? document.data : undefined
  return { file: file?.path, document: parsed, pending: configs.isPending || document.isPending }
}

/** One key of that file, by its dotted path - `undefined` when the file has no such key. */
function entryAt(document: ParsedConfigDocument | undefined, path: string): ConfigEntry | undefined {
  return document?.entries.find((entry) => entry.path === path)
}

// --- the numbers ----------------------------------------------------------------------------------

/**
 * Four numbers, two to a row on a phone.
 *
 * Mobile first, the standing rule since steward/64: the narrow grid is the layout and the wide row
 * is what falls out of it. "On disk" is the sum of the whole directory rather than of the newest
 * run, because what fills a disk is every archive being kept, not the last one written.
 */
function Summary() {
  const backups = useBackups()
  const schedule = useSchedule()
  const { document } = useWorkerConfig()

  const files = backups.data ?? []
  const finished = files.filter((backup) => !backup.partial)
  const newest = finished[0]
  const total = files.reduce((sum, backup) => sum + backup.bytes, 0)
  const keep = entryAt(document, "backup.keep")?.value
  const at = entryAt(document, "backup.at")?.value

  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-5 lg:grid-cols-4">
      <Stat
        label="Latest backup"
        value={newest ? relative(newest.modified) : backups.data ? "none" : "–"}
        tone={backups.data && !newest ? "down" : undefined}
        hint={newest ? newest.human : backups.data ? "no finished backup" : "–"}
      />
      <Stat
        label="On disk"
        value={backups.data ? bytes(total) : "–"}
        hint={backups.data ? `${count(files.length)} files` : "–"}
      />
      <Stat
        label="Keep"
        value={keep ?? "–"}
        hint="each volume, on this host"
      />
      <Stat
        label="Next"
        value={schedule.data?.nextBackupAt ? relative(schedule.data.nextBackupAt) : "–"}
        hint={
          schedule.data?.nextBackupAt
            ? `${at ?? schedule.data.backupAt} ${schedule.data.zone}`
            : schedule.isPending
              ? "–"
              : "no nightly clock"
        }
      />
    </div>
  )
}

// --- the runs -------------------------------------------------------------------------------------

/** The `BACKUP` rows of `update_request`, newest first. */
function backupRuns(runs: Run[] | undefined): Run[] {
  return (runs ?? []).filter((run) => run.kind === "BACKUP")
}

/** How many of a run's report lines were actually saved, and how many there were. */
function saved(run: Run): { saved: number; total: number } {
  const lines = run.report?.services ?? []
  return { saved: lines.filter((line) => line.state === "SAVED").length, total: lines.length }
}

function ran(run: Run): string {
  const started = parseInstant(run.started)
  const finished = parseInstant(run.finished)
  if (!started || !finished) return "–"
  return duration((finished.getTime() - started.getTime()) / 1000)
}

/**
 * The last backup runs, with the fraction rather than a single verdict.
 *
 * The fraction is the point (the wireframes' own argument, 2026-09-12): a run that saved nothing
 * and still stopped the network for a minute looks exactly like a successful one in a result badge
 * alone. `0 of 12` does not.
 */
function Runs() {
  const runs = useRuns(40)
  const rows = backupRuns(runs.data).slice(0, 8)

  return (
    <Panel title="Runs">
      {runs.isPending ? (
        <Loading rows={4} />
      ) : runs.error ? (
        <Failure error={runs.error} onRetry={runs.refetch} />
      ) : rows.length === 0 ? (
        <Empty title="No backup run yet" />
      ) : (
        <Table className="steward-table">
          <TableHeader>
            <TableRow>
              <TableHead className="w-[5rem]">Run</TableHead>
              <TableHead className="w-[12rem]">When</TableHead>
              <TableHead className="w-[9rem]">Status</TableHead>
              <TableHead className="w-[7rem] text-right">Volumes</TableHead>
              <TableHead className="w-[7rem] text-right">Took</TableHead>
              <TableHead>Requested by</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((run) => {
              const volumes = saved(run)
              return (
                <TableRow key={run.id}>
                  <TableCell data-label="Run" className="font-medium tnum">
                    <Link
                      to="/operations/runs/$id"
                      params={{ id: String(run.id) }}
                      className="underline-offset-4 hover:text-primary hover:underline"
                    >
                      #{run.id}
                    </Link>
                  </TableCell>
                  <TableCell data-label="When">{dateTime(run.started || run.requested)}</TableCell>
                  <TableCell data-label="Status">
                    <RunStatus status={run.status} />
                  </TableCell>
                  <TableCell data-label="Volumes" className="text-right tnum">
                    {volumes.total === 0 ? "–" : `${volumes.saved} of ${volumes.total}`}
                  </TableCell>
                  <TableCell data-label="Took" className="text-right tnum">
                    {ran(run)}
                  </TableCell>
                  <TableCell data-label="Requested by" className="text-muted-foreground">
                    {run.requestedBy || run.source}
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}
    </Panel>
  )
}

// --- one volume at a time -------------------------------------------------------------------------

/**
 * The newest run's own lines, one per volume.
 *
 * Not a table: on a phone this is the thing most likely to be read in a hurry, and a name with a
 * state after it is a list. A volume that was not saved is drawn in the destructive colour and
 * keeps the run's own `detail` beside it - the worker already wrote the reason down, so nothing
 * here has to guess at one.
 */
function Volumes() {
  const runs = useRuns(40)
  const newest = backupRuns(runs.data)[0]
  const lines = newest?.report?.services ?? []

  return (
    <Panel title="Volumes">
      {runs.isPending ? (
        <Loading rows={3} />
      ) : lines.length === 0 ? (
        <Empty title="No run has reported a volume yet" />
      ) : (
        <ul className="flex flex-col gap-1.5 text-sm">
          {lines.map((line) => (
            <li key={line.service} className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
              <span className="font-medium">{line.service}</span>
              <span className={line.state === "FAILED" ? "text-destructive" : "text-muted-foreground"}>
                {line.state.toLowerCase()}
              </span>
              {line.detail ? (
                <span className="min-w-0 text-xs text-muted-foreground">{line.detail}</span>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}

// --- what is in the directory ---------------------------------------------------------------------

/** The files themselves, so a restore can be planned against what is actually there. */
function Archives() {
  const backups = useBackups()
  const rows = backups.data ?? []

  return (
    <Panel title="Archives">
      {backups.isPending ? (
        <Loading rows={4} />
      ) : backups.error ? (
        <Failure error={backups.error} onRetry={backups.refetch} />
      ) : rows.length === 0 ? (
        <Empty title="Nothing in the backup directory" />
      ) : (
        <Table className="steward-table">
          <TableHeader>
            <TableRow>
              <TableHead>Archive</TableHead>
              <TableHead className="w-[9rem]">Holds</TableHead>
              <TableHead className="w-[12rem]">Written</TableHead>
              <TableHead className="w-[8rem] text-right">Size</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((backup) => (
              <TableRow key={backup.name}>
                <TableCell data-label="Archive">
                  <Link
                    to="/operations/backups/$id"
                    params={{ id: backup.name }}
                    className="underline-offset-4 hover:text-primary hover:underline"
                  >
                    <code className="text-xs">{backup.name}</code>
                  </Link>
                </TableCell>
                <TableCell data-label="Holds">{holds(backup)}</TableCell>
                <TableCell data-label="Written">{dateTime(backup.modified)}</TableCell>
                <TableCell data-label="Size" className="text-right tnum">
                  {bytes(backup.bytes)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Panel>
  )
}

/**
 * What one file is, out of its name - and `.partial` said plainly.
 *
 * A `.partial` is either a run in flight or one that was abandoned, and it cannot be restored
 * either way. The word is in the column rather than in a legend somewhere, because the column is
 * where somebody is looking when it matters.
 */
function holds(backup: Backup): string {
  const what = archived(backup.name)
  const subject = what.kind === "database" ? "database" : (what.subject ?? "unknown")
  return backup.partial ? `${subject}, partial` : subject
}

// --- the offsite target ---------------------------------------------------------------------------

/** The five keys of `backup.remote`, in the order the form draws them. */
const REMOTE_KEYS = [
  "backup.remote.endpoint",
  "backup.remote.bucket",
  "backup.remote.prefix",
  "backup.remote.access-key",
  "backup.remote.secret-key",
] as const

/**
 * Where a copy goes that is not on this disk (steward/95, and steward/08 hangs off it).
 *
 * <h2>It writes into `steward.yml`, and it is deliberately not a form of its own</h2>
 * The values are five keys of the worker's own config file, saved through the same `PUT
 * /api/config/…` every other setting in this interface goes through - revision and all, so two open
 * browsers get a 409 rather than a silent overwrite. Inventing an endpoint for these five would
 * have meant a second way to write a config file, and the second one is the one that forgets the
 * revision.
 *
 * <h2>The keys are never read back, which is what makes typing them here safe</h2>
 * `access-key` and `secret-key` are `@Secret` in `StewardSpec`, so the worker sends them as
 * `filled` and no value. {@link ScalarControl} draws exactly that: a password field that says
 * whether something is set. Nothing on this page can show a credential, because nothing on this
 * page ever receives one.
 *
 * <h2>An empty endpoint is a real answer</h2>
 * A deployment with no offsite copy is the normal state of this stack today, so the form's empty
 * state is not an error - it is the truth, and the tile above already says every archive is on this
 * host.
 */
function RemoteTarget() {
  const { file, document, pending } = useWorkerConfig()
  const save = useSaveConfig(file ?? "")
  const [draft, setDraft] = useState<Record<string, string>>({})

  // The answer to a save IS the file as it now reads, so a write empties the form's own state and
  // what is on screen afterwards is what was written rather than what this browser hoped for.
  useEffect(() => setDraft({}), [document])

  const entries = useMemo(
    () =>
      REMOTE_KEYS.map((path) => entryAt(document, path)).filter(
        (entry): entry is ConfigEntry => entry !== undefined,
      ),
    [document],
  )

  const changes: ConfigChanges = {}
  for (const entry of entries) {
    const typed = draft[entry.path]
    if (typed === undefined) continue
    // A secret has no value to compare against - it was never sent - so anything typed into one is
    // a change. Everything else is compared with what the file says.
    if (entry.secret ? typed !== "" : typed !== (entry.value ?? "")) changes[entry.path] = typed
  }
  const changed = Object.keys(changes).length

  if (pending) return <Loading rows={3} />
  if (!document || entries.length === 0) {
    return (
      <Panel title="Remote target">
        <Empty
          title="This worker's config has no backup.remote section"
          note="The file in the volume predates it. A worker that has started since the section was added writes it in."
        />
      </Panel>
    )
  }

  return (
    <Panel title="Remote target">
      <div className="flex flex-col gap-4">
        {entries.map((entry) => (
          <div key={entry.path} className="flex flex-col gap-1.5">
            <Label htmlFor={entry.path}>{entry.label || entry.key}</Label>
            <ScalarControl
              id={entry.path}
              entry={entry}
              value={draft[entry.path] ?? (entry.secret ? "" : (entry.value ?? ""))}
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
            disabled={changed === 0 || !document.writable || save.isPending}
            onClick={() =>
              save.mutate(
                { revision: document.revision, changes },
                {
                  onSuccess: () => toast.success("Remote target saved."),
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
          {changed > 0 ? (
            <span className="text-sm text-muted-foreground tnum">{changed} changed</span>
          ) : null}
        </div>
      </div>
    </Panel>
  )
}
