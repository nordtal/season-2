import { useRef, useState } from "react"
import type { ReactNode } from "react"
import {
  BracketsCurlyIcon,
  CodeIcon,
  CursorClickIcon,
  LightningIcon,
  LinkIcon,
  ShuffleIcon,
  SlidersHorizontalIcon,
  SmileyIcon,
  TextBIcon,
  TextItalicIcon,
  TextStrikethroughIcon,
  TextTSlashIcon,
  TextUnderlineIcon,
} from "@phosphor-icons/react"
import { cn } from "cn"

import type { GlyphInfo, MessageArg } from "@/lib/api"
import { capabilities } from "@/lib/rich-text"
import type { Click, ClickAction, Decoration, Format, Style } from "@/lib/rich-text"
import { t } from "@/lib/texts"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { placeholderGroups, type Fill } from "@/components/steward/message-editor/examples"
import { ColourMenu } from "@/components/steward/message-editor/colour-menu"
import { GlyphTile, type Tones } from "@/components/steward/message-editor/preview"
import { MenuTrigger, ToolButton } from "@/components/steward/message-editor/tools"

/** What both editors share: the placeholder, glyph and colour menus, and the style buttons that use them. */

/** A placeholder as the editors show it: always a pill, one every message has set apart in grey. */
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

/** The message's values, a role's attributes under its name, each with the example it is previewed with. */
export function PlaceholderMenu({
  args,
  fill,
  onPick,
  disabled,
}: {
  args: MessageArg[]
  fill: Fill
  onPick: (arg: MessageArg) => void
  disabled?: boolean
}) {
  const [open, setOpen] = useState(false)
  const groups = placeholderGroups(args)
  const row = (arg: MessageArg, label: string, indented: boolean) => (
    <button
      key={arg.name}
      type="button"
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => {
        onPick(arg)
        setOpen(false)
      }}
      className={cn(
        "flex min-h-control w-full min-w-0 items-center justify-between gap-3 rounded-md px-2 text-left hover:bg-accent",
        indented && "pl-6",
      )}
    >
      <PlaceholderChip name={label} global={arg.global} />
      <span className="truncate text-xs text-muted-foreground">{fill(arg.name)}</span>
    </button>
  )
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <MenuTrigger label={t("steward.message-editor.placeholder")} disabled={disabled || groups.length === 0}>
          <BracketsCurlyIcon aria-hidden />
        </MenuTrigger>
      </PopoverTrigger>
      <PopoverContent align="start" className="max-h-80 w-[min(20rem,calc(100vw-2rem))] overflow-y-auto p-1">
        {groups.map((group) =>
          group.role === null ? (
            row(group.args[0], group.args[0].name, false)
          ) : (
            <div key={group.role} className="flex flex-col">
              <span className="px-2 pt-2 pb-1 font-mono text-xs text-muted-foreground">{group.role}</span>
              {group.args.map((arg) => row(arg, arg.name.slice(group.role!.length + 1), true))}
            </div>
          ),
        )}
      </PopoverContent>
    </Popover>
  )
}

export function GlyphMenu({
  glyphs,
  onPick,
  disabled,
}: {
  glyphs: GlyphInfo[]
  onPick: (name: string) => void
  disabled?: boolean
}) {
  const [open, setOpen] = useState(false)
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <MenuTrigger label={t("steward.message-editor.glyph")} disabled={disabled || glyphs.length === 0}>
          <SmileyIcon aria-hidden />
        </MenuTrigger>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-[min(20rem,calc(100vw-2rem))] p-1">
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
              <GlyphTile glyph={glyph} />
              <span className="w-full truncate text-center text-[11px] text-muted-foreground">{glyph.name}</span>
            </button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  )
}

const CLICK_ACTIONS: ClickAction[] = ["open_url", "run_command", "suggest_command", "copy_to_clipboard"]

function isClickAction(value: string): value is ClickAction {
  return (CLICK_ACTIONS as string[]).includes(value)
}

const CLICK_LABELS = {
  open_url: "steward.message-editor.open-url",
  run_command: "steward.message-editor.run-command",
  suggest_command: "steward.message-editor.suggest-command",
  copy_to_clipboard: "steward.message-editor.copy-to-clipboard",
} as const satisfies Record<ClickAction, string>

function clickLabel(action: ClickAction): string {
  return t(CLICK_LABELS[action])
}

/** A field whose value may hold the message's values: the placeholder menu writes one in where the caret is. */
function ValueField({
  value,
  onChange,
  args,
  fill,
  label,
  placeholder,
}: {
  value: string
  onChange: (value: string) => void
  args: MessageArg[]
  fill: Fill
  label: string
  placeholder?: string
}) {
  const input = useRef<HTMLInputElement>(null)
  return (
    <div className="flex items-center gap-1">
      <Input
        ref={input}
        aria-label={label}
        value={value}
        spellCheck={false}
        placeholder={placeholder}
        className="min-w-0 font-mono"
        onChange={(event) => onChange(event.target.value)}
      />
      <PlaceholderMenu
        args={args}
        fill={fill}
        onPick={(arg) => {
          const element = input.current
          const from = element?.selectionStart ?? value.length
          const to = element?.selectionEnd ?? value.length
          const token = `{${arg.name}}`
          onChange(value.slice(0, from) + token + value.slice(to))
          requestAnimationFrame(() => {
            element?.focus()
            element?.setSelectionRange(from + token.length, from + token.length)
          })
        }}
      />
    </div>
  )
}

/** Apply and remove under a small form in a popover. */
function ApplyRow({ onApply, onRemove, ready }: { onApply: () => void; onRemove?: () => void; ready: boolean }) {
  return (
    <div className="flex gap-2">
      <Button type="button" size="sm" className="flex-1" disabled={!ready} onClick={onApply}>
        {t("steward.message-editor.apply")}
      </Button>
      {onRemove ? (
        <Button type="button" size="sm" variant="outline" onClick={onRemove}>
          {t("steward.message-editor.remove")}
        </Button>
      ) : null}
    </div>
  )
}

export function ClickMenu({
  click,
  args,
  fill,
  onChange,
  disabled,
}: {
  click: Click | undefined
  args: MessageArg[]
  fill: Fill
  onChange: (click: Click | undefined) => void
  disabled?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<Click>(click ?? { action: "open_url", value: "" })
  const done = (next: Click | undefined) => {
    onChange(next)
    setOpen(false)
  }
  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        if (next) setDraft(click ?? { action: "open_url", value: "" })
        setOpen(next)
      }}
    >
      <PopoverTrigger asChild>
        <MenuTrigger label={t("steward.message-editor.click")} pressed={Boolean(click)} disabled={disabled}>
          <CursorClickIcon aria-hidden />
        </MenuTrigger>
      </PopoverTrigger>
      <PopoverContent align="start" className="flex w-[min(20rem,calc(100vw-2rem))] flex-col gap-2">
        <Select
          value={draft.action}
          onValueChange={(action) => {
            if (isClickAction(action)) setDraft({ ...draft, action })
          }}
        >
          <SelectTrigger className="w-full" aria-label={t("steward.message-editor.click")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {CLICK_ACTIONS.map((action) => (
              <SelectItem key={action} value={action}>
                {clickLabel(action)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <ValueField
          value={draft.value}
          onChange={(value) => setDraft({ ...draft, value })}
          args={args}
          fill={fill}
          label={t("steward.message-editor.click-value")}
          placeholder={draft.action === "open_url" ? "https://" : "/"}
        />
        <ApplyRow
          ready={Boolean(draft.value)}
          onApply={() => done(draft)}
          onRemove={click ? () => done(undefined) : undefined}
        />
      </PopoverContent>
    </Popover>
  )
}

export function LinkMenu({
  click,
  args,
  fill,
  onChange,
  disabled,
}: {
  click: Click | undefined
  args: MessageArg[]
  fill: Fill
  onChange: (click: Click | undefined) => void
  disabled?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [url, setUrl] = useState(click?.value ?? "")
  const done = (next: Click | undefined) => {
    onChange(next)
    setOpen(false)
  }
  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        if (next) setUrl(click?.value ?? "")
        setOpen(next)
      }}
    >
      <PopoverTrigger asChild>
        <MenuTrigger label={t("steward.message-editor.link")} pressed={Boolean(click)} disabled={disabled}>
          <LinkIcon aria-hidden />
        </MenuTrigger>
      </PopoverTrigger>
      <PopoverContent align="start" className="flex w-[min(20rem,calc(100vw-2rem))] flex-col gap-2">
        <ValueField
          value={url}
          onChange={setUrl}
          args={args}
          fill={fill}
          label={t("steward.message-editor.link")}
          placeholder="https://"
        />
        <ApplyRow
          ready={Boolean(url)}
          onApply={() => done({ action: "open_url", value: url })}
          onRemove={click ? () => done(undefined) : undefined}
        />
      </PopoverContent>
    </Popover>
  )
}

/** One of the message's actions around the selection: a click that runs what the code bound to that name. */
export function ActionMenu({
  actions,
  action,
  onChange,
  disabled,
}: {
  actions: MessageArg[]
  action: string | undefined
  onChange: (action: string | undefined) => void
  disabled?: boolean
}) {
  const [open, setOpen] = useState(false)
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <MenuTrigger label={t("steward.message-editor.action")} pressed={Boolean(action)} disabled={disabled}>
          <LightningIcon aria-hidden />
        </MenuTrigger>
      </PopoverTrigger>
      <PopoverContent align="start" className="flex w-[min(16rem,calc(100vw-2rem))] flex-col gap-1 p-1">
        {actions.map((candidate) => (
          <button
            key={candidate.name}
            type="button"
            aria-pressed={action === candidate.name}
            onClick={() => {
              onChange(candidate.name)
              setOpen(false)
            }}
            className={cn(
              "flex min-h-control items-center rounded-md px-2 text-left font-mono text-sm hover:bg-accent",
              action === candidate.name && "bg-accent",
            )}
          >
            {candidate.name}
          </button>
        ))}
        {action ? (
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={() => {
              onChange(undefined)
              setOpen(false)
            }}
          >
            {t("steward.message-editor.remove")}
          </Button>
        ) : null}
      </PopoverContent>
    </Popover>
  )
}

/** How one value is written: the styles its kind offers, or none for the kind's own way. */
export function ValueStyleMenu({
  styles,
  value,
  onChange,
  disabled,
}: {
  styles: string[]
  value: string | undefined
  onChange: (style: string | undefined) => void
  disabled?: boolean
}) {
  const [open, setOpen] = useState(false)
  const pick = (style: string | undefined) => {
    onChange(style)
    setOpen(false)
  }
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <MenuTrigger
          label={t("steward.message-editor.value-style")}
          pressed={value !== undefined}
          disabled={disabled || styles.length === 0}
        >
          <SlidersHorizontalIcon aria-hidden />
        </MenuTrigger>
      </PopoverTrigger>
      <PopoverContent align="start" className="flex w-[min(14rem,calc(100vw-2rem))] flex-col gap-1 p-1">
        {[undefined, ...styles].map((style) => (
          <button
            key={style ?? ""}
            type="button"
            aria-pressed={value === style}
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => pick(style)}
            className={cn(
              "flex min-h-control items-center rounded-md px-2 text-left text-sm hover:bg-accent",
              value === style && "bg-accent",
              style !== undefined && "font-mono",
            )}
          >
            {style ?? t("steward.message-editor.default-style")}
          </button>
        ))}
      </PopoverContent>
    </Popover>
  )
}

const DECORATION_LABELS = {
  bold: "steward.message-editor.bold",
  italic: "steward.message-editor.italic",
  underlined: "steward.message-editor.underlined",
  strikethrough: "steward.message-editor.strikethrough",
  obfuscated: "steward.message-editor.obfuscated",
} as const satisfies Record<Decoration, string>

function decorationLabel(decoration: Decoration): string {
  return t(DECORATION_LABELS[decoration])
}

const DECORATION_ICONS: Record<Decoration, ReactNode> = {
  bold: <TextBIcon aria-hidden />,
  italic: <TextItalicIcon aria-hidden />,
  underlined: <TextUnderlineIcon aria-hidden />,
  strikethrough: <TextStrikethroughIcon aria-hidden />,
  obfuscated: <ShuffleIcon aria-hidden />,
}

/**
 * The style buttons for a format, acting on the editor's selection.
 *
 * `hover` is the editor's own hover control, left out inside a hover, which has neither hover, click nor action.
 */
export function StyleButtons({
  format,
  style,
  tones,
  args,
  fill,
  onStyle,
  hover,
  nested,
  disabled,
}: {
  format: Format
  style: Style
  tones: Tones
  args: MessageArg[]
  fill: Fill
  onStyle: (change: (style: Style) => Style) => void
  hover?: ReactNode
  nested?: boolean
  disabled?: boolean
}) {
  const can = capabilities(format)
  const actions = args.filter((arg) => arg.action)
  return (
    <>
      {can.colour ? (
        <ColourMenu
          style={style}
          tones={tones}
          disabled={disabled}
          onChange={(patch) => onStyle((old) => ({ ...old, ...patch }))}
        />
      ) : null}
      {can.decorations.map((decoration) => (
        <ToolButton
          key={decoration}
          label={decorationLabel(decoration)}
          pressed={style[decoration] === true}
          disabled={disabled}
          onPress={() => onStyle((old) => ({ ...old, [decoration]: style[decoration] === true ? undefined : true }))}
        >
          {DECORATION_ICONS[decoration]}
        </ToolButton>
      ))}
      {can.code ? (
        <ToolButton
          label={t("steward.message-editor.code")}
          pressed={style.code === true}
          disabled={disabled}
          onPress={() => onStyle((old) => ({ ...old, code: style.code ? undefined : true }))}
        >
          <CodeIcon aria-hidden />
        </ToolButton>
      ) : null}
      {can.link ? (
        <LinkMenu
          click={style.click}
          args={args}
          fill={fill}
          disabled={disabled}
          onChange={(click) => onStyle((old) => ({ ...old, click }))}
        />
      ) : null}
      {can.hover && !nested ? hover : null}
      {can.click && !nested ? (
        <ClickMenu
          click={style.click}
          args={args}
          fill={fill}
          disabled={disabled}
          onChange={(click) => onStyle((old) => ({ ...old, click }))}
        />
      ) : null}
      {can.action && !nested && actions.length > 0 ? (
        <ActionMenu
          actions={actions}
          action={style.action}
          disabled={disabled}
          onChange={(action) => onStyle((old) => ({ ...old, action }))}
        />
      ) : null}
      {format !== "PLAIN" ? (
        <ToolButton
          label={t("steward.message-editor.clear-formatting")}
          disabled={disabled}
          onPress={() => onStyle(() => ({}))}
        >
          <TextTSlashIcon aria-hidden />
        </ToolButton>
      ) : null}
    </>
  )
}
