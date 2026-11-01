import type { ConfigBreach, ConfigDocument, ConfigEntry } from "@/lib/api"
import { ApiError } from "@/lib/api"
import { type Draft, draftSections, draftText } from "@/components/steward/configuration"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import { problemOf } from "@/components/steward/config-controls"
import {
  type Breach,
  breaches,
  controlOf,
  type Json,
  type JsonObject,
  type RuleNode,
  unread,
} from "@/lib/setting-rules"
import { choice, t } from "@/lib/texts"

/** A schema node with the name the form shows for it, so a breach is worded with the field's label. */
type Labelled = RuleNode & { label: string; children?: Record<string, Labelled> }

/** A group as the evaluator reads it: the schema the entries describe, the values the draft leaves, the skipped paths. */
export type RuleTree = { schema: Labelled; values: JsonObject; skipped: Set<string> }

/**
 * A typed text as the save converts it. A text the field's type cannot read is marked so no rule judges it: the
 * field already shows its type, and the save refuses it.
 */
function typed(text: string, entry: ConfigEntry): Json {
  if (problemOf(entry, text) !== null) return unread()
  const stripped = text.trim()
  if (entry.type === "INTEGER" || entry.type === "DECIMAL") {
    const number = Number(stripped)
    return stripped === "" || Number.isNaN(number) ? unread() : number
  }
  if (entry.type === "BOOLEAN") return stripped === "true"
  return text
}

function nodeOf(entry: ConfigEntry): Labelled {
  const node: Labelled = {
    kind: entry.kind === "SECTIONS" ? "LIST" : entry.kind,
    label: entry.label || entry.key,
    rules: entry.rules,
    appliesWhen: entry.appliesWhen,
  }
  if (entry.kind === "SECTIONS") {
    const template = entry.template ?? []
    node.children = Object.fromEntries(template.map((field) => [field.key, nodeOf(field)]))
    node.order = template.map((field) => field.key)
  }
  return node
}

function strings(value: SectionValues[string] | undefined): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : []
}

function sectionsIn(value: SectionValues[string] | undefined): SectionValues[] {
  return Array.isArray(value)
    ? value.filter((item): item is SectionValues => typeof item === "object" && item !== null)
    : []
}

function sectionJson(template: ConfigEntry[], section: SectionValues): JsonObject {
  const values: JsonObject = {}
  for (const field of template) {
    const held = section[field.key]
    if (field.kind === "SECTIONS") {
      values[field.key] = sectionsIn(held).map((nested) => sectionJson(field.template ?? [], nested))
    } else if (field.kind === "LIST") {
      values[field.key] = strings(held).map((item) => typed(item, field))
    } else if (field.kind === "SCALAR") {
      values[field.key] = typed(typeof held === "string" ? held : "", field)
    }
  }
  return values
}

function valueOf(entry: ConfigEntry, draft: Draft): Json {
  if (entry.kind === "SECTIONS") {
    return draftSections(draft, entry).map((section) => sectionJson(entry.template ?? [], section))
  }
  if (entry.kind === "LIST") {
    const raw = draft[entry.path]
    const items = Array.isArray(raw) && raw.every((item) => typeof item === "string") ? raw : (entry.items ?? [])
    return items.map((item) => typed(item, entry))
  }
  return typed(draftText(draft, entry), entry)
}

/**
 * Builds the group from its entries and the draft over them. A secret and a value the environment holds are
 * skipped, because the form does not know them.
 */
export function ruleTree(document: ConfigDocument, draft: Draft): RuleTree {
  const schema: Labelled = { kind: "MAP", label: "", children: {}, order: [] }
  const values: JsonObject = {}
  const nodes = new Map<string, { node: Labelled; values: JsonObject }>([["", { node: schema, values }]])
  const skipped = new Set<string>()
  for (const entry of document.entries) {
    const cut = entry.path.lastIndexOf(".")
    const parent = nodes.get(cut < 0 ? "" : entry.path.slice(0, cut))
    if (parent === undefined) continue
    const node = nodeOf(entry)
    parent.node.children = { ...parent.node.children, [entry.key]: node }
    parent.node.order = [...(parent.node.order ?? []), entry.key]
    if (entry.secret || entry.environmentOverridden) skipped.add(entry.path)
    if (entry.kind === "MAP") {
      const nested: JsonObject = {}
      node.children = {}
      node.order = []
      parent.values[entry.key] = nested
      nodes.set(entry.path, { node, values: nested })
    } else if (!entry.secret) {
      parent.values[entry.key] = valueOf(entry, draft)
    }
  }
  return { schema, values, skipped }
}

/** Every breach of the declared rules in the group the draft leaves. */
export function formBreaches(tree: RuleTree): Breach[] {
  return breaches(tree.schema, tree.values, tree.skipped)
}

function nodeAt(schema: Labelled, setting: string): { node?: Labelled; parent: Labelled } {
  let parent = schema
  let node: Labelled | undefined
  for (const key of setting.split(".")) {
    if (node !== undefined) parent = node
    node = parent.children?.[key]
    if (node === undefined) break
  }
  return { node, parent }
}

/** `breach` in words, with the field's label and, for a rule that names a sibling, the sibling's label. */
export function worded(schema: Labelled, breach: Breach): string {
  const { node, parent } = nodeAt(schema, breach.setting)
  const value =
    breach.rule === "ABOVE" && breach.value !== null
      ? (parent.children?.[breach.value]?.label ?? breach.value)
      : (breach.value ?? "")
  return t("steward.settings.breach", {
    setting: node?.label ?? breach.setting,
    rule: choice(breach.rule),
    value,
  })
}

/** Returns each control's id with the first breach it shows, in words. */
export function breachesById(schema: Labelled, found: Breach[]): Map<string, string> {
  const byId = new Map<string, string>()
  for (const breach of found) {
    const id = controlOf(schema, breach)
    if (!byId.has(id)) byId.set(id, worded(schema, breach))
  }
  return byId
}

function isBreach(value: unknown): value is ConfigBreach {
  return (
    typeof value === "object" &&
    value !== null &&
    "setting" in value &&
    typeof value.setting === "string" &&
    "rule" in value &&
    typeof value.rule === "string" &&
    "entries" in value &&
    Array.isArray(value.entries) &&
    value.entries.every((index) => typeof index === "number")
  )
}

/** The broken rule a refused save names, or null for any other failure. */
export function refusalOf(error: unknown): Breach | null {
  if (!(error instanceof ApiError) || error.code !== "SETTINGS_REFUSED") return null
  const refusal = error.body?.refusal
  if (!isBreach(refusal)) return null
  return { setting: refusal.setting, entries: refusal.entries, rule: refusal.rule, value: refusal.value ?? null }
}
