import type { RunArchive, RunChange, RunImageRemoved, RunMove, RunOffsite } from "@/lib/api"
import { bytes, time } from "@/lib/format"
import { choice, type MessageRef, span, t } from "@/lib/texts"

/**
 * A run's step and a plan's step alike. A plan's steps are computed, so they carry neither times nor typed facts.
 */
export type RailStep = {
  step: string
  service?: string
  started?: string
  finished?: string
  outcome?: string
  reason?: string
  lines: MessageRef[]
  changes?: RunChange[]
  players?: RunMove[]
  imagesRemoved?: RunImageRemoved[]
  cacheFreed?: number
  offsite?: RunOffsite
  saved?: RunArchive[]
  pruned?: RunArchive[]
}

/** The stages of the rail in the order a run reaches them, each with the step kinds it gathers. */
const STAGES = [
  ["RESOLVE", ["RUNTIME", "SCOPE", "RESOLVE", "HAND_OVER"]],
  ["STANDBY", ["STANDBY_UP", "STANDBY_DOWN"]],
  ["COUNTDOWN", ["COUNTDOWN"]],
  ["PLAYERS", ["PLAYERS_MOVED", "PLAYERS_BACK"]],
  ["STOP", ["STOP", "HOLD"]],
  ["BACKUP", ["ROOM_CHECK", "PRUNE", "DUMP_DATABASE", "SAVE_VOLUME"]],
  ["MIGRATE", ["MIGRATE"]],
  ["INSTALL", ["INSTALL"]],
  ["RESTORE", ["RESTORE"]],
  ["STARTING", ["START", "UNHOLD", "VERIFY"]],
  ["CLEANUP", ["CLEANUP"]],
  ["OFFSITE", ["OFFSITE_COPY"]],
] as const

export type StageKey = (typeof STAGES)[number][0]

/** `planned` is a plan's step, which no run has begun. */
export type Verdict = "failed" | "warning" | "running" | "done" | "skipped" | "planned"

export type Stage = { key: StageKey; verdict: Verdict; steps: RailStep[] }

/** A coarse fact on the rail, drawn from typed values only. A pill with a tone is an outcome's reason. */
export type Pill = { text: string; tone?: "warn" | "down" | "idle"; services?: boolean }

const STAGE_OF = new Map<string, StageKey>(STAGES.flatMap(([key, kinds]) => kinds.map((kind) => [kind, key])))

/** Returns the stages that hold a step, in rail order, each with its steps in the order the run began them. */
export function stagesOf(steps: RailStep[]): Stage[] {
  return STAGES.flatMap(([key]) => {
    // A step kind the rail does not know yet shows under the first stage rather than not at all.
    const held = steps.filter((step) => (STAGE_OF.get(step.step) ?? "RESOLVE") === key)
    return held.length > 0 ? [{ key, verdict: verdictOf(held), steps: held }] : []
  })
}

function verdictOf(steps: RailStep[]): Verdict {
  if (steps.some((step) => step.outcome === "FAILED")) return "failed"
  if (steps.some((step) => step.outcome === "WARNING")) return "warning"
  if (steps.some((step) => !step.outcome && step.started)) return "running"
  if (steps.some((step) => !step.outcome)) return "planned"
  if (steps.every((step) => step.outcome === "SKIPPED")) return "skipped"
  return "done"
}

/** Returns a step's verdict, as a stage of that one step would have. */
export function stepVerdict(step: RailStep): Verdict {
  return verdictOf([step])
}

export function stageName(key: StageKey): string {
  return t("steward.operations.stage", { stage: choice(key) })
}

/** The most services a pill row names one by one before it counts them. */
const NAMED = 3

/** Returns a stage's coarse facts: what it acted on and counted, then why a step went wrong or was left out. */
export function pillsOf(stage: Stage): Pill[] {
  const facts = FACTS[stage.key](stage.steps)
  const reasons = reasonsOf(stage.steps)
  if (facts.length === 0 && reasons.length === 0) {
    if (stage.verdict === "done") return [{ text: t("steward.updates.nothing") }]
    if (stage.verdict === "skipped") return [{ text: t("run.outcome", { outcome: "skipped" }), tone: "idle" }]
  }
  return [...facts, ...reasons]
}

const TONE = new Map<string, NonNullable<Pill["tone"]>>([
  ["FAILED", "down"],
  ["WARNING", "warn"],
  ["SKIPPED", "idle"],
])

function reasonsOf(steps: RailStep[]): Pill[] {
  const seen = new Map<string, Pill>()
  for (const step of steps) {
    const tone = step.outcome ? TONE.get(step.outcome) : undefined
    if (!tone || !step.reason) continue
    seen.set(`${step.outcome} ${step.reason}`, { text: t("run.reason", { reason: choice(step.reason) }), tone })
  }
  return [...seen.values()]
}

const FACTS: Record<StageKey, (steps: RailStep[]) => Pill[]> = {
  RESOLVE: (steps) => {
    const release = steps.flatMap((step) => (step.service ? [] : (step.changes ?? []))).find(isRelease)
    return release ? [{ text: changeText(release) }] : []
  },
  STANDBY: (steps) =>
    [...new Set(steps.flatMap((step) => (step.service ? [step.service] : [])))].flatMap((standby) => {
      const at = (kind: string) => steps.find((step) => step.step === kind && step.service === standby)?.finished
      const up = at("STANDBY_UP")
      const down = at("STANDBY_DOWN")
      return [
        { text: standby, services: true },
        ...(up ? [{ text: t("steward.operations.standby-up", { time: time(up) }) }] : []),
        ...(down ? [{ text: t("steward.operations.standby-down", { time: time(down) }) }] : []),
      ]
    }),
  COUNTDOWN: (steps) => {
    const seconds = steps.map(secondsOf).find((value) => value !== null)
    return seconds == null || steps.some((step) => step.outcome === "SKIPPED") ? [] : [{ text: span(seconds) }]
  },
  PLAYERS: (steps) => {
    const moves = steps.flatMap((step) => step.players ?? [])
    if (moves.length === 0) return []
    const moved = new Map<string, number>()
    for (const move of moves) moved.set(move.to, (moved.get(move.to) ?? 0) + move.moved)
    const back = moves.reduce((total, move) => total + move.returned, 0)
    const reached = steps.some((step) => step.step === "PLAYERS_BACK")
    return [
      ...[...moved].flatMap(([to, count]) =>
        count > 0 ? [{ text: t("steward.operations.players-moved", { count, to }) }] : [],
      ),
      ...(reached ? [{ text: t("steward.operations.players-back", { count: back }) }] : []),
    ]
  },
  STOP: (steps) => services(steps),
  BACKUP: (steps) => {
    const saved = steps.flatMap((step) => step.saved ?? [])
    const pruned = steps.flatMap((step) => step.pruned ?? [])
    const pills: Pill[] = []
    if (saved.length > 0) {
      pills.push({ text: t("steward.operations.archives-saved", { count: saved.length }) })
      pills.push({ text: bytes(saved.reduce((total, archive) => total + archive.bytes, 0)) })
    }
    if (pruned.length > 0) pills.push({ text: t("steward.operations.archives-pruned", { count: pruned.length }) })
    return pills
  },
  MIGRATE: (steps) =>
    steps.flatMap((step) => step.changes ?? []).flatMap((change) => (change.to ? [{ text: changeText(change) }] : [])),
  INSTALL: (steps) => {
    const jumps = new Map<string, Pill>()
    for (const step of steps) {
      if (!step.service) continue
      const own = (step.changes ?? []).find((change) => change.artefact === step.service && change.to)
      if (own) jumps.set(step.service, { text: changeText(own), services: true })
      else if (!jumps.has(step.service)) jumps.set(step.service, { text: step.service, services: true })
    }
    return [...jumps.values()]
  },
  RESTORE: (steps) => services(steps),
  STARTING: (steps) => services(steps),
  CLEANUP: (steps) => {
    const removed = steps.flatMap((step) => step.imagesRemoved ?? [])
    const freed =
      removed.reduce((total, image) => total + image.bytes, 0) + totalOf(steps.map((step) => step.cacheFreed))
    return [
      ...(removed.length > 0 ? [{ text: t("steward.operations.images-removed", { count: removed.length }) }] : []),
      ...(freed > 0 ? [{ text: t("steward.operations.freed", { size: bytes(freed) }) }] : []),
    ]
  },
  OFFSITE: (steps) =>
    steps.flatMap((step) =>
      step.offsite ? [{ text: t("steward.operations.added", { size: bytes(step.offsite.bytesAdded) }) }] : [],
    ),
}

/** Names the services of the steps, or counts them past a few. */
function services(steps: RailStep[]): Pill[] {
  const named = [...new Set(steps.flatMap((step) => (step.service ? [step.service] : [])))]
  if (named.length > NAMED) return [{ text: t("steward.operations.services", { count: named.length }), services: true }]
  return named.map((service) => ({ text: service, services: true }))
}

function isRelease(change: RunChange): boolean {
  return change.artefact === "release" && change.to !== undefined
}

/** A change as its versions, as the server's RunLines.change words it. */
function changeText(change: RunChange): string {
  if (change.from && change.to)
    return t("run.moved", { artefact: change.artefact, from: shown(change.from), to: change.to })
  return t("run.version", { artefact: change.artefact, version: change.to ?? change.from ?? "" })
}

/** A fingerprint, as the resource pack reports its SHA-1. */
const FINGERPRINT = /^[0-9a-f]{32,64}$/i

/** Returns a version as it is, and a fingerprint by its first eight characters. */
function shown(version: string): string {
  return FINGERPRINT.test(version) ? version.slice(0, 8) : version
}

function secondsOf(step: RailStep): number | null {
  if (!step.started || !step.finished) return null
  return (Date.parse(step.finished) - Date.parse(step.started)) / 1000
}

function totalOf(values: (number | undefined)[]): number {
  return values.reduce<number>((total, value) => total + (value ?? 0), 0)
}
