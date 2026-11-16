import type { Dialogue, Npc, NpcAnswer, NpcStep, Words } from "@/lib/api"

/**
 * An NPC's draft as the editor changes it: every edit answers a new NPC, and the canvas geometry follows from the steps alone.
 */

export type Language = "de" | "en"

/** German first, as the editor lists the two texts of a step. */
export const LANGUAGES: readonly Language[] = ["de", "en"]

/** The most answers a step offers, as the panel holds them. */
export const MOST_ANSWERS = 3

/** The longest name, as the table holds it. */
export const LONGEST_NAME = 32

/** What follows a text or an answer: the end, a menu or another step. */
export type Then = { to: "end" } | { to: "menu"; menu: string } | { to: "step"; step: string }

/** What a step ends in when nothing follows its text: the menu it opens, or the end of the dialogue. */
export type Tail = "menu" | "end"

export function tailOf(step: NpcStep): Tail | undefined {
  if (step.answers.length > 0) return undefined
  if (step.menu !== undefined) return "menu"
  return step.next === undefined ? "end" : undefined
}

export function thenOf(step: NpcStep): Then {
  if (step.menu !== undefined) return { to: "menu", menu: step.menu }
  return step.next === undefined ? { to: "end" } : { to: "step", step: step.next }
}

const NO_WORDS: Words = { de: "", en: "" }

/** A new NPC: no name, no position, and one empty step to start with. */
export function blankNpc(id: string): Npc {
  return {
    id,
    name: NO_WORDS,
    place: { world: "", x: 0, y: 0, z: 0, yaw: 0 },
    pose: "STAND",
    pitch: 1,
    equipment: {},
    dialogue: { start: "step-1", steps: [{ id: "step-1", x: MARGIN, y: MARGIN, text: NO_WORDS, answers: [] }] },
    skinModel: "CLASSIC",
  }
}

/** A value's JSON with its keys in order, so two NPCs that hold the same compare equal. */
function canonical(value: unknown): string {
  return JSON.stringify(value, (_, inner: unknown) =>
    typeof inner === "object" && inner !== null && !Array.isArray(inner)
      ? Object.fromEntries(Object.entries(inner).toSorted(([a], [b]) => a.localeCompare(b)))
      : inner,
  )
}

/** Whether a draft holds exactly what is published. */
export function sameNpc(a: Npc, b: Npc): boolean {
  return canonical(a) === canonical(b)
}

function withSteps(npc: Npc, steps: NpcStep[], start = npc.dialogue.start): Npc {
  return { ...npc, dialogue: { start, steps } }
}

function editStep(npc: Npc, id: string, edit: (step: NpcStep) => NpcStep): Npc {
  return withSteps(
    npc,
    npc.dialogue.steps.map((step) => (step.id === id ? edit(step) : step)),
  )
}

export function stepOf(dialogue: Dialogue, id: string | undefined): NpcStep | undefined {
  return dialogue.steps.find((step) => step.id === id)
}

export function setStepText(npc: Npc, id: string, language: Language, text: string): Npc {
  return editStep(npc, id, (step) => ({ ...step, text: { ...step.text, [language]: text } }))
}

/** Sets what follows a step's text. A step with answers keeps them, since its answers say what follows. */
export function setThen(npc: Npc, id: string, then: Then): Npc {
  return editStep(npc, id, (step) => {
    const { next: _next, menu: _menu, ...rest } = step
    if (then.to === "menu") return { ...rest, menu: then.menu }
    return then.to === "step" ? { ...rest, next: then.step } : rest
  })
}

function editAnswer(npc: Npc, id: string, index: number, edit: (answer: NpcAnswer) => NpcAnswer): Npc {
  return editStep(npc, id, (step) => ({
    ...step,
    answers: step.answers.map((answer, at) => (at === index ? edit(answer) : answer)),
  }))
}

export function setAnswerText(npc: Npc, id: string, index: number, language: Language, text: string): Npc {
  return editAnswer(npc, id, index, (answer) => ({ ...answer, text: { ...answer.text, [language]: text } }))
}

/** Sets where an answer leads; an answer leads to a step or ends the dialogue. */
export function setAnswerThen(npc: Npc, id: string, index: number, then: Then): Npc {
  return editAnswer(npc, id, index, (answer) =>
    then.to === "step" ? { text: answer.text, next: then.step } : { text: answer.text },
  )
}

/**
 * Adds an empty answer. The first answer takes over the step its text went on to, and a menu the step opened goes,
 * because a step with answers has neither.
 */
export function addAnswer(npc: Npc, id: string): Npc {
  return editStep(npc, id, (step) => {
    if (step.answers.length >= MOST_ANSWERS) return step
    const { next, menu: _menu, ...rest } = step
    const answer: NpcAnswer =
      step.answers.length === 0 && next !== undefined ? { text: NO_WORDS, next } : { text: NO_WORDS }
    return { ...rest, answers: [...step.answers, answer] }
  })
}

/** Removes an answer. The step whose last answer goes takes over where that answer led. */
export function removeAnswer(npc: Npc, id: string, index: number): Npc {
  return editStep(npc, id, (step) => {
    const answers = step.answers.filter((_, at) => at !== index)
    const last = step.answers.length === 1 ? step.answers[0] : undefined
    return last?.next === undefined ? { ...step, answers } : { ...step, answers, next: last.next }
  })
}

export function setStart(npc: Npc, id: string): Npc {
  return withSteps(npc, npc.dialogue.steps, id)
}

/** Moves a step on the canvas, to whole pixels and never left of or above it. */
export function moveStep(npc: Npc, id: string, x: number, y: number): Npc {
  return editStep(npc, id, (step) => ({ ...step, x: Math.max(0, Math.round(x)), y: Math.max(0, Math.round(y)) }))
}

function freeId(dialogue: Dialogue): string {
  const taken = new Set(dialogue.steps.map((step) => step.id))
  let number = dialogue.steps.length + 1
  while (taken.has(`step-${number}`)) number++
  return `step-${number}`
}

/**
 * Adds an empty step and answers its id. Beside `from` it stands to the right, as an answer's step does, or below, as
 * the step a text goes on to does, and moves down until it covers no other step.
 */
export function addStep(
  npc: Npc,
  from: string | undefined,
  side: "right" | "below" = "right",
): { npc: Npc; id: string } {
  const id = freeId(npc.dialogue)
  const origin = stepOf(npc.dialogue, from)
  const x = origin === undefined ? MARGIN : side === "right" ? origin.x + NODE_WIDTH + GAP_X : origin.x
  let y = origin === undefined ? MARGIN : side === "right" ? origin.y : origin.y + boxHeight(origin) + GAP_Y
  for (let moved = true; moved;) {
    moved = false
    for (const other of npc.dialogue.steps) {
      const overlapsX = x < other.x + NODE_WIDTH + GAP_Y / 2 && other.x < x + NODE_WIDTH + GAP_Y / 2
      const bottom = other.y + boxHeight(other)
      if (overlapsX && y < bottom + GAP_Y / 2 && other.y < y + TEXT_HEIGHT + TAIL_GAP + END_HEIGHT + GAP_Y / 2) {
        y = bottom + GAP_Y
        moved = true
      }
    }
  }
  const step: NpcStep = { id, x, y, text: NO_WORDS, answers: [] }
  return { npc: withSteps(npc, [...npc.dialogue.steps, step]), id }
}

/**
 * Deletes a step. Whatever led to it ends the dialogue instead, and a dialogue that started there starts at its first
 * remaining step. The last step stays.
 */
export function deleteStep(npc: Npc, id: string): Npc {
  if (npc.dialogue.steps.length <= 1) return npc
  const steps = npc.dialogue.steps
    .filter((step) => step.id !== id)
    .map((step) => {
      const answers = step.answers.map((answer) => (answer.next === id ? { text: answer.text } : answer))
      if (step.next !== id) return { ...step, answers }
      const { next: _next, ...rest } = step
      return { ...rest, answers }
    })
  return withSteps(npc, steps, npc.dialogue.start === id ? steps[0].id : npc.dialogue.start)
}

/** The steps a step leads to, its answers' first and in their order. */
function followers(step: NpcStep): string[] {
  const ids = step.answers.flatMap((answer) => (answer.next === undefined ? [] : [answer.next]))
  return step.next === undefined ? ids : [...ids, step.next]
}

export type Ordered = { step: NpcStep; linked: boolean; depth: number }

/**
 * The steps in the order a player can meet them, from the start and each answer's step before the next answer's. The
 * steps nothing leads to come last, in the order they were added.
 */
export function ordered(dialogue: Dialogue): Ordered[] {
  const seen = new Set<string>()
  const found: Ordered[] = []
  const visit = (id: string, depth: number) => {
    const step = stepOf(dialogue, id)
    if (step === undefined || seen.has(id)) return
    seen.add(id)
    found.push({ step, linked: true, depth })
    followers(step).forEach((next) => visit(next, depth + 1))
  }
  visit(dialogue.start, 0)
  for (const step of dialogue.steps) {
    if (!seen.has(step.id)) found.push({ step, linked: false, depth: 0 })
  }
  return found
}

/** What keeps a draft from playing, which the page finds before it sends the draft. */
export type Problem =
  | { problem: "name"; language: Language }
  | { problem: "place" }
  | { problem: "step-text"; language: Language; step: string }
  | { problem: "answer-text"; language: Language; step: string }

/** The problems of a draft, the NPC's own first and then each step's in the order a player meets them. */
export function problemsOf(npc: Npc): Problem[] {
  const problems: Problem[] = []
  for (const language of LANGUAGES) {
    if (npc.name[language].trim() === "") problems.push({ problem: "name", language })
  }
  if (npc.place.world.trim() === "") problems.push({ problem: "place" })
  for (const { step } of ordered(npc.dialogue)) {
    for (const language of LANGUAGES) {
      if (step.text[language].trim() === "") problems.push({ problem: "step-text", language, step: step.id })
    }
    for (const answer of step.answers) {
      for (const language of LANGUAGES) {
        if (answer.text[language].trim() === "") problems.push({ problem: "answer-text", language, step: step.id })
      }
    }
  }
  return problems
}

/** The languages in which a step lacks a text, its own or an answer's. */
export function missingIn(step: NpcStep): Language[] {
  return LANGUAGES.filter(
    (language) =>
      step.text[language].trim() === "" || step.answers.some((answer) => answer.text[language].trim() === ""),
  )
}

/** A step's text as a short name for a picker: English, or German where there is no English. */
export function labelOf(step: NpcStep): string {
  return step.text.en.trim() || step.text.de.trim()
}

/** The canvas: every step is a box of one width, and its height follows from its answers. */
export const NODE_WIDTH = 248
export const MARGIN = 24
export const GAP_X = 72
export const GAP_Y = 24
/** A step's kind line and its two texts. */
export const TEXT_HEIGHT = 100
export const ANSWER_HEIGHT = 32
const ANSWER_GAP = 4
/** The line from a step down to the menu it opens or the end. */
export const TAIL_GAP = 40
export const MENU_HEIGHT = 72
export const END_HEIGHT = 44
/** Where a line enters a step: at its kind line. */
const ENTRY = 22

export function nodeHeight(step: NpcStep): number {
  const answers = step.answers.length
  return answers === 0 ? TEXT_HEIGHT : TEXT_HEIGHT + answers * (ANSWER_HEIGHT + ANSWER_GAP) + 8
}

function tailHeight(tail: Tail): number {
  return tail === "menu" ? MENU_HEIGHT : END_HEIGHT
}

/** The height a step takes with the menu or end under it. */
export function boxHeight(step: NpcStep): number {
  const tail = tailOf(step)
  return nodeHeight(step) + (tail === undefined ? 0 : TAIL_GAP + tailHeight(tail))
}

/** The middle of answer `index`'s row, from the step's top. */
export function answerY(index: number): number {
  return TEXT_HEIGHT + index * (ANSWER_HEIGHT + ANSWER_GAP) + ANSWER_HEIGHT / 2
}

export type Point = { x: number; y: number }

/** A line of the canvas, straight down or a curve from a right edge to a left edge. */
export type Edge = { key: string; from: Point; to: Point; down: boolean }

/** Whether `to` stands under `from` closely enough for a straight line down. */
function below(from: NpcStep, to: NpcStep): boolean {
  return to.y >= from.y + nodeHeight(from) && Math.abs(to.x - from.x) < NODE_WIDTH / 2
}

export function edgesOf(dialogue: Dialogue): Edge[] {
  const edges: Edge[] = []
  for (const step of dialogue.steps) {
    const height = nodeHeight(step)
    const middle = step.x + NODE_WIDTH / 2
    const tail = tailOf(step)
    if (tail !== undefined) {
      edges.push({
        key: `${step.id}:tail`,
        from: { x: middle, y: step.y + height },
        to: { x: middle, y: step.y + height + TAIL_GAP },
        down: true,
      })
    }
    const next = stepOf(dialogue, step.next)
    if (next !== undefined && step.answers.length === 0) {
      edges.push(
        below(step, next)
          ? {
              key: `${step.id}:next`,
              from: { x: middle, y: step.y + height },
              to: { x: next.x + NODE_WIDTH / 2, y: next.y },
              down: true,
            }
          : {
              key: `${step.id}:next`,
              from: { x: step.x + NODE_WIDTH, y: step.y + height / 2 },
              to: { x: next.x, y: next.y + ENTRY },
              down: false,
            },
      )
    }
    step.answers.forEach((answer, index) => {
      const to = stepOf(dialogue, answer.next)
      if (to === undefined) return
      edges.push({
        key: `${step.id}:${index}`,
        from: { x: step.x + NODE_WIDTH, y: step.y + answerY(index) },
        to: { x: to.x, y: to.y + ENTRY },
        down: false,
      })
    })
  }
  return edges
}

/** The SVG path of an edge. */
export function pathOf(edge: Edge): string {
  const { from, to } = edge
  if (edge.down) {
    const bend = (to.y - from.y) / 2
    return `M${from.x},${from.y} C${from.x},${from.y + bend} ${to.x},${to.y - bend} ${to.x},${to.y}`
  }
  const reach = Math.max(GAP_X / 2, Math.abs(to.x - from.x) / 2)
  return `M${from.x},${from.y} C${from.x + reach},${from.y} ${to.x - reach},${to.y} ${to.x},${to.y}`
}

/** The size of the canvas that holds every step with room to add one. */
export function extentOf(dialogue: Dialogue): { width: number; height: number } {
  let width = 0
  let height = 0
  for (const step of dialogue.steps) {
    width = Math.max(width, step.x + NODE_WIDTH)
    height = Math.max(height, step.y + boxHeight(step))
  }
  return { width: width + NODE_WIDTH / 2, height: height + MARGIN * 3 }
}

/** Whether two steps cover each other on the canvas, as steps saved without a place do. */
export function overlapping(dialogue: Dialogue): boolean {
  return dialogue.steps.some((a, index) =>
    dialogue.steps
      .slice(index + 1)
      .some(
        (b) => a.x < b.x + NODE_WIDTH && b.x < a.x + NODE_WIDTH && a.y < b.y + boxHeight(b) && b.y < a.y + boxHeight(a),
      ),
  )
}

/**
 * Arranges every step: a column per number of steps from the start, each column from the top in the order a player
 * meets its steps. The steps nothing leads to stand in the first column, under the start.
 */
export function arranged(npc: Npc): Npc {
  const bottoms = new Map<number, number>()
  const places = new Map<string, Point>()
  for (const { step, depth } of ordered(npc.dialogue)) {
    const y = bottoms.get(depth) ?? MARGIN
    places.set(step.id, { x: MARGIN + depth * (NODE_WIDTH + GAP_X), y })
    bottoms.set(depth, y + boxHeight(step) + GAP_Y)
  }
  return withSteps(
    npc,
    npc.dialogue.steps.map((step) => ({ ...step, ...places.get(step.id) })),
  )
}
