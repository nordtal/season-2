/**
 * The web target. It renders every text that Steward's page shows from the trees Steward parsed. The validator is Java's, and `messages/src/test/resources/web-target.json` holds both sides to one result.
 */
import { api } from "@/lib/api"
import { LOCALE, date, dateTime, money, parseInstant, relative, time, NO_VALUE } from "@/lib/format"
import packaged from "@/lib/texts.gen.json"
import type { TextArgs } from "@/lib/texts.gen"

export type TextNode =
  | string
  | { v: string; k?: string; s?: string }
  | { c: string; plural: boolean; cases: Record<string, TextNode[]> }
  | { pound: string }
  | { b: string; with?: Record<string, string> }
  | { tag: string; shape: string; args: TextNode[][] }

export type Texts = Record<string, TextNode[][]>

/** What a value is written in: a locale's formats and the `values.*` words of its language. */
export type Words = { locale: string; texts: Texts }

export type Typed =
  | { kind: "text"; value: string }
  | { kind: "number"; value: number }
  | { kind: "duration"; value: number }
  | { kind: "instant"; value: string }
  | { kind: "money"; value: { minor: number; currency: string } }
  | { kind: "list"; value: Typed[] }
  | { kind: "name"; value: { player: string; name: string } }
  | { kind: "mention"; value: { member: string; name: string } }
  | { kind: "item"; value: { key: string; english: string; args?: Typed[] } }
  | { kind: "glyph"; value: string }
  | { kind: "choice"; value: boolean | string }
  | { kind: "message"; value: MessageRef }

export type Kind = Typed["kind"]

/**
 * What a call may pass for a value of each kind. A bare value takes the kind its text declares.
 */
export type Arg = {
  text: string | Typed
  number: number | Typed
  duration: number | Typed
  instant: string | Date | Typed
  money: { minor: number; currency: string } | Typed
  list: Array<string | number | Typed> | Typed
  name: { player: string; name: string } | Typed
  mention: { member: string; name: string } | Typed
  item: { key: string; english: string } | Typed
  glyph: string | Typed
  choice: boolean | string | Typed
  message: MessageRef | Typed
}

export type MessageRef = { key: string; args?: Record<string, Typed> }

export type Run = { text: string; kind?: Kind; name?: string }

type Packaged = { kinds: Record<string, Record<string, Kind>>; texts: Texts }

function isPackaged(value: unknown): value is Packaged {
  return (
    typeof value === "object" &&
    value !== null &&
    "kinds" in value &&
    "texts" in value &&
    typeof value.kinds === "object" &&
    typeof value.texts === "object"
  )
}

if (!isPackaged(packaged)) throw new Error("texts.gen.json is not the packaged texts")

const PACKAGED: Packaged = packaged

const KINDS = new Set<string>([
  "text",
  "number",
  "duration",
  "instant",
  "money",
  "list",
  "name",
  "mention",
  "item",
  "glyph",
  "choice",
  "message",
])

const LINE_BREAKS = new Set(["newline", "br"])

const NUMBERS = new Map<string, Intl.NumberFormat>()

function numberOf(value: number, locale: string): string {
  let format = NUMBERS.get(locale)
  if (format === undefined) {
    format = new Intl.NumberFormat(locale, { maximumFractionDigits: 2 })
    NUMBERS.set(locale, format)
  }
  return format.format(value)
}

/** The packaged texts, until Steward's arrive and for good where Steward cannot be reached. */
let current: Texts = PACKAGED.texts

/**
 * Takes the texts Steward serves with an admin's overrides. A failure keeps the packaged texts.
 */
export async function loadTexts(): Promise<void> {
  try {
    current = await api<Texts>("/api/texts")
  } catch (error) {
    console.error("the texts could not be read, so the packaged ones show", error)
  }
}

export function packagedTexts(): Texts {
  return PACKAGED.texts
}

export function replaceTexts(texts: Texts): void {
  current = texts
}

type ArgsOf<K extends keyof TextArgs> = TextArgs[K] extends Record<string, never> ? [] : [TextArgs[K]]

export function t<K extends keyof TextArgs>(key: K, ...args: ArgsOf<K>): string {
  return plain(runs(key, args[0] ?? {}))
}

export function message(ref: MessageRef): string {
  return plain(runs(ref.key, ref.args ?? {}))
}

export function runs(key: string, args: Record<string, unknown>, texts: Texts = current): Run[] {
  const variants = texts[key] ?? current[key] ?? PACKAGED.texts[key]
  if (!variants || variants.length === 0) return [{ text: key }]
  const nodes = variants.length === 1 ? variants[0] : variants[Math.floor(Math.random() * variants.length)]
  const out: Run[] = []
  fill(nodes, args, PACKAGED.kinds[key] ?? {}, out, texts, new Set([key]))
  return out
}

/**
 * Returns an enum constant as a `select` names it, as Java's `Kind.choiceOf` does: `REMOVE_PLUGIN` is `remove-plugin`.
 */
export function choice(constant: string): string {
  return constant.toLowerCase().replaceAll("_", "-")
}

/**
 * Returns a span outside a message through the duration kind. Anything that is not a span shows the en dash.
 */
export function span(seconds: number | null | undefined, style: "short" | "minutes" = "short"): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return NO_VALUE
  return durationOf(seconds, style)
}

export function since(value: string | Date | null | undefined, now = Date.now()): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? NO_VALUE : span((now - parsed.getTime()) / 1000)
}

function plain(pieces: Run[]): string {
  return pieces.map((piece) => piece.text).join("")
}

function fill(
  nodes: TextNode[],
  args: Record<string, unknown>,
  declared: Record<string, Kind>,
  out: Run[],
  texts: Texts,
  open: Set<string>,
): void {
  for (const node of nodes) {
    if (typeof node === "string") {
      out.push({ text: node })
    } else if ("v" in node) {
      out.push(filled(node.v, node.k, node.s, args, declared))
    } else if ("pound" in node) {
      out.push(filled(node.pound, "number", undefined, args, declared))
    } else if ("c" in node) {
      fill(chosen(node, args[node.c]), args, declared, out, texts, open)
    } else if ("b" in node) {
      block(node, args, out, texts, open)
    } else if (LINE_BREAKS.has(node.tag)) {
      out.push({ text: "\n" })
    }
  }
}

/**
 * Fills a building block with the values around it, as Java's `Filling` does. A bound value takes the outer one and its `outer.*` attributes. A cycle or a key that is no text shows the missing word.
 */
function block(
  node: { b: string; with?: Record<string, string> },
  args: Record<string, unknown>,
  out: Run[],
  texts: Texts,
  open: Set<string>,
): void {
  const variants = texts[node.b] ?? current[node.b] ?? PACKAGED.texts[node.b]
  if (open.has(node.b) || !variants || variants.length === 0) {
    out.push({ text: missing("text", { locale: LOCALE, texts }), kind: "text", name: `@${node.b}` })
    return
  }
  const inner: Record<string, unknown> = { ...args }
  for (const [name, outer] of Object.entries(node.with ?? {})) {
    for (const held of Object.keys(inner)) if (held === name || held.startsWith(`${name}.`)) delete inner[held]
    for (const [given, value] of Object.entries(args)) {
      if (given === outer) inner[name] = value
      else if (given.startsWith(`${outer}.`)) inner[name + given.slice(outer.length)] = value
    }
  }
  fill(variants[0], inner, PACKAGED.kinds[node.b] ?? {}, out, texts, new Set([...open, node.b]))
}

function filled(
  name: string,
  written: string | undefined,
  style: string | undefined,
  args: Record<string, unknown>,
  declared: Record<string, Kind>,
): Run {
  const hint = declared[name] ?? (written !== undefined && isKind(written) ? written : undefined)
  const value = typedOf(args[name], hint)
  if (value === undefined) {
    const kind = hint ?? "text"
    return { text: missing(kind), kind, name }
  }
  return { text: shown(value, style), kind: value.kind, name }
}

/**
 * Returns a value as a placeholder in the given style draws it. A bare value takes the kind its form declares.
 * `words` writes it in a preview's language, else in the page's own.
 */
export function valueText(raw: unknown, style?: string, words?: Words): string {
  const value = typedOf(raw, undefined)
  return value === undefined ? missing("text", words) : shown(value, style, words)
}

export function chosen(node: { plural: boolean; cases: Record<string, TextNode[]> }, raw: unknown): TextNode[] {
  const other = node.cases.other
  const value = typedOf(raw, node.plural ? "number" : "choice")
  if (value === undefined) return other
  if (!node.plural) return node.cases[wordOf(value)] ?? other
  if (value.kind !== "number") return other
  return node.cases[`=${String(value.value)}`] ?? node.cases[category(value.value)] ?? other
}

/** Returns `one` for exactly 1 and `other` otherwise, as every language Steward ships does. */
function category(number: number): string {
  return number === 1 ? "one" : "other"
}

function wordOf(value: Typed): string {
  switch (value.kind) {
    case "choice":
    case "text":
    case "number":
    case "glyph":
      return String(value.value)
    default:
      return shown(value, undefined)
  }
}

function isKind(token: string): token is Kind {
  return KINDS.has(token)
}

function isTyped(raw: unknown): raw is Typed {
  return (
    typeof raw === "object" &&
    raw !== null &&
    !Array.isArray(raw) &&
    !(raw instanceof Date) &&
    "kind" in raw &&
    "value" in raw &&
    typeof raw.kind === "string" &&
    KINDS.has(raw.kind)
  )
}

function typedOf(raw: unknown, hint: Kind | undefined): Typed | undefined {
  if (raw === undefined || raw === null) return undefined
  if (isTyped(raw)) return raw
  if (raw instanceof Date)
    return Number.isNaN(raw.getTime()) ? undefined : { kind: "instant", value: raw.toISOString() }
  if (typeof raw === "boolean") return { kind: "choice", value: raw }
  if (typeof raw === "number") {
    if (!Number.isFinite(raw)) return undefined
    return hint === "duration" ? { kind: "duration", value: raw } : { kind: "number", value: raw }
  }
  if (hint === "message" && isMessageRef(raw)) return { kind: "message", value: raw }
  if (typeof raw === "string") {
    if (hint === "instant") return { kind: "instant", value: raw }
    if (hint === "choice") return { kind: "choice", value: raw }
    return { kind: "text", value: raw }
  }
  if (Array.isArray(raw)) {
    const items: Typed[] = []
    for (const item of raw) {
      const typed = typedOf(item, undefined)
      if (typed !== undefined) items.push(typed)
    }
    return { kind: "list", value: items }
  }
  return typeof raw === "object" ? typedObject(raw) : undefined
}

function isMessageRef(raw: unknown): raw is MessageRef {
  return typeof raw === "object" && raw !== null && "key" in raw && typeof raw.key === "string"
}

function typedObject(raw: object): Typed | undefined {
  if ("minor" in raw && "currency" in raw && typeof raw.minor === "number" && typeof raw.currency === "string") {
    return { kind: "money", value: { minor: raw.minor, currency: raw.currency } }
  }
  if (!("name" in raw) || typeof raw.name !== "string") {
    return "key" in raw && "english" in raw && typeof raw.key === "string" && typeof raw.english === "string"
      ? { kind: "item", value: { key: raw.key, english: raw.english } }
      : undefined
  }
  if ("player" in raw && typeof raw.player === "string") {
    return { kind: "name", value: { player: raw.player, name: raw.name } }
  }
  if ("member" in raw && typeof raw.member === "string") {
    return { kind: "mention", value: { member: raw.member, name: raw.name } }
  }
  return undefined
}

function shown(value: Typed, style: string | undefined, words?: Words): string {
  switch (value.kind) {
    case "text":
      return value.value
    case "number":
      return style === "plain" ? String(value.value) : numberOf(value.value, words?.locale ?? LOCALE)
    case "duration":
      return durationOf(value.value, style, words)
    case "instant":
      return instantOf(value.value, style, words?.locale)
    case "money":
      return money(value.value.minor, value.value.currency, words?.locale)
    case "list":
      return join(
        value.value.map((item) => shown(item, undefined, words)),
        style === "or",
        words,
      )
    case "name":
      return value.value.name
    case "mention":
      return `@${value.value.name}`
    case "item":
      return itemOf(value.value, words)
    case "glyph":
      return ""
    case "message":
      return plain(runs(value.value.key, value.value.args ?? {}, words?.texts))
    default:
      return typeof value.value === "boolean" ? word(value.value ? "choice.yes" : "choice.no", {}, words) : value.value
  }
}

function word(key: string, values: Record<string, unknown>, words?: Words): string {
  return plain(runs(`values.${key}`, values, words?.texts))
}

function missing(kind: Kind, words?: Words): string {
  return word(`missing.${kind}`, {}, words)
}

function join(shownItems: string[], or: boolean, words?: Words): string {
  if (shownItems.length === 0) return missing("list", words)
  if (shownItems.length === 1) return shownItems[0]
  return word(
    or ? "list.or" : "list.and",
    { rest: shownItems.slice(0, -1).join(", "), last: shownItems[shownItems.length - 1] },
    words,
  )
}

/**
 * Returns the two largest units that are not zero, as `ValueText` shows a duration, or a style's units.
 */
function durationOf(seconds: number, style: string | undefined, words?: Words): string {
  const total = Math.max(0, Math.trunc(seconds))
  if (style === "clock") {
    const hours = Math.floor(total / 3600)
    const minutes = String(Math.floor(total / 60) % 60).padStart(2, "0")
    const rest = String(total % 60).padStart(2, "0")
    return hours > 0 ? `${hours}:${minutes}:${rest}` : `${Math.floor(total / 60) % 60}:${rest}`
  }
  const amounts = [Math.floor(total / 86_400), Math.floor(total / 3600) % 24, Math.floor(total / 60) % 60, total % 60]
  const units = ["days", "hours", "minutes", "seconds"]
  if (style === "minutes") {
    const parts = units
      .slice(0, 3)
      .flatMap((unit, index) =>
        amounts[index] > 0 || (index === 2 && amounts[0] + amounts[1] === 0)
          ? [word(`duration.short.${unit}`, { n: amounts[index] }, words)]
          : [],
      )
    return parts.join(" ")
  }
  const prefix = style === "short" ? "duration.short." : "duration."
  const parts: string[] = []
  for (let unit = 0; unit < units.length && parts.length < 2; unit++) {
    const started = parts.length > 0
    if (amounts[unit] > 0 || (unit === units.length - 1 && !started)) {
      parts.push(word(prefix + units[unit], { n: amounts[unit] }, words))
    } else if (started) {
      break
    }
  }
  if (style === "short") return parts.join(" ")
  return parts.length === 1 ? parts[0] : word("list.and", { rest: parts[0], last: parts[1] }, words)
}

/**
 * Returns the instant in the browser's zone. `relative` reads as a span from now, which a page can show and a log cannot.
 */
function instantOf(iso: string, style: string | undefined, locale?: string): string {
  switch (style) {
    case "date":
      return date(iso, locale)
    case "time":
      return time(iso, locale)
    case "relative":
      return relative(iso, undefined, locale)
    default:
      return dateTime(iso, locale)
  }
}

function itemOf(content: { english: string; args?: Typed[] }, words?: Words): string {
  const args = (content.args ?? []).map((arg) => shown(arg, undefined, words))
  let next = 0
  return content.english.replaceAll(/%(?:(\d+)\$)?s/g, (_, position: string | undefined) => {
    const index = position === undefined ? next++ : Number(position) - 1
    return args[index] ?? ""
  })
}
