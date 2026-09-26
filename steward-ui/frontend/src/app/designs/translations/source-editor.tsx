import { useRef } from "react"
import type { ReactNode } from "react"
import { cn } from "cn"

import type { GlyphInfo, MessageArg } from "@/lib/api"
import { capabilities, hexOf, isColour, parse, serialize } from "@/lib/rich-text"
import type { Format, Style } from "@/lib/rich-text"
import { BreakButton, GlyphMenu, PlaceholderMenu, StyleButtons } from "@/app/designs/translations/parts"
import type { Fill } from "@/app/designs/translations/preview"
import { tokenOf } from "@/lib/message-text"

/**
 * B: the text as it is stored, with its syntax coloured. A textarea lies over a highlighted copy
 * of itself, so the browser keeps doing everything a text field does - caret, selection, undo,
 * a phone keyboard - and the colours are only drawn underneath.
 */

export type SourceEditorProps = {
  text: string
  onChange: (text: string) => void
  format: Format
  args: MessageArg[]
  glyphs: GlyphInfo[]
  fill: Fill
  label: string
  disabled?: boolean
}

const MONO = "font-mono text-[13px] leading-6"

export function SourceEditor({ text, onChange, format, args, glyphs, fill, label, disabled }: SourceEditorProps) {
  const area = useRef<HTMLTextAreaElement>(null)
  const under = useRef<HTMLPreElement>(null)
  const can = capabilities(format)

  function replaceSelection(make: (selected: string) => string, select: "inner" | "after" = "after") {
    const element = area.current
    if (!element) return
    const { selectionStart: from, selectionEnd: to } = element
    const selected = text.slice(from, to)
    const inserted = make(selected)
    const next = text.slice(0, from) + inserted + text.slice(to)
    onChange(next)
    requestAnimationFrame(() => {
      element.focus()
      if (select === "inner" && selected) {
        const at = inserted.indexOf(selected)
        element.setSelectionRange(from + at, from + at + selected.length)
      } else {
        element.setSelectionRange(from + inserted.length, from + inserted.length)
      }
    })
  }

  /** The style row works here too: the selection is re-styled through the run model and written back. */
  function restyle(change: (style: Style) => Style) {
    const element = area.current
    if (!element) return
    const { selectionStart: from, selectionEnd: to } = element
    if (from === to) return
    const selected = text.slice(from, to)
    const runs = parse(selected, format, args).map((run) => ({ ...run, style: change(run.style) }))
    const written = serialize(runs, format, args)
    replaceSelection(() => written, "after")
  }

  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <div className="flex flex-wrap items-center gap-0.5">
        {args.length > 0 ? (
          <PlaceholderMenu
            args={args}
            fill={fill}
            disabled={disabled}
            onPick={(arg) => replaceSelection(() => tokenOf(arg))}
          />
        ) : null}
        {can.glyph ? <GlyphMenu glyphs={glyphs} onPick={(name) => replaceSelection(() => `<glyph:${name}>`)} /> : null}
        {can.breaks ? (
          <BreakButton
            disabled={disabled}
            onPress={() => replaceSelection(() => (format === "MINIMESSAGE" ? "<newline>" : "\n"))}
          />
        ) : null}
        {format !== "PLAIN" ? (
          <>
            <span aria-hidden className="mx-1 h-5 w-px bg-border" />
            <StyleButtons format={format} style={{}} disabled={disabled} onStyle={restyle} nested />
          </>
        ) : null}
      </div>
      <div className="relative min-w-0 overflow-hidden rounded-md border border-input bg-input/30 focus-within:ring-2 focus-within:ring-ring">
        <pre
          ref={under}
          aria-hidden
          className={cn(MONO, "pointer-events-none m-0 min-h-24 px-2.5 py-2 break-words whitespace-pre-wrap")}
        >
          {highlight(text, format, args)}
          {"\n"}
        </pre>
        <textarea
          ref={area}
          aria-label={label}
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

/** The source, cut into coloured pieces: tags, colour tags in their own colour, placeholders, the unknown in red. */
export function highlight(text: string, format: Format, args: MessageArg[]): ReactNode[] {
  const known = new Set(args.map((arg) => arg.name))
  const out: ReactNode[] = []
  const pattern =
    format === "MINIMESSAGE"
      ? /\\.|<\/?[^<>\s][^<>]*>|\{[^{}\s]+\}/g
      : format === "DISCORD_MARKDOWN"
        ? /\\.|\*\*|__|~~|\*|_|`[^`]*`|\[[^\]]*\]\([^)]*\)|\{[^{}\s]+\}/g
        : /\{[^{}\s]+\}/g
  let last = 0
  let key = 0
  for (const match of text.matchAll(pattern)) {
    const at = match.index ?? 0
    if (at > last) out.push(text.slice(last, at))
    out.push(piece(match[0], format, known, key++))
    last = at + match[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

function piece(token: string, format: Format, known: Set<string>, key: number): ReactNode {
  if (token.startsWith("\\"))
    return (
      <span key={key} className="text-muted-foreground">
        {token}
      </span>
    )
  if (token.startsWith("{")) {
    const name = token.slice(1, -1)
    const ok = known.has(name)
    return (
      <span
        key={key}
        className={cn("rounded-sm", ok ? "bg-primary/15 text-primary" : "bg-destructive/15 text-destructive")}
      >
        {token}
      </span>
    )
  }
  if (format === "MINIMESSAGE" && token.startsWith("<")) {
    const body = token.replace(/^<\/?/, "").replace(/>$/, "")
    const name = body.split(":")[0].replace(/^!/, "")
    if (name.startsWith("_")) {
      const ok = known.has(name)
      return (
        <span
          key={key}
          className={cn("rounded-sm", ok ? "bg-muted text-muted-foreground" : "bg-destructive/15 text-destructive")}
        >
          {token}
        </span>
      )
    }
    const colour = colourOf(body)
    if (colour) {
      return (
        <span key={key} style={{ color: colour }} className="font-semibold">
          {token}
        </span>
      )
    }
    return (
      <span key={key} className="text-chart-2">
        {token}
      </span>
    )
  }
  return (
    <span key={key} className="text-chart-2">
      {token}
    </span>
  )
}

function colourOf(body: string): string | null {
  const bare = body.replace(/^(color|colour|c):/, "")
  if (isColour(bare)) return hexOf(bare)
  if (body.startsWith("gradient:")) {
    const first = body.split(":")[1]
    if (first && isColour(first)) return hexOf(first)
  }
  return null
}
