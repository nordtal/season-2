import { useMemo, useState } from "react"
import { Link, useParams } from "@tanstack/react-router"
import {
  Archive,
  ArrowRight,
  Check,
  CircleSlash,
  Copy,
  Database,
  Download,
  FileText,
  Play,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  TriangleAlert,
} from "lucide-react"
import { toast } from "sonner"

import type { Backup, ReportChange, ReportLine, Run, ServiceTable } from "@/lib/api"
import { bytes, count, dateTime, duration, parseInstant, relative, since } from "@/lib/format"
import { useAskForRun, useBackups, useRun, useRuns, useServices } from "@/lib/queries"
import { PageHeader } from "@/components/steward/page-header"
import { Stat } from "@/components/steward/stat"
import {
  DriftBadge,
  RUN_KIND,
  RunStatus,
  StatusBadge,
  type Tone,
} from "@/components/steward/status"
import { RecreateButton } from "@/components/steward/recreate"
import { Empty, Failure, Loading, QueryState } from "@/components/steward/query-state"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
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
 * Betrieb - the five pages about runs, images, backups and the way back (concept §10a).
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
  RESOLVING: "Ermittelt",
  PLANNED: "Geplant",
  COUNTDOWN: "Countdown",
  STOPPING: "Stoppt",
  BACKING_UP: "Sichert",
  INSTALLING: "Installiert",
  STARTING: "Startet",
  VERIFYING: "Prüft",
  DONE: "Fertig",
  NOTHING_TO_DO: "Nichts zu tun",
  FAILED: "Fehlgeschlagen",
  CANCELLED: "Abgebrochen",
}

/** The four stages a run stops at. `NOTHING_TO_DO` is one of them and is not a kind of „fertig". */
const ENDINGS = new Set(["DONE", "NOTHING_TO_DO", "FAILED", "CANCELLED"])

/**
 * `NOTHING_TO_DO` is grey, not green, and that is the whole point of this function.
 *
 * A run that found nothing to do stopped no server and installed nothing. Painting it in the same
 * colour as a finished update makes the two indistinguishable in a list - and the difference
 * between „es wurde aktualisiert" and „es gab nichts zu aktualisieren" is exactly what somebody
 * scanning this table is looking for.
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
  UNCHANGED: { label: "unverändert", tone: "idle" },
  PLANNED: { label: "wartet", tone: "idle" },
  STOPPED: { label: "gestoppt", tone: "warn" },
  INSTALLED: { label: "installiert", tone: "warn" },
  SAVED: { label: "gesichert", tone: "ok" },
  STARTING: { label: "startet", tone: "warn" },
  HEALTHY: { label: "gesund", tone: "ok" },
  FAILED: { label: "fehlgeschlagen", tone: "down" },
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
        <code className="text-xs">{change.artefact}</code> – kein Build für diese
        Minecraft-Version
      </span>
    )
  }
  return (
    <span className="flex items-center gap-1.5">
      <code className="text-xs">{change.artefact}</code>
      {change.from ? (
        <>
          <span className="text-muted-foreground tnum">{change.from}</span>
          <ArrowRight className="size-3 shrink-0 text-muted-foreground" aria-hidden />
        </>
      ) : (
        <span className="text-muted-foreground">neu:</span>
      )}
      <span className="tnum">{change.to}</span>
    </span>
  )
}

/**
 * Whether a change row is a file actually moving.
 *
 * Deliberately not `state === "MOVING"`. `UpdateReports` omits the field entirely when it is MOVING
 * and writes it only for UNSUPPORTED - measured against runs 22 and 24 in this host's database on
 * 2026-09-13 - while steward-ui parses that JSON into the record and re-writes it through Gson,
 * which puts the field back. „Anything that is not UNSUPPORTED" is true of both shapes; a literal
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
 * What a run did, in one line for the table.
 *
 * Counted out of the report rather than taken from a sentence, because there is no sentence: the
 * worker writes structure and every surface renders its own summary (`UpdateReport`'s own comment
 * says so).
 */
function summaryOf(run: Run): string {
  if (run.resultText) return "Bericht nicht lesbar"
  const report = run.report
  if (!report) return run.status === "PENDING" ? "noch nichts geschrieben" : "–"
  if (report.stage === "NOTHING_TO_DO") return "nichts zu tun"

  const parts: string[] = []
  const saved = report.services.filter((line) => line.state === "SAVED")
  if (saved.length > 0) parts.push(`${count(saved.length)} gesichert`)

  const moving = report.services.filter((line) => line.changes.some(isMoving))
  if (moving.length > 0) {
    const artefacts = moving.reduce(
      (sum, line) => sum + line.changes.filter(isMoving).length,
      0,
    )
    parts.push(
      `${count(moving.length)} ${moving.length === 1 ? "Dienst" : "Dienste"}, ` +
        `${count(artefacts)} ${artefacts === 1 ? "Artefakt" : "Artefakte"}`,
    )
  }

  const failed = report.services.filter((line) => line.state === "FAILED")
  if (failed.length > 0) parts.push(`${count(failed.length)} fehlgeschlagen`)

  if (parts.length > 0) return parts.join(" · ")
  return report.services.length === 0 ? "keine Zeile im Bericht" : "keine Änderung"
}

const SOURCE_LABEL: Record<string, string> = {
  DISCORD: "Discord",
  GAME: "im Spiel",
  CONSOLE: "Oberfläche/Konsole",
}

// --- asking for a run ----------------------------------------------------------------------------

type Kind = "UPDATE" | "BACKUP" | "RESTART"

/**
 * The hour „heute Nacht" means.
 *
 * 04:00 rather than a round midnight, and before the worker's own backup clock at 04:45
 * (`steward.yml#backup.at`) rather than on top of it: update and backup take the same lock, so two
 * runs at the same minute are one run waiting for the other with the network already down.
 */
const NIGHT_HOUR = 4

/** Seconds from now until the next {@link NIGHT_HOUR} o'clock, in the browser's own time zone. */
function untilTonight(now = new Date()): number {
  const target = new Date(now)
  target.setHours(NIGHT_HOUR, 0, 0, 0)
  if (target.getTime() <= now.getTime()) target.setDate(target.getDate() + 1)
  return Math.round((target.getTime() - now.getTime()) / 1000)
}

const ASKS: Record<
  Kind,
  { title: string; what: string; warning?: string; icon: typeof RefreshCw }
> = {
  UPDATE: {
    title: "Update eintragen",
    what:
      "Fragt jede Quelle nach der neuesten Version, stoppt die Dienste, bei denen sich etwas ändert, tauscht deren Jars und startet sie wieder. Ist nichts neu, wird nichts gestoppt – der Lauf endet dann bei „Nichts zu tun\".",
    icon: RefreshCw,
  },
  BACKUP: {
    title: "Sicherung eintragen",
    what:
      "Zieht zuerst den Datenbankabzug (dafür wird nichts gestoppt), stoppt dann smp, network-control und den Bot, packt jedes Volume und startet alles wieder.",
    warning: "Solange gepackt wird, ist das Netzwerk nicht erreichbar.",
    icon: Archive,
  },
  RESTART: {
    title: "Neustart eintragen",
    what: "Stoppt die Dienste des Netzwerks und startet sie wieder. Es wird nichts getauscht.",
    warning: "Ein Neustart wirft jeden Spieler vom SMP.",
    icon: RotateCcw,
  },
}

/**
 * One button, one confirmation, two timings.
 *
 * The dialog is not a formality: all three of these stop servers, and the middle one is the only
 * page in this interface that can empty the SMP. So it names what will happen before it happens,
 * and it offers „heute Nacht" beside „sofort" - which costs one number in the request body and is
 * the difference between an operator waiting up and an operator going to bed.
 */
function AskButton({ kind, variant = "outline" }: { kind: Kind; variant?: "default" | "outline" }) {
  const ask = useAskForRun()
  const spec = ASKS[kind]
  const Icon = spec.icon
  const delay = untilTonight()
  const tonight = new Date(Date.now() + delay * 1000)

  const submit = (delaySeconds?: number) => {
    ask.mutate(
      { kind, delaySeconds },
      {
        onSuccess: (run) => {
          toast.success(`${RUN_KIND[kind]} als Lauf #${run.id} eingetragen`, {
            description: delaySeconds
              ? `steward-worker holt die Zeile frühestens ${dateTime(run.notBefore)} ab.`
              : "steward-worker holt die Zeile beim nächsten Durchgang ab.",
          })
        },
        onError: (error) => {
          toast.error(`${RUN_KIND[kind]} wurde nicht eingetragen`, { description: String(error) })
        },
      },
    )
  }

  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button type="button" variant={variant} disabled={ask.isPending}>
          <Icon aria-hidden />
          {RUN_KIND[kind]}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{spec.title}</AlertDialogTitle>
          <AlertDialogDescription>{spec.what}</AlertDialogDescription>
        </AlertDialogHeader>

        <div className="flex flex-col gap-3 text-sm">
          {spec.warning ? (
            <p className="flex items-start gap-2 rounded-md border border-warning/30 bg-warning/8 px-3 py-2 text-warning">
              <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
              {spec.warning}
            </p>
          ) : null}
          <p className="text-muted-foreground">
            Dieser Knopf schreibt nur eine Zeile in <code className="text-xs">update_request</code>.
            steward-worker holt sie ab, sobald ihr Zeitpunkt erreicht ist, und lässt vor jedem Stopp
            einen Countdown laufen, den jeder Spieler sieht.
          </p>
          <p className="text-muted-foreground">
            Einen Abbruch-Knopf hat diese Oberfläche noch nicht – die API kennt bisher nur das
            Eintragen und das Lesen eines Laufs.
          </p>
          <p className="text-muted-foreground">
            „Heute Nacht" heißt <span className="text-foreground tnum">{dateTime(tonight)}</span>
            – kurz vor der eigenen Sicherungsuhr des Workers, damit sich beide nicht um dieselbe
            Sperre streiten.
          </p>
        </div>

        <AlertDialogFooter>
          <AlertDialogCancel>Abbrechen</AlertDialogCancel>
          <AlertDialogAction variant="outline" onClick={() => submit(delay)}>
            Heute Nacht
          </AlertDialogAction>
          <AlertDialogAction
            variant={kind === "RESTART" ? "destructive" : "default"}
            onClick={() => submit()}
          >
            Sofort
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

function AskBar() {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <AskButton kind="UPDATE" variant="default" />
      <AskButton kind="BACKUP" />
      <AskButton kind="RESTART" />
    </div>
  )
}

// --- the drift table, shared by /betrieb and /betrieb/plan ---------------------------------------

/** Whatever wants attention first: OUTDATED, then UNKNOWN, then UP_TO_DATE, then by name. */
const DRIFT_RANK: Record<string, number> = { OUTDATED: 0, UP_TO_DATE: 2 }

function DriftCard({ note }: { note?: string }) {
  const services = useServices()

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Images</CardTitle>
        <CardDescription>
          {note ??
            "Was die Registry hat, verglichen mit dem, was läuft. Der Vergleich ist ein zwischengespeichertes Ergebnis, keine Live-Abfrage."}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <QueryState
          query={services}
          rows={6}
          empty={{
            title: "Kein Container im Projekt",
            note: "steward-worker hat geantwortet, aber kein Container trägt das Compose-Projektlabel.",
          }}
          isEmpty={(table: ServiceTable) => table.services.length === 0}
        >
          {(table) => {
            const rows = [...table.services].sort(
              (left, right) =>
                (DRIFT_RANK[left.drift] ?? 1) - (DRIFT_RANK[right.drift] ?? 1) ||
                left.service.localeCompare(right.service, "de"),
            )
            return (
              <>
                <p className="text-xs text-muted-foreground">
                  {table.drift.checkedAt
                    ? `Registry zuletzt gefragt ${relative(table.drift.checkedAt)} (${dateTime(table.drift.checkedAt)}) – so alt ist dieser Vergleich, nicht die Zeile daneben.`
                    : "Die Registry wurde noch nicht gefragt; keine Zeile unten ist ein Vergleich."}
                </p>
                {table.drift.reached === false ? (
                  <p className="flex items-start gap-2 text-xs text-warning">
                    <TriangleAlert className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                    Die Registry war nicht erreichbar
                    {table.drift.reason ? ` (${table.drift.reason})` : ""}.
                    {table.drift.message ? ` ${table.drift.message}` : ""}
                  </p>
                ) : null}

                <Table className="steward-table">
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-[14rem]">Dienst</TableHead>
                      <TableHead>Image</TableHead>
                      <TableHead className="w-[8rem]">Vergleich</TableHead>
                      <TableHead className="w-[10rem] text-right">Container</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((service) => (
                      <TableRow key={service.service}>
                        <TableCell className="font-medium">
                          <Link
                            to="/dienste/$name"
                            params={{ name: service.service }}
                            className="underline-offset-4 hover:text-primary hover:underline"
                          >
                            {service.service}
                          </Link>
                        </TableCell>
                        <TableCell className="text-muted-foreground">
                          <code className="text-xs">{service.image}</code>
                        </TableCell>
                        <TableCell>
                          <DriftBadge drift={service.drift} />
                        </TableCell>
                        <TableCell className="text-right">
                          <RecreateButton service={service.service} />
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>

                {table.drift.unverifiable.length > 0 ? (
                  <p className="text-xs text-muted-foreground">
                    Ungeprüft: {table.drift.unverifiable.join(", ")} – ein hier gebautes Image trägt
                    keinen Registry-Digest und lässt sich deshalb mit nichts vergleichen. Das ist
                    nicht „aktuell".
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

// --- 1. /betrieb ---------------------------------------------------------------------------------

/**
 * The overview: runs, images and backups on one page.
 *
 * Three questions, in this order: what happened last, is anything behind, and is there a backup I
 * could actually use. The third one comes last on screen and first in consequence - it is what
 * decides whether acting on the other two is safe.
 */
export function BetriebPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Betrieb"
        note="Läufe, der Vergleich der Images gegen die Registry und die Sicherungen, die auf der Platte liegen."
        actions={<AskBar />}
      />

      <RunsCard />
      <DriftCard />
      <BackupsCard />
    </div>
  )
}

function RunsCard() {
  const runs = useRuns(20)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Läufe</CardTitle>
        <CardDescription>
          Die letzten 20 Zeilen aus <code className="text-xs">update_request</code> – jede von ihnen
          ein Auftrag, kein Aufruf.
        </CardDescription>
        <CardAction>
          <Button asChild variant="outline" size="sm">
            <Link to="/betrieb/plan">
              <FileText aria-hidden />
              Plan ansehen
            </Link>
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent>
        <QueryState
          query={runs}
          rows={8}
          empty={{
            title: "Noch kein Lauf",
            note: "In update_request steht keine Zeile – weder von dieser Oberfläche, noch aus Discord, noch von der Uhr des Workers.",
          }}
          isEmpty={(rows: Run[]) => rows.length === 0}
        >
          {(rows) => (
            <Table className="steward-table">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[5rem]">Lauf</TableHead>
                  <TableHead className="w-[7rem]">Art</TableHead>
                  <TableHead className="w-[9rem]">Status</TableHead>
                  <TableHead className="w-[14rem]">Angefordert von</TableHead>
                  <TableHead className="w-[10rem]">Wann</TableHead>
                  <TableHead className="w-[7rem] text-right">Dauer</TableHead>
                  <TableHead>Ergebnis</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((run) => (
                  <TableRow key={run.id}>
                    <TableCell className="font-medium tnum">
                      <Link
                        to="/betrieb/lauf/$id"
                        params={{ id: String(run.id) }}
                        className="underline-offset-4 hover:text-primary hover:underline"
                      >
                        #{run.id}
                      </Link>
                    </TableCell>
                    <TableCell>{RUN_KIND[run.kind] ?? run.kind}</TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1.5">
                        <RunStatus status={run.status} />
                        {run.report && ENDINGS.has(run.report.stage) === false ? (
                          <StageBadge stage={run.report.stage} />
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell className="truncate text-muted-foreground">
                      {run.requestedBy}
                      <span className="ml-1 text-xs">
                        ({SOURCE_LABEL[run.source] ?? run.source})
                      </span>
                    </TableCell>
                    <TableCell
                      className="text-muted-foreground"
                      title={dateTime(run.requested)}
                    >
                      {relative(run.requested)}
                    </TableCell>
                    <TableCell className="text-right tnum text-muted-foreground">
                      {duration(runSeconds(run))}
                    </TableCell>
                    <TableCell className="truncate">
                      {run.report?.stage === "NOTHING_TO_DO" ? (
                        <span className="flex items-center gap-1.5 text-muted-foreground">
                          <CircleSlash className="size-3.5 shrink-0" aria-hidden />
                          nichts zu tun
                        </span>
                      ) : (
                        summaryOf(run)
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

function BackupsCard() {
  const backups = useBackups()

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Sicherungen</CardTitle>
        <CardDescription>
          Was im Sicherungsverzeichnis liegt – gelesen von der Platte, nicht aus dem Bericht eines
          Laufs. Angefangene Dateien (<code className="text-xs">.partial</code>) stehen mit dabei:
          eine Liste, die sie verschweigt, sieht ordentlich aus und lügt.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <QueryState
          query={backups}
          rows={6}
          empty={{
            title: "Kein Archiv auf der Platte",
            note: "Im Sicherungsverzeichnis liegt keine Datei. Ein Lauf der Art „Sicherung\" legt die erste an.",
          }}
          isEmpty={(rows: Backup[]) => rows.length === 0}
        >
          {(rows) => (
            <>
              <Table className="steward-table">
                <TableHeader>
                  <TableRow>
                    <TableHead>Datei</TableHead>
                    <TableHead className="w-[9rem]">Inhalt</TableHead>
                    <TableHead className="w-[12rem]">Zeitpunkt</TableHead>
                    <TableHead className="w-[8rem]">Alter</TableHead>
                    <TableHead className="w-[8rem] text-right">Größe</TableHead>
                    <TableHead className="w-[9rem]">Zustand</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((backup) => (
                    <TableRow key={backup.name}>
                      <TableCell className="font-medium">
                        <Link
                          to="/betrieb/sicherung/$id"
                          params={{ id: backup.name }}
                          className="underline-offset-4 hover:text-primary hover:underline"
                        >
                          <code className="text-xs">{backup.name}</code>
                        </Link>
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        <ArchiveKind name={backup.name} />
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {dateTime(backup.modified)}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {since(backup.modified)}
                      </TableCell>
                      <TableCell className="text-right tnum">{bytes(backup.bytes)}</TableCell>
                      <TableCell>
                        {backup.partial ? (
                          <StatusBadge
                            tone="warn"
                            title="Entweder läuft diese Sicherung gerade, oder sie ist abgebrochen. Zurückspielen lässt sich eine .partial-Datei nicht."
                          >
                            unvollständig
                          </StatusBadge>
                        ) : (
                          <StatusBadge tone="ok">fertig</StatusBadge>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>

              <div className="flex flex-wrap items-center gap-4">
                <Stat
                  label="Fertige Archive"
                  value={count(rows.filter((backup) => !backup.partial).length)}
                  hint={`zusammen ${bytes(
                    rows
                      .filter((backup) => !backup.partial)
                      .reduce((sum, backup) => sum + backup.bytes, 0),
                  )}`}
                />
                <Separator orientation="vertical" className="h-10" />
                <p className="max-w-prose text-xs text-muted-foreground">
                  Alle Archive liegen auf derselben Platte wie das, wovon sie eine Kopie sind. Eine
                  Kopie außer Haus gibt es nicht.
                </p>
                <Button asChild variant="outline" size="sm" className="ml-auto">
                  <Link to="/betrieb/wiederherstellen">
                    <Download aria-hidden />
                    Zurückspielen
                  </Link>
                </Button>
              </div>
            </>
          )}
        </QueryState>
      </CardContent>
    </Card>
  )
}

// --- 2. /betrieb/plan ----------------------------------------------------------------------------

/**
 * What a run would change - out of the two sources that actually exist for it.
 *
 * **There is no dry run.** The API knows three ways to a run (`POST /api/updates` with UPDATE,
 * BACKUP or RESTART) and none that only calculates. What there is: the report of a run still
 * sitting in `RESOLVING` or `PLANNED` - exactly what a `REPORT` run used to leave behind - and the
 * image comparison. Both are here, and the page says next to every figure where it came from. An invented
 * preview would be more convenient and would be a lie.
 */
export function BetriebPlanPage() {
  const runs = useRuns(20)
  const planned = (runs.data ?? []).find(
    (run) => run.report?.stage === "PLANNED" || run.report?.stage === "RESOLVING",
  )

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Plan"
        note="Was ein Lauf ändern würde, bevor er startet – zusammengesetzt aus dem, was es wirklich gibt."
        actions={<AskButton kind="UPDATE" variant="default" />}
      />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Woher die Zahlen kommen</CardTitle>
          <CardDescription>
            Damit auf dieser Seite nichts steht, was der Server nicht gesagt hat.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-2 text-sm text-muted-foreground">
          <p className="max-w-prose">
            <strong className="text-foreground">Einen Probelauf gibt es nicht.</strong>{" "}
            steward-worker kennt keinen Endpunkt, der ausrechnet, was ein Update täte, ohne es zu
            tun. Diese Seite zeigt deshalb zwei echte Dinge: den Bericht eines Laufs, der die
            Versionen gerade ermittelt hat und noch nichts angefasst hat, und den Vergleich der
            Images gegen die Registry.
          </p>
          <p className="max-w-prose">
            Ein Lauf steht nur für Sekunden in <code className="text-xs">RESOLVING</code> oder{" "}
            <code className="text-xs">PLANNED</code>. Findet sich unten keiner, heißt das nicht,
            dass es nichts zu tun gäbe – es heißt, dass gerade kein Lauf in diesem Zustand steht.
            Ein reiner Berichtslauf (die alte Art <code className="text-xs">REPORT</code>) lässt
            sich aus dieser Oberfläche bisher nicht anfordern.
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Zuletzt ermittelt</CardTitle>
          <CardDescription>
            Der jüngste Lauf, dessen Bericht noch in <code className="text-xs">RESOLVING</code>{" "}
            oder <code className="text-xs">PLANNED</code> steht.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {runs.isPending ? (
            <Loading rows={4} />
          ) : runs.error ? (
            <Failure error={runs.error} onRetry={runs.refetch} />
          ) : planned === undefined ? (
            <Empty
              title="Kein Lauf steht gerade im Plan"
              note={`Unter den letzten 20 Zeilen ist keine, deren Bericht noch bei „Ermittelt" oder „Geplant" steht. Was ein Update täte, lässt sich hier deshalb nur am Image-Vergleich unten ablesen.`}
            />
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-4">
                <Stat
                  label="Lauf"
                  value={
                    <Link
                      to="/betrieb/lauf/$id"
                      params={{ id: String(planned.id) }}
                      className="underline-offset-4 hover:text-primary hover:underline"
                    >
                      #{planned.id}
                    </Link>
                  }
                  hint={`${RUN_KIND[planned.kind] ?? planned.kind} · ${planned.requestedBy}`}
                />
                <Stat
                  label="Stand"
                  value={<StageBadge stage={planned.report!.stage} />}
                  hint={`ermittelt ${relative(planned.requested)}`}
                />
              </div>
              <ReportLines lines={planned.report!.services} />
              <Notes notes={planned.report!.notes} />
            </>
          )}
        </CardContent>
      </Card>

      <DriftCard note="Der zweite Teil des Plans: welches Image hinter der Registry zurückliegt. Das sagt, welcher Container neu erzeugt würde – nicht, welche Jars ein Update tauschen würde." />
    </div>
  )
}

// --- 3. /betrieb/lauf/$id ------------------------------------------------------------------------

/**
 * One run, drawn rather than dumped.
 *
 * `useRun` polls every two seconds while the run is unfinished and stops by itself, so the report
 * grows on screen without a socket or an interval of this page's own.
 *
 * `$id` may also be the word `letzter`, because the sidebar links there. It is resolved through
 * `useRuns(1)` before any run is asked for - without that, `GET /api/updates/letzter` would die on
 * the backend's `Long.parseLong`.
 */
export function BetriebLaufPage() {
  const { id } = useParams({ from: "/betrieb/lauf/$id" })
  const wantsNewest = id === "letzter"
  const newest = useRuns(1, wantsNewest)
  const resolved = wantsNewest ? (newest.data?.[0]?.id?.toString() ?? "") : id
  const numeric = /^\d+$/.test(resolved)
  const run = useRun(resolved, numeric)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={numeric ? `Lauf #${resolved}` : "Lauf"}
        note="Der Bericht, wie steward-worker ihn in die Zeile schreibt – Stufe für Stufe, Dienst für Dienst."
        actions={
          <Button asChild variant="outline">
            <Link to="/betrieb">
              <ArrowRight aria-hidden />
              Alle Läufe
            </Link>
          </Button>
        }
      />

      {wantsNewest && newest.isPending ? (
        <Loading rows={4} />
      ) : wantsNewest && newest.error ? (
        <Failure error={newest.error} onRetry={newest.refetch} />
      ) : wantsNewest && !numeric ? (
        <Empty
          title="Noch kein Lauf"
          note={`In update_request steht keine Zeile, auf die „letzter" zeigen könnte.`}
        />
      ) : !numeric ? (
        <Empty
          title="Keine Laufnummer"
          note={`„${id}" ist weder eine Zahl noch das Wort „letzter". Ein Lauf wird über die Nummer seiner Zeile adressiert.`}
        />
      ) : run.isPending ? (
        <Loading rows={4} />
      ) : run.error ? (
        <Failure error={run.error} onRetry={run.refetch} />
      ) : run.data === undefined ? (
        <Empty title="Unbekannter Lauf" note={`Zu #${resolved} gibt es keine Zeile.`} />
      ) : (
        <RunDetail run={run.data} />
      )}
    </div>
  )
}

function RunDetail({ run }: { run: Run }) {
  const report = run.report
  const finished = report
    ? ENDINGS.has(report.stage)
    : run.status !== "PENDING" && run.status !== "RUNNING"

  return (
    <>
      <Card>
        <CardContent className="flex flex-wrap items-start gap-6 pt-6">
          <div className="flex flex-col gap-2">
            <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
              Status
            </span>
            <div className="flex items-center gap-2">
              <RunStatus status={run.status} />
              {report ? <StageBadge stage={report.stage} /> : null}
            </div>
            <span className="text-xs text-muted-foreground">
              {RUN_KIND[run.kind] ?? run.kind} · {SOURCE_LABEL[run.source] ?? run.source}
            </span>
          </div>

          <Separator orientation="vertical" className="h-14" />

          <Stat label="Angefordert von" value={run.requestedBy} hint={dateTime(run.requested)} />
          <Stat
            label="Frühestens"
            value={dateTime(run.notBefore)}
            hint="vorher holt der Worker die Zeile nicht"
          />
          <Stat label="Gestartet" value={dateTime(run.started)} hint={relative(run.started)} />
          <Stat
            label="Dauer"
            value={duration(runSeconds(run))}
            hint={finished ? dateTime(run.finished) : "läuft noch"}
          />
        </CardContent>
      </Card>

      {report?.stage === "NOTHING_TO_DO" ? (
        <div
          className="flex items-start gap-3 rounded-md border border-border bg-secondary/40 px-4 py-3"
          role="status"
        >
          <CircleSlash className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />
          <div className="flex flex-col gap-1">
            <p className="text-sm font-medium">Nichts zu tun.</p>
            <p className="max-w-prose text-sm text-muted-foreground">
              Das ist die dritte Antwort, nicht eine leise Art von „fertig": es wurde nichts
              gestoppt, nichts getauscht und nichts gesichert. Ein Lauf, der dafür das Netzwerk
              angehalten hätte, wäre ein Fehler.
            </p>
          </div>
        </div>
      ) : null}

      {run.savedSomething === false && run.kind === "BACKUP" && finished ? (
        <div
          className="flex items-start gap-3 rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3"
          role="alert"
        >
          <ShieldAlert className="mt-0.5 size-5 shrink-0 text-destructive" aria-hidden />
          <div className="flex flex-col gap-1">
            <p className="text-sm font-medium">Dieser Lauf hat nichts gesichert.</p>
            <p className="max-w-prose text-sm text-muted-foreground">
              Keine Zeile des Berichts steht auf „gesichert". Der Status eines Laufs beantwortet, ob
              ein Schritt einen Fehler gemeldet hat – ob eine Datei entstanden ist, beantwortet nur
              diese Zeile.
            </p>
          </div>
        </div>
      ) : null}

      {report ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm font-medium">Stufen</CardTitle>
            <CardDescription>
              Nicht jeder Lauf durchläuft jede Stufe – ein Update sichert nichts, eine Sicherung
              installiert nichts. Grau heißt „hier war dieser Lauf nicht", nicht „übersprungen".
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <StageTrail stage={report.stage} />
            {!finished ? (
              <p className="flex items-center gap-2 text-xs text-muted-foreground">
                <span className="size-2 animate-pulse rounded-full bg-warning" aria-hidden />
                Der Bericht wird alle zwei Sekunden neu gelesen und wächst währenddessen.
              </p>
            ) : null}
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Bericht</CardTitle>
          <CardDescription>
            Eine Zeile je Dienst – bei einer Sicherung je Volume – mit dem, was an ihr bewegt wurde.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {run.resultText ? (
            <>
              <p className="flex items-start gap-2 text-sm text-warning">
                <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
                Der Inhalt der Spalte <code className="text-xs">result</code> ließ sich nicht als
                Bericht lesen – eine alte Zeile, oder eine aus einer neueren Fassung als dieser. Der
                rohe Text steht darum hier.
              </p>
              <pre className="max-h-96 overflow-auto rounded-md border border-border bg-[#0a0a0a] p-3 font-mono text-xs leading-5 whitespace-pre-wrap">
                {run.resultText}
              </pre>
            </>
          ) : !report ? (
            <Empty
              title="Noch kein Bericht"
              note="In der Spalte result steht nichts. Solange der Worker die Zeile nicht geholt hat, schreibt auch niemand hinein."
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

function StageTrail({ stage }: { stage: string }) {
  const reached = TRAIL.indexOf(stage as (typeof TRAIL)[number])
  const ending = ENDINGS.has(stage)

  return (
    <ol className="flex flex-wrap items-center gap-x-2 gap-y-3">
      {TRAIL.map((step, index) => {
        // A run that has ended has walked everything it was going to walk, so every step behind it
        // is past; a running one has walked everything before its current stage.
        const past = ending || (reached >= 0 && index < reached)
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
              {past && !now ? <Check className="size-3 shrink-0" aria-hidden /> : null}
              {STAGE_LABEL[step]}
            </span>
            {index < TRAIL.length - 1 ? (
              <span className="text-muted-foreground" aria-hidden>
                ·
              </span>
            ) : null}
          </li>
        )
      })}
      {ending ? (
        <li className="flex items-center gap-2">
          <ArrowRight className="size-3 shrink-0 text-muted-foreground" aria-hidden />
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
        title="Keine Zeile im Bericht"
        note="Der Bericht steht, nennt aber keinen Dienst – der Lauf hat noch keinen angefasst."
      />
    )
  }
  return (
    <Table className="steward-table">
      <TableHeader>
        <TableRow>
          <TableHead className="w-[14rem]">Dienst</TableHead>
          <TableHead className="w-[10rem]">Zustand</TableHead>
          <TableHead>Änderungen</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {lines.map((line) => (
          <TableRow key={line.service} className="align-top">
            <TableCell className="font-medium">{line.service}</TableCell>
            <TableCell>
              <LineState state={line.state} />
            </TableCell>
            <TableCell>
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
      <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
        Anmerkungen
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

// --- 4. /betrieb/sicherung/$id -------------------------------------------------------------------

/**
 * How the worker names what it writes into the backup directory.
 *
 * `TarSnapshots`: `<volume>-<stamp>.tar.zst`, and `<…>.partial` while it is being written.
 * `DatabaseDump`: `nordtal-<stamp>.dump`. Nothing in the run's report carries the file name, so the
 * name is the only thing that says what an archive holds - which is why it is taken apart here
 * rather than printed as one string.
 */
const VOLUME_ARCHIVE = /^(.+)-(\d{8}T\d{6}Z)\.tar\.zst(\.partial)?$/
const DATABASE_DUMP = /^(.+)-(\d{8}T\d{6}Z)\.dump(\.partial)?$/

type Archived = { kind: "volume" | "database" | "unknown"; subject: string | null }

function archived(name: string): Archived {
  const dump = DATABASE_DUMP.exec(name)
  // `DatabaseDump.NAME` is the word the report's line carries for the dump; the file is named after
  // the database, not after that word.
  if (dump) return { kind: "database", subject: "database" }
  const volume = VOLUME_ARCHIVE.exec(name)
  if (volume) return { kind: "volume", subject: volume[1] }
  return { kind: "unknown", subject: null }
}

function ArchiveKind({ name }: { name: string }) {
  const what = archived(name)
  if (what.kind === "database") {
    return (
      <span className="flex items-center gap-1.5">
        <Database className="size-3.5 shrink-0" aria-hidden />
        Datenbank
      </span>
    )
  }
  if (what.kind === "volume") {
    return (
      <span className="flex items-center gap-1.5">
        <Archive className="size-3.5 shrink-0" aria-hidden />
        <span className="truncate">{what.subject}</span>
      </span>
    )
  }
  return <span className="text-muted-foreground">unbekannt</span>
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
export function BetriebSicherungPage() {
  const { id } = useParams({ from: "/betrieb/sicherung/$id" })
  const backups = useBackups()
  const wantsNewest = id === "letzte"

  const backup = useMemo(() => {
    const all = backups.data ?? []
    if (!wantsNewest) return all.find((entry) => entry.name === id)
    // "letzte" means the newest FINISHED one: a .partial is not a backup, and sending the sidebar's
    // link to a half-written file would be the one case where the word is actively misleading.
    return all.find((entry) => !entry.partial) ?? all[0]
  }, [backups.data, id, wantsNewest])

  const runs = useRuns(50)
  const match = useMemo(() => matchingRun(backup, runs.data ?? []), [backup, runs.data])

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={backup ? backup.name : "Sicherung"}
        note="Eine einzelne Datei aus dem Sicherungsverzeichnis: wann sie entstand, wie groß sie ist und ob sie vollständig ist."
        actions={
          <Button asChild variant="outline">
            <Link to="/betrieb/wiederherstellen">
              <Download aria-hidden />
              Zurückspielen
            </Link>
          </Button>
        }
      />

      {backups.isPending ? (
        <Loading rows={4} />
      ) : backups.error ? (
        <Failure error={backups.error} onRetry={backups.refetch} />
      ) : backup === undefined ? (
        <Empty
          title={wantsNewest ? "Keine Sicherung vorhanden" : "Unbekannte Datei"}
          note={
            wantsNewest
              ? "Im Sicherungsverzeichnis liegt keine Datei."
              : `„${id}" liegt nicht im Sicherungsverzeichnis. Möglicherweise hat der Aufräumlauf sie inzwischen weggeräumt.`
          }
        />
      ) : (
        <>
          <Card>
            <CardContent className="flex flex-wrap items-start gap-6 pt-6">
              <div className="flex flex-col gap-2">
                <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
                  Zustand
                </span>
                {backup.partial ? (
                  <StatusBadge tone="warn">unvollständig</StatusBadge>
                ) : (
                  <StatusBadge tone="ok">fertig</StatusBadge>
                )}
                <span className="max-w-xs text-xs text-muted-foreground">
                  {backup.partial
                    ? "Die Datei trägt noch die Endung .partial. Entweder wird sie gerade geschrieben, oder der Lauf ist dabei gestorben – zurückspielen lässt sie sich nicht."
                    : "Die Datei wurde nach dem Schreiben einmal zurückgelesen und erst danach umbenannt."}
                </span>
              </div>

              <Separator orientation="vertical" className="h-14" />

              <Stat
                label="Zeitpunkt"
                value={dateTime(backup.modified)}
                hint={relative(backup.modified)}
              />
              <Stat label="Alter" value={since(backup.modified)} />
              <Stat
                label="Größe"
                value={bytes(backup.bytes)}
                hint={`der Worker nennt sie ${backup.human}`}
              />
              <div className="flex min-w-48 flex-col gap-1">
                <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
                  Inhalt
                </span>
                <span className="text-sm">
                  <ArchiveKind name={backup.name} />
                </span>
                <span className="text-xs text-muted-foreground">
                  {archived(backup.name).kind === "unknown"
                    ? "Der Name folgt keinem der beiden Muster, die der Worker schreibt."
                    : "Aus dem Dateinamen gelesen – im Archiv nachgesehen hat hier niemand."}
                </span>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-sm font-medium">Der Lauf dazu</CardTitle>
              <CardDescription>
                Erschlossen, nicht vermerkt: kein Bericht enthält einen Dateinamen. Gesucht wird ein
                Lauf, der zur Entstehungszeit dieser Datei lief und eine Berichtszeile für
                {" "}
                <code className="text-xs">
                  {archived(backup.name).subject ?? "dieses Archiv"}
                </code>{" "}
                trägt.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {runs.isPending ? (
                <Loading rows={2} />
              ) : runs.error ? (
                <Failure error={runs.error} onRetry={runs.refetch} />
              ) : match === undefined ? (
                <Empty
                  title="Kein passender Lauf gefunden"
                  note="Unter den letzten 50 Zeilen ist keine, die zeitlich und namentlich passt. Das heißt nicht, dass es keinen gab – es heißt, dass er nicht mehr unter den letzten 50 steht."
                />
              ) : (
                <div className="flex flex-wrap items-center gap-4">
                  <Stat
                    label="Lauf"
                    value={
                      <Link
                        to="/betrieb/lauf/$id"
                        params={{ id: String(match.id) }}
                        className="underline-offset-4 hover:text-primary hover:underline"
                      >
                        #{match.id}
                      </Link>
                    }
                    hint={`${RUN_KIND[match.kind] ?? match.kind} · ${match.requestedBy}`}
                  />
                  <Stat label="Status" value={<RunStatus status={match.status} />} />
                  <Stat
                    label="Gelaufen"
                    value={dateTime(match.started)}
                    hint={duration(runSeconds(match))}
                  />
                  <Button asChild variant="outline" size="sm" className="ml-auto">
                    <Link to="/betrieb/lauf/$id" params={{ id: String(match.id) }}>
                      <Play aria-hidden />
                      Bericht ansehen
                    </Link>
                  </Button>
                </div>
              )}
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

// --- 5. /betrieb/wiederherstellen ----------------------------------------------------------------

/**
 * This page restores nothing - and that is the decision, not a gap in it (concept §10a,
 * 2026-09-12).
 *
 * A restore is needed exactly when the stack is broken. `steward-ui` is part of the stack it would
 * be restoring: a button here works in every situation except the one it would exist for. A path
 * that fails in the emergency is not a path. So the page builds the finished command and a person
 * runs it on the host - which is where they would have to run it in the emergency anyway.
 */
export function BetriebWiederherstellenPage() {
  const backups = useBackups()
  const [chosen, setChosen] = useState<string>("")
  const command = `sudo bash deploy/restore.sh ${chosen || "<archiv>"}`

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Wiederherstellen"
        note="Der fertige Befehl zum Kopieren. Diese Oberfläche führt ihn nicht aus."
      />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Warum hier kein Knopf steht</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2 text-sm text-muted-foreground">
          <p className="max-w-prose">
            Eine Sicherung braucht man an dem Tag, an dem etwas kaputt ist. Diese Oberfläche läuft
            als Container in genau dem Stack, den sie zurückspielen würde – ein Knopf hier
            funktionierte also in jeder Lage außer der einen, für die es ihn gäbe. Deshalb baut die
            Seite den Befehl und führt ihn nicht aus.
          </p>
          <p className="max-w-prose">
            Ausgeführt wird er auf dem Host, im Verzeichnis des Repositorys, mit Root-Rechten – die
            Volumes gehören Docker.
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Archiv wählen</CardTitle>
          <CardDescription>
            Unvollständige Dateien stehen mit in der Liste, sind aber nicht wählbar: aus einer{" "}
            <code className="text-xs">.partial</code>-Datei lässt sich nichts zurückspielen.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {backups.isPending ? (
            <Loading rows={3} />
          ) : backups.error ? (
            <Failure error={backups.error} onRetry={backups.refetch} />
          ) : (backups.data ?? []).length === 0 ? (
            <Empty
              title="Kein Archiv auf der Platte"
              note="Es liegt keine Datei im Sicherungsverzeichnis, die sich zurückspielen ließe."
            />
          ) : (
            <div className="flex max-w-xl flex-col gap-1.5">
              <Label htmlFor="restore-archive">Sicherung</Label>
              <Select value={chosen} onValueChange={setChosen}>
                <SelectTrigger id="restore-archive" className="w-full">
                  <SelectValue placeholder="Archiv auswählen…" />
                </SelectTrigger>
                <SelectContent>
                  {(backups.data ?? []).map((backup) => (
                    <SelectItem key={backup.name} value={backup.name} disabled={backup.partial}>
                      {backup.name} · {bytes(backup.bytes)} · {relative(backup.modified)}
                      {backup.partial ? " · unvollständig" : ""}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}

          <div className="flex flex-col gap-2">
            <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
              Befehl
            </span>
            <div className="flex items-center gap-2">
              <code className="min-w-0 flex-1 overflow-x-auto rounded-md border border-border bg-[#0a0a0a] px-3 py-2 font-mono text-xs whitespace-pre">
                {command}
              </code>
              <CopyButton text={command} disabled={!chosen} />
            </div>
            <p className="max-w-prose text-xs text-muted-foreground">
              Das Skript fragt vor dem Überschreiben den Volume-Namen ab – getippt, nicht bestätigt.
              <code className="text-xs"> --list</code> zeigt, was auf der Platte liegt.
            </p>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Was dabei passiert</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2 text-sm text-muted-foreground">
          <p className="max-w-prose">
            <strong className="text-foreground">
              Ein Volume wird überschrieben, nicht ergänzt.
            </strong>{" "}
            Der Inhalt des Archivs tritt an die Stelle dessen, was jetzt im Volume liegt. Alles, was
            seit dem Zeitpunkt der Sicherung entstanden ist – gebaute Häuser, geänderte Configs –
            ist danach weg.
          </p>
          <p className="max-w-prose">
            Vorher wird gestoppt, was auf das Volume schreibt. Ein Archiv in ein Volume zu
            entpacken, in das ein laufender Server gerade schreibt, erzeugt eine Mischung aus
            beidem, die keinen der beiden Zustände darstellt.
          </p>
          <p className="max-w-prose">
            Ein Datenbankabzug (<code className="text-xs">.dump</code>) ist ein anderer Fall als ein
            Volume-Archiv (<code className="text-xs">.tar.zst</code>): er gehört zuerst in eine
            frische Datenbank, damit man hineinsehen kann, bevor etwas darauf zeigt.
          </p>
          <p className="max-w-prose">
            Alle Archive liegen auf derselben Platte wie die Originale. Gegen einen Fehlgriff hilft
            das; gegen den Ausfall dieser Platte hilft es nicht.
          </p>
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
          toast.success("Befehl kopiert")
        } catch {
          toast.error("Kopieren nicht möglich", {
            description:
              "Die Zwischenablage steht dieser Seite nicht zur Verfügung. Der Befehl lässt sich daneben markieren.",
          })
        }
      }}
    >
      {copied ? <Check aria-hidden /> : <Copy aria-hidden />}
      {copied ? "Kopiert" : "Kopieren"}
    </Button>
  )
}
