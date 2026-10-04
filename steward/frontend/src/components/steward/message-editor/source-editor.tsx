import { useMemo, useRef } from "react"
import type { CSSProperties, ReactNode } from "react"
import { cn } from "cn"

import type { GlyphInfo, MessageArg } from "@/lib/api"
import { argumentText, marksOf, type Mark } from "@/lib/message-tree"
import { capabilities, hexOf, isColour, parse, serialize } from "@/lib/rich-text"
import type { Format, Style } from "@/lib/rich-text"
import type { TextNode } from "@/lib/texts"
import type { Fill } from "@/components/steward/message-editor/examples"
import { GlyphMenu, PlaceholderMenu, StyleButtons } from "@/components/steward/message-editor/parts"
import { BreakButton, ToolDivider, Toolbar } from "@/components/steward/message-editor/tools"
import type { Tones } from "@/components/steward/message-editor/preview"

export type SourceEditorProps = {
  text: string
  onChange: (text: string) => void
  format: Format
  args: MessageArg[]
  glyphs: GlyphInfo[]
  fill: Fill
  tones: Tones
  label: string
  disabled?: boolean
}

const MONO = "font-mono text-[13px] leading-6 max-md:text-base"

/**
 * B: the stored text with its syntax coloured, a textarea over a highlighted copy of itself.
 *
 * The browser keeps caret, selection, undo and the phone keyboard; the colours are drawn underneath, from the marks
 * the one parser leaves where it read syntax, and from where it stopped reading.
 */
export function SourceEditor({
  text,
  onChange,
  format,
  args,
  glyphs,
  fill,
  tones,
  label,
  disabled,
}: SourceEditorProps) {
  const area = useRef<HTMLTextAreaElement>(null)
  const under = useRef<HTMLPreElement>(null)
  const can = capabilities(format)
  const toneTags = useMemo(() => Object.keys(tones), [tones])
  const pieces = useMemo(() => highlight(text, format === "MINIMESSAGE", tones), [text, format, tones])

  function replaceSelection(make: (selected: string) => string) {
    const element = area.current
    if (!element) return
    const { selectionStart: from, selectionEnd: to } = element
    const inserted = make(text.slice(from, to))
    onChange(text.slice(0, from) + inserted + text.slice(to))
    requestAnimationFrame(() => {
      element.focus()
      element.setSelectionRange(from + inserted.length, from + inserted.length)
    })
  }

  /** The style tools work here too, re-styling the selection through the runs when it reads on its own. */
  function restyle(change: (style: Style) => Style) {
    const element = area.current
    if (!element || element.selectionStart === element.selectionEnd) return
    const runs = parse(text.slice(element.selectionStart, element.selectionEnd), format, toneTags)
    if (runs === null) return
    replaceSelection(() =>
      serialize(
        runs.map((run) => ({ ...run, style: change(run.style) })),
        format,
      ),
    )
  }

  return (
    <div className="flex min-w-0 flex-col">
      <Toolbar label={label}>
        {args.some((arg) => !arg.action) ? (
          <PlaceholderMenu
            args={args}
            fill={fill}
            disabled={disabled}
            onPick={(arg) => replaceSelection(() => `{${arg.name}}`)}
          />
        ) : null}
        {can.glyph ? (
          <GlyphMenu glyphs={glyphs} disabled={disabled} onPick={(name) => replaceSelection(() => `<glyph:${name}>`)} />
        ) : null}
        {can.breaks ? (
          <BreakButton
            disabled={disabled}
            onPress={() => replaceSelection(() => (format === "MINIMESSAGE" ? "<newline>" : "\n"))}
          />
        ) : null}
        {format !== "PLAIN" ? (
          <>
            <ToolDivider />
            <StyleButtons
              format={format}
              style={{}}
              tones={tones}
              args={args}
              fill={fill}
              disabled={disabled}
              onStyle={restyle}
              nested
            />
          </>
        ) : null}
      </Toolbar>
      <div className="relative min-w-0">
        <pre
          ref={under}
          aria-hidden
          className={cn(MONO, "pointer-events-none m-0 min-h-24 px-2.5 py-2 break-words whitespace-pre-wrap")}
        >
          {pieces}
          {"\n"}
        </pre>
        <textarea
          ref={area}
          aria-label={label}
          data-slot="input-group-control"
          value={text}
          disabled={disabled}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          onChange={(event) => onChange(event.target.value)}
          onScroll={(event) => {
            if (under.current) under.current.scrollTop = event.currentTarget.scrollTop
          }}
          className={cn(
            MONO,
            "absolute inset-0 h-full w-full resize-none overflow-hidden bg-transparent px-2.5 py-2 break-words whitespace-pre-wrap text-transparent caret-foreground outline-none",
            "selection:bg-primary/25 selection:text-transparent",
          )}
        />
      </div>
    </div>
  )
}

/**
 * The source in coloured pieces: a colour or tone tag in its colour, other tags, values, escapes, and from where the
 * parser stopped reading, the rest in red. A mark inside another (a value in a tag's argument) keeps the outer one.
 */
export function highlight(text: string, markup: boolean, tones: Tones): ReactNode[] {
  const { marks, error } = marksOf(text, markup)
  const end = error ?? text.length
  const out: ReactNode[] = []
  let at = 0
  for (const mark of marks.toSorted((a, b) => a.from - b.from || b.to - a.to)) {
    if (mark.from < at || mark.from >= end) continue
    if (mark.from > at) out.push(text.slice(at, mark.from))
    const to = Math.min(mark.to, end)
    out.push(
      <span key={mark.from} className={MARK_CLASSES[mark.kind]} style={styleOf(mark, tones)}>
        {text.slice(mark.from, to)}
      </span>,
    )
    at = to
  }
  if (at < end) out.push(text.slice(at, end))
  if (error !== null) {
    out.push(
      <span key="error" className="rounded-sm bg-destructive/15 text-destructive underline decoration-wavy">
        {text.slice(error) || " "}
      </span>,
    )
  }
  return out
}

const MARK_CLASSES: Record<Mark["kind"], string> = {
  value: "rounded-sm bg-primary/15 text-primary",
  escape: "text-muted-foreground",
  tag: "text-chart-2",
  choice: "text-chart-4",
  pound: "text-chart-4",
}

function styleOf(mark: Mark, tones: Tones): CSSProperties | undefined {
  const node = mark.node
  if (mark.kind !== "tag" || node === undefined || typeof node !== "object" || !("tag" in node)) return undefined
  const colour = tagColour(node, tones)
  return colour ? { color: colour, fontWeight: 600 } : undefined
}

/** The colour a tag names: a tone, a colour by name or hex, `color:…`, or a gradient's first stop. */
function tagColour(node: Extract<TextNode, { tag: string }>, tones: Tones): string | null {
  const name = node.tag.toLowerCase()
  const first = node.args[0] ? argumentText(node.args[0]) : null
  if (tones[name]) return tones[name]
  if (isColour(name)) return hexOf(name)
  const named = name === "color" || name === "colour" || name === "c" || name === "gradient"
  if (named && first && isColour(first)) return hexOf(first)
  return null
}
