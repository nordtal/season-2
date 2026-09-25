import { useState } from "react"
import { ArrowDownIcon, ArrowUpIcon, CaretDownIcon, PlusIcon, TrashIcon } from "@phosphor-icons/react"
import { cn } from "cn"

import type { GlyphInfo, MessageArg } from "@/lib/api"
import { capabilities } from "@/lib/rich-text"
import type { Format, Run, Style } from "@/lib/rich-text"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { GlyphMenu, PlaceholderChip, PlaceholderMenu, StyleButtons, Swatch, ToolButton } from "@/app/designs/translations/parts"
import { Glyph, MinecraftText } from "@/app/designs/translations/preview"
import type { Fill } from "@/app/designs/translations/preview"
import { VisualEditor } from "@/app/designs/translations/visual-editor"

/**
 * C: the text as a list of pieces, one row each - a stretch of text, a placeholder, a glyph or a
 * line break, every one with its own style. Nothing is selected by dragging, so this is the one
 * that works the same with a thumb as with a mouse.
 *
 * The list is kept as the author built it, not merged: two neighbours of the same style stay two
 * rows until the text is written out, so a row never vanishes under the finger that just styled it.
 */

export type SegmentEditorProps = {
  runs: Run[]
  onChange: (runs: Run[]) => void
  format: Format
  args: MessageArg[]
  glyphs: GlyphInfo[]
  fill: Fill
  disabled?: boolean
}

export function SegmentEditor({ runs, onChange, format, args, glyphs, fill, disabled }: SegmentEditorProps) {
  const [open, setOpen] = useState<number | null>(null)
  const can = capabilities(format)

  const set = (index: number, run: Run) => onChange(runs.map((old, at) => (at === index ? run : old)))
  const move = (index: number, by: number) => {
    const next = [...runs]
    const [run] = next.splice(index, 1)
    next.splice(index + by, 0, run)
    onChange(next)
    setOpen(open === index ? index + by : open)
  }
  const drop = (index: number) => {
    onChange(runs.filter((_, at) => at !== index))
    setOpen(null)
  }
  const add = (run: Run) => {
    const style = runs[runs.length - 1]?.style ?? {}
    onChange([...runs, { ...run, style: run.kind === "break" ? {} : style } as Run])
    setOpen(run.kind === "text" ? runs.length : null)
  }

  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <ol className="flex min-w-0 flex-col divide-y divide-border rounded-md border border-border">
        {runs.map((run, index) => (
          <li key={index} className="min-w-0">
            <div className="flex min-h-control min-w-0 items-center gap-1 pr-1">
              <button
                type="button"
                aria-expanded={open === index}
                aria-label={`Piece ${index + 1}`}
                disabled={disabled}
                onClick={() => setOpen(open === index ? null : index)}
                className="flex min-h-control min-w-0 flex-1 items-center gap-2 px-2.5 text-left"
              >
                <CaretDownIcon aria-hidden className={cn("size-3.5 shrink-0 text-muted-foreground transition-transform", open === index || "-rotate-90")} />
                <Summary run={run} format={format} glyphs={glyphs} fill={fill} args={args} />
              </button>
              {open === index ? (
                <>
                  <ToolButton label="Up" disabled={disabled || index === 0} onPress={() => move(index, -1)}>
                    <ArrowUpIcon aria-hidden />
                  </ToolButton>
                  <ToolButton label="Down" disabled={disabled || index === runs.length - 1} onPress={() => move(index, 1)}>
                    <ArrowDownIcon aria-hidden />
                  </ToolButton>
                  <ToolButton label="Remove" disabled={disabled} onPress={() => drop(index)}>
                    <TrashIcon aria-hidden />
                  </ToolButton>
                </>
              ) : null}
            </div>
            {open === index ? (
              <div className="flex min-w-0 flex-col gap-2 px-2.5 pb-2.5">
                {run.kind === "text" ? (
                  <Input aria-label="Text" value={run.text} disabled={disabled} onChange={(event) => set(index, { ...run, text: event.target.value })} />
                ) : null}
                {run.kind === "placeholder" ? (
                  <PlaceholderMenu args={args} fill={fill} onPick={(arg) => set(index, { ...run, name: arg.name })}>
                    <Button type="button" variant="outline" size="sm" className="self-start">
                      <PlaceholderChip name={run.name.replace(/^_/, "")} global={args.find((arg) => arg.name === run.name)?.global} />
                    </Button>
                  </PlaceholderMenu>
                ) : null}
                {run.kind === "glyph" ? (
                  <GlyphMenu glyphs={glyphs} onPick={(name) => set(index, { ...run, name })}>
                    <Button type="button" variant="outline" size="sm" className="self-start">
                      <Glyph name={run.name} glyphs={glyphs} scale={2} />
                      {run.name}
                    </Button>
                  </GlyphMenu>
                ) : null}
                {run.kind === "raw" ? (
                  <Input aria-label="Tag" value={run.source} disabled={disabled} className="font-mono" onChange={(event) => set(index, { ...run, source: event.target.value })} />
                ) : null}
                {run.kind !== "break" && format !== "PLAIN" ? (
                  <div className="flex flex-wrap items-center gap-0.5">
                    <StyleButtons
                      format={format}
                      style={run.style}
                      disabled={disabled}
                      onStyle={(change) => set(index, { ...run, style: change(run.style) } as Run)}
                      hover={<HoverRow hover={run.style.hover} args={args} glyphs={glyphs} fill={fill} onChange={(hover) => set(index, { ...run, style: { ...run.style, hover } } as Run)} />}
                    />
                  </div>
                ) : null}
                {run.style.hover && can.hover ? (
                  <HoverRow hover={run.style.hover} args={args} glyphs={glyphs} fill={fill} expanded onChange={(hover) => set(index, { ...run, style: { ...run.style, hover } } as Run)} />
                ) : null}
              </div>
            ) : null}
          </li>
        ))}
        {runs.length === 0 ? <li className="px-2.5 py-3 text-sm text-muted-foreground">Empty</li> : null}
      </ol>
      <div className="flex flex-wrap gap-1">
        <Button type="button" size="sm" variant="outline" disabled={disabled} onClick={() => add({ kind: "text", text: "", style: {} })}>
          <PlusIcon aria-hidden />
          Text
        </Button>
        {args.length > 0 ? (
          <PlaceholderMenu args={args} fill={fill} disabled={disabled} onPick={(arg) => add({ kind: "placeholder", name: arg.name, style: {} })}>
            <Button type="button" size="sm" variant="outline" disabled={disabled}>
              <PlusIcon aria-hidden />
              Placeholder
            </Button>
          </PlaceholderMenu>
        ) : null}
        {can.glyph ? (
          <GlyphMenu glyphs={glyphs} onPick={(name) => add({ kind: "glyph", name, style: {} })}>
            <Button type="button" size="sm" variant="outline" disabled={disabled}>
              <PlusIcon aria-hidden />
              Glyph
            </Button>
          </GlyphMenu>
        ) : null}
        {can.breaks ? (
          <Button type="button" size="sm" variant="outline" disabled={disabled} onClick={() => add({ kind: "break", style: {} })}>
            <PlusIcon aria-hidden />
            Line break
          </Button>
        ) : null}
      </div>
    </div>
  )
}

function Summary({ run, format, glyphs, fill, args }: { run: Run; format: Format; glyphs: GlyphInfo[]; fill: Fill; args: MessageArg[] }) {
  const marks = <Marks style={run.style} />
  switch (run.kind) {
    case "text":
      return (
        <span className="flex min-w-0 items-center gap-2">
          {format === "MINIMESSAGE" ? <Swatch style={run.style} /> : null}
          <span className="truncate font-mono text-sm whitespace-pre">{run.text === "" ? " " : run.text}</span>
          {marks}
        </span>
      )
    case "placeholder":
      return (
        <span className="flex min-w-0 items-center gap-2">
          {format === "MINIMESSAGE" ? <Swatch style={run.style} /> : null}
          <PlaceholderChip name={run.name.replace(/^_/, "")} global={args.find((arg) => arg.name === run.name)?.global} />
          <span className="truncate text-xs text-muted-foreground">{fill(run.name)}</span>
          {marks}
        </span>
      )
    case "glyph":
      return (
        <span className="flex min-w-0 items-center gap-2">
          <Glyph name={run.name} glyphs={glyphs} scale={2} />
          <span className="truncate text-sm">{run.name}</span>
        </span>
      )
    case "break":
      return <span className="text-sm text-muted-foreground">{"↵"}</span>
    case "raw":
      return <span className="truncate font-mono text-xs text-muted-foreground">{run.source}</span>
  }
}

/** The decorations a row carries, as the letters a style button shows. */
function Marks({ style }: { style: Style }) {
  const on = [
    style.bold && <b key="b">B</b>,
    style.italic && <i key="i">I</i>,
    style.underlined && <u key="u">U</u>,
    style.strikethrough && <s key="s">S</s>,
    style.code && <code key="c">{"</>"}</code>,
    style.hover && <span key="h">H</span>,
    style.click && <span key="k">{"↗"}</span>,
  ].filter(Boolean)
  if (on.length === 0) return null
  return <span className="flex shrink-0 gap-1 text-xs text-muted-foreground">{on}</span>
}

/** A hover inside a row: the button in the style row, and the hover's own text once it has one. */
function HoverRow({
  hover,
  onChange,
  args,
  glyphs,
  fill,
  expanded,
}: {
  hover: Run[] | undefined
  onChange: (hover: Run[] | undefined) => void
  args: MessageArg[]
  glyphs: GlyphInfo[]
  fill: Fill
  expanded?: boolean
}) {
  if (!expanded) {
    return (
      <ToolButton label="Hover" pressed={Boolean(hover)} onPress={() => onChange(hover ? undefined : [{ kind: "text", text: "", style: {} }])}>
        <span aria-hidden className="text-xs font-semibold">
          H
        </span>
      </ToolButton>
    )
  }
  return (
    <div className="flex min-w-0 flex-col gap-1 rounded-md bg-muted/50 p-2">
      <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
        <span>Hover</span>
        <span className="rounded-sm bg-black/80 px-1.5 py-0.5">
          <MinecraftText runs={hover ?? []} fill={fill} glyphs={glyphs} scale={1} />
        </span>
      </div>
      <VisualEditor runs={hover ?? []} onChange={(next) => onChange(next.length > 0 ? next : [])} format="MINIMESSAGE" args={args} glyphs={glyphs} fill={fill} label="Hover text" nested />
    </div>
  )
}
