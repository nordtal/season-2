/**
 * The web target: every text Steward's page shows, rendered in the browser from the trees Steward parsed.
 *
 * Steward parsed them; the validator is Java's. `messages/src/test/resources/web-target.json` holds both sides to one
 * result.
 */
import { api } from "@/lib/api"
import { LOCALE, date, dateTime, money, parseInstant, relative, time } from "@/lib/format"
import packaged from "@/lib/texts.gen.json"
import type { TextArgs } from "@/lib/texts.gen"

/** One node of a parsed text, as `NodeJson` writes it. */
export type TextNode =
  | string
  | { v: string; k?: string; s?: string }
  | { c: string; plural: boolean; cases: Record<string, TextNode[]> }
  | { pound: string }
  | { tag: string; shape: string; args: TextNode[][] }

/** Every key's variants, as `GET /api/texts` answers. */
export type Texts = Record<string, TextNode[][]>

/** A value with its kind, as `MessageJson` writes it into a row and onto the wire. */
export type Typed =
  | { kind: "text"; value: string }
  | { kind: "number"; value: number }
  /** Seconds. */
  | { kind: "duration"; value: number }
  /** ISO-8601. */
  | { kind: "instant"; value: string }
  | { kind: "money"; value: { minor: number; currency: string } }
  | { kind: "list"; value: Typed[] }
  | { kind: "name"; value: { player: string; name: string } }
  | { kind: "mention"; value: { member: string; name: string } }
  | { kind: "item"; value: { key: string; english: string; args?: Typed[] } }
  | { kind: "glyph"; value: string }
  | { kind: "choice"; value: boolean | string }
  /** Another message, shown for the same reader. */
  | { kind: "message"; value: MessageRef }

export type Kind = Typed["kind"]

/** What a call may pass for a value of each kind; a bare value takes the kind its text declares. */
export type Arg = {
  text: string | Typed
  number: number | Typed
  /** Seconds. */
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

/** A message as data, its key and typed values, as a journal row or a request carries one. */
export type MessageRef = { key: string; args?: Record<string, Typed> }

/** A piece of a rendered text: a literal, or a value with its kind, each drawn as its own text node. */
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

const NUMBER = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 2 })

/** The packaged texts until Steward's arrive, and for good where Steward cannot be reached. */
let current: Texts = PACKAGED.texts

/** Takes the texts Steward serves, an admin's overrides layered; a failure keeps the packaged ones. */
export async function loadTexts(): Promise<void> {
  try {
    current = await api<Texts>("/api/texts")
  } catch (error) {
    console.error("the texts could not be read, so the packaged ones show", error)
  }
}

/** The texts this build packages, which a test layers an override over. */
export function packagedTexts(): Texts {
  return PACKAGED.texts
}

/** Replaces the texts, for a test that renders an override. */
export function replaceTexts(texts: Texts): void {
  current = texts
}

type ArgsOf<K extends keyof TextArgs> = TextArgs[K] extends Record<string, never> ? [] : [TextArgs[K]]

/** A text of Steward's as the page shows it. */
export function t<K extends keyof TextArgs>(key: K, ...args: ArgsOf<K>): string {
  return plain(runs(key, args[0] ?? {}))
}

/** A message a row or a request carries, by a key the page cannot know ahead. */
export function message(ref: MessageRef): string {
  return plain(runs(ref.key, ref.args ?? {}))
}

/** A text's runs, for a page that draws its values apart from the words around them. */
export function runs(key: string, args: Record<string, unknown>): Run[] {
  const variants = current[key] ?? PACKAGED.texts[key]
  if (!variants || variants.length === 0) return [{ text: key }]
  const nodes = variants.length === 1 ? variants[0] : variants[Math.floor(Math.random() * variants.length)]
  const out: Run[] = []
  fill(nodes, args, PACKAGED.kinds[key] ?? {}, out)
  return out
}

/** An enum constant as a `select` names it, as Java's `Kind.choiceOf` does: `REMOVE_PLUGIN` is `remove-plugin`. */
export function choice(constant: string): string {
  return constant.toLowerCase().replaceAll("_", "-")
}

/**
 * A span outside a message, through the duration kind: `short` for an uptime or a run, `minutes` for play time.
 *
 * What is not a span is the en dash every cell shows for nothing.
 */
export function span(seconds: number | null | undefined, style: "short" | "minutes" = "short"): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return "\u2013"
  return durationOf(seconds, style)
}

/** How long ago an instant was, as a span rather than as "… ago". */
export function since(value: string | Date | null | undefined, now = Date.now()): string {
  const parsed = value instanceof Date ? value : parseInstant(value)
  return parsed == null ? "\u2013" : span((now - parsed.getTime()) / 1000)
}

function plain(pieces: Run[]): string {
  return pieces.map((piece) => piece.text).join("")
}

function fill(nodes: TextNode[], args: Record<string, unknown>, declared: Record<string, Kind>, out: Run[]): void {
  for (const node of nodes) {
    if (typeof node === "string") {
      out.push({ text: node })
    } else if ("v" in node) {
      out.push(filled(node.v, node.k, node.s, args, declared))
    } else if ("pound" in node) {
      out.push(filled(node.pound, "number", undefined, args, declared))
    } else if ("c" in node) {
      fill(chosen(node, args[node.c]), args, declared, out)
    } else if (LINE_BREAKS.has(node.tag)) {
      out.push({ text: "\n" })
    }
  }
}

/** A value of no kind is no value: it shows its kind's replacement word, as every target does. */
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

function chosen(node: { plural: boolean; cases: Record<string, TextNode[]> }, raw: unknown): TextNode[] {
  const other = node.cases.other
  const value = typedOf(raw, node.plural ? "number" : "choice")
  if (value === undefined) return other
  if (!node.plural) return node.cases[wordOf(value)] ?? other
  if (value.kind !== "number") return other
  return node.cases[`=${String(value.value)}`] ?? node.cases[category(value.value)] ?? other
}

/** Every language Steward ships says `one` for exactly 1 and `other` otherwise, as `PluralRules` does. */
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

/** A bare value takes the kind its text declares where it fits one, else the kind its type has. */
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

/** A bare object is known by its fields, as the wire writes each kind's value. */
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

/** Each kind's value as this target shows it; the one formatter of the browser. */
function shown(value: Typed, style: string | undefined): string {
  switch (value.kind) {
    case "text":
      return value.value
    case "number":
      return style === "plain" ? String(value.value) : NUMBER.format(value.value)
    case "duration":
      return durationOf(value.value, style)
    case "instant":
      return instantOf(value.value, style)
    case "money":
      return money(value.value.minor, value.value.currency)
    case "list":
      return join(
        value.value.map((item) => shown(item, undefined)),
        style === "or",
      )
    case "name":
      return value.value.name
    case "mention":
      return `@${value.value.name}`
    case "item":
      return itemOf(value.value)
    case "glyph":
      return ""
    case "message":
      return message(value.value)
    default:
      return typeof value.value === "boolean" ? word(value.value ? "choice.yes" : "choice.no", {}) : value.value
  }
}

/** The `values` bundle's word at `key`, as `Words` reads it. */
function word(key: string, values: Record<string, unknown>): string {
  return plain(runs(`values.${key}`, values))
}

function missing(kind: Kind): string {
  return word(`missing.${kind}`, {})
}

function join(shownItems: string[], or: boolean): string {
  if (shownItems.length === 0) return missing("list")
  if (shownItems.length === 1) return shownItems[0]
  return word(or ? "list.or" : "list.and", {
    rest: shownItems.slice(0, -1).join(", "),
    last: shownItems[shownItems.length - 1],
  })
}

/** The two largest units that are not zero, as `ValueText` shows a duration; or a style's. */
function durationOf(seconds: number, style: string | undefined): string {
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
    // Every unit down to the minute, so a span typed in days, hours and minutes reads back as it was typed.
    const parts = units
      .slice(0, 3)
      .flatMap((unit, index) =>
        amounts[index] > 0 || (index === 2 && amounts[0] + amounts[1] === 0)
          ? [word(`duration.short.${unit}`, { n: amounts[index] })]
          : [],
      )
    return parts.join(" ")
  }
  const prefix = style === "short" ? "duration.short." : "duration."
  const parts: string[] = []
  for (let unit = 0; unit < units.length && parts.length < 2; unit++) {
    const started = parts.length > 0
    if (amounts[unit] > 0 || (unit === units.length - 1 && !started)) {
      parts.push(word(prefix + units[unit], { n: amounts[unit] }))
    } else if (started) {
      break
    }
  }
  if (style === "short") return parts.join(" ")
  return parts.length === 1 ? parts[0] : word("list.and", { rest: parts[0], last: parts[1] })
}

/** In the browser's zone; `relative` reads as a span from now, which a page can show and a log cannot. */
function instantOf(iso: string, style: string | undefined): string {
  switch (style) {
    case "date":
      return date(iso)
    case "time":
      return time(iso)
    case "relative":
      return relative(iso)
    default:
      return dateTime(iso)
  }
}

/** The English line with each argument where the game's line places it: `%s` in turn, or `%2$s` by position. */
function itemOf(content: { english: string; args?: Typed[] }): string {
  const args = (content.args ?? []).map((arg) => shown(arg, undefined))
  let next = 0
  return content.english.replaceAll(/%(?:(\d+)\$)?s/g, (_, position: string | undefined) => {
    const index = position === undefined ? next++ : Number(position) - 1
    return args[index] ?? ""
  })
}
