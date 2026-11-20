import { describe, expect, it } from "vitest"

import type { Npc, NpcStep } from "@/lib/api"
import {
  addAnswer,
  addStep,
  arranged,
  blankNpc,
  boxHeight,
  deleteStep,
  edgesOf,
  NODE_WIDTH,
  ordered,
  overlapping,
  problemsOf,
  removeAnswer,
  sameNpc,
  setAnswerThen,
  setThen,
  tailOf,
} from "@/lib/npc"
import { isSlim } from "@/lib/npc-skin"

function step(id: string, change: Partial<NpcStep> = {}): NpcStep {
  return { id, x: 0, y: 0, text: { de: `${id} de`, en: `${id} en` }, answers: [], ...change }
}

function npcOf(steps: NpcStep[], start = steps[0].id): Npc {
  return {
    ...blankNpc("00000000-0000-0000-0000-000000000001"),
    name: { de: "Runa", en: "Runa" },
    place: { world: "world", x: 1, y: 64, z: 1, yaw: 0 },
    dialogue: { start, steps },
  }
}

const said = { de: "ja", en: "yes" }

describe("what follows a step", () => {
  it("draws an end under a text nothing follows, a menu under one that opens it, and nothing under answers", () => {
    expect(tailOf(step("a"))).toBe("end")
    expect(tailOf(step("a", { menu: "objectives" }))).toBe("menu")
    expect(tailOf(step("a", { next: "b" }))).toBeUndefined()
    expect(tailOf(step("a", { answers: [{ text: said }] }))).toBeUndefined()
  })

  it("replaces the next step by a menu and the menu by the end", () => {
    const opened = setThen(npcOf([step("a", { next: "b" }), step("b")]), "a", { to: "menu", menu: "wheel" })
    expect(opened.dialogue.steps[0]).toEqual(step("a", { menu: "wheel" }))
    expect(setThen(opened, "a", { to: "end" }).dialogue.steps[0]).toEqual(step("a"))
  })

  it("gives the first answer the step its text went on to, and gives it back with the last answer", () => {
    const asked = addAnswer(npcOf([step("a", { next: "b" }), step("b")]), "a")
    expect(asked.dialogue.steps[0].next).toBeUndefined()
    expect(asked.dialogue.steps[0].answers).toEqual([{ text: { de: "", en: "" }, next: "b" }])
    expect(removeAnswer(asked, "a", 0).dialogue.steps[0]).toEqual(step("a", { next: "b" }))
  })

  it("drops the menu of a step that gets an answer, and stops at three answers", () => {
    let asked = addAnswer(npcOf([step("a", { menu: "wheel" })]), "a")
    expect(asked.dialogue.steps[0].menu).toBeUndefined()
    for (let more = 0; more < 5; more++) asked = addAnswer(asked, "a")
    expect(asked.dialogue.steps[0].answers).toHaveLength(3)
  })

  it("ends an answer that led to a step when the end is picked", () => {
    const npc = npcOf([step("a", { answers: [{ text: said, next: "b" }] }), step("b")])
    expect(setAnswerThen(npc, "a", 0, { to: "end" }).dialogue.steps[0].answers).toEqual([{ text: said }])
  })
})

describe("adding and deleting steps", () => {
  it("puts a new step where it covers no other", () => {
    const first = addStep(npcOf([step("a")]), "a")
    const second = addStep(first.npc, "a")
    expect(first.id).not.toBe(second.id)
    expect(overlapping(second.npc.dialogue)).toBe(false)
    expect(second.npc.dialogue.steps[2].x).toBe(second.npc.dialogue.steps[1].x)
  })

  it("ends whatever led to a deleted step and moves the start off it", () => {
    const npc = npcOf([step("a", { next: "b" }), step("b"), step("c", { answers: [{ text: said, next: "b" }] })])
    const without = deleteStep(npc, "b")
    expect(without.dialogue.steps).toEqual([step("a"), step("c", { answers: [{ text: said }] })])
    expect(deleteStep(npc, "a").dialogue.start).toBe("b")
  })

  it("keeps the last step", () => {
    const npc = npcOf([step("a")])
    expect(deleteStep(npc, "a")).toBe(npc)
  })
})

describe("the order of the steps", () => {
  const npc = npcOf(
    [
      step("lost"),
      step("ask", { answers: [{ text: said, next: "work" }, { text: said, next: "valley" }, { text: said }] }),
      step("valley", { next: "ask" }),
      step("work"),
      step("hello", { next: "ask" }),
    ],
    "hello",
  )

  it("follows the dialogue from its start, an answer's step before the next answer's, and lists the rest last", () => {
    expect(ordered(npc.dialogue).map((entry) => [entry.step.id, entry.linked])).toEqual([
      ["hello", true],
      ["ask", true],
      ["work", true],
      ["valley", true],
      ["lost", false],
    ])
  })

  it("arranges steps that cover each other into columns that do not", () => {
    expect(overlapping(npc.dialogue)).toBe(true)
    const tidy = arranged(npc)
    expect(overlapping(tidy.dialogue)).toBe(false)
    const at = (id: string) => tidy.dialogue.steps.find((each) => each.id === id)!
    expect(at("ask").x).toBeGreaterThan(at("hello").x + NODE_WIDTH)
    expect(at("valley").y).toBeGreaterThanOrEqual(at("work").y + boxHeight(at("work")))
  })

  it("draws one line per answer that leads somewhere, one per next step and one per menu or end", () => {
    expect(
      edgesOf(arranged(npc).dialogue)
        .map((edge) => edge.key)
        .toSorted(),
    ).toEqual(["ask:0", "ask:1", "hello:next", "lost:tail", "valley:next", "work:tail"].toSorted())
  })
})

describe("what the page refuses before it publishes", () => {
  it("accepts a named, placed NPC whose texts are all there", () => {
    expect(problemsOf(npcOf([step("a")]))).toEqual([])
  })

  it("names the missing name, the missing position and the step that lacks a text, in that order", () => {
    const npc = {
      ...blankNpc("00000000-0000-0000-0000-000000000001"),
      name: { de: "Runa", en: " " },
      dialogue: {
        start: "a",
        steps: [
          step("a", { next: "b" }),
          step("b", { text: { de: "", en: "b" }, answers: [{ text: { de: "x", en: "" } }] }),
        ],
      },
    }
    expect(problemsOf(npc)).toEqual([
      { problem: "name", language: "en" },
      { problem: "place" },
      { problem: "step-text", language: "de", step: "b" },
      { problem: "answer-text", language: "en", step: "b" },
    ])
  })
})

describe("a draft against what is published", () => {
  it("is the same whatever the order of its keys, and differs by one letter", () => {
    const npc = npcOf([step("a")])
    const reordered = { ...npc, name: { en: "Runa", de: "Runa" } }
    expect(sameNpc(npc, reordered)).toBe(true)
    expect(sameNpc(npc, { ...npc, name: { de: "Runa", en: "Runas" } })).toBe(false)
  })
})

describe("the arms of a skin", () => {
  it("reads a skin as slim when the fourth column of its right arm is empty", () => {
    expect(isSlim(() => 0)).toBe(true)
    expect(isSlim((x, y) => (x === 54 && y === 31 ? 255 : 0))).toBe(false)
  })
})
