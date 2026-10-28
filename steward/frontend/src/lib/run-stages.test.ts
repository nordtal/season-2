import { describe, expect, it } from "vitest"

import { bytes, time } from "@/lib/format"
import { pillsOf, type RailStep, stagesOf } from "@/lib/run-stages"

function step(over: Partial<RailStep> & { step: string }): RailStep {
  return { started: "2026-10-01T04:00:00Z", finished: "2026-10-01T04:00:10Z", outcome: "DONE", lines: [], ...over }
}

function archive(size: number) {
  return { volume: "nordtal-s2_mc-smp", file: "smp.tar.zst", bytes: size, tookMillis: 1000, stopVerified: true }
}

const texts = (steps: RailStep[]) => stagesOf(steps).map((stage) => pillsOf(stage).map((pill) => pill.text))

describe("stagesOf - a run's steps gathered into the rail's stages", () => {
  it("puts the stages in the order a run reaches them, whatever order the steps came in", () => {
    const stages = stagesOf([
      step({ step: "START", service: "smp" }),
      step({ step: "RESOLVE" }),
      step({ step: "STOP", service: "smp" }),
      step({ step: "PLAYERS_BACK" }),
      step({ step: "PLAYERS_MOVED" }),
    ])
    expect(stages.map((stage) => stage.key)).toEqual(["RESOLVE", "PLAYERS", "STOP", "STARTING"])
    expect(stages[1].steps.map((held) => held.step)).toEqual(["PLAYERS_BACK", "PLAYERS_MOVED"])
  })

  it("judges a stage by its worst step, and calls one of skipped steps alone skipped", () => {
    const verdicts = stagesOf([
      step({ step: "STOP", service: "smp" }),
      step({ step: "STOP", service: "proxy", outcome: "WARNING" }),
      step({ step: "INSTALL", service: "smp", outcome: "FAILED" }),
      step({ step: "INSTALL", service: "proxy", outcome: undefined, finished: undefined }),
      step({ step: "OFFSITE_COPY", outcome: "SKIPPED" }),
      step({ step: "CLEANUP", outcome: undefined, finished: undefined }),
      step({ step: "MIGRATE", outcome: undefined, started: undefined, finished: undefined }),
    ]).map((stage) => [stage.key, stage.verdict])
    expect(verdicts).toEqual([
      ["STOP", "warning"],
      ["MIGRATE", "planned"],
      ["INSTALL", "failed"],
      ["CLEANUP", "running"],
      ["OFFSITE", "skipped"],
    ])
  })
})

describe("pillsOf - a stage's coarse facts, each from a typed value", () => {
  it("names a few services and counts many", () => {
    const few = ["smp", "proxy"].map((service) => step({ step: "STOP", service }))
    const many = ["smp", "proxy", "limbo", "bot"].map((service) => step({ step: "START", service }))
    expect(texts([...few, ...many])).toEqual([["smp", "proxy"], ["4 services"]])
  })

  it("gives each installed service its own version jump once, and the release on resolve", () => {
    expect(
      texts([
        step({ step: "RESOLVE", changes: [{ artefact: "release", from: "0.63.2", to: "0.64.0" }] }),
        step({ step: "INSTALL", service: "smp", changes: [{ artefact: "smp", from: "1.0", to: "1.1" }] }),
        step({ step: "INSTALL", service: "smp", changes: [{ artefact: "chunky" }] }),
        step({ step: "INSTALL", service: "proxy", changes: [] }),
      ]),
    ).toEqual([["release 0.63.2 → 0.64.0"], ["smp 1.0 → 1.1", "proxy"]])
  })

  it("counts players moved and back, archives saved with their size, and archives pruned", () => {
    expect(
      texts([
        step({ step: "PLAYERS_MOVED", players: [{ from: "smp", to: "limbo", moved: 3, returned: 0 }] }),
        step({ step: "PLAYERS_BACK", players: [{ from: "smp", to: "limbo", moved: 0, returned: 2 }] }),
        step({ step: "PRUNE", pruned: [archive(10), archive(10)] }),
        step({ step: "SAVE_VOLUME", service: "smp", saved: [archive(1_000_000_000), archive(500_000_000)] }),
      ]),
    ).toEqual([
      ["3 players moved to limbo", "2 back"],
      ["2 archives saved", bytes(1_500_000_000), "2 pruned"],
    ])
  })

  it("names each standby with the times it came up and went down", () => {
    const [stage] = stagesOf([
      step({ step: "STANDBY_UP", service: "proxy-standby", finished: "2026-10-01T04:01:00Z" }),
      step({ step: "STANDBY_DOWN", service: "proxy-standby", finished: "2026-10-01T04:06:00Z" }),
    ])
    expect(pillsOf(stage).map((pill) => pill.text)).toEqual([
      "proxy-standby",
      `up ${time("2026-10-01T04:01:00Z")}`,
      `down ${time("2026-10-01T04:06:00Z")}`,
    ])
  })

  it("counts images removed and the space freed, and what offsite added", () => {
    expect(
      texts([
        step({ step: "CLEANUP", imagesRemoved: [{ image: "a", bytes: 1_000_000_000 }], cacheFreed: 500_000_000 }),
        step({ step: "OFFSITE_COPY", offsite: { snapshot: "ab12", bytesAdded: 240_000_000 } }),
      ]),
    ).toEqual([["1 image removed", `${bytes(1_500_000_000)} freed`], [`${bytes(240_000_000)} added`]])
  })

  it("gives a stage that did nothing a pill that says so, and a left-out one its reason", () => {
    const stages = stagesOf([
      step({ step: "PLAYERS_MOVED", players: [] }),
      step({ step: "OFFSITE_COPY", outcome: "SKIPPED", reason: "NO_REPOSITORY" }),
    ])
    expect(stages.map(pillsOf)).toEqual([[{ text: "nothing" }], [{ text: "no offsite repository", tone: "idle" }]])
  })
})
