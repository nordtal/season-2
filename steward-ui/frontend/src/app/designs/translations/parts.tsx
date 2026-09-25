import { useState } from "react"
import type { ReactNode } from "react"
import {
  ArrowElbowDownLeftIcon,
  BracketsCurlyIcon,
  CodeIcon,
  CursorClickIcon,
  LinkIcon,
  PaletteIcon,
  PlusIcon,
  ShuffleIcon,
  SmileyIcon,
  TextBIcon,
  TextItalicIcon,
  TextStrikethroughIcon,
  TextTSlashIcon,
  TextUnderlineIcon,
  TrashIcon,
} from "@phosphor-icons/react"
import { cn } from "cn"

import type { GlyphInfo, MessageArg } from "@/lib/api"
import { NAMED_COLOURS } from "@/lib/mini-message"
import { capabilities, gradientAt, hexOf } from "@/lib/rich-text"
import type { Click, ClickAction, Decoration, Format, Style } from "@/lib/rich-text"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Glyph } from "@/app/designs/translations/preview"
import type { Fill } from "@/app/designs/translations/preview"

/**
 * What the three editors share: the placeholder and glyph menus, the colour menu with its
 * gradient, click and link, and the row of style buttons that uses them. Built once; each editor
 * only decides where the row stands and what a press applies to.
 */

/** Minecraft's sixteen colours in the order of their old section codes, 0 to f. */
export const COLOUR_ORDER = [
  "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
  "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
]

/** A placeholder as the editors show it: always a pill, a general one set apart in grey. */
export function PlaceholderChip({ name, global, className }: { name: string; global?: boolean; className?: string }) {
  return (
    <span
      className={cn(
        "mx-px inline-flex items-center rounded-full border px-1.5 align-baseline font-mono text-[0.75em] leading-[1.5] whitespace-nowrap",
        global ? "border-border bg-muted text-muted-foreground" : "border-primary/50 bg-primary/20 text-[#c9d2ff]",
        className,
      )}
      style={{ textShadow: "none" }}
    >
      {name}
    </span>
  )
}

export function ToolButton({
  label,
  pressed,
  disabled,
  onPress,
  children,
}: {
  label: string
  pressed?: boolean
  disabled?: boolean
  onPress?: () => void
  children: ReactNode
}) {
  return (
    <Button
      type="button"
      size="icon-sm"
      variant={pressed ? "secondary" : "ghost"}
      aria-label={label}
      aria-pressed={pressed}
      disabled={disabled}
      // The selection stays where it is: a button that took focus would drop it.
      onMouseDown={(event) => event.preventDefault()}
      onClick={onPress}
    >
      {children}
    </Button>
  )
}

export function PlaceholderMenu({
  args,
  fill,
  onPick,
  disabled,
  children,
}: {
  args: MessageArg[]
  fill: Fill
  onPick: (arg: MessageArg) => void
  disabled?: boolean
  children?: ReactNode
}) {
  const [open, setOpen] = useState(false)
  const own = args.filter((arg) => !arg.global)
  const general = args.filter((arg) => arg.global)
  const row = (arg: MessageArg) => (
    <button
      key={arg.name}
      type="button"
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => {
        onPick(arg)
        setOpen(false)
      }}
      className="flex min-h-control w-full min-w-0 items-center justify-between gap-3 rounded-md px-2 text-left hover:bg-accent"
    >
      <PlaceholderChip name={arg.name.replace(/^_/, "")} global={arg.global} />
      <span className="truncate text-xs text-muted-foreground">{fill(arg.name)}</span>
    </button>
  )
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        {children ?? (
          <Button type="button" size="sm" variant="ghost" disabled={disabled || args.length === 0} onMouseDown={(event) => event.preventDefault()}>
            <BracketsCurlyIcon aria-hidden />
            Placeholder
          </Button>
        )}
      </PopoverTrigger>
      <PopoverContent align="start" className="w-72 p-1">
        {own.map(row)}
        {own.length > 0 && general.length > 0 ? <div className="my-1 border-t border-border" /> : null}
        {general.map(row)}
      </PopoverContent>
    </Popover>
  )
}

export function GlyphMenu({ glyphs, onPick, children }: { glyphs: GlyphInfo[]; onPick: (name: string) => void; children?: ReactNode }) {
  const [open, setOpen] = useState(false)
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        {children ?? (
          <Button type="button" size="sm" variant="ghost" disabled={glyphs.length === 0} onMouseDown={(event) => event.preventDefault()}>
            <SmileyIcon aria-hidden />
            Glyph
          </Button>
        )}
      </PopoverTrigger>
      <PopoverContent align="start" className="w-80 p-1">
        <div className="grid max-h-72 grid-cols-4 gap-1 overflow-y-auto">
          {glyphs.map((glyph) => (
            <button
              key={glyph.name}
              type="button"
              aria-label={glyph.name}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => {
                onPick(glyph.name)
                setOpen(false)
              }}
              className="flex min-w-0 flex-col items-center gap-1 rounded-md p-1.5 hover:bg-accent"
            >
              <span className="flex h-12 items-center">
                <Glyph name={glyph.name} glyphs={glyphs} scale={Math.min(3, 40 / glyph.height)} className="!align-middle" />
              </span>
              <span className="w-full truncate text-center text-[11px] text-muted-foreground">{glyph.name}</span>
            </button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  )
}

/** The colour a style is drawn in, as a small swatch: a gradient shows as one. */
export function Swatch({ style, className }: { style: Style; className?: string }) {
  const background = style.gradient
    ? `linear-gradient(90deg, ${style.gradient.map(hexOf).join(", ")})`
    : style.colour
      ? hexOf(style.colour)
      : "transparent"
  return (
    <span
      aria-hidden
      className={cn("inline-block size-4 shrink-0 rounded-sm border border-border", !style.colour && !style.gradient && "bg-[linear-gradient(135deg,transparent_45%,var(--destructive)_45%,var(--destructive)_55%,transparent_55%)]", className)}
      style={style.colour || style.gradient ? { background } : undefined}
    />
  )
}

export function ColourMenu({ style, onChange, disabled }: { style: Style; onChange: (patch: Pick<Style, "colour" | "gradient">) => void; disabled?: boolean }) {
  const [mode, setMode] = useState<"colour" | "gradient">(style.gradient ? "gradient" : "colour")
  const stops = style.gradient ?? [style.colour ?? "#4a63d8", "#ffffff"]
  const setStops = (next: string[]) => onChange({ gradient: next, colour: undefined })
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button type="button" size="icon-sm" variant="ghost" aria-label="Colour" disabled={disabled} onMouseDown={(event) => event.preventDefault()}>
          {style.colour || style.gradient ? <Swatch style={style} /> : <PaletteIcon aria-hidden />}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="flex w-72 flex-col gap-3" onOpenAutoFocus={(event) => event.preventDefault()}>
        <Tabs value={mode} onValueChange={(value) => setMode(value as "colour" | "gradient")}>
          <TabsList className="w-full">
            <TabsTrigger value="colour">Colour</TabsTrigger>
            <TabsTrigger value="gradient">Gradient</TabsTrigger>
          </TabsList>
        </Tabs>
        {mode === "colour" ? (
          <>
            <div className="grid grid-cols-8 gap-1">
              {COLOUR_ORDER.map((name) => (
                <button
                  key={name}
                  type="button"
                  aria-label={name.replace(/_/g, " ")}
                  aria-pressed={style.colour === name}
                  onClick={() => onChange({ colour: name, gradient: undefined })}
                  className={cn(
                    "aspect-square rounded-sm border border-border",
                    style.colour === name && "ring-2 ring-ring ring-offset-1 ring-offset-popover",
                  )}
                  style={{ background: NAMED_COLOURS[name] }}
                />
              ))}
            </div>
            <HexField value={style.colour ? hexOf(style.colour) : ""} onChange={(hex) => onChange({ colour: hex, gradient: undefined })} />
          </>
        ) : (
          <>
            <div className="h-4 rounded-sm" style={{ background: `linear-gradient(90deg, ${stops.map((_, at) => gradientAt(stops, at / (stops.length - 1))).join(", ")})` }} />
            {stops.map((stop, at) => (
              <div key={at} className="flex items-center gap-2">
                <HexField value={hexOf(stop)} onChange={(hex) => setStops(stops.map((old, index) => (index === at ? hex : old)))} />
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  aria-label="Remove colour"
                  disabled={stops.length <= 2}
                  onClick={() => setStops(stops.filter((_, index) => index !== at))}
                >
                  <TrashIcon aria-hidden />
                </Button>
              </div>
            ))}
            <Button type="button" size="sm" variant="ghost" className="self-start" onClick={() => setStops([...stops, stops[stops.length - 1]])}>
              <PlusIcon aria-hidden />
              Colour
            </Button>
          </>
        )}
        <Button type="button" size="sm" variant="outline" onClick={() => onChange({ colour: undefined, gradient: undefined })}>
          No colour
        </Button>
      </PopoverContent>
    </Popover>
  )
}

function HexField({ value, onChange }: { value: string; onChange: (hex: string) => void }) {
  const [typed, setTyped] = useState<string | null>(null)
  return (
    <div className="flex min-w-0 flex-1 items-center gap-2">
      <input
        type="color"
        aria-label="Pick a colour"
        value={value || "#ffffff"}
        onChange={(event) => onChange(event.target.value)}
        className="size-8 shrink-0 cursor-pointer rounded-md border border-border bg-transparent p-0.5"
      />
      <Input
        aria-label="Hex colour"
        value={typed ?? value}
        placeholder="#4a63d8"
        spellCheck={false}
        className="min-w-0 font-mono"
        onChange={(event) => {
          setTyped(event.target.value)
          if (/^#[0-9a-fA-F]{6}$/.test(event.target.value)) onChange(event.target.value.toLowerCase())
        }}
        onBlur={() => setTyped(null)}
      />
    </div>
  )
}

const CLICK_LABELS: Record<ClickAction, string> = {
  open_url: "Open link",
  run_command: "Run command",
  suggest_command: "Suggest command",
  copy_to_clipboard: "Copy",
}

export function ClickMenu({ click, onChange, disabled }: { click: Click | undefined; onChange: (click: Click | undefined) => void; disabled?: boolean }) {
  const [draft, setDraft] = useState<Click>(click ?? { action: "open_url", value: "" })
  return (
    <Popover onOpenChange={(open) => open && setDraft(click ?? { action: "open_url", value: "" })}>
      <PopoverTrigger asChild>
        <Button type="button" size="icon-sm" variant={click ? "secondary" : "ghost"} aria-label="Click" disabled={disabled} onMouseDown={(event) => event.preventDefault()}>
          <CursorClickIcon aria-hidden />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="flex w-72 flex-col gap-2">
        <Select value={draft.action} onValueChange={(action) => setDraft({ ...draft, action: action as ClickAction })}>
          <SelectTrigger className="w-full" aria-label="On click">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {(Object.keys(CLICK_LABELS) as ClickAction[]).map((action) => (
              <SelectItem key={action} value={action}>
                {CLICK_LABELS[action]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Input
          aria-label="Click target"
          value={draft.value}
          spellCheck={false}
          placeholder={draft.action === "open_url" ? "https://" : "/"}
          className="font-mono"
          onChange={(event) => setDraft({ ...draft, value: event.target.value })}
        />
        <div className="flex gap-2">
          <Button type="button" size="sm" className="flex-1" disabled={!draft.value} onClick={() => onChange(draft)}>
            Apply
          </Button>
          {click ? (
            <Button type="button" size="sm" variant="outline" onClick={() => onChange(undefined)}>
              Remove
            </Button>
          ) : null}
        </div>
      </PopoverContent>
    </Popover>
  )
}

export function LinkMenu({ click, onChange, disabled }: { click: Click | undefined; onChange: (click: Click | undefined) => void; disabled?: boolean }) {
  const [url, setUrl] = useState(click?.value ?? "")
  return (
    <Popover onOpenChange={(open) => open && setUrl(click?.value ?? "")}>
      <PopoverTrigger asChild>
        <Button type="button" size="icon-sm" variant={click ? "secondary" : "ghost"} aria-label="Link" disabled={disabled} onMouseDown={(event) => event.preventDefault()}>
          <LinkIcon aria-hidden />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="flex w-72 flex-col gap-2">
        <Input aria-label="Link target" value={url} placeholder="https://" spellCheck={false} className="font-mono" onChange={(event) => setUrl(event.target.value)} />
        <div className="flex gap-2">
          <Button type="button" size="sm" className="flex-1" disabled={!url} onClick={() => onChange({ action: "open_url", value: url })}>
            Apply
          </Button>
          {click ? (
            <Button type="button" size="sm" variant="outline" onClick={() => onChange(undefined)}>
              Remove
            </Button>
          ) : null}
        </div>
      </PopoverContent>
    </Popover>
  )
}

const DECORATION_ICONS: Record<Decoration, [string, ReactNode]> = {
  bold: ["Bold", <TextBIcon key="b" aria-hidden />],
  italic: ["Italic", <TextItalicIcon key="i" aria-hidden />],
  underlined: ["Underline", <TextUnderlineIcon key="u" aria-hidden />],
  strikethrough: ["Strikethrough", <TextStrikethroughIcon key="s" aria-hidden />],
  obfuscated: ["Obfuscated", <ShuffleIcon key="o" aria-hidden />],
}

/**
 * The style buttons for a format, acting on whatever the editor says is selected. `hover` is the
 * editor's own hover control, left out inside a hover: a hover text has no hover and no click.
 */
export function StyleButtons({
  format,
  style,
  onStyle,
  hover,
  nested,
  disabled,
}: {
  format: Format
  style: Style
  onStyle: (change: (style: Style) => Style) => void
  hover?: ReactNode
  nested?: boolean
  disabled?: boolean
}) {
  const can = capabilities(format)
  return (
    <>
      {can.colour ? <ColourMenu style={style} disabled={disabled} onChange={(patch) => onStyle((old) => ({ ...old, ...patch }))} /> : null}
      {can.decorations.map((decoration) => (
        <ToolButton
          key={decoration}
          label={DECORATION_ICONS[decoration][0]}
          pressed={style[decoration] === true}
          disabled={disabled}
          onPress={() => onStyle((old) => ({ ...old, [decoration]: style[decoration] === true ? undefined : true }))}
        >
          {DECORATION_ICONS[decoration][1]}
        </ToolButton>
      ))}
      {can.code ? (
        <ToolButton label="Code" pressed={style.code === true} disabled={disabled} onPress={() => onStyle((old) => ({ ...old, code: style.code ? undefined : true }))}>
          <CodeIcon aria-hidden />
        </ToolButton>
      ) : null}
      {can.link ? <LinkMenu click={style.click} disabled={disabled} onChange={(click) => onStyle((old) => ({ ...old, click }))} /> : null}
      {can.hover && !nested ? hover : null}
      {can.click && !nested ? <ClickMenu click={style.click} disabled={disabled} onChange={(click) => onStyle((old) => ({ ...old, click }))} /> : null}
      {format !== "PLAIN" ? (
        <ToolButton label="Clear formatting" disabled={disabled} onPress={() => onStyle(() => ({}))}>
          <TextTSlashIcon aria-hidden />
        </ToolButton>
      ) : null}
    </>
  )
}

export function BreakButton({ onPress, disabled }: { onPress: () => void; disabled?: boolean }) {
  return (
    <ToolButton label="Line break" onPress={onPress} disabled={disabled}>
      <ArrowElbowDownLeftIcon aria-hidden />
    </ToolButton>
  )
}
