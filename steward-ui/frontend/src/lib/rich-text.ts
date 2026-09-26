import type { MessageArg } from "@/lib/api"
import { NAMED_COLOURS } from "@/lib/mini-message"
import { tokenOf } from "@/lib/message-text"

/**
 * A message as a list of runs, the one model all three translation editors edit: each run is a
 * piece of text, a placeholder, a glyph or a line break, and carries its whole style itself. Tags
 * exist only at the two edges - `parse` reads them into runs, `serialize` writes the fewest tags
 * that say the same thing back out - so an editor never has to know how a style is spelled.
 *
 * The same runs serve all three formats. MiniMessage uses every field; Discord markdown uses the
 * decorations, `code` and an `open_url` click as a link; plain text uses none.
 */

export type Format = "MINIMESSAGE" | "DISCORD_MARKDOWN" | "PLAIN"

export type ClickAction = "open_url" | "run_command" | "suggest_command" | "copy_to_clipboard"

export type Click = { action: ClickAction; value: string }

export type Style = {
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
}

export type Run =
  | { kind: "text"; text: string; style: Style }
  | { kind: "placeholder"; name: string; style: Style }
  | { kind: "glyph"; name: string; style: Style }
  | { kind: "break"; style: Style }
  /** A tag no editor offers (`<rainbow>`, `<lang:…>`), kept exactly as written. */
  | { kind: "raw"; source: string; style: Style }

export const DECORATIONS = ["bold", "italic", "underlined", "strikethrough", "obfuscated"] as const
export type Decoration = (typeof DECORATIONS)[number]

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
    link: discord,
    glyph: mini,
    breaks: format !== "PLAIN",
  }
}

export function formatOf(value: string | undefined): Format {
  return value === "DISCORD_MARKDOWN" || value === "PLAIN" ? value : "MINIMESSAGE"
}

// --- parsing -------------------------------------------------------------------------------

export function parse(source: string, format: Format, args: MessageArg[]): Run[] {
  if (format === "DISCORD_MARKDOWN") return normalize(parseMarkdown(source, args))
  if (format === "PLAIN") return normalize(parsePlain(source, args))
  return normalize(parseMini(source, args))
}

function placeholderTokens(args: MessageArg[]): Map<string, string> {
  return new Map(args.map((arg) => [tokenOf(arg), arg.name]))
}

function parsePlain(source: string, args: MessageArg[]): Run[] {
  const tokens = placeholderTokens(args)
  const out: Run[] = []
  let pending = ""
  const flush = () => {
    if (pending) out.push({ kind: "text", text: pending, style: {} })
    pending = ""
  }
  for (let index = 0; index < source.length;) {
    const char = source[index]
    if (char === "\n") {
      flush()
      out.push({ kind: "break", style: {} })
      index += 1
      continue
    }
    if (char === "{") {
      const end = source.indexOf("}", index)
      const name = end > index ? tokens.get(source.slice(index, end + 1)) : undefined
      if (name !== undefined) {
        flush()
        out.push({ kind: "placeholder", name, style: {} })
        index = end + 1
        continue
      }
    }
    pending += char
    index += 1
  }
  flush()
  return out
}

type Frame = { name: string; style: Style }

function parseMini(source: string, args: MessageArg[]): Run[] {
  const tokens = placeholderTokens(args)
  const out: Run[] = []
  const stack: Frame[] = []
  const style = (): Style => stack[stack.length - 1]?.style ?? {}
  let pending = ""
  const flush = () => {
    if (pending) out.push({ kind: "text", text: pending, style: style() })
    pending = ""
  }

  for (let index = 0; index < source.length;) {
    const char = source[index]
    if (char === "\\" && (source[index + 1] === "<" || source[index + 1] === "\\")) {
      pending += source[index + 1]
      index += 2
      continue
    }
    if (char === "{") {
      const end = source.indexOf("}", index)
      const name = end > index ? tokens.get(source.slice(index, end + 1)) : undefined
      if (name !== undefined) {
        flush()
        out.push({ kind: "placeholder", name, style: style() })
        index = end + 1
        continue
      }
    }
    if (char === "<") {
      const end = tagEnd(source, index)
      if (end > index) {
        const tag = source.slice(index + 1, end)
        flush()
        index = end + 1
        const placeholder = tokens.get(`<${tag}>`)
        if (placeholder !== undefined) {
          out.push({ kind: "placeholder", name: placeholder, style: style() })
          continue
        }
        const parts = splitArgs(tag)
        const head = parts[0].toLowerCase()
        if (tag.startsWith("/")) {
          const closing = tag.slice(1).split(":")[0].toLowerCase()
          const at = findLast(stack, (frame) => closes(frame.name, closing))
          if (at >= 0) stack.length = at
          else if (!KNOWN.has(closing.replace(/^!/, "")) && !closing.startsWith("#"))
            out.push({ kind: "raw", source: `<${tag}>`, style: style() })
          continue
        }
        if (head === "reset") {
          stack.length = 0
          continue
        }
        if (head === "newline" || head === "br") {
          out.push({ kind: "break", style: style() })
          continue
        }
        if (head === "glyph" && parts[1]) {
          out.push({ kind: "glyph", name: parts[1], style: style() })
          continue
        }
        const frame = opening(parts, args)
        if (frame) stack.push({ name: frame.name, style: frame.apply(style()) })
        else out.push({ kind: "raw", source: `<${tag}>`, style: style() })
        continue
      }
    }
    pending += char
    index += 1
  }
  flush()
  return out
}

const KNOWN = new Set([
  "color",
  "colour",
  "c",
  "gradient",
  "hover",
  "click",
  ...Object.keys(NAMED_COLOURS),
  "bold",
  "b",
  "italic",
  "i",
  "em",
  "underlined",
  "u",
  "strikethrough",
  "st",
  "obfuscated",
  "obf",
])

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

function opening(parts: string[], args: MessageArg[]): { name: string; apply: (style: Style) => Style } | null {
  const [head, ...rest] = parts
  const lower = head.toLowerCase()
  if (HEX.test(head)) return { name: lower, apply: (style) => ({ ...style, colour: head, gradient: undefined }) }
  if (lower in NAMED_COLOURS)
    return { name: lower, apply: (style) => ({ ...style, colour: lower, gradient: undefined }) }
  if (lower === "color" || lower === "colour" || lower === "c") {
    const value = rest[0] ?? ""
    if (!isColour(value)) return null
    const colour = HEX.test(value) ? value : value.toLowerCase()
    return { name: "color", apply: (style) => ({ ...style, colour, gradient: undefined }) }
  }
  if (lower === "gradient") {
    const colours = rest.filter(isColour).map((value) => (HEX.test(value) ? value : value.toLowerCase()))
    if (colours.length < 2 || colours.length !== rest.length) return null
    return { name: "gradient", apply: (style) => ({ ...style, gradient: colours, colour: undefined }) }
  }
  if (lower === "hover" && rest[0]?.toLowerCase() === "show_text" && rest.length === 2) {
    const hover = normalize(parseMini(rest[1], args))
    return { name: "hover", apply: (style) => ({ ...style, hover }) }
  }
  if (lower === "click" && rest.length === 2 && CLICK_ACTIONS.has(rest[0].toLowerCase())) {
    const click: Click = { action: rest[0].toLowerCase() as ClickAction, value: rest[1] }
    return { name: "click", apply: (style) => ({ ...style, click }) }
  }
  const negated = lower.startsWith("!")
  const decoration = DECORATION_NAMES[negated ? lower.slice(1) : lower]
  if (decoration) {
    const on = negated ? false : rest[0] !== "false"
    return { name: negated ? lower.slice(1) : lower, apply: (style) => ({ ...style, [decoration]: on }) }
  }
  return null
}

const CLICK_ACTIONS = new Set<string>(["open_url", "run_command", "suggest_command", "copy_to_clipboard"])

function closes(frame: string, closing: string): boolean {
  const lower = closing.replace(/^!/, "")
  if (frame === lower) return true
  if (lower === "color" || lower === "colour" || lower === "c")
    return frame === "color" || frame.startsWith("#") || frame in NAMED_COLOURS
  const decoration = DECORATION_NAMES[lower]
  return decoration !== undefined && DECORATION_NAMES[frame] === decoration
}

/** A tag's parts, split at `:` outside quotes, with the quotes and their escapes taken off. */
function splitArgs(tag: string): string[] {
  const parts: string[] = []
  let current = ""
  let quote: string | null = null
  for (let index = 0; index < tag.length; index += 1) {
    const char = tag[index]
    if (quote) {
      if (char === "\\" && (tag[index + 1] === quote || tag[index + 1] === "\\")) {
        current += tag[index + 1]
        index += 1
      } else if (char === quote) quote = null
      else current += char
    } else if (char === "'" || char === '"') quote = char
    else if (char === ":") {
      parts.push(current)
      current = ""
    } else current += char
  }
  parts.push(current)
  return parts
}

/** The `>` that ends the tag at `start`, skipping quoted arguments; -1 when the `<` opens nothing. */
function tagEnd(source: string, start: number): number {
  let quote: string | null = null
  for (let index = start + 1; index < source.length; index += 1) {
    const char = source[index]
    if (quote) {
      if (char === "\\") index += 1
      else if (char === quote) quote = null
    } else if (char === "'" || char === '"') {
      quote = char
    } else if (char === ">") {
      return index === start + 1 ? -1 : index
    } else if (char === "<" || char === "\n" || char === " ") {
      return -1
    }
  }
  return -1
}

function findLast<T>(items: T[], test: (item: T) => boolean): number {
  for (let index = items.length - 1; index >= 0; index -= 1) if (test(items[index])) return index
  return -1
}

const MARKS: [string, keyof Style][] = [
  ["**", "bold"],
  ["__", "underlined"],
  ["~~", "strikethrough"],
  ["*", "italic"],
  ["_", "italic"],
]

function parseMarkdown(source: string, args: MessageArg[]): Run[] {
  const tokens = placeholderTokens(args)
  const out: Run[] = []
  let style: Style = {}
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
    if (char === "\n") {
      flush()
      out.push({ kind: "break", style })
      index += 1
      continue
    }
    if (char === "{") {
      const end = source.indexOf("}", index)
      const name = end > index ? tokens.get(source.slice(index, end + 1)) : undefined
      if (name !== undefined) {
        flush()
        out.push({ kind: "placeholder", name, style })
        index = end + 1
        continue
      }
    }
    if (char === "`") {
      const end = source.indexOf("`", index + 1)
      if (end > index + 1) {
        flush()
        const inner = parsePlain(source.slice(index + 1, end), args)
        for (const run of inner) out.push({ ...run, style: { ...style, code: true } } as Run)
        index = end + 1
        continue
      }
    }
    if (char === "[") {
      const link = /^\[([^\]\n]+)\]\(([^)\s]+)\)/.exec(source.slice(index))
      if (link) {
        flush()
        const click: Click = { action: "open_url", value: link[2] }
        for (const run of parseMarkdown(link[1], args))
          out.push({ ...run, style: { ...run.style, ...style, click } } as Run)
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

// --- serializing ---------------------------------------------------------------------------

export function serialize(runs: Run[], format: Format, args: MessageArg[]): string {
  const tokens = new Map(args.map((arg) => [arg.name, tokenOf(arg)]))
  if (format === "PLAIN") return runs.map((run) => leaf(run, tokens, format)).join("")
  if (format === "DISCORD_MARKDOWN") return group(runs, MARKDOWN_LEVELS, 0, tokens, format)
  return group(runs, MINI_LEVELS, 0, tokens, format)
}

type Level = {
  of: (style: Style) => unknown
  open: (value: never, format: Format, tokens: Map<string, string>) => string
  close: (value: never) => string
}

const decorationLevel = (name: Decoration): Level => ({
  of: (style) => style[name],
  open: (value: boolean) => (value ? `<${name}>` : `<${name}:false>`),
  close: () => `</${name}>`,
})

const MINI_LEVELS: Level[] = [
  {
    of: (style) => style.click,
    open: (click: Click) => `<click:${click.action}:'${quote(click.value)}'>`,
    close: () => "</click>",
  },
  {
    of: (style) => style.hover,
    open: (hover: Run[], format, tokens) =>
      `<hover:show_text:'${quote(group(hover, MINI_LEVELS, 0, tokens, format))}'>`,
    close: () => "</hover>",
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

function group(runs: Run[], levels: Level[], depth: number, tokens: Map<string, string>, format: Format): string {
  if (depth >= levels.length) return runs.map((run) => leaf(run, tokens, format)).join("")
  const level = levels[depth]
  let out = ""
  let start = 0
  while (start < runs.length) {
    const value = level.of(runs[start].style)
    let end = start + 1
    // A break carries no style of its own worth splitting a tag over.
    while (end < runs.length && same(level.of(runs[end].style), value)) end += 1
    const inner = group(runs.slice(start, end), levels, depth + 1, tokens, format)
    out +=
      value === undefined ? inner : level.open(value as never, format, tokens) + inner + level.close(value as never)
    start = end
  }
  return out
}

function leaf(run: Run, tokens: Map<string, string>, format: Format): string {
  switch (run.kind) {
    case "text":
      return format === "MINIMESSAGE"
        ? run.text.replace(/\\/g, "\\\\").replace(/</g, "\\<")
        : format === "DISCORD_MARKDOWN"
          ? run.text.replace(/([\\*_~`[\]])/g, "\\$1")
          : run.text
    case "placeholder":
      return tokens.get(run.name) ?? `{${run.name}}`
    case "glyph":
      return `<glyph:${run.name}>`
    case "break":
      return format === "MINIMESSAGE" ? "<newline>" : "\n"
    case "raw":
      return run.source
  }
}

function quote(value: string): string {
  return value.replace(/\\/g, "\\\\").replace(/'/g, "\\'")
}

// --- editing -------------------------------------------------------------------------------

export function same(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a === undefined || b === undefined || a === null || b === null) return false
  if (typeof a !== "object" || typeof b !== "object") return false
  return JSON.stringify(a) === JSON.stringify(b)
}

export function cleanStyle(style: Style): Style {
  return Object.fromEntries(Object.entries(style).filter(([, value]) => value !== undefined)) as Style
}

/** Adjacent text runs of one style merged, empty ones dropped, undefined fields gone. */
export function normalize(runs: Run[]): Run[] {
  const out: Run[] = []
  for (const run of runs) {
    const style = cleanStyle(run.style)
    if (run.kind === "text" && run.text === "") continue
    const last = out[out.length - 1]
    if (run.kind === "text" && last?.kind === "text" && same(last.style, style)) {
      out[out.length - 1] = { ...last, text: last.text + run.text }
    } else out.push({ ...run, style } as Run)
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
    index >= first.index && index < second.index ? ({ ...run, style: change(run.style) } as Run) : run,
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
  const placed = inserted.map((run) => (Object.keys(run.style).length === 0 ? ({ ...run, style } as Run) : run))
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
  return cleanStyle(
    Object.fromEntries(
      Object.entries(first).map(([key, value]) => [
        key,
        rest.every((style) => same(style[key as keyof Style], value)) ? value : undefined,
      ]),
    ) as Style,
  )
}

/** The plain characters of the runs, with placeholders as their names - for counting and search. */
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

// --- colour --------------------------------------------------------------------------------

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
