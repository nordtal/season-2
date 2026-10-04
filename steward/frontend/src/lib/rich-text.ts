import { argumentText, readText, writeText } from "@/lib/message-tree"
import type { TextNode } from "@/lib/texts"

/**
 * A message as the translation editor edits it: runs, each carrying its whole style, read from the one parser's tree.
 *
 * Only {@link parse} and {@link serialize} know how a style is spelled; the parsing itself is `message-tree`'s.
 */

export type Format = "MINIMESSAGE" | "DISCORD_MARKDOWN" | "PLAIN"

export type ClickAction = "open_url" | "run_command" | "suggest_command" | "copy_to_clipboard"

/** What a click does; `value` is the argument as written, so it may hold values like `{player.name}`. */
export type Click = { action: ClickAction; value: string }

export type Style = {
  /** One of the palette's tones, as its tag (`good`); a packaged text names colours only so. */
  tone?: string
  /** A named colour (`gray`) or a hex one (`#4a63d8`), as it is written. */
  colour?: string
  /** Two or more colours, spread over every run that carries the same list. */
  gradient?: string[]
  /** `false` is an explicit "off" (`<!italic>`), `undefined` inherits. */
  bold?: boolean
  italic?: boolean
  underlined?: boolean
  strikethrough?: boolean
  obfuscated?: boolean
  /** Discord's inline code. */
  code?: boolean
  hover?: Run[]
  click?: Click
  /** The message's action this text triggers, `<action:name>`. */
  action?: string
}

/**
 * One piece of the text. A value keeps the kind and style it was written with; a plural, a select or a tag no editor
 * offers (`<rainbow>`, `<lang:…>`) is kept as its source and moves as one character.
 */
export type Run =
  | { kind: "text"; text: string; style: Style }
  | { kind: "placeholder"; name: string; k?: string; s?: string; style: Style }
  | { kind: "glyph"; name: string; style: Style }
  | { kind: "break"; style: Style }
  | { kind: "raw"; source: string; style: Style }

export const DECORATIONS = ["bold", "italic", "underlined", "strikethrough", "obfuscated"] as const
export type Decoration = (typeof DECORATIONS)[number]

/** Minecraft's sixteen named colours, as the client draws them. */
export const NAMED_COLOURS: Record<string, string> = {
  black: "#000000",
  dark_blue: "#0000AA",
  dark_green: "#00AA00",
  dark_aqua: "#00AAAA",
  dark_red: "#AA0000",
  dark_purple: "#AA00AA",
  gold: "#FFAA00",
  gray: "#AAAAAA",
  grey: "#AAAAAA",
  dark_gray: "#555555",
  dark_grey: "#555555",
  blue: "#5555FF",
  green: "#55FF55",
  aqua: "#55FFFF",
  red: "#FF5555",
  light_purple: "#FF55FF",
  yellow: "#FFFF55",
  white: "#FFFFFF",
}

/** What a format lets a text carry; an editor offers exactly this and nothing else. */
export function capabilities(format: Format) {
  const mini = format === "MINIMESSAGE"
  const discord = format === "DISCORD_MARKDOWN"
  return {
    colour: mini,
    gradient: mini,
    decorations: mini ? DECORATIONS : discord ? (["bold", "italic", "underlined", "strikethrough"] as const) : [],
    code: discord,
    hover: mini,
    click: mini,
    action: mini,
    link: discord,
    glyph: mini,
    breaks: format !== "PLAIN",
  }
}

export function formatOf(value: string | undefined): Format {
  return value === "DISCORD_MARKDOWN" || value === "PLAIN" ? value : "MINIMESSAGE"
}

// Parsing

/**
 * The runs of `source`, or `null` while the one parser cannot read it; `tones` are the palette's tags.
 *
 * Only a Minecraft text reads tags; Discord's and a plain one read `{…}` alone, Discord's markdown on top.
 */
export function parse(source: string, format: Format, tones: readonly string[]): Run[] | null {
  const markup = format === "MINIMESSAGE"
  const nodes = readText(source, markup)
  if (nodes === null) return null
  if (format === "MINIMESSAGE") return normalize(fromTags(nodes, new Set(tones)))
  const queue: Run[] = []
  let flat = ""
  for (const node of nodes) {
    if (typeof node === "string") flat += node.replaceAll(HOLE, "")
    else {
      queue.push(leafOf(node, false, {}))
      flat += HOLE
    }
  }
  return normalize(format === "PLAIN" ? fromPlain(flat, queue) : fromMarkdown(flat, queue, {}))
}

/** Where a value or a choice stands in the text a plain or markdown reading scans: one character, never typed. */
const HOLE = "￿"

function leafOf(node: Exclude<TextNode, string>, markup: boolean, style: Style): Run {
  if ("v" in node) return { kind: "placeholder", name: node.v, k: node.k, s: node.s, style }
  return { kind: "raw", source: writeText([node], markup), style }
}

type Frame = { name: string; style: Style }

function fromTags(nodes: TextNode[], tones: Set<string>): Run[] {
  const out: Run[] = []
  const stack: Frame[] = []
  const style = (): Style => stack.at(-1)?.style ?? {}
  for (const node of nodes) {
    if (typeof node === "string") {
      out.push({ kind: "text", text: node, style: style() })
      continue
    }
    if (!("tag" in node)) {
      out.push(leafOf(node, true, style()))
      continue
    }
    const raw: Run = { kind: "raw", source: writeText([node], true), style: style() }
    if (node.shape === "CLOSE") {
      const at = stack.findLastIndex((frame) => closes(frame.name, node.tag))
      if (at >= 0) stack.length = at
      else out.push(raw)
      continue
    }
    if (node.tag === "reset") {
      stack.length = 0
      continue
    }
    if (node.tag === "newline" || node.tag === "br") {
      out.push({ kind: "break", style: style() })
      continue
    }
    const glyph = node.tag === "glyph" && node.args.length === 1 ? literalOf(node.args[0]) : null
    if (glyph) {
      out.push({ kind: "glyph", name: glyph, style: style() })
      continue
    }
    const frame = node.shape === "OPEN" ? opening(node.tag, node.args, tones) : null
    if (frame) stack.push({ name: frame.name, style: frame.apply(style()) })
    else out.push(raw)
  }
  return out
}

function literalOf(parts: TextNode[]): string | null {
  return parts.length === 1 && typeof parts[0] === "string" ? parts[0] : parts.length === 0 ? "" : null
}

const DECORATION_NAMES: Record<string, Decoration> = {
  bold: "bold",
  b: "bold",
  italic: "italic",
  i: "italic",
  em: "italic",
  underlined: "underlined",
  u: "underlined",
  strikethrough: "strikethrough",
  st: "strikethrough",
  obfuscated: "obfuscated",
  obf: "obfuscated",
}

const HEX = /^#[0-9a-fA-F]{6}$/

export function isColour(value: string): boolean {
  return HEX.test(value) || value.toLowerCase() in NAMED_COLOURS
}

const CLICK_ACTIONS: Record<string, ClickAction> = {
  open_url: "open_url",
  run_command: "run_command",
  suggest_command: "suggest_command",
  copy_to_clipboard: "copy_to_clipboard",
}

/** What an opening tag does to the style, and the name its closing tag will use; `null` for one no editor offers. */
function opening(
  tag: string,
  args: TextNode[][],
  tones: Set<string>,
): { name: string; apply: (style: Style) => Style } | null {
  const words = args.map(literalOf)
  if (tones.has(tag) && args.length === 0) {
    return { name: tag, apply: (style) => ({ ...style, tone: tag, colour: undefined, gradient: undefined }) }
  }
  if (HEX.test(tag))
    return { name: tag, apply: (style) => ({ ...style, colour: tag, gradient: undefined, tone: undefined }) }
  if (tag in NAMED_COLOURS)
    return { name: tag, apply: (style) => ({ ...style, colour: tag, gradient: undefined, tone: undefined }) }
  if ((tag === "color" || tag === "colour" || tag === "c") && words.length === 1 && words[0] && isColour(words[0])) {
    const colour = HEX.test(words[0]) ? words[0] : words[0].toLowerCase()
    return { name: "color", apply: (style) => ({ ...style, colour, gradient: undefined, tone: undefined }) }
  }
  if (tag === "gradient") {
    const colours = words.filter((word): word is string => word !== null && isColour(word))
    if (colours.length < 2 || colours.length !== words.length) return null
    const gradient = colours.map((value) => (HEX.test(value) ? value : value.toLowerCase()))
    return { name: "gradient", apply: (style) => ({ ...style, gradient, colour: undefined, tone: undefined }) }
  }
  if (tag === "hover" && args.length === 2 && words[0] === "show_text") {
    const inner = readText(unquoted(argumentText(args[1])), true)
    if (inner === null) return null
    const hover = normalize(fromTags(inner, tones))
    return { name: "hover", apply: (style) => ({ ...style, hover }) }
  }
  if (tag === "click" && args.length === 2 && words[0] !== null && words[0] in CLICK_ACTIONS) {
    const click: Click = { action: CLICK_ACTIONS[words[0]], value: unquoted(argumentText(args[1])) }
    return { name: "click", apply: (style) => ({ ...style, click }) }
  }
  if (tag === "action" && args.length === 1 && words[0]) {
    const action = words[0]
    return { name: "action", apply: (style) => ({ ...style, action }) }
  }
  const negated = tag.startsWith("!")
  const decoration = DECORATION_NAMES[negated ? tag.slice(1) : tag]
  if (decoration && args.length <= 1) {
    const on = !negated && words[0] !== "false"
    return { name: negated ? tag.slice(1) : tag, apply: (style) => ({ ...style, [decoration]: on }) }
  }
  return null
}

/** A quoted argument as Adventure reads it: an escaped quote is a quote. */
function unquoted(text: string): string {
  return text.replaceAll(/\\(['"])/g, "$1")
}

function closes(frame: string, closing: string): boolean {
  const lower = closing.replace(/^!/, "")
  if (frame === lower) return true
  if (lower === "color" || lower === "colour" || lower === "c")
    return frame === "color" || frame.startsWith("#") || frame in NAMED_COLOURS
  const decoration = DECORATION_NAMES[lower]
  return decoration !== undefined && DECORATION_NAMES[frame] === decoration
}

/** A plain text: a line break is a break, a hole the next value. */
function fromPlain(flat: string, queue: Run[]): Run[] {
  const out: Run[] = []
  let pending = ""
  const flush = () => {
    if (pending) out.push({ kind: "text", text: pending, style: {} })
    pending = ""
  }
  for (const char of flat) {
    if (char === "\n" || char === HOLE) {
      flush()
      out.push(char === "\n" ? { kind: "break", style: {} } : queue.shift()!)
    } else pending += char
  }
  flush()
  return out
}

const MARKS: [string, keyof Style][] = [
  ["**", "bold"],
  ["__", "underlined"],
  ["~~", "strikethrough"],
  ["*", "italic"],
  ["_", "italic"],
]

/** Discord's markdown over the literal text; a hole takes the next value in the style around it. */
function fromMarkdown(source: string, queue: Run[], outer: Style): Run[] {
  const out: Run[] = []
  let style: Style = outer
  let pending = ""
  const flush = () => {
    if (pending) out.push({ kind: "text", text: pending, style })
    pending = ""
  }
  for (let index = 0; index < source.length;) {
    const char = source[index]
    if (char === "\\" && /[\\*_~`[\]]/.test(source[index + 1] ?? "")) {
      pending += source[index + 1]
      index += 2
      continue
    }
    if (char === "\n" || char === HOLE) {
      flush()
      out.push(char === "\n" ? { kind: "break", style } : { ...queue.shift()!, style })
      index += 1
      continue
    }
    if (char === "`") {
      const end = source.indexOf("`", index + 1)
      if (end > index + 1) {
        flush()
        for (const run of fromPlain(source.slice(index + 1, end), queue))
          out.push({ ...run, style: { ...style, code: true } })
        index = end + 1
        continue
      }
    }
    if (char === "[") {
      const link = /^\[([^\]\n]+)\]\(([^)\s]+)\)/.exec(source.slice(index))
      if (link) {
        flush()
        const click: Click = { action: "open_url", value: link[2] }
        out.push(...fromMarkdown(link[1], queue, { ...style, click }))
        index += link[0].length
        continue
      }
    }
    const mark = MARKS.find(([marker]) => source.startsWith(marker, index))
    if (mark) {
      const [marker, key] = mark
      const on = Boolean(style[key])
      if (on || source.indexOf(marker, index + marker.length) > index + marker.length) {
        flush()
        style = { ...style, [key]: on ? undefined : true }
        index += marker.length
        continue
      }
    }
    pending += char
    index += 1
  }
  flush()
  return out
}

// Serializing

/** The runs as the text the one parser reads back to them. */
export function serialize(runs: Run[], format: Format): string {
  if (format === "PLAIN") return runs.map((run) => leaf(run, format)).join("")
  if (format === "DISCORD_MARKDOWN") return group(runs, MARKDOWN_LEVELS, 0, format)
  return group(runs, MINI_LEVELS, 0, format)
}

type Level = {
  of: (style: Style) => unknown
  open(value: unknown): string
  close(value: unknown): string
}

const decorationLevel = (name: Decoration): Level => ({
  of: (style) => style[name],
  open: (value: boolean) => (value ? `<${name}>` : `<${name}:false>`),
  close: () => `</${name}>`,
})

/** A quoted tag argument: the text as it stands, a single quote in it escaped. */
function quoted(value: string): string {
  return `'${value.replaceAll("'", "\\'")}'`
}

const MINI_LEVELS: Level[] = [
  {
    of: (style) => style.click,
    open: (click: Click) => `<click:${click.action}:${quoted(click.value)}>`,
    close: () => "</click>",
  },
  {
    of: (style) => style.hover,
    open: (hover: Run[]) => `<hover:show_text:${quoted(group(hover, MINI_LEVELS, 0, "MINIMESSAGE"))}>`,
    close: () => "</hover>",
  },
  {
    of: (style) => style.action,
    open: (action: string) => `<action:${action}>`,
    close: () => "</action>",
  },
  {
    of: (style) => style.tone,
    open: (tone: string) => `<${tone}>`,
    close: (tone: string) => `</${tone}>`,
  },
  {
    of: (style) => style.gradient,
    open: (colours: string[]) => `<gradient:${colours.join(":")}>`,
    close: () => "</gradient>",
  },
  {
    of: (style) => (style.gradient ? undefined : style.colour),
    open: (colour: string) => `<${colour}>`,
    close: (colour: string) => `</${colour}>`,
  },
  ...DECORATIONS.map(decorationLevel),
]

const markdownLevel = (name: keyof Style, marker: string): Level => ({
  of: (style) => (style[name] ? true : undefined),
  open: () => marker,
  close: () => marker,
})

const MARKDOWN_LEVELS: Level[] = [
  {
    of: (style) => (style.click?.action === "open_url" ? style.click : undefined),
    open: () => "[",
    close: (click: Click) => `](${click.value})`,
  },
  markdownLevel("code", "`"),
  markdownLevel("bold", "**"),
  markdownLevel("italic", "*"),
  markdownLevel("underlined", "__"),
  markdownLevel("strikethrough", "~~"),
]

function group(runs: Run[], levels: Level[], depth: number, format: Format): string {
  if (depth >= levels.length) return runs.map((run) => leaf(run, format)).join("")
  const level = levels[depth]
  let out = ""
  let start = 0
  while (start < runs.length) {
    const value = level.of(runs[start].style)
    let end = start + 1
    while (end < runs.length && same(level.of(runs[end].style), value)) end += 1
    const inner = group(runs.slice(start, end), levels, depth + 1, format)
    out += value === undefined ? inner : level.open(value) + inner + level.close(value)
    start = end
  }
  return out
}

function leaf(run: Run, format: Format): string {
  switch (run.kind) {
    case "text":
      return format === "DISCORD_MARKDOWN" ? markdownText(run.text) : writeText([run.text], format === "MINIMESSAGE")
    case "placeholder":
      return writeText([{ v: run.name, k: run.k, s: run.s }], false)
    case "glyph":
      return `<glyph:${run.name}>`
    case "break":
      return format === "MINIMESSAGE" ? "<newline>" : "\n"
    case "raw":
      return run.source
    default: {
      const exhaustive: never = run
      throw new Error(`unreachable run kind: ${JSON.stringify(exhaustive)}`)
    }
  }
}

/**
 * Literal text in Discord's markdown, escaped for both readings: markdown's markers first, then the message syntax.
 *
 * A backslash stays single where the message parser keeps it anyway, so a source's `\*` is written back as it was.
 */
function markdownText(text: string): string {
  const marked = text.replaceAll(/([\\*_~`[\]])/g, "\\$1")
  return marked.replaceAll(/\\(?=[{}\\]|$)|[{}]/g, (c) => `\\${c}`)
}

// Editing

export function same(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a === undefined || b === undefined || a === null || b === null) return false
  if (typeof a !== "object" || typeof b !== "object") return false
  return JSON.stringify(a) === JSON.stringify(b)
}

export function cleanStyle(style: Style): Style {
  return Object.fromEntries(Object.entries(style).filter(([, value]) => value !== undefined))
}

/** Adjacent text runs of one style merged, empty ones dropped, undefined fields gone. */
export function normalize(runs: Run[]): Run[] {
  const out: Run[] = []
  for (const run of runs) {
    const style = cleanStyle(run.style)
    if (run.kind === "text" && run.text === "") continue
    const last = out.at(-1)
    if (run.kind === "text" && last?.kind === "text" && same(last.style, style)) {
      out[out.length - 1] = { ...last, text: last.text + run.text }
    } else out.push({ ...run, style })
  }
  return out
}

/** How many caret positions a run takes: its characters, or one for anything drawn as a unit. */
export function lengthOf(run: Run): number {
  return run.kind === "text" ? run.text.length : 1
}

export function totalLength(runs: Run[]): number {
  return runs.reduce((sum, run) => sum + lengthOf(run), 0)
}

/** The runs, split so that a run boundary falls at `offset`; returns the index of the run after it. */
function splitAt(runs: Run[], offset: number): { runs: Run[]; index: number } {
  let position = 0
  for (let index = 0; index < runs.length; index += 1) {
    const run = runs[index]
    const length = lengthOf(run)
    if (offset === position) return { runs, index }
    if (offset < position + length && run.kind === "text") {
      const cut = offset - position
      const next = [...runs]
      next.splice(index, 1, { ...run, text: run.text.slice(0, cut) }, { ...run, text: run.text.slice(cut) })
      return { runs: next, index: index + 1 }
    }
    position += length
  }
  return { runs, index: runs.length }
}

export function applyStyle(runs: Run[], from: number, to: number, change: (style: Style) => Style): Run[] {
  if (from >= to) return runs
  const first = splitAt(runs, from)
  const second = splitAt(first.runs, to)
  const next = second.runs.map((run, index) =>
    index >= first.index && index < second.index ? { ...run, style: change(run.style) } : run,
  )
  return normalize(next)
}

export function remove(runs: Run[], from: number, to: number): Run[] {
  if (from >= to) return runs
  const first = splitAt(runs, from)
  const second = splitAt(first.runs, to)
  return normalize([...second.runs.slice(0, first.index), ...second.runs.slice(second.index)])
}

/** Inserts runs at `offset`; a text run with no style of its own takes the style of what it follows. */
export function insert(runs: Run[], offset: number, inserted: Run[], inherit = true): Run[] {
  const { runs: split, index } = splitAt(runs, offset)
  const before = split[index - 1] ?? split[index]
  const style = inherit && before ? before.style : {}
  const placed = inserted.map((run) => (Object.keys(run.style).length === 0 ? { ...run, style } : run))
  return normalize([...split.slice(0, index), ...placed, ...split.slice(index)])
}

/** The style every run in the range shares, field by field. */
export function commonStyle(runs: Run[], from: number, to: number): Style {
  const covered: Style[] = []
  let position = 0
  for (const run of runs) {
    const length = lengthOf(run)
    const overlaps =
      to > from ? position < to && position + length > from : position < from && position + length >= from
    if (overlaps) covered.push(run.style)
    position += length
  }
  if (covered.length === 0) return {}
  const [first, ...rest] = covered
  const result: Style = {}
  for (const key of STYLE_KEYS) {
    if (!(key in first)) continue
    setShared(result, key, first, rest)
  }
  return cleanStyle(result)
}

const STYLE_KEYS: (keyof Style)[] = [
  "tone",
  "colour",
  "gradient",
  "bold",
  "italic",
  "underlined",
  "strikethrough",
  "obfuscated",
  "code",
  "hover",
  "click",
  "action",
]

/** Copies `first`'s value for `key` into `result`, only when every style in `rest` agrees with it. */
function setShared(result: Style, key: keyof Style, first: Style, rest: Style[]): void {
  const value = first[key]
  if (rest.every((style) => same(style[key], value))) Object.assign(result, { [key]: value })
}

/** The plain characters of the runs, with placeholders filled, for counting and search. */
export function plainText(runs: Run[], fill: (name: string) => string = (name) => `{${name}}`): string {
  return runs
    .map((run) =>
      run.kind === "text"
        ? run.text
        : run.kind === "placeholder"
          ? fill(run.name)
          : run.kind === "break"
            ? "\n"
            : run.kind === "glyph"
              ? "□"
              : "",
    )
    .join("")
}

// Colour

export function hexOf(colour: string): string {
  return HEX.test(colour) ? colour : (NAMED_COLOURS[colour.toLowerCase()] ?? "#FFFFFF")
}

function channels(hex: string): [number, number, number] {
  const value = Number.parseInt(hex.slice(1), 16)
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255]
}

/** The colour `t` of the way along a gradient, the way Adventure spreads it: evenly between stops. */
export function gradientAt(colours: string[], t: number): string {
  if (colours.length === 1) return hexOf(colours[0])
  const scaled = Math.min(Math.max(t, 0), 1) * (colours.length - 1)
  const at = Math.min(Math.floor(scaled), colours.length - 2)
  const local = scaled - at
  const [a, b] = [channels(hexOf(colours[at])), channels(hexOf(colours[at + 1]))]
  const mixed = a.map((channel, index) => Math.round(channel + (b[index] - channel) * local))
  return (
    "#" +
    mixed
      .map((channel) => channel.toString(16).padStart(2, "0"))
      .join("")
      .toUpperCase()
  )
}

/** Minecraft's text shadow: the same colour at a quarter of its brightness. */
export function shadowOf(hex: string): string {
  const [r, g, b] = channels(hexOf(hex))
  return `rgb(${r >> 2}, ${g >> 2}, ${b >> 2})`
}

/** The colour a run's style draws in: its gradient's first stop aside, a colour, else its tone, else `base`. */
export function colourOf(style: Style, tones: Record<string, string>, base: string): string {
  if (style.colour) return hexOf(style.colour)
  if (style.tone && tones[style.tone]) return tones[style.tone]
  return base
}
