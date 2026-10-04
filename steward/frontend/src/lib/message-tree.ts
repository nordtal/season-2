/**
 * The browser's reading of a message text: Java's `Parser` line by line, into the tree `NodeJson` writes.
 *
 * It exists once, for the editor, which has to read a text while it is typed. Steward's own texts arrive parsed, and
 * whether a text is right is still the server's `MessageCheck`. `web-target.json` holds both parsers to one tree.
 */
import type { TextNode } from "@/lib/texts"

/** A text that cannot be read, and where: `at` counts characters from 0, as the caret does. */
export class UnreadableText extends Error {
  readonly at: number

  constructor(at: number) {
    super(`unreadable at ${at}`)
    this.at = at
  }
}

/** The tree of `text`, or {@link UnreadableText} where it cannot be read; `markup` reads MiniMessage tags too. */
export function parseText(text: string, markup: boolean): TextNode[] {
  const reader = new Reader(text, markup)
  const nodes = reader.sequence(null, false)
  if (reader.at < text.length) throw new UnreadableText(reader.at)
  return nodes
}

/** The tree of `text`, or `null` while it cannot be read. */
export function readText(text: string, markup: boolean): TextNode[] | null {
  try {
    return parseText(text, markup)
  } catch (error) {
    if (error instanceof UnreadableText) return null
    throw error
  }
}

/**
 * One piece of syntax the reader passed, `from` inclusive and `to` exclusive: what the source editor colours.
 *
 * A tag's marks include the values in its arguments, which have marks of their own as well.
 */
export type Mark = { from: number; to: number; kind: "value" | "choice" | "tag" | "escape" | "pound"; node?: TextNode }

/** The syntax of `text` as far as it reads, and where reading stopped, `null` when it read to the end. */
export function marksOf(text: string, markup: boolean): { marks: Mark[]; error: number | null } {
  const reader = new Reader(text, markup)
  try {
    reader.sequence(null, false)
    return { marks: reader.marks, error: reader.at < text.length ? reader.at : null }
  } catch (error) {
    if (error instanceof UnreadableText) return { marks: reader.marks, error: error.at }
    throw error
  }
}

const LETTER = /\p{L}/u
const DIGIT = /\p{Nd}/u
const TAG_NAME = /[a-zA-Z0-9_-]/

/** Java's `Character.isWhitespace`: the controls it counts and every space but the non-breaking ones. */
function isSpace(c: string): boolean {
  const code = c.charCodeAt(0)
  return (
    (code >= 0x09 && code <= 0x0d) ||
    (code >= 0x1c && code <= 0x20) ||
    code === 0x1680 ||
    (code >= 0x2000 && code <= 0x2006) ||
    (code >= 0x2008 && code <= 0x200a) ||
    code === 0x2028 ||
    code === 0x2029 ||
    code === 0x205f ||
    code === 0x3000
  )
}

class Reader {
  at = 0
  readonly marks: Mark[] = []
  private readonly text: string
  private readonly markup: boolean

  constructor(text: string, markup: boolean) {
    this.text = text
    this.markup = markup
  }

  /** Reads until the end, or until the `}` that closes a case. */
  sequence(plural: string | null, inCase: boolean): TextNode[] {
    const nodes: TextNode[] = []
    let literal = ""
    const flush = () => {
      if (literal) nodes.push(literal)
      literal = ""
    }
    const text = this.text
    while (this.at < text.length) {
      const c = text[this.at]
      if (c === "\\" && this.at + 1 < text.length && "{}#\\<".includes(text[this.at + 1])) {
        literal += text[this.at + 1]
        this.at += 2
        this.mark(this.at - 2, "escape")
      } else if (c === "{") {
        flush()
        nodes.push(this.placeholder(plural))
      } else if (c === "}") {
        if (!inCase) break
        flush()
        return nodes
      } else if (c === "#" && plural !== null) {
        flush()
        nodes.push({ pound: plural })
        this.at++
        this.mark(this.at - 1, "pound")
      } else if (c === "<" && this.markup) {
        const start = this.at
        const tag = this.tag()
        if (tag === null) {
          literal += c
          this.at++
        } else {
          flush()
          nodes.push(tag)
          this.mark(start, "tag", tag)
        }
      } else {
        literal += c
        this.at++
      }
    }
    if (inCase) throw new UnreadableText(this.at)
    flush()
    return nodes
  }

  /** At an opening brace: a value, a plural or a select. */
  private placeholder(plural: string | null): TextNode {
    const start = this.at
    this.at++
    const name = this.name()
    if (!name) throw new UnreadableText(start)
    this.space()
    if (this.peek() === "}") {
      this.at++
      return this.value(start, { v: name })
    }
    this.expect(",")
    this.space()
    const second = this.word()
    this.space()
    if (second === "plural" || second === "select") {
      this.expect(",")
      this.mark(start, "choice")
      return this.choice(name, second === "plural", plural, start)
    }
    if (!second) throw new UnreadableText(this.at)
    let style: string | undefined
    if (this.peek() === ",") {
      this.at++
      this.space()
      style = this.word()
      this.space()
      if (!style) throw new UnreadableText(this.at)
    }
    this.expect("}")
    return this.value(start, style === undefined ? { v: name, k: second } : { v: name, k: second, s: style })
  }

  private choice(name: string, plural: boolean, outer: string | null, start: number): TextNode {
    const cases: Record<string, TextNode[]> = {}
    for (;;) {
      this.space()
      if (this.at >= this.text.length) throw new UnreadableText(start)
      if (this.peek() === "}") {
        this.at++
        this.mark(this.at - 1, "choice")
        break
      }
      const keyStart = this.at
      const key = this.caseKey()
      if (!key) throw new UnreadableText(this.at)
      this.space()
      this.expect("{")
      this.mark(keyStart, "choice")
      if (Object.hasOwn(cases, key)) throw new UnreadableText(this.at)
      cases[key] = this.sequence(plural ? name : outer, true)
      this.at++
      this.mark(this.at - 1, "choice")
    }
    if (!Object.hasOwn(cases, "other")) throw new UnreadableText(start)
    return { c: name, plural, cases }
  }

  private caseKey(): string {
    const start = this.at
    if (this.peek() === "=") {
      this.at++
      while (this.at < this.text.length && DIGIT.test(this.text[this.at])) this.at++
      return this.text.slice(start, this.at)
    }
    return this.word()
  }

  /** At a `<`: a tag, or `null` when what follows is no tag and the character is text. */
  private tag(): TextNode | null {
    const text = this.text
    const start = this.at
    let cursor = this.at + 1
    let shape = "OPEN"
    if (cursor < text.length && text[cursor] === "/") {
      shape = "CLOSE"
      cursor++
    }
    const nameStart = cursor
    if (cursor < text.length && "#!?".includes(text[cursor])) cursor++
    while (cursor < text.length && TAG_NAME.test(text[cursor])) cursor++
    if (cursor === nameStart || cursor >= text.length) return null
    const name = text.slice(nameStart, cursor).toLowerCase()
    this.at = cursor
    const args: TextNode[][] = []
    while (this.at < text.length && text[this.at] === ":") {
      this.at++
      const arg = this.argument()
      if (arg === null) {
        this.at = start
        return null
      }
      args.push(arg)
    }
    if (this.at < text.length && text[this.at] === "/" && shape === "OPEN") {
      shape = "SELF_CLOSING"
      this.at++
    }
    if (this.at >= text.length || text[this.at] !== ">") {
      this.at = start
      return null
    }
    this.at++
    return { tag: name, shape, args }
  }

  /** One tag argument after its `:`, or `null` when the tag never closes. */
  private argument(): TextNode[] | null {
    const text = this.text
    const quote = this.at < text.length && (text[this.at] === "'" || text[this.at] === '"') ? text[this.at] : ""
    if (quote) this.at++
    const parts: TextNode[] = []
    let literal = ""
    const flush = () => {
      if (literal) parts.push(literal)
      literal = ""
    }
    while (this.at < text.length) {
      const c = text[this.at]
      if (quote && c === "\\" && this.at + 1 < text.length) {
        const next = text[this.at + 1]
        literal += (next === "{" || next === "}" ? "" : "\\") + next
        this.at += 2
        continue
      }
      if (quote && c === quote) {
        this.at++
        flush()
        return parts
      }
      const selfClosing = c === "/" && this.at + 1 < text.length && text[this.at + 1] === ">"
      if (!quote && (c === ":" || c === ">" || selfClosing)) {
        flush()
        return parts
      }
      if (c === "{") {
        flush()
        const start = this.at
        const value = this.placeholder(null)
        if (typeof value === "string" || !("v" in value)) throw new UnreadableText(start)
        parts.push(value)
        continue
      }
      literal += c
      this.at++
    }
    return null
  }

  private name(): string {
    const text = this.text
    const start = this.at
    while (this.at < text.length) {
      const c = text[this.at]
      const letter = LETTER.test(c) || c === "_"
      const inner = letter || DIGIT.test(c) || c === "-" || c === "."
      if (this.at === start ? !letter : !inner) break
      this.at++
    }
    const name = text.slice(start, this.at)
    if (name.endsWith(".") || name.includes("..")) throw new UnreadableText(start)
    return name
  }

  private word(): string {
    const text = this.text
    const start = this.at
    while (this.at < text.length) {
      const c = text[this.at]
      if (!(LETTER.test(c) || DIGIT.test(c) || c === "-" || c === "_")) break
      this.at++
    }
    return text.slice(start, this.at)
  }

  private space(): void {
    while (this.at < this.text.length && isSpace(this.text[this.at])) this.at++
  }

  /** Notes the syntax from `from` to where the reader stands. */
  private mark(from: number, kind: Mark["kind"], node?: TextNode): void {
    this.marks.push({ from, to: this.at, kind, node })
  }

  /** Notes a value from `from` to where the reader stands, and hands it back. */
  private value(from: number, node: TextNode): TextNode {
    this.mark(from, "value", node)
    return node
  }

  private peek(): string {
    return this.at < this.text.length ? this.text[this.at] : ""
  }

  private expect(c: string): void {
    if (this.peek() !== c) throw new UnreadableText(this.at)
    this.at++
  }
}

/**
 * `nodes` written back as a text that reads to the same tree: the editor's way from a changed tree to the source.
 *
 * `plural` names the number a `#` stands for where the nodes sit in a plural's case, so a literal `#` is escaped there.
 */
export function writeText(nodes: TextNode[], markup: boolean, plural: string | null = null): string {
  return nodes.map((node) => written(node, markup, plural)).join("")
}

function written(node: TextNode, markup: boolean, plural: string | null): string {
  if (typeof node === "string") return escaped(node, markup, plural !== null)
  if ("v" in node) return `{${[node.v, node.k, node.s].filter((part) => part !== undefined).join(", ")}}`
  if ("pound" in node) return "#"
  if ("c" in node) {
    const inner = node.plural ? node.c : plural
    const cases = Object.entries(node.cases).map(([key, value]) => `${key} {${writeText(value, markup, inner)}}`)
    return `{${node.c}, ${node.plural ? "plural" : "select"}, ${cases.join(" ")}}`
  }
  const args = node.args.map((arg) => `:${argument(arg)}`).join("")
  return `<${node.shape === "CLOSE" ? "/" : ""}${node.tag}${args}${node.shape === "SELF_CLOSING" ? "/" : ""}>`
}

function escaped(text: string, markup: boolean, inPlural: boolean): string {
  return text.replaceAll(/[\\{}#<]/g, (c) => {
    if (c === "#" && !inPlural) return c
    if (c === "<" && !markup) return c
    return `\\${c}`
  })
}

/** A bare word stays bare; anything else is quoted, its values written in and its braces escaped. */
function argument(parts: TextNode[]): string {
  if (parts.length === 1 && typeof parts[0] === "string" && /^[A-Za-z0-9_#.-]+$/.test(parts[0])) return parts[0]
  const inner = argumentText(parts)
  // An argument read from double quotes may hold a single one, which it keeps.
  const quote = /(^|[^\\])'/.test(inner) ? '"' : "'"
  return `${quote}${inner}${quote}`
}

/**
 * A tag argument's parts as the text inside its quotes: literals as they stand, braces escaped, values as written.
 *
 * A hover's argument is a text of its own, which this reads back to its tree; a click's is the value it carries.
 */
export function argumentText(parts: TextNode[]): string {
  return parts
    .map((part) => (typeof part === "string" ? part.replaceAll(/[{}]/g, (c) => `\\${c}`) : written(part, true, null)))
    .join("")
}
