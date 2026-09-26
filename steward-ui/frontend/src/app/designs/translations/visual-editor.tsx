import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import type { CSSProperties } from "react"
import { ChatTextIcon } from "@phosphor-icons/react"
import { cn } from "cn"

import type { GlyphInfo, MessageArg } from "@/lib/api"
import {
  applyStyle,
  capabilities,
  commonStyle,
  gradientAt,
  hexOf,
  insert,
  lengthOf,
  plainText,
  remove,
  shadowOf,
  totalLength,
} from "@/lib/rich-text"
import type { Format, Run, Style } from "@/lib/rich-text"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import {
  BreakButton,
  GlyphMenu,
  PlaceholderChip,
  PlaceholderMenu,
  StyleButtons,
} from "@/app/designs/translations/parts"
import { Glyph, MINECRAFT_FONT } from "@/app/designs/translations/preview"
import type { Fill } from "@/app/designs/translations/preview"

/**
 * A: the text as it looks, typed into directly. Colours, decorations and glyphs are drawn the way
 * the game or Discord draws them; placeholders are pills that move as one character. Selecting
 * text brings up the style row; the placeholder, glyph and line break buttons insert at the caret.
 *
 * The runs are the state, never the DOM: every edit the browser announces through `beforeinput`
 * is cancelled and applied to the runs instead, and the caret is put back from the model. The one
 * edit that cannot be cancelled - an input method composing a word, which is how a phone keyboard
 * types - is read back out of the DOM when it ends.
 */

export type VisualEditorProps = {
  runs: Run[]
  onChange: (runs: Run[]) => void
  format: Format
  args: MessageArg[]
  glyphs: GlyphInfo[]
  fill: Fill
  label: string
  /** A hover's own text: no hover and no click inside it. */
  nested?: boolean
  disabled?: boolean
}

type Range = { from: number; to: number }

export function VisualEditor({
  runs,
  onChange,
  format,
  args,
  glyphs,
  fill,
  label,
  nested,
  disabled,
}: VisualEditorProps) {
  const root = useRef<HTMLDivElement>(null)
  const [generation, setGeneration] = useState(0)
  const [range, setRange] = useState<Range>({ from: totalLength(runs), to: totalLength(runs) })
  const last = useRef<Range>(range)
  const caret = useRef<Range | null>(null)
  const history = useRef<{ past: Run[][]; future: Run[][] }>({ past: [], future: [] })
  const composing = useRef(false)
  const can = capabilities(format)
  const wide = useWide()

  const commit = useCallback(
    (next: Run[], at: Range) => {
      history.current.past.push(runs)
      history.current.future = []
      caret.current = at
      last.current = at
      onChange(next)
    },
    [runs, onChange],
  )

  // After every render that moved the caret, put it back where the model says it is.
  useLayoutEffect(() => {
    const element = root.current
    if (!element || !caret.current || document.activeElement !== element) return
    placeCaret(element, runs, caret.current)
    caret.current = null
  })

  // Keep the selection in model offsets, so the style row and the menus act on it after focus left.
  useEffect(() => {
    const update = () => {
      const element = root.current
      const selection = document.getSelection()
      if (!element || !selection || selection.rangeCount === 0 || composing.current) return
      if (!element.contains(selection.anchorNode) || !element.contains(selection.focusNode)) return
      const a = modelOffset(element, runs, selection.anchorNode!, selection.anchorOffset)
      const b = modelOffset(element, runs, selection.focusNode!, selection.focusOffset)
      const next = { from: Math.min(a, b), to: Math.max(a, b) }
      last.current = next
      setRange((old) => (old.from === next.from && old.to === next.to ? old : next))
    }
    document.addEventListener("selectionchange", update)
    return () => document.removeEventListener("selectionchange", update)
  }, [runs])

  const insertAt = useCallback(
    (inserted: Run[], at: Range = last.current) => {
      const cleared = remove(runs, at.from, at.to)
      const length = inserted.reduce((sum, run) => sum + lengthOf(run), 0)
      commit(insert(cleared, at.from, inserted), { from: at.from + length, to: at.from + length })
      root.current?.focus()
    },
    [runs, commit],
  )

  useEffect(() => {
    const element = root.current
    if (!element) return
    const onBeforeInput = (event: InputEvent) => {
      if (event.inputType === "insertCompositionText") return
      event.preventDefault()
      const at = currentRange(element, runs) ?? last.current
      const collapsed = at.from === at.to
      switch (event.inputType) {
        case "insertText":
        case "insertReplacementText":
        case "insertFromPaste":
        case "insertFromDrop": {
          const text = event.data ?? event.dataTransfer?.getData("text/plain") ?? ""
          insertAt(textRuns(text, can.breaks && !nested), at)
          return
        }
        case "insertParagraph":
        case "insertLineBreak":
          if (can.breaks) insertAt([{ kind: "break", style: {} }], at)
          return
        case "deleteContentBackward":
        case "deleteWordBackward":
        case "deleteSoftLineBackward":
        case "deleteHardLineBackward": {
          const from = collapsed ? backward(runs, at.from, event.inputType) : at.from
          commit(remove(runs, from, at.to), { from, to: from })
          return
        }
        case "deleteContentForward":
        case "deleteWordForward": {
          const to = collapsed ? forward(runs, at.to, event.inputType) : at.to
          commit(remove(runs, at.from, to), { from: at.from, to: at.from })
          return
        }
        case "deleteByCut":
        case "deleteContent":
          commit(remove(runs, at.from, at.to), { from: at.from, to: at.from })
          return
        case "historyUndo":
          undo()
          return
        case "historyRedo":
          redo()
          return
        case "formatBold":
          toggle("bold", at)
          return
        case "formatItalic":
          toggle("italic", at)
          return
        case "formatUnderline":
          toggle("underlined", at)
          return
      }
    }
    const onCompositionStart = () => {
      composing.current = true
    }
    const onCompositionEnd = () => {
      composing.current = false
      const read = readDom(element, runs)
      caret.current = read.caret
      last.current = read.caret
      history.current.past.push(runs)
      setGeneration((value) => value + 1)
      onChange(read.runs)
    }
    const onPaste = (event: ClipboardEvent) => {
      event.preventDefault()
      const text = event.clipboardData?.getData("text/plain") ?? ""
      insertAt(textRuns(text, can.breaks && !nested), currentRange(element, runs) ?? last.current)
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") {
        event.preventDefault()
        if (event.shiftKey) redo()
        else undo()
      }
    }
    element.addEventListener("beforeinput", onBeforeInput)
    element.addEventListener("compositionstart", onCompositionStart)
    element.addEventListener("compositionend", onCompositionEnd)
    element.addEventListener("paste", onPaste)
    element.addEventListener("keydown", onKeyDown)
    return () => {
      element.removeEventListener("beforeinput", onBeforeInput)
      element.removeEventListener("compositionstart", onCompositionStart)
      element.removeEventListener("compositionend", onCompositionEnd)
      element.removeEventListener("paste", onPaste)
      element.removeEventListener("keydown", onKeyDown)
    }
  })

  function undo() {
    const previous = history.current.past.pop()
    if (!previous) return
    history.current.future.push(runs)
    caret.current = { from: totalLength(previous), to: totalLength(previous) }
    onChange(previous)
  }

  function redo() {
    const next = history.current.future.pop()
    if (!next) return
    history.current.past.push(runs)
    caret.current = { from: totalLength(next), to: totalLength(next) }
    onChange(next)
  }

  function restyle(change: (style: Style) => Style, at: Range = last.current) {
    if (at.from === at.to) return
    commit(applyStyle(runs, at.from, at.to, change), at)
  }

  function toggle(decoration: "bold" | "italic" | "underlined", at: Range) {
    const on = commonStyle(runs, at.from, at.to)[decoration] === true
    restyle((style) => ({ ...style, [decoration]: on ? undefined : true }), at)
  }

  const selected = range.from < range.to
  const shared = useMemo(() => (selected ? commonStyle(runs, range.from, range.to) : {}), [runs, range, selected])
  const empty = runs.length === 0

  const styleRow = (
    <StyleButtons
      format={format}
      style={shared}
      nested={nested}
      disabled={!selected || disabled}
      onStyle={(change) => restyle(change, range)}
      hover={
        <HoverMenu
          hover={shared.hover ?? []}
          disabled={!selected || disabled}
          onChange={(hover) => restyle((style) => ({ ...style, hover: hover.length > 0 ? hover : undefined }), range)}
          args={args}
          glyphs={glyphs}
          fill={fill}
        />
      }
    />
  )

  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <div className="flex flex-wrap items-center gap-0.5">
        {args.length > 0 ? (
          <PlaceholderMenu
            args={args}
            fill={fill}
            disabled={disabled}
            onPick={(arg) => insertAt([{ kind: "placeholder", name: arg.name, style: {} }])}
          />
        ) : null}
        {can.glyph ? (
          <GlyphMenu glyphs={glyphs} onPick={(name) => insertAt([{ kind: "glyph", name, style: {} }])} />
        ) : null}
        {can.breaks && !nested ? (
          <BreakButton disabled={disabled} onPress={() => insertAt([{ kind: "break", style: {} }])} />
        ) : null}
        {wide || nested ? (
          <>
            <span aria-hidden className="mx-1 h-5 w-px bg-border" />
            {styleRow}
          </>
        ) : null}
      </div>
      <div
        key={generation}
        ref={root}
        role="textbox"
        aria-label={label}
        aria-multiline={can.breaks}
        contentEditable={!disabled}
        suppressContentEditableWarning
        spellCheck={false}
        data-empty={empty || undefined}
        className={cn(
          "min-h-12 w-full min-w-0 rounded-md px-2.5 py-2 break-words whitespace-pre-wrap outline-none focus-visible:ring-2 focus-visible:ring-ring",
          "data-[empty]:before:text-muted-foreground data-[empty]:before:content-['Empty']",
          format === "PLAIN" && "border border-input bg-input/30 text-sm",
        )}
        style={surfaceStyle(format)}
      >
        {runs.map((run, index) => (
          <RunView
            key={index}
            index={index}
            run={run}
            runs={runs}
            format={format}
            args={args}
            glyphs={glyphs}
            fill={fill}
          />
        ))}
        {runs[runs.length - 1]?.kind === "break" ? <br /> : null}
      </div>
      {!wide && !nested && selected ? <div className="flex flex-wrap items-center gap-0.5">{styleRow}</div> : null}
    </div>
  )
}

function surfaceStyle(format: Format): CSSProperties {
  if (format === "MINIMESSAGE") {
    return {
      ...MINECRAFT_FONT,
      background: "linear-gradient(rgba(0,0,0,0.5), rgba(0,0,0,0.5)), #3a4a5e",
      color: "#FFFFFF",
      caretColor: "#FFFFFF",
    }
  }
  if (format === "DISCORD_MARKDOWN") return { background: "#313338", color: "#dbdee1", fontSize: 15, lineHeight: 1.375 }
  return {}
}

function RunView({
  index,
  run,
  runs,
  format,
  args,
  glyphs,
  fill,
}: {
  index: number
  run: Run
  runs: Run[]
  format: Format
  args: MessageArg[]
  glyphs: GlyphInfo[]
  fill: Fill
}) {
  const minecraft = format === "MINIMESSAGE"
  const marks = marksOf(run.style, format)
  switch (run.kind) {
    case "text": {
      if (minecraft && run.style.gradient) {
        const { start, total } = gradientSpan(runs, index)
        const colours = run.style.gradient
        return (
          <span data-run={index} style={{ ...textStyle(run.style, format), ...marks }}>
            {[...run.text].map((char, at) => {
              const colour = gradientAt(colours, total <= 1 ? 0 : (start + at) / (total - 1))
              return (
                <span key={at} style={{ color: colour, textShadow: `2px 2px 0 ${shadowOf(colour)}` }}>
                  {char}
                </span>
              )
            })}
          </span>
        )
      }
      return (
        <span data-run={index} style={{ ...textStyle(run.style, format), ...marks }}>
          {run.text}
        </span>
      )
    }
    case "placeholder": {
      const arg = args.find((candidate) => candidate.name === run.name)
      return (
        <span
          data-run={index}
          contentEditable={false}
          style={{ ...textStyle(run.style, format), ...marks }}
          title={fill(run.name)}
        >
          <PlaceholderChip name={run.name.replace(/^_/, "")} global={arg?.global} />
        </span>
      )
    }
    case "glyph":
      return (
        <span data-run={index} contentEditable={false} style={marks}>
          <Glyph name={run.name} glyphs={glyphs} />
        </span>
      )
    case "break":
      return (
        <span data-run={index} contentEditable={false}>
          <span aria-hidden className="text-[0.7em] opacity-40 select-none">
            {"↵"}
          </span>
          <br />
        </span>
      )
    case "raw":
      return (
        <span
          data-run={index}
          contentEditable={false}
          className="rounded-sm bg-muted px-1 font-mono text-[0.7em] text-muted-foreground"
        >
          {run.source}
        </span>
      )
  }
}

/** Hover and click drawn as underlines in the editor, since neither can be seen in the game until used. */
function marksOf(style: Style, format: Format): CSSProperties {
  if (format === "DISCORD_MARKDOWN") return style.click ? { color: "#00a8fc" } : {}
  const marks: CSSProperties = {}
  if (style.hover) Object.assign(marks, { borderBottom: "2px dotted #c9d2ff", background: "rgba(201,210,255,0.08)" })
  if (style.click) Object.assign(marks, { outline: "1px dashed #4a63d8", outlineOffset: 1, cursor: "pointer" })
  return marks
}

function textStyle(style: Style, format: Format): CSSProperties {
  const lines = [style.underlined && "underline", style.strikethrough && "line-through"].filter(Boolean).join(" ")
  const css: CSSProperties = {
    fontWeight: style.bold ? 700 : undefined,
    fontStyle: style.italic ? "italic" : undefined,
    textDecorationLine: lines || undefined,
    fontSynthesis: "weight style",
  }
  if (format === "MINIMESSAGE") {
    const colour = style.colour ? hexOf(style.colour) : "#FFFFFF"
    Object.assign(css, {
      color: colour,
      textShadow: `2px 2px 0 ${shadowOf(colour)}`,
      textDecorationThickness: lines ? 2 : undefined,
    })
    if (style.obfuscated)
      Object.assign(css, {
        backgroundImage: "repeating-linear-gradient(90deg, transparent 0 3px, rgba(255,255,255,0.15) 3px 4px)",
      })
  }
  if (format === "DISCORD_MARKDOWN" && style.code) {
    Object.assign(css, {
      fontFamily: "var(--font-mono, monospace)",
      fontSize: "0.85em",
      background: "#2b2d31",
      borderRadius: 4,
      padding: "0 4px",
    })
  }
  return css
}

function gradientSpan(runs: Run[], index: number): { start: number; total: number } {
  const key = JSON.stringify(runs[index].style.gradient)
  let first = index
  while (first > 0 && JSON.stringify(runs[first - 1].style.gradient) === key) first -= 1
  let end = index
  while (end < runs.length && JSON.stringify(runs[end].style.gradient) === key) end += 1
  const length = (run: Run) => (run.kind === "text" ? run.text.length : run.kind === "placeholder" ? 1 : 0)
  const start = runs.slice(first, index).reduce((sum, run) => sum + length(run), 0)
  const total = runs.slice(first, end).reduce((sum, run) => sum + length(run), 0)
  return { start, total }
}

/** E14: a hover's text is edited with this same editor, in a popover, without hover or click. */
function HoverMenu({
  hover,
  onChange,
  disabled,
  args,
  glyphs,
  fill,
}: {
  hover: Run[]
  onChange: (hover: Run[]) => void
  disabled?: boolean
  args: MessageArg[]
  glyphs: GlyphInfo[]
  fill: Fill
}) {
  const [draft, setDraft] = useState<Run[]>(hover)
  const [open, setOpen] = useState(false)
  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        if (next) setDraft(hover)
        setOpen(next)
      }}
    >
      <PopoverTrigger asChild>
        <Button
          type="button"
          size="icon-sm"
          variant={hover.length > 0 ? "secondary" : "ghost"}
          aria-label="Hover"
          disabled={disabled}
          onMouseDown={(event) => event.preventDefault()}
        >
          <ChatTextIcon aria-hidden />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="flex w-[min(22rem,calc(100vw-2rem))] flex-col gap-2">
        <VisualEditor
          runs={draft}
          onChange={setDraft}
          format="MINIMESSAGE"
          args={args}
          glyphs={glyphs}
          fill={fill}
          label="Hover text"
          nested
        />
        <div className="flex gap-2">
          <Button
            type="button"
            size="sm"
            className="flex-1"
            onClick={() => {
              onChange(draft)
              setOpen(false)
            }}
          >
            Apply
          </Button>
          {hover.length > 0 ? (
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => {
                onChange([])
                setOpen(false)
              }}
            >
              Remove
            </Button>
          ) : null}
        </div>
      </PopoverContent>
    </Popover>
  )
}

// --- the DOM and the model -------------------------------------------------------------------

function textRuns(text: string, breaks: boolean): Run[] {
  const lines = text.replace(/\r\n?/g, "\n").split("\n")
  const out: Run[] = []
  lines.forEach((line, index) => {
    if (index > 0) out.push(breaks ? { kind: "break", style: {} } : { kind: "text", text: " ", style: {} })
    if (line) out.push({ kind: "text", text: line, style: {} })
  })
  return out
}

function backward(runs: Run[], offset: number, type: string): number {
  if (offset === 0) return 0
  if (type === "deleteContentBackward") return offset - 1
  const text = plainText(runs, () => "\u0001")
  if (type === "deleteWordBackward") {
    let at = offset
    while (at > 0 && /\s/.test(text[at - 1])) at -= 1
    while (at > 0 && !/\s/.test(text[at - 1])) at -= 1
    return at
  }
  const line = text.lastIndexOf("\n", offset - 1)
  return line + 1
}

function forward(runs: Run[], offset: number, type: string): number {
  const length = totalLength(runs)
  if (offset >= length) return length
  if (type === "deleteContentForward") return offset + 1
  const text = plainText(runs, () => "\u0001")
  let at = offset
  while (at < length && /\s/.test(text[at])) at += 1
  while (at < length && !/\s/.test(text[at])) at += 1
  return at
}

function runIndex(node: Node): number | null {
  if (!(node instanceof HTMLElement)) return null
  const value = node.dataset.run
  return value === undefined ? null : Number(value)
}

/** How many model positions a top-level child of the editor stands for. */
function lengthOfNode(node: Node, runs: Run[]): number {
  const index = runIndex(node)
  if (index !== null) {
    const run = runs[index]
    return run?.kind === "text" ? (node.textContent ?? "").length : 1
  }
  return node.nodeType === Node.TEXT_NODE ? (node.textContent ?? "").length : 0
}

function textOffsetWithin(element: Node, node: Node, offset: number): number {
  let sum = 0
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
  const stop: Node | null = node.nodeType === Node.TEXT_NODE ? node : (node.childNodes[offset] ?? null)
  for (let current = walker.nextNode(); current; current = walker.nextNode()) {
    if (current === stop) return sum + (node.nodeType === Node.TEXT_NODE ? offset : 0)
    if (stop && stop.contains(current)) return sum
    sum += (current.textContent ?? "").length
  }
  return sum
}

function modelOffset(root: HTMLElement, runs: Run[], node: Node, offset: number): number {
  const children = Array.from(root.childNodes)
  if (node === root) return children.slice(0, offset).reduce((sum, child) => sum + lengthOfNode(child, runs), 0)
  let top: Node | null = node
  while (top && top.parentNode !== root) top = top.parentNode
  if (!top) return 0
  let position = 0
  for (const child of children) {
    if (child === top) break
    position += lengthOfNode(child, runs)
  }
  const index = runIndex(top)
  if (index === null) return position + (top.nodeType === Node.TEXT_NODE ? offset : 0)
  if (runs[index]?.kind !== "text")
    return position + (offset === 0 && (node === top || node === top.firstChild) ? 0 : 1)
  return position + textOffsetWithin(top, node, offset)
}

function currentRange(root: HTMLElement, runs: Run[]): Range | null {
  const selection = document.getSelection()
  if (!selection || selection.rangeCount === 0) return null
  const range = selection.getRangeAt(0)
  if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return null
  return {
    from: modelOffset(root, runs, range.startContainer, range.startOffset),
    to: modelOffset(root, runs, range.endContainer, range.endOffset),
  }
}

function domPoint(root: HTMLElement, runs: Run[], target: number): { node: Node; offset: number } {
  let position = 0
  const children = Array.from(root.childNodes)
  for (let index = 0; index < runs.length; index += 1) {
    const run = runs[index]
    const length = lengthOf(run)
    const element = children.find((child) => runIndex(child) === index)
    if (!element) continue
    if (run.kind === "text" && target >= position && target <= position + length) {
      let remaining = target - position
      const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
      let lastText: Node | null = null
      for (let current = walker.nextNode(); current; current = walker.nextNode()) {
        const size = (current.textContent ?? "").length
        if (remaining <= size) return { node: current, offset: remaining }
        remaining -= size
        lastText = current
      }
      if (lastText) return { node: lastText, offset: (lastText.textContent ?? "").length }
    }
    if (run.kind !== "text" && target === position) return { node: root, offset: children.indexOf(element) }
    position += length
    if (run.kind !== "text" && target === position && runs[index + 1]?.kind !== "text") {
      return { node: root, offset: children.indexOf(element) + 1 }
    }
  }
  return { node: root, offset: children.length }
}

function placeCaret(root: HTMLElement, runs: Run[], range: Range) {
  const selection = document.getSelection()
  if (!selection) return
  const start = domPoint(root, runs, range.from)
  const end = range.to === range.from ? start : domPoint(root, runs, range.to)
  selection.setBaseAndExtent(start.node, start.offset, end.node, end.offset)
}

/** After a composition: the runs as the DOM now reads, and where its caret is. */
function readDom(root: HTMLElement, runs: Run[]): { runs: Run[]; caret: Range } {
  const selection = document.getSelection()
  const caretAt =
    selection && selection.rangeCount > 0 && root.contains(selection.focusNode)
      ? modelOffset(root, runs, selection.focusNode!, selection.focusOffset)
      : totalLength(runs)
  const out: Run[] = []
  for (const child of Array.from(root.childNodes)) {
    const index = runIndex(child)
    if (index !== null && runs[index]) {
      const run = runs[index]
      out.push(run.kind === "text" ? { ...run, text: child.textContent ?? "" } : run)
    } else if (child.nodeType === Node.TEXT_NODE && child.textContent) {
      const before = out[out.length - 1]
      out.push({ kind: "text", text: child.textContent, style: before?.style ?? {} })
    }
  }
  return { runs: insert(out, 0, []), caret: { from: caretAt, to: caretAt } }
}

function useWide(): boolean {
  const query = "(min-width: 768px)"
  const [wide, setWide] = useState(() => typeof window !== "undefined" && window.matchMedia?.(query).matches)
  useEffect(() => {
    const list = window.matchMedia?.(query)
    if (!list) return
    const update = () => setWide(list.matches)
    list.addEventListener("change", update)
    return () => list.removeEventListener("change", update)
  }, [])
  return Boolean(wide)
}
