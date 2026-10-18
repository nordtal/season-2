import type { LineState, ReportChange, Run, RunNote, RunStep } from "@/lib/api"
import { type MessageRef, choice, t } from "@/lib/texts"

/**
 * The record steps in the order a run reaches them. Start is where the servers come back. `RUN` belongs to the header.
 */
export const STAGES = [
  "SCOPE",
  "SOURCES",
  "RELEASE",
  "STANDBY",
  "PLAYERS",
  "STOP",
  "BACKUP",
  "MIGRATE",
  "INSTALL",
  "START",
  "CLEANUP",
] as const

export type StageKey = (typeof STAGES)[number]

/**
 * The result of a stage: its worst record, `quiet` when it has none, `not-reached` after a failure.
 */
export type Verdict = "failed" | "warning" | "done" | "skipped" | "quiet" | "now" | "not-reached"

/**
 * One service as a stage shows it. `undone` marks a change that a run planned and did not make.
 */
export type StageItem = {
  service: string
  state?: LineState
  changes: ReportChange[]
  said?: MessageRef
  undone?: boolean
}

export type Stage = { key: StageKey; verdict: Verdict; records: RunNote[]; items: StageItem[] }

/** The stages each kind walks through. They show even when empty. */
const WALKS: Record<string, readonly StageKey[]> = {
  UPDATE: ["SOURCES", "RELEASE", "STANDBY", "PLAYERS", "STOP", "MIGRATE", "INSTALL", "START", "CLEANUP"],
  BACKUP: ["PLAYERS", "STOP", "BACKUP", "START"],
  RESTART: ["STANDBY", "PLAYERS", "STOP", "START"],
  DOWN: ["PLAYERS", "STOP"],
  START: ["START"],
}

/** The stage a running run is in, taken from the stage that its report names. */
const NOW: Record<string, StageKey> = {
  RESOLVING: "SOURCES",
  PLANNED: "RELEASE",
  COUNTDOWN: "PLAYERS",
  STOPPING: "STOP",
  BACKING_UP: "BACKUP",
  INSTALLING: "INSTALL",
  STARTING: "START",
  VERIFYING: "START",
}

/** The artefacts that mark which kind of run touched a line. They are not installs. */
const MARKERS = new Set(["backup", "restart"])

/** The states of a server that went down and came back, or failed to. */
const CAME_BACK: ReadonlySet<LineState> = new Set(["STOPPED", "STARTING", "HEALTHY", "FAILED"])

/**
 * Returns the name of a stage: the record step's word, or the trail's word for Start, which no record step names.
 */
export function stageName(key: StageKey): string {
  return key === "START"
    ? t("steward.operations.step", { stage: "starting" })
    : t("run.step", { step: choice(key satisfies RunStep) })
}

/**
 * Returns a run as its stages, each with the records and service lines that belong to it, in the order a run reaches them.
 *
 * The report keeps only the last stage, so the state of a line decides its stage: saved goes under Backup, changes under Install.
 */
export function stagesOf(run: Run): Stage[] {
  const report = run.report
  if (!report || report.stage === "NOTHING_TO_DO") return []
  const lines = report.services
  const reached = lines.some((line) => line.state !== "PLANNED")

  const items: Record<StageKey, StageItem[]> = {
    SCOPE: [],
    SOURCES: [],
    RELEASE: [],
    STANDBY: [],
    PLAYERS: [],
    STOP: [],
    BACKUP: [],
    MIGRATE: [],
    INSTALL: [],
    START: [],
    CLEANUP: [],
  }
  for (const line of lines) {
    const installs = line.changes.filter((change) => !MARKERS.has(change.artefact))
    if (line.state === "SAVED") items.BACKUP.push({ service: line.service, state: line.state, changes: [] })
    if (CAME_BACK.has(line.state)) items.STOP.push({ service: line.service, changes: [] })
    if (installs.length > 0 && line.state !== "SAVED") {
      const planned = line.state === "PLANNED"
      const said = line.state === "FAILED" ? undefined : run.said[line.service]
      items.INSTALL.push({ service: line.service, changes: installs, said, undone: planned || !reached })
    }
    if (line.state === "STARTING" || line.state === "HEALTHY" || line.state === "FAILED") {
      const said = line.state === "FAILED" ? run.said[line.service] : undefined
      items.START.push({ service: line.service, state: line.state, changes: [], said })
    }
  }

  const records = (key: StageKey) => report.notes.filter((note) => note.step === key)
  const walked = WALKS[run.kind] ?? []
  const shown = STAGES.filter((key) => walked.includes(key) || records(key).length > 0 || items[key].length > 0)

  const verdicts = shown.map((key) => contentVerdict(records(key), items[key]))
  const failedAt = verdicts.indexOf("failed")
  const now = report.stage in NOW ? shown.indexOf(NOW[report.stage]) : -1
  const stopped = report.stage === "FAILED" || report.stage === "CANCELLED"

  return shown.map((key, index) => {
    const content = verdicts[index]
    const allUndone = items[key].length > 0 && items[key].every((item) => item.undone)
    let verdict: Verdict = content ?? "quiet"
    if (now >= 0 && index === now) verdict = "now"
    else if (now >= 0 && index > now && !content) verdict = "not-reached"
    else if (stopped && failedAt >= 0 && index > failedAt && (!content || allUndone)) verdict = "not-reached"
    else if (allUndone) verdict = "not-reached"
    return { key, verdict, records: records(key), items: items[key] }
  })
}

/** Returns the verdict that the records and lines of a stage give, or none when it has neither. */
function contentVerdict(records: RunNote[], items: StageItem[]): Verdict | undefined {
  if (records.some((note) => note.outcome === "FAILED") || items.some((item) => item.state === "FAILED")) {
    return "failed"
  }
  if (records.some((note) => note.outcome === "WARNING")) return "warning"
  if (items.some((item) => !item.undone) || records.some((note) => note.outcome === "DONE")) return "done"
  if (records.length > 0) return "skipped"
  return undefined
}
