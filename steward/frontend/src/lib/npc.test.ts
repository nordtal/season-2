import { describe, expect, it } from "vitest"

import type { Npc, NpcMenu, NpcStep } from "@/lib/api"
import {
  addAnswer,
  addPage,
  addStep,
  addStepAt,
  arranged,
  blankNpc,
  deleteStep,
  missingCount,
  moveStep,
  NODE_WIDTH,
  nodeHeight,
  ordered,
  overlapping,
  placeStep,
  problemsOf,
  removeAnswer,
  removePage,
  sameNpc,
  setAnswerThen,
  setStepPageText,
  setThen,
  stepNumbers,
  warningsOf,
} from "@/lib/npc"
import { measureKey } from "@/lib/queries-npcs"
import { isSlim } from "@/lib/npc-skin"

function step(id: string, change: Partial<NpcStep> = {}): NpcStep {
  return { id, x: 0, y: 0, pages: [{ de: `${id} de`, en: `${id} en` }], answers: [], ...change }
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
const menus: NpcMenu[] = [{ key: "wheel", hint: { en: "Open wheel", de: "Rad" } }]

describe("what follows a step", () => {
  it("replaces the next step by a menu, the menu by a step, and the menu by the end", () => {
    const opened = setThen(npcOf([step("a", { next: "b" }), step("b")]), "a", { to: "menu", menu: "wheel" })
    expect(opened.dialogue.steps[0]).toEqual(step("a", { menu: "wheel" }))
    const relinked = setThen(opened, "a", { to: "step", step: "b" })
    expect(relinked.dialogue.steps[0]).toEqual(step("a", { next: "b" }))
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

describe("adding, moving, placing and deleting steps", () => {
  it("puts a new step to the right where it covers no other", () => {
    const first = addStep(npcOf([step("a")]), "a")
    const second = addStep(first.npc, "a")
    expect(first.id).not.toBe(second.id)
    expect(overlapping(second.npc.dialogue)).toBe(false)
    expect(second.npc.dialogue.steps[2].x).toBe(second.npc.dialogue.steps[1].x)
  })

  it("allows negative integer coordinates and nudges only the dropped step on overlap", () => {
    const moved = moveStep(npcOf([step("a")]), "a", -120.4, -45.7)
    expect(moved.dialogue.steps[0].x).toBe(-120)
    expect(moved.dialogue.steps[0].y).toBe(-46)

    const two = npcOf([step("a", { x: 100, y: 100 }), step("b", { x: 500, y: 100 })])
    const nudged = placeStep(two, "b", 110, 110)
    expect(nudged.dialogue.steps[0].x).toBe(100)
    expect(nudged.dialogue.steps[0].y).toBe(100)
    expect(overlapping(nudged.dialogue)).toBe(false)

    // Measured heights govern nudging: a drop leaving a 12px gap stays at the dropped position.
    const sizes = new Map([
      ["a", { width: NODE_WIDTH, height: 120 }],
      ["b", { width: NODE_WIDTH, height: 120 }],
    ])
    const kept = placeStep(two, "b", 100, 232, sizes)
    expect(kept.dialogue.steps[1].x).toBe(100)
    expect(kept.dialogue.steps[1].y).toBe(232)
  })

  it("adds a step at given coordinates without overlapping existing steps", () => {
    const base = npcOf([step("a", { x: 0, y: 0 })])
    const added = addStepAt(base, -400.2, 20.6)
    expect(added.npc.dialogue.steps[1].x).toBe(-400)
    expect(added.npc.dialogue.steps[1].y).toBe(21)
    const onTop = addStepAt(base, 10, 10)
    expect(overlapping(onTop.npc.dialogue)).toBe(false)
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

describe("the order and arrangement of the steps", () => {
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

  it("follows the dialogue from its start, numbers steps in reading order, and lists unlinked steps last", () => {
    expect(ordered(npc.dialogue).map((entry) => [entry.step.id, entry.linked])).toEqual([
      ["hello", true],
      ["ask", true],
      ["work", true],
      ["valley", true],
      ["lost", false],
    ])
    expect([...stepNumbers(npc.dialogue).entries()]).toEqual([
      ["hello", 1],
      ["ask", 2],
      ["work", 3],
      ["valley", 4],
      ["lost", 5],
    ])
  })

  it("arranges steps that cover each other into columns that do not", () => {
    expect(overlapping(npc.dialogue)).toBe(true)
    const tidy = arranged(npc)
    expect(overlapping(tidy.dialogue)).toBe(false)
    const at = (id: string) => tidy.dialogue.steps.find((each) => each.id === id)!
    expect(at("ask").x).toBeGreaterThan(at("hello").x + NODE_WIDTH)
    expect(at("valley").y).toBeGreaterThanOrEqual(at("work").y + nodeHeight(at("work")))
  })
})

describe("problems, warnings and missing text counts", () => {
  it("accepts a named, placed NPC whose texts are all there", () => {
    expect(problemsOf(npcOf([step("a")]), undefined, menus)).toEqual([])
  })

  it("lists missing name, position, step text and answer text in English-first order", () => {
    const npc = {
      ...blankNpc("00000000-0000-0000-0000-000000000001"),
      name: { de: "Runa", en: " " },
      dialogue: {
        start: "a",
        steps: [
          step("a", { next: "b" }),
          step("b", { pages: [{ de: "", en: "b" }], answers: [{ text: { de: "x", en: "" } }] }),
        ],
      },
    }
    expect(problemsOf(npc)).toEqual([
      { problem: "name", language: "en" },
      { problem: "place" },
      { problem: "step-text", language: "de", step: "b", page: 0 },
      { problem: "answer-text", language: "en", step: "b", answer: 0 },
    ])
    expect(missingCount(npc.dialogue.steps[1])).toBe(2)
  })

  it("flags an unregistered menu only once menus have loaded", () => {
    const npc = npcOf([step("a", { menu: "missing-menu" })])
    expect(problemsOf(npc, undefined, undefined)).toEqual([])
    expect(problemsOf(npc, undefined, menus)).toEqual([{ problem: "menu-unknown", step: "a", menu: "missing-menu" }])
  })

  it("reports unlinked steps and width-cut texts as non-blocking warnings", () => {
    const npc = npcOf([step("a", { pages: [{ en: "wide", de: "ok" }] }), step("orphan")])
    const measured = new Map([
      [
        measureKey({ text: "wide", kind: "BODY", answers: 0 }),
        { lines: ["wide"], width: 300, room: 226, lineLimit: 6, fits: false },
      ],
    ])
    expect(problemsOf(npc, measured, menus)).toEqual([])
    expect(warningsOf(npc, measured)).toEqual([
      { warning: "step-wide", language: "en", step: "a", page: 0 },
      { warning: "unlinked", step: "orphan" },
    ])
  })

  it("adds, edits and removes explicit pages while keeping the last page", () => {
    const start = npcOf([step("a")])
    const two = setStepPageText(addPage(start, "a"), "a", 1, "en", "Page two")
    expect(two.dialogue.steps[0].pages).toEqual([
      { de: "a de", en: "a en" },
      { de: "", en: "Page two" },
    ])
    const back = removePage(two, "a", 1)
    expect(back.dialogue.steps[0].pages).toEqual([{ de: "a de", en: "a en" }])
    expect(removePage(back, "a", 0).dialogue.steps[0].pages).toHaveLength(1)
  })

  it("refuses a page that exceeds its measured line limit", () => {
    const npc = npcOf([step("a", { pages: [{ de: "kurz", en: "too tall" }] })])
    const measured = new Map([
      [
        measureKey({ text: "too tall", kind: "BODY", answers: 0 }),
        { lines: ["1", "2", "3", "4", "5", "6", "7"], width: 100, room: 226, lineLimit: 6, fits: false },
      ],
    ])
    expect(problemsOf(npc, measured)).toEqual([{ problem: "step-tall", language: "en", step: "a", page: 0, limit: 6 }])
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
