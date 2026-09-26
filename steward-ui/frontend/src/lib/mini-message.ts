import type { MessageArg } from "@/lib/api"
import { tokenOf } from "@/lib/message-text"

/**
 * A MiniMessage text read far enough to draw it on one line: colours, the decorations, and the
 * placeholders the key declares. Everything else - click, hover, gradient, lang - is dropped and
 * what it wraps is kept, because a preview that shows the words in roughly the right colour is
 * what a row in a list of keys has room for. The editor is where a text is read in full.
 */

export type PreviewStyle = {
  colour?: string
  bold?: boolean
  italic?: boolean
  underlined?: boolean
  strikethrough?: boolean
  obfuscated?: boolean
}

export type PreviewSegment = { kind: "placeholder"; name: string } | ({ kind: "text"; text: string } & PreviewStyle)

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

const DECORATIONS: Record<string, keyof PreviewStyle> = {
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

/** What an opening tag does to the style, and the name its closing tag will use. */
function opening(tag: string): { name: string; apply: (style: PreviewStyle) => PreviewStyle } | null {
  const [head, ...rest] = tag.split(":")
  const lower = head.toLowerCase()
  if (HEX.test(head)) return { name: lower, apply: (style) => ({ ...style, colour: head }) }
  if (lower in NAMED_COLOURS) return { name: lower, apply: (style) => ({ ...style, colour: NAMED_COLOURS[lower] }) }
  if (lower === "color" || lower === "colour" || lower === "c") {
    const value = rest[0] ?? ""
    const colour = HEX.test(value) ? value : NAMED_COLOURS[value.toLowerCase()]
    return colour ? { name: "color", apply: (style) => ({ ...style, colour }) } : null
  }
  const negated = lower.startsWith("!")
  const decoration = DECORATIONS[negated ? lower.slice(1) : lower]
  if (decoration) {
    const on = negated ? false : rest[0] !== "false"
    return { name: negated ? lower.slice(1) : lower, apply: (style) => ({ ...style, [decoration]: on }) }
  }
  return null
}

/** Closing tags name their opener by any of its spellings: `</color>` also closes a `<#hex>`. */
function closes(frame: string, closing: string): boolean {
  const lower = closing.toLowerCase()
  if (frame === lower) return true
  if (lower === "color" || lower === "colour" || lower === "c")
    return frame === "color" || frame.startsWith("#") || frame in NAMED_COLOURS
  const decoration = DECORATIONS[lower]
  return decoration !== undefined && DECORATIONS[frame] === decoration
}

export function previewSegments(source: string, args: MessageArg[]): PreviewSegment[] {
  const placeholders = new Map(args.map((arg) => [tokenOf(arg), arg.name.replace(/^_/, "")]))
  const out: PreviewSegment[] = []
  const stack: { name: string; style: PreviewStyle }[] = []
  const style = () => stack[stack.length - 1]?.style ?? {}

  function text(value: string) {
    if (!value) return
    const current = style()
    const last = out[out.length - 1]
    if (last?.kind === "text" && sameStyle(last, current)) last.text += value
    else out.push({ kind: "text", text: value, ...clean(current) })
  }

  let index = 0
  let pending = ""
  while (index < source.length) {
    const char = source[index]
    if (char === "\\" && (source[index + 1] === "<" || source[index + 1] === "\\")) {
      pending += source[index + 1]
      index += 2
      continue
    }
    if (char === "{") {
      const end = source.indexOf("}", index)
      const token = end > index ? source.slice(index, end + 1) : ""
      const name = placeholders.get(token)
      if (name !== undefined) {
        text(pending)
        pending = ""
        out.push({ kind: "placeholder", name })
        index = end + 1
        continue
      }
    }
    if (char === "<") {
      const end = tagEnd(source, index)
      if (end > index) {
        const tag = source.slice(index + 1, end)
        text(pending)
        pending = ""
        index = end + 1
        const name = placeholders.get(`<${tag}>`)
        if (name !== undefined) {
          out.push({ kind: "placeholder", name })
        } else if (tag.startsWith("/")) {
          const closing = tag.slice(1).split(":")[0]
          const at = findLast(stack, (frame) => closes(frame.name, closing))
          if (at >= 0) stack.length = at
        } else if (tag === "reset") {
          stack.length = 0
        } else if (tag === "newline" || tag === "br") {
          text(" ")
        } else {
          const open = opening(tag)
          if (open) stack.push({ name: open.name, style: open.apply(style()) })
        }
        continue
      }
    }
    pending += char
    index += 1
  }
  text(pending)
  return out
}

/** The `>` that ends the tag at `start`, skipping quoted arguments; -1 when the `<` opens nothing. */
function tagEnd(source: string, start: number): number {
  let quote: string | null = null
  for (let index = start + 1; index < source.length; index += 1) {
    const char = source[index]
    if (quote) {
      if (char === quote) quote = null
    } else if (char === "'" || char === '"') {
      quote = char
    } else if (char === ">") {
      return index === start + 1 ? -1 : index
    } else if (char === "<" || char === "\n") {
      return -1
    }
  }
  return -1
}

function findLast<T>(items: T[], test: (item: T) => boolean): number {
  for (let index = items.length - 1; index >= 0; index -= 1) if (test(items[index])) return index
  return -1
}

function clean(style: PreviewStyle): PreviewStyle {
  return Object.fromEntries(Object.entries(style).filter(([, value]) => value !== undefined && value !== false))
}

function sameStyle(a: PreviewStyle, b: PreviewStyle): boolean {
  const left = clean(a)
  const right = clean(b)
  const keys = new Set([...Object.keys(left), ...Object.keys(right)].filter((key) => key !== "kind" && key !== "text"))
  return [...keys].every((key) => left[key as keyof PreviewStyle] === right[key as keyof PreviewStyle])
}
