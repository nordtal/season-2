import type { Router, Run, SeasonMode, Service } from "@/lib/api"
import { type Pill, type RailStep, type Verdict, verdictOf } from "@/lib/run-stages"
import { choice, span, t } from "@/lib/texts"
import type { Tone } from "@/components/steward/status"

export const OTHER: Record<SeasonMode, SeasonMode> = { SMP: "HUNGER_GAMES", HUNGER_GAMES: "SMP" }

/** The waiting room's service, where the proxy holds players while the gate is shut. */
export const LIMBO = "limbo"

/** Returns the service of a mode's server, which is its constant as a `select` names it. */
export function serverOf(mode: SeasonMode): string {
  return choice(mode)
}

export function modeName(mode: SeasonMode): string {
  return t("steward.router.now", { now: choice(mode) })
}

/** The last switch as the tab shows it. A switch that found nothing to do counts as none. */
export type SwitchState = "none" | "queued" | "running" | "done" | "failed"

export function switchState(router: Pick<Router, "lastSwitch">): SwitchState {
  const run = router.lastSwitch?.run
  if (!run) return "none"
  if (run.status === "PENDING") return "queued"
  if (run.status === "RUNNING") return "running"
  if (run.status === "FAILED") return "failed"
  if (run.status === "DONE" && run.stage !== "NOTHING_TO_DO") return "done"
  return "none"
}

/** Returns whether a switch is open or failed, which keeps both servers out of a hand's reach. */
export function switchHolds(state: SwitchState): boolean {
  return state === "queued" || state === "running" || state === "failed"
}

/** Returns whether the switch has reached its last stage, where the proxy lets the players out. */
export function atLastStage(run: Run): boolean {
  return run.steps.some((step) => step.step === "PLAYERS_BACK")
}

export type NowWord = "closed" | "countdown" | "waiting" | SeasonMode

/**
 * The status line: where members land now, the gate in a word, why, and what holds for admins.
 *
 * `tone` colours the gate's word, and the large word too where the gate holds members.
 */
export type Gate = {
  now: NowWord
  tone: Tone
  state?: "closed" | "wait" | "open"
  why?: string
  except: string[]
}

export function gateOf(router: Router, now: number): Gate {
  const sw = switchState(router)
  const target = router.lastSwitch?.target ?? router.mode
  const targetName = modeName(target)
  const throughSwitch = [t("steward.router.admins-wait"), t("steward.router.server-works")]
  if (router.opening === "CLOSED") {
    return {
      now: "closed",
      tone: "idle",
      why: t("steward.router.no-launch-date"),
      except: [t("steward.router.admins-through")],
    }
  }
  if (router.opening === "COUNTDOWN") {
    return {
      now: "countdown",
      tone: "idle",
      state: "closed",
      why: t("steward.router.opens-at", { at: router.launch ?? "" }),
      except: [t("steward.router.admins-through")],
    }
  }
  if (router.maintenance) {
    const on = router.lastMaintenance?.on
    const seconds = on ? Math.max(0, (now - new Date(on).getTime()) / 1000) : 0
    const except =
      sw === "queued" || sw === "failed" || (sw === "running" && !atLastStage(router.lastSwitch!.run))
        ? throughSwitch
        : sw === "running"
          ? [t("steward.router.admins-to", { server: targetName })]
          : [t("steward.router.admins-through")]
    return {
      now: "waiting",
      tone: "warn",
      state: "wait",
      why: t("steward.router.maintenance-for", { length: seconds }),
      except,
    }
  }
  if (sw === "failed") {
    return {
      now: "waiting",
      tone: "down",
      state: "wait",
      why: t("steward.router.switch-failed", { server: targetName }),
      except: throughSwitch,
    }
  }
  if (sw === "running" && !atLastStage(router.lastSwitch!.run)) {
    return {
      now: "waiting",
      tone: "warn",
      state: "wait",
      why: t("steward.router.until-runs", { server: targetName }),
      except: throughSwitch,
    }
  }
  return {
    now: router.mode,
    tone: "ok",
    state: "open",
    why: sw === "queued" ? t("steward.router.switch-waiting", { server: targetName }) : undefined,
    except: router.mode === "SMP" ? [t("steward.router.no-access")] : [],
  }
}

/** The services the status line counts players on, in the order it names them. */
export function whereOf(router: Router, services: Service[]): Array<{ service: string; players: number }> {
  const order = [LIMBO, serverOf(router.mode), serverOf(OTHER[router.mode])]
  return order.flatMap((name) => {
    const players = services.find((row) => row.service === name)?.players ?? 0
    return players > 0 ? [{ service: name, players }] : []
  })
}

export type ServerState = "running" | "resting" | "by-hand" | "stopped" | "starting" | "failed"

export const SERVER_TONE: Record<ServerState, Tone> = {
  running: "ok",
  resting: "idle",
  "by-hand": "idle",
  stopped: "idle",
  starting: "warn",
  failed: "down",
}

/** Returns how the game mode card shows a mode's server. */
export function serverState(router: Router, mode: SeasonMode, service: Service | undefined): ServerState {
  const sw = switchState(router)
  const active = mode === router.mode
  const run = router.lastSwitch?.run
  if (service?.state === "running") {
    if (service.health === "unhealthy" || service.alert === "down") return "failed"
    if (service.health === "starting") return "starting"
    if (active || switchHolds(sw)) return "running"
    return "by-hand"
  }
  if (service?.alert === "down" || (active && sw === "failed")) return "failed"
  if (sw === "running" && run && router.lastSwitch?.target === mode && bringsUp(run, serverOf(mode))) return "starting"
  if (!active && !switchHolds(sw)) return "resting"
  return "stopped"
}

/** Returns whether a step of the run is starting the server or waiting for its verdict. */
function bringsUp(run: Run, server: string): boolean {
  return run.steps.some(
    (step) => (step.step === "START" || step.step === "VERIFY") && step.service === server && !step.outcome,
  )
}

/** The four stages of a mode switch, as its card draws them. */
export type SwitchStage = {
  key: "waiting-room" | "stop" | "start" | "to-server"
  name: string
  verdict: Verdict
  pills: Pill[]
  steps: RailStep[]
}

const STAGE_OF: Record<string, SwitchStage["key"]> = {
  STOP: "stop",
  HOLD: "stop",
  UNHOLD: "start",
  START: "start",
  VERIFY: "start",
  PLAYERS_BACK: "to-server",
}

/** Returns the four stages of a switch run with their verdicts and pills. */
export function switchStages(run: Run, target: SeasonMode, maintenance: boolean): SwitchStage[] {
  const held: Record<SwitchStage["key"], RailStep[]> = { "waiting-room": [], stop: [], start: [], "to-server": [] }
  for (const step of run.steps) held[STAGE_OF[step.step] ?? "waiting-room"].push(step)
  const moved = run.steps.flatMap((step) => step.players).reduce((sum, move) => sum + move.moved, 0)
  const returned = run.steps.flatMap((step) => step.players).reduce((sum, move) => sum + move.returned, 0)
  const names: Record<SwitchStage["key"], string> = {
    "waiting-room": t("steward.router.step-to-waiting-room"),
    stop: t("steward.router.step-stop", { server: modeName(OTHER[target]) }),
    start: t("steward.router.step-start", { server: modeName(target) }),
    "to-server": t("steward.router.step-to-server", { server: modeName(target) }),
  }
  const keys = ["waiting-room", "stop", "start", "to-server"] as const
  return keys.map((key, index) => {
    const steps = held[key]
    const later = keys.slice(index + 1).some((next) => held[next].length > 0)
    const verdict: Verdict = steps.length > 0 ? verdictOf(steps) : later ? "skipped" : "planned"
    return { key, name: names[key], verdict, pills: pillsOf(key, steps, { moved, returned, maintenance, run }), steps }
  })
}

function pillsOf(
  key: SwitchStage["key"],
  steps: RailStep[],
  { moved, returned, maintenance, run }: { moved: number; returned: number; maintenance: boolean; run: Run },
): Pill[] {
  const pills: Pill[] = []
  if (key === "waiting-room" && moved > 0) {
    pills.push({ text: t("steward.router.arrived", { arrived: moved, total: moved }) })
  }
  if (key === "to-server" && moved > 0) {
    pills.push({ text: t("steward.router.released", { released: returned, total: moved }) })
    const rest = moved - returned
    if (rest > 0 && run.finished) {
      pills.push({
        text: maintenance
          ? t("steward.router.stay-maintenance", { count: rest })
          : t("steward.router.without-access-count", { count: rest }),
        tone: "warn",
      })
    }
  }
  for (const step of steps) {
    if ((step.outcome === "FAILED" || step.outcome === "WARNING") && step.reason) {
      pills.push({
        text: t("run.reason", { reason: choice(step.reason) }),
        tone: step.outcome === "FAILED" ? "down" : "warn",
      })
    }
  }
  return pills
}

/** Returns the seconds between two instants, or since the first while the second is missing. */
export function secondsBetween(from: string | undefined, to: string | undefined, now: number): number {
  if (!from) return 0
  return Math.max(0, ((to ? new Date(to).getTime() : now) - new Date(from).getTime()) / 1000)
}

/** Returns a span in words, as the cards print a length. */
export function lengthOf(seconds: number): string {
  return span(seconds)
}
