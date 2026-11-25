import type { Dialogue, Npc, NpcAnswer, NpcMenu, NpcStep, NpcTextMeasurement, Words } from "@/lib/api"
import { measureKey } from "@/lib/queries-npcs"

/**
 * An NPC's draft as the editor changes it: every edit answers a new NPC, and the canvas geometry follows from the steps alone.
 */

export type Language = "en" | "de"

/** English first, as the editor and problems list order the two texts. */
export const LANGUAGES: readonly Language[] = ["en", "de"]

/** The most answers a step offers, as the panel holds them. */
export const MOST_ANSWERS = 3

/** The most explicit pages a single step holds. */
export const MOST_PAGES = 20

/** The longest text the length check measures. */
export const LONGEST_TEXT = 2000

/** The longest name, as the table holds it. */
export const LONGEST_NAME = 32

/** What follows a text or an answer: the end, a menu or another step. */
export type Then = { to: "end" } | { to: "menu"; menu: string } | { to: "step"; step: string }

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
    dialogue: { start: "step-1", steps: [{ id: "step-1", x: MARGIN, y: MARGIN, pages: [NO_WORDS], answers: [] }] },
    skinModel: "CLASSIC",
    plate: { colour: "BLUE" },
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

export function setStepPageText(npc: Npc, id: string, pageIndex: number, language: Language, text: string): Npc {
  return editStep(npc, id, (step) => ({
    ...step,
    pages: step.pages.map((page, at) => (at === pageIndex ? { ...page, [language]: text } : page)),
  }))
}

/** Appends a blank explicit page to a step, up to `MOST_PAGES`. */
export function addPage(npc: Npc, id: string): Npc {
  return editStep(npc, id, (step) =>
    step.pages.length >= MOST_PAGES ? step : { ...step, pages: [...step.pages, NO_WORDS] },
  )
}

/** Removes an explicit page from a step; the last remaining page stays. */
export function removePage(npc: Npc, id: string, pageIndex: number): Npc {
  return editStep(npc, id, (step) =>
    step.pages.length <= 1 ? step : { ...step, pages: step.pages.filter((_, at) => at !== pageIndex) },
  )
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

export type Point = { x: number; y: number }

/** The canvas: every step is a card of one width, and its height follows from its rows. */
export const NODE_WIDTH = 280
export const MARGIN = 24
export const GAP_X = 80
export const GAP_Y = 24
const HEADER_BODY_HEIGHT = 112
const ROW_HEIGHT = 36
const ROW_GAP = 4

export function nodeHeight(step: NpcStep): number {
  const rows = Math.max(1, step.answers.length)
  return HEADER_BODY_HEIGHT + rows * (ROW_HEIGHT + ROW_GAP) + 8
}

function rectsOverlap(ax: number, ay: number, ah: number, bx: number, by: number, bh: number, pad = 0): boolean {
  return ax < bx + NODE_WIDTH + pad && bx < ax + NODE_WIDTH + pad && ay < by + bh + pad && by < ay + ah + pad
}

/** Nudges a single box to the nearest free integer position when it overlaps another step. */
function freePlace(others: NpcStep[], x: number, y: number, height: number): Point {
  const startX = Math.round(x)
  const startY = Math.round(y)
  const clear = (cx: number, cy: number) =>
    others.every((other) => !rectsOverlap(cx, cy, height, other.x, other.y, nodeHeight(other), GAP_Y / 2))
  if (clear(startX, startY)) return { x: startX, y: startY }
  const step = 24
  for (let ring = 1; ring <= 40; ring++) {
    let best: Point | undefined
    let bestDist = Infinity
    for (let dx = -ring; dx <= ring; dx++) {
      for (let dy = -ring; dy <= ring; dy++) {
        if (Math.abs(dx) !== ring && Math.abs(dy) !== ring) continue
        const cx = startX + dx * step
        const cy = startY + dy * step
        if (!clear(cx, cy)) continue
        const dist = Math.hypot(cx - startX, cy - startY)
        if (dist < bestDist) {
          bestDist = dist
          best = { x: cx, y: cy }
        }
      }
    }
    if (best !== undefined) return best
  }
  return { x: startX, y: startY }
}

/** Moves a step to whole pixels, allowing negative coordinates. */
export function moveStep(npc: Npc, id: string, x: number, y: number): Npc {
  return editStep(npc, id, (step) => ({ ...step, x: Math.round(x), y: Math.round(y) }))
}

/** Places a dropped step at integer coordinates, nudging only the dropped step if it overlaps another. */
export function placeStep(npc: Npc, id: string, x: number, y: number): Npc {
  const target = stepOf(npc.dialogue, id)
  if (target === undefined) return npc
  const others = npc.dialogue.steps.filter((each) => each.id !== id)
  const placed = freePlace(others, x, y, nodeHeight(target))
  return editStep(npc, id, (step) => ({ ...step, x: placed.x, y: placed.y }))
}

function freeId(dialogue: Dialogue): string {
  const taken = new Set(dialogue.steps.map((step) => step.id))
  let number = dialogue.steps.length + 1
  while (taken.has(`step-${number}`)) number++
  return `step-${number}`
}

/**
 * Adds an empty step to the right of `from` (or at the margin) and moves it down until it covers no other step.
 */
export function addStep(npc: Npc, from: string | undefined): { npc: Npc; id: string } {
  const id = freeId(npc.dialogue)
  const origin = stepOf(npc.dialogue, from)
  const x = origin === undefined ? MARGIN : origin.x + NODE_WIDTH + GAP_X
  let y = origin === undefined ? MARGIN : origin.y
  const height = nodeHeight({ id, x, y, pages: [NO_WORDS], answers: [] })
  for (let moved = true; moved;) {
    moved = false
    for (const other of npc.dialogue.steps) {
      if (rectsOverlap(x, y, height, other.x, other.y, nodeHeight(other), GAP_Y / 2)) {
        y = other.y + nodeHeight(other) + GAP_Y
        moved = true
      }
    }
  }
  const step: NpcStep = { id, x: Math.round(x), y: Math.round(y), pages: [NO_WORDS], answers: [] }
  return { npc: withSteps(npc, [...npc.dialogue.steps, step]), id }
}

/** Adds an empty step at canvas coordinates `(x, y)`, nudging if it overlaps an existing step. */
export function addStepAt(npc: Npc, x: number, y: number): { npc: Npc; id: string } {
  const id = freeId(npc.dialogue)
  const blank: NpcStep = { id, x: 0, y: 0, pages: [NO_WORDS], answers: [] }
  const placed = freePlace(npc.dialogue.steps, x, y, nodeHeight(blank))
  const step: NpcStep = { ...blank, x: placed.x, y: placed.y }
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

/** Maps each step id to its 1-based reading-order number. */
export function stepNumbers(dialogue: Dialogue): Map<string, number> {
  return new Map(ordered(dialogue).map((entry, index) => [entry.step.id, index + 1]))
}

/** Blocking problems that keep a draft from publishing or playing. */
export type Problem =
  | { problem: "name"; language: Language }
  | { problem: "place" }
  | { problem: "step-text"; language: Language; step: string; page: number }
  | { problem: "step-tall"; language: Language; step: string; page: number; limit: number }
  | { problem: "answer-text"; language: Language; step: string; answer: number }
  | { problem: "menu-unknown"; step: string; menu: string }

/** Non-blocking warnings shown in the second group of the problems list. */
export type Warning =
  | { warning: "unlinked"; step: string }
  | { warning: "step-wide"; language: Language; step: string; page: number }
  | { warning: "answer-wide"; language: Language; step: string; answer: number }

/**
 * Blocking problems of a draft, English first: NPC name and position, then each step in reading order.
 * An unregistered menu is flagged only when `menus` has loaded.
 */
export function problemsOf(npc: Npc, measured?: Map<string, NpcTextMeasurement>, menus?: NpcMenu[]): Problem[] {
  const problems: Problem[] = []
  for (const language of LANGUAGES) {
    if (npc.name[language].trim() === "") problems.push({ problem: "name", language })
  }
  if (npc.place.world.trim() === "") problems.push({ problem: "place" })
  const menuKeys = menus === undefined ? undefined : new Set(menus.map((menu) => menu.key))
  for (const { step } of ordered(npc.dialogue)) {
    step.pages.forEach((page, index) => {
      const answers = index === step.pages.length - 1 ? step.answers.length : 0
      for (const language of LANGUAGES) {
        if (page[language].trim() === "") {
          problems.push({ problem: "step-text", language, step: step.id, page: index })
        } else {
          const check = measured?.get(measureKey({ text: page[language], kind: "BODY", answers }))
          if (check !== undefined && check.lines.length > check.lineLimit) {
            problems.push({ problem: "step-tall", language, step: step.id, page: index, limit: check.lineLimit })
          }
        }
      }
    })
    step.answers.forEach((answer, answerIndex) => {
      for (const language of LANGUAGES) {
        if (answer.text[language].trim() === "") {
          problems.push({ problem: "answer-text", language, step: step.id, answer: answerIndex })
        }
      }
    })
    if (step.answers.length === 0 && step.menu !== undefined && menuKeys !== undefined && !menuKeys.has(step.menu)) {
      problems.push({ problem: "menu-unknown", step: step.id, menu: step.menu })
    }
  }
  return problems
}

/** Non-blocking warnings of a draft: unlinked steps and texts the panel cuts width-wise. */
export function warningsOf(npc: Npc, measured?: Map<string, NpcTextMeasurement>): Warning[] {
  const warnings: Warning[] = []
  for (const { step, linked } of ordered(npc.dialogue)) {
    if (!linked) warnings.push({ warning: "unlinked", step: step.id })
    step.pages.forEach((page, index) => {
      const answers = index === step.pages.length - 1 ? step.answers.length : 0
      for (const language of LANGUAGES) {
        if (page[language].trim() === "") continue
        const check = measured?.get(measureKey({ text: page[language], kind: "BODY", answers }))
        if (check !== undefined && !check.fits && check.lines.length <= check.lineLimit) {
          warnings.push({ warning: "step-wide", language, step: step.id, page: index })
        }
      }
    })
    step.answers.forEach((answer, answerIndex) => {
      for (const language of LANGUAGES) {
        if (answer.text[language].trim() === "") continue
        const check = measured?.get(measureKey({ text: answer.text[language], kind: "ANSWER", answers: 0 }))
        if (check !== undefined && !check.fits) {
          warnings.push({ warning: "answer-wide", language, step: step.id, answer: answerIndex })
        }
      }
    })
  }
  return warnings
}

/** Count of missing texts on a step across both languages (pages and answers). */
export function missingCount(step: NpcStep): number {
  let count = 0
  for (const page of step.pages) {
    for (const language of LANGUAGES) {
      if (page[language].trim() === "") count++
    }
  }
  for (const answer of step.answers) {
    for (const language of LANGUAGES) {
      if (answer.text[language].trim() === "") count++
    }
  }
  return count
}

/** A step's first page text as a short name: English, or German where there is no English. */
export function labelOf(step: NpcStep): string {
  const first = step.pages[0]
  return first ? first.en.trim() || first.de.trim() : ""
}

/** Whether two steps cover each other on the canvas, as steps saved without a place do. */
export function overlapping(dialogue: Dialogue): boolean {
  return dialogue.steps.some((a, index) =>
    dialogue.steps.slice(index + 1).some((b) => rectsOverlap(a.x, a.y, nodeHeight(a), b.x, b.y, nodeHeight(b))),
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
    bottoms.set(depth, y + nodeHeight(step) + GAP_Y)
  }
  return withSteps(
    npc,
    npc.dialogue.steps.map((step) => ({ ...step, ...places.get(step.id) })),
  )
}
