import { describe, expect, it } from "vitest"

import type { Router, Run, RunStep, Service, StepKind } from "@/lib/api"
import {
  gateOf,
  secondsBetween,
  serverState,
  switchStages,
  switchState,
  whereOf,
} from "@/components/steward/router/router-state"

/** What the Router tab derives from the season's state: the status line, each server's word and the switch's stages. */

const NOW = Date.parse("2026-06-01T12:00:00Z")
const at = (offset: number) => new Date(NOW + offset).toISOString()

function season(over: Partial<Router> = {}): Router {
  return { opening: "LAUNCHED", launch: at(-86_400_000), maintenance: false, mode: "SMP", anchoredGrants: 0, ...over }
}

function step(kind: StepKind, over: Partial<RunStep> = {}): RunStep {
  return {
    seq: 1,
    step: kind,
    started: at(-60_000),
    finished: at(-60_000),
    outcome: "DONE",
    lines: [],
    changes: [],
    players: [],
    imagesRemoved: [],
    saved: [],
    pruned: [],
    ...over,
  }
}

function run(over: Partial<Run> = {}): Run {
  return {
    id: 7,
    kind: "SWITCH_MODE",
    status: "RUNNING",
    actorKind: "HOST",
    actorId: "",
    scope: [],
    requested: at(-120_000),
    scheduledFor: at(-120_000),
    moving: [],
    started: at(-120_000),
    steps: [],
    ...over,
  }
}

const switching = (over: Partial<Run>, target: Router["mode"] = "HUNGER_GAMES") =>
  season({ lastSwitch: { target, run: run(over) } })

function service(name: string, state: string, over: Partial<Service> = {}): Service {
  return { service: name, containerId: name, state, hasConsole: true, drift: "UP_TO_DATE", ...over }
}

describe("switchState", () => {
  it("reads each run status, and a switch that had nothing to do as none", () => {
    expect(switchState(season())).toBe("none")
    expect(switchState(switching({ status: "PENDING" }))).toBe("queued")
    expect(switchState(switching({ status: "RUNNING" }))).toBe("running")
    expect(switchState(switching({ status: "FAILED" }))).toBe("failed")
    expect(switchState(switching({ status: "DONE" }))).toBe("done")
    expect(switchState(switching({ status: "DONE", stage: "NOTHING_TO_DO" }))).toBe("none")
  })
})

describe("gateOf", () => {
  it("is closed without a launch date, and admins go through", () => {
    const gate = gateOf(season({ opening: "CLOSED", launch: undefined }), NOW)
    expect(gate).toMatchObject({ now: "closed", tone: "idle", why: "no launch date", except: ["admins go through"] })
  })

  it("counts down to a date ahead", () => {
    expect(gateOf(season({ opening: "COUNTDOWN", launch: at(3_600_000) }), NOW)).toMatchObject({
      now: "countdown",
      state: "closed",
    })
  })

  it("holds members in the waiting room during maintenance, for as long as it has been on", () => {
    const gate = gateOf(
      season({ maintenance: true, lastMaintenance: { on: at(-600_000), byKind: "HOST", byId: "" } }),
      NOW,
    )
    expect(gate).toMatchObject({ now: "waiting", tone: "warn", state: "wait", except: ["admins go through"] })
    expect(gate.why).toMatch(/^maintenance for /)
  })

  it("holds admins too while a switch has not reached its last stage", () => {
    const gate = gateOf(switching({ steps: [step("STOP", { service: "smp" })] }), NOW)
    expect(gate).toMatchObject({ now: "waiting", why: "mode switch, until Hunger Games runs" })
    expect(gate.except).toEqual(["admins wait too", "/server works"])
  })

  it("is open again once the switch lets players out", () => {
    const gate = gateOf(switching({ steps: [step("PLAYERS_BACK", { finished: undefined, outcome: undefined })] }), NOW)
    expect(gate).toMatchObject({ now: "SMP", state: "open" })
  })

  it("sends admins to the target during maintenance once the switch lets players out", () => {
    const router = {
      ...switching({ steps: [step("PLAYERS_BACK", { finished: undefined, outcome: undefined })] }),
      maintenance: true,
    }
    expect(gateOf(router, NOW).except).toEqual(["admins go to Hunger Games"])
  })

  it("says the switch failed and keeps members waiting", () => {
    expect(gateOf(switching({ status: "FAILED" }), NOW)).toMatchObject({
      now: "waiting",
      tone: "down",
      why: "switch failed, Hunger Games did not start",
    })
  })

  it("names a queued switch on an open gate", () => {
    expect(gateOf(switching({ status: "PENDING" }), NOW)).toMatchObject({
      now: "SMP",
      why: "switch to Hunger Games waiting",
    })
  })
})

describe("serverState", () => {
  it("shows the inactive server resting, or started by hand while it runs", () => {
    expect(serverState(season(), "HUNGER_GAMES", service("hunger-games", "exited"))).toBe("resting")
    expect(serverState(season(), "HUNGER_GAMES", service("hunger-games", "running"))).toBe("by-hand")
    expect(serverState(season(), "SMP", service("smp", "running"))).toBe("running")
  })

  it("shows the target starting while the switch brings it up", () => {
    const router = switching({
      steps: [step("START", { service: "hunger-games", finished: undefined, outcome: undefined })],
    })
    expect(serverState(router, "HUNGER_GAMES", service("hunger-games", "exited"))).toBe("starting")
  })

  it("shows a server failed on an alert or an unhealthy container", () => {
    expect(serverState(season(), "SMP", service("smp", "running", { health: "unhealthy" }))).toBe("failed")
    expect(serverState(season(), "SMP", service("smp", "exited", { alert: "down" }))).toBe("failed")
  })

  it("disables a start by hand on a stopped server while a switch holds", () => {
    expect(serverState(switching({ status: "FAILED" }), "HUNGER_GAMES", service("hunger-games", "exited"))).toBe(
      "stopped",
    )
    expect(serverState(switching({ status: "FAILED" }), "SMP", service("smp", "exited"))).toBe("failed")
  })
})

describe("switchStages", () => {
  it("sorts the steps into four stages and marks the ones not yet reached", () => {
    const stages = switchStages(
      run({
        steps: [
          step("PLAYERS_MOVED", { players: [{ from: "smp", to: "limbo", moved: 10, returned: 0 }] }),
          step("STOP", { service: "smp" }),
          step("START", { service: "hunger-games", finished: undefined, outcome: undefined }),
        ],
      }),
      "HUNGER_GAMES",
      false,
    )
    expect(stages.map((stage) => [stage.name, stage.verdict])).toEqual([
      ["Players to the waiting room", "done"],
      ["Stop SMP", "done"],
      ["Start Hunger Games", "running"],
      ["Players to Hunger Games", "planned"],
    ])
    expect(stages[0].pills.map((pill) => pill.text)).toEqual(["10 / 10 arrived"])
  })

  it("counts the players left behind only once the run has finished", () => {
    const back = step("PLAYERS_BACK", { players: [{ from: "limbo", to: "smp", moved: 10, returned: 6 }] })
    const running = switchStages(run({ steps: [back] }), "SMP", false)
    expect(running[3].pills.map((pill) => pill.text)).toEqual(["6 / 10"])
    const done = switchStages(run({ status: "DONE", finished: at(0), steps: [back] }), "SMP", true)
    expect(done[3].pills.map((pill) => pill.text)).toEqual(["6 / 10", "4 stay, maintenance"])
    expect(done[0].verdict).toBe("skipped")
  })

  it("marks a failed step's stage failed with its reason", () => {
    const stages = switchStages(
      run({ steps: [step("START", { service: "smp", outcome: "FAILED", reason: "NOT_HEALTHY" })] }),
      "SMP",
      false,
    )
    expect(stages[2].verdict).toBe("failed")
    expect(stages[2].pills.some((pill) => pill.tone === "down")).toBe(true)
  })
})

describe("whereOf and secondsBetween", () => {
  it("lists only the services with players, the waiting room first", () => {
    const services = [service("smp", "running", { players: 3 }), service("limbo", "running", { players: 2 })]
    expect(whereOf(season(), services)).toEqual([
      { service: "limbo", players: 2 },
      { service: "smp", players: 3 },
    ])
  })

  it("counts up to now while the end is missing", () => {
    expect(secondsBetween(at(-90_000), undefined, NOW)).toBe(90)
    expect(secondsBetween(at(-90_000), at(-30_000), NOW)).toBe(60)
    expect(secondsBetween(undefined, undefined, NOW)).toBe(0)
  })
})
