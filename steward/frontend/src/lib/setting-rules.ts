/**
 * Checks a group's values against the rules its schema declares, as the server and Steward's save check them. The
 * shared cases under `settings/src/test/resources` hold this and the Java evaluator to one result.
 */
import type { ConfigCondition, ConfigRule, SettingRule } from "@/lib/api"

/** A schema node as the evaluator reads it: the rules, the condition, the children and their order. */
export type RuleNode = {
  kind: string
  children?: Record<string, RuleNode>
  order?: string[]
  rules?: ConfigRule[]
  appliesWhen?: ConfigCondition
}

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json }

export type JsonObject = { [key: string]: Json }

/**
 * A setting that broke a declared rule. `setting` is the dotted path, which for an entry of a list is the list and
 * the field; `entries` holds the index of the entry in each list on the way, outermost first.
 */
export type Breach = { setting: string; entries: number[]; rule: SettingRule; value: string | null }

type Place = { setting: string; shown: string; entries: number[] }

function join(prefix: string, key: string): string {
  return prefix === "" ? key : `${prefix}.${key}`
}

function child(place: Place, key: string): Place {
  return { setting: join(place.setting, key), shown: join(place.shown, key), entries: place.entries }
}

function entry(place: Place, index: number): Place {
  return { setting: place.setting, shown: `${place.shown}[${index}]`, entries: [...place.entries, index] }
}

function field(place: Place, key: string): Place {
  return key === "" ? place : child(place, key)
}

function isObject(value: Json | undefined): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

/** Returns every breach of the rules `schema` declares, in the order of the schema. */
export function breaches(schema: RuleNode, values: JsonObject, skipped: ReadonlySet<string> = new Set()): Breach[] {
  const found: Breach[] = []
  walk(schema, values, { setting: "", shown: "", entries: [] }, skipped, found)
  return found
}

function walk(node: RuleNode, values: JsonObject | null, place: Place, skipped: ReadonlySet<string>, into: Breach[]) {
  const children = node.children
  if (children === undefined) return
  for (const key of node.order ?? Object.keys(children)) {
    const childNode = children[key]
    if (childNode === undefined) continue
    const at = child(place, key)
    if (skipped.has(at.setting) || !applies(childNode, values)) continue
    const value = values === null ? undefined : values[key]
    for (const rule of childNode.rules ?? []) apply(rule, value, values, at, into)
    if (isObject(value) && childNode.kind === "MAP") {
      walk(childNode, value, at, skipped, into)
    } else if (Array.isArray(value) && childNode.kind === "LIST") {
      value.forEach((item, index) => {
        if (isObject(item)) walk(childNode, item, entry(at, index), skipped, into)
      })
    }
  }
}

/** Whether the sibling an `appliesWhen` names holds one of its values, as Steward's form decides it. */
function applies(node: RuleNode, siblings: JsonObject | null): boolean {
  const condition = node.appliesWhen
  if (condition === undefined) return true
  return condition.values.includes(text(siblings === null ? undefined : siblings[condition.key]))
}

function apply(declared: ConfigRule, value: Json | undefined, siblings: JsonObject | null, at: Place, into: Breach[]) {
  const add = (place: Place, named: string | null) =>
    into.push({ setting: place.setting, entries: place.entries, rule: declared.rule, value: named })
  const limit = declared.limit ?? 0
  const list = Array.isArray(value) ? value : null
  switch (declared.rule) {
    case "REQUIRED":
      if (isEmpty(value)) add(at, null)
      return
    case "ENTRIES":
      if ((list?.length ?? 0) !== limit) add(at, String(limit))
      return
    case "MOST_ENTRIES":
      if ((list?.length ?? 0) > limit) add(at, String(limit))
      return
    case "ABOVE": {
      const sibling = declared.field ?? ""
      const number = numberOf(value)
      const other = numberOf(siblings === null ? undefined : siblings[sibling])
      if (number !== null && other !== null && number <= other) add(at, sibling)
      return
    }
    case "UNIQUE": {
      if (list === null) return
      const key = declared.field ?? ""
      const seen = new Set<string>()
      for (const [index, item] of list.entries()) {
        const held = fieldOf(item, key)
        if (held === undefined || held === null) continue
        const identity = identityOf(held)
        if (seen.has(identity)) {
          add(field(entry(at, index), key), text(held))
          return
        }
        seen.add(identity)
      }
      return
    }
    case "ASCENDING": {
      if (list === null) return
      const key = declared.field ?? ""
      let previous: number | null = null
      for (const [index, item] of list.entries()) {
        const held = numberOf(fieldOf(item, key))
        if (held !== null && previous !== null && held < previous) {
          add(field(entry(at, index), key), null)
          return
        }
        previous = held ?? previous
      }
      return
    }
    default:
      if (list !== null) list.forEach((item, index) => scalar(declared.rule, limit, item, entry(at, index), add))
      else scalar(declared.rule, limit, value, at, add)
  }
}

/** Applies a rule on one number or one text: a sign, a bound or a length. A rule that needs the game is the save's. */
function scalar(
  rule: SettingRule,
  limit: number,
  value: Json | undefined,
  at: Place,
  add: (place: Place, named: string | null) => void,
) {
  if (rule === "LONGEST") {
    if (typeof value === "string" && codePoints(value) > limit) add(at, String(limit))
    return
  }
  const number = numberOf(value)
  if (number === null) return
  if (rule === "POSITIVE" && number <= 0) add(at, null)
  else if (rule === "NOT_NEGATIVE" && number < 0) add(at, null)
  else if (rule === "AT_LEAST" && number < limit) add(at, String(limit))
  else if (rule === "AT_MOST" && number > limit) add(at, String(limit))
}

/** Counts characters as Java's `codePointCount` does, so an emoji is one. */
function codePoints(value: string): number {
  return value.match(/./gsu)?.length ?? 0
}

function fieldOf(item: Json, key: string): Json | undefined {
  if (key === "") return item
  return isObject(item) ? item[key] : undefined
}

/** Two values are the same as Java's JSON elements are equal: by number, by text, or by their whole shape. */
function identityOf(value: Json): string {
  if (typeof value === "number") return `n${value}`
  if (typeof value === "string") return `s${value}`
  return `j${JSON.stringify(value)}`
}

function isEmpty(value: Json | undefined): boolean {
  if (value === undefined || value === null) return true
  if (Array.isArray(value)) return value.length === 0
  return typeof value === "string" && value.trim() === ""
}

/** Java's number syntax, which `Double.parseDouble` reads after stripping. */
const JAVA_NUMBER = /^[+-]?(NaN|Infinity|(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?[fFdD]?)$/

/** Returns the number a value holds, or null for anything else, which a rule on a number skips. */
function numberOf(value: Json | undefined): number | null {
  if (typeof value === "number") return value
  if (typeof value !== "string") return null
  const stripped = value.trim()
  if (!JAVA_NUMBER.test(stripped)) return null
  return Number(stripped.replace(/[fFdD]$/, ""))
}

function text(value: Json | undefined): string {
  if (value === undefined || value === null) return ""
  if (typeof value === "string") return value
  if (typeof value === "number" || typeof value === "boolean") return String(value)
  return JSON.stringify(value)
}

/**
 * Walks `breach`'s path through `schema` and returns each node on it with the index of its entry, where it has one.
 * A list whose index the breach does not name ends the walk with no index.
 */
function stepsOf(schema: RuleNode, breach: Breach): { key: string; node: RuleNode; index?: number }[] {
  const steps: { key: string; node: RuleNode; index?: number }[] = []
  let node = schema
  let at = 0
  for (const key of breach.setting.split(".")) {
    const next = node.children?.[key]
    if (next === undefined) break
    node = next
    const index = node.kind === "LIST" && at < breach.entries.length ? breach.entries[at++] : undefined
    steps.push({ key, node, index })
  }
  return steps
}

/** Returns the path of `breach` with the index of each entry, such as `tiers[2].days`. */
export function shown(schema: RuleNode, breach: Breach): string {
  const steps = stepsOf(schema, breach)
  const walked = steps.map((step) => (step.index === undefined ? step.key : `${step.key}[${step.index}]`)).join(".")
  const rest = breach.setting.split(".").slice(steps.length)
  return [walked, ...rest].filter((part) => part !== "").join(".")
}

/**
 * Returns the id of the control that shows `breach`: the path with each entry's index as a segment, such as
 * `tiers.2.days`. A breach without the index of a list it passes is shown by that list.
 */
export function controlOf(schema: RuleNode, breach: Breach): string {
  const parts: string[] = []
  for (const step of stepsOf(schema, breach)) {
    parts.push(step.key)
    if (step.node.kind !== "LIST") continue
    if (step.index === undefined) return parts.join(".")
    parts.push(String(step.index))
  }
  return parts.join(".")
}
