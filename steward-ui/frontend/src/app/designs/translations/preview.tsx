import { useEffect, useState } from "react"
import type { CSSProperties, ReactNode } from "react"
import { CaretDownIcon, HashIcon } from "@phosphor-icons/react"
import { cn } from "cn"

import type { GlyphInfo } from "@/lib/api"
import { gradientAt, hexOf, plainText, shadowOf } from "@/lib/rich-text"
import type { Format, Run, Style } from "@/lib/rich-text"

/**
 * A text drawn where it is shown: the Minecraft ones in the game's pixel font at the size the game
 * draws them, on the surface they appear on; the Discord ones the way Discord lays them out. Every
 * placeholder is its example value, so what is read here is what a player or a member reads.
 */

export type Fill = (name: string) => string

/** Minecraft draws its font eight pixels tall; at GUI scale 2 that is sixteen CSS pixels. */
const SCALE = 2

export const MINECRAFT_FONT: CSSProperties = {
  fontFamily: "Monocraft, ui-monospace, monospace",
  fontSize: 8 * SCALE,
  lineHeight: `${9 * SCALE + 2}px`,
  letterSpacing: 0,
  fontVariantLigatures: "none",
}

type McProps = {
  runs: Run[]
  fill: Fill
  glyphs: GlyphInfo[]
  /** The colour a run without one is drawn in. */
  base?: string
  shadow?: boolean
  scale?: number
}

/** Runs drawn in the game's look: colour, gradient, decorations, glyphs, the shadow, and hover. */
export function MinecraftText({ runs, fill, glyphs, base = "#FFFFFF", shadow = true, scale = SCALE }: McProps) {
  const gradients = gradientPositions(runs, fill)
  return (
    <>
      {runs.map((run, index) => {
        const drawn = (
          <McRun
            key={index}
            run={run}
            fill={fill}
            glyphs={glyphs}
            base={base}
            shadow={shadow}
            scale={scale}
            gradient={gradients[index]}
          />
        )
        return run.style.hover ? (
          <HoverTip
            key={index}
            tip={<MinecraftText runs={run.style.hover} fill={fill} glyphs={glyphs} scale={scale} />}
          >
            {drawn}
          </HoverTip>
        ) : (
          drawn
        )
      })}
    </>
  )
}

/** For each run inside a gradient, where along it its first character sits and how long it is. */
function gradientPositions(runs: Run[], fill: Fill): ({ start: number; total: number } | undefined)[] {
  const out: ({ start: number; total: number } | undefined)[] = runs.map(() => undefined)
  let index = 0
  while (index < runs.length) {
    const colours = runs[index].style.gradient
    if (!colours) {
      index += 1
      continue
    }
    let end = index
    const key = JSON.stringify(colours)
    while (end < runs.length && JSON.stringify(runs[end].style.gradient) === key) end += 1
    const lengths = runs.slice(index, end).map((run) => visible(run, fill).length)
    const total = lengths.reduce((sum, length) => sum + length, 0)
    let start = 0
    for (let at = index; at < end; at += 1) {
      out[at] = { start, total }
      start += lengths[at - index]
    }
    index = end
  }
  return out
}

function visible(run: Run, fill: Fill): string {
  return run.kind === "text" ? run.text : run.kind === "placeholder" ? fill(run.name) : ""
}

function McRun({
  run,
  fill,
  glyphs,
  base,
  shadow,
  scale,
  gradient,
}: Omit<McProps, "runs"> & {
  run: Run
  base: string
  shadow: boolean
  scale: number
  gradient?: { start: number; total: number }
}) {
  const style = run.style
  if (run.kind === "break") return <br />
  if (run.kind === "raw") return null
  if (run.kind === "glyph") return <Glyph name={run.name} glyphs={glyphs} scale={scale} />
  const text = visible(run, fill)
  const decoration = decorationStyle(style)
  if (style.gradient && gradient) {
    const colours = style.gradient
    return (
      <span style={decoration}>
        {[...text].map((char, at) => {
          const colour = gradientAt(colours, gradient.total <= 1 ? 0 : (gradient.start + at) / (gradient.total - 1))
          return (
            <span key={at} style={{ color: colour, textShadow: shadow ? shadowCss(colour, scale) : undefined }}>
              {style.obfuscated ? <Obfuscated text={char} /> : char}
            </span>
          )
        })}
      </span>
    )
  }
  const colour = style.colour ? hexOf(style.colour) : base
  return (
    <span style={{ ...decoration, color: colour, textShadow: shadow ? shadowCss(colour, scale) : undefined }}>
      {style.obfuscated ? <Obfuscated text={text} /> : text}
    </span>
  )
}

function shadowCss(colour: string, scale: number): string {
  return `${scale / 2 + 0.5}px ${scale / 2 + 0.5}px 0 ${shadowOf(colour)}`
}

function decorationStyle(style: Style): CSSProperties {
  const lines = [style.underlined && "underline", style.strikethrough && "line-through"].filter(Boolean).join(" ")
  return {
    fontWeight: style.bold ? 700 : undefined,
    fontStyle: style.italic ? "italic" : undefined,
    textDecorationLine: lines || undefined,
    textDecorationThickness: lines ? 2 : undefined,
    textUnderlineOffset: lines ? 2 : undefined,
    fontSynthesis: "weight style",
  }
}

const NOISE = "ABCDEFGHJKLMNOPQRSTUVWXYZabdefghkmnopqrstuwxyz0123456789#$%&?"

/** `<obfuscated>`: the same number of characters, changing all the time. */
function Obfuscated({ text }: { text: string }) {
  const [tick, setTick] = useState(0)
  useEffect(() => {
    const timer = window.setInterval(() => setTick((value) => value + 1), 90)
    return () => window.clearInterval(timer)
  }, [])
  return (
    <>
      {[...text]
        .map((char, at) => (char === " " ? " " : NOISE[(at * 7 + tick * 13 + char.charCodeAt(0)) % NOISE.length]))
        .join("")}
    </>
  )
}

export function Glyph({
  name,
  glyphs,
  scale = SCALE,
  className,
}: {
  name: string
  glyphs: GlyphInfo[]
  scale?: number
  className?: string
}) {
  const glyph = glyphs.find((candidate) => candidate.name === name)
  if (!glyph) {
    return (
      <span
        className={cn("inline-block border border-dashed border-current px-0.5 text-[0.6em] align-middle", className)}
      >
        {name}
      </span>
    )
  }
  return (
    <img
      src={`/glyphs/${glyph.image}`}
      alt={name}
      className={cn("inline-block", className)}
      style={{
        height: glyph.height * scale,
        verticalAlign: (glyph.ascent - glyph.height) * scale,
        imageRendering: "pixelated",
        marginInline: scale / 2,
      }}
    />
  )
}

/** The game's tooltip: near-black, a purple frame, drawn above whatever it belongs to. */
export function McTooltip({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span
      className={cn("inline-block rounded-[2px] px-2 py-1 text-left whitespace-pre-wrap", className)}
      style={{
        background: "#100010F0",
        boxShadow: "inset 0 0 0 2px #100010F0, inset 0 0 0 4px #5000FF50, 0 0 0 2px #100010F0",
        ...MINECRAFT_FONT,
      }}
    >
      {children}
    </span>
  )
}

function HoverTip({ tip, children }: { tip: ReactNode; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  return (
    <span
      className="relative cursor-help"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onClick={() => setOpen((value) => !value)}
    >
      {children}
      {open ? (
        <span className="absolute bottom-full left-0 z-20 mb-1 w-max max-w-72">
          <McTooltip>{tip}</McTooltip>
        </span>
      ) : null}
    </span>
  )
}

// --- the places ----------------------------------------------------------------------------

const SKY = "linear-gradient(180deg, #6b8cc4 0%, #9db8e3 55%, #5f8a3a 55.5%, #4b6e2c 70%, #3b2a1d 70.5%, #2e2117 100%)"

type PreviewProps = {
  runs: Run[]
  format: Format
  shown: string | undefined
  keyName: string
  fill: Fill
  glyphs: GlyphInfo[]
  className?: string
}

export function Preview({ runs, format, shown, keyName, fill, glyphs, className }: PreviewProps) {
  if (format === "DISCORD_MARKDOWN" || shown?.startsWith("DISCORD_")) {
    return (
      <DiscordPreview
        runs={runs}
        shown={shown ?? "DISCORD_MESSAGE"}
        keyName={keyName}
        fill={fill}
        className={className}
      />
    )
  }
  const text = (base = "#FFFFFF", shadow = true, scale = SCALE) => (
    <MinecraftText runs={runs} fill={fill} glyphs={glyphs} base={base} shadow={shadow} scale={scale} />
  )
  const scene = (children: ReactNode, background = SKY) => (
    <div
      className={cn("relative flex min-h-56 w-full overflow-hidden rounded-md", className)}
      style={{ background, ...MINECRAFT_FONT }}
    >
      {children}
    </div>
  )
  switch (shown) {
    case "TITLE":
    case "SUBTITLE": {
      const title = shown === "TITLE"
      return scene(
        <div
          className="flex w-full flex-col items-center justify-center gap-2 px-3 text-center"
          style={{ background: "rgba(0,0,0,0.15)" }}
        >
          <div
            style={{ fontSize: 8 * (title ? 4 : 2), lineHeight: `${9 * (title ? 4 : 2) + 2}px` }}
            className="break-words"
          >
            {text("#FFFFFF", true, title ? 4 : 2)}
          </div>
        </div>,
      )
    }
    case "ACTION_BAR":
      return scene(
        <div className="mt-auto flex w-full flex-col items-center gap-2 px-3 pb-3 text-center">
          <div>{text()}</div>
          <Hotbar />
        </div>,
      )
    case "BOSS_BAR":
      return scene(
        <div className="flex w-full flex-col items-center gap-1 px-3 pt-3 text-center">
          <div>{text()}</div>
          <div
            className="h-2.5 w-full max-w-[364px] rounded-[1px]"
            style={{ background: "#4a2a64", boxShadow: "inset 0 0 0 1px #1d0f28" }}
          >
            <div className="h-full w-2/3" style={{ background: "#c05bf0" }} />
          </div>
        </div>,
      )
    case "TAB_LIST":
      return scene(
        <div className="flex w-full justify-center px-3 pt-3">
          <div
            className="flex w-full max-w-[520px] flex-col items-center gap-1 px-2 py-1 text-center"
            style={{ background: "rgba(0,0,0,0.5)" }}
          >
            <div>{text()}</div>
            <div className="grid w-full grid-cols-2 gap-px">
              {["Steve", "Alex", "Nordlicht", "Effi"].map((name) => (
                <span
                  key={name}
                  className="px-1 text-left"
                  style={{ background: "rgba(255,255,255,0.12)", color: "#FFFFFF" }}
                >
                  {name}
                </span>
              ))}
            </div>
          </div>
        </div>,
      )
    case "SIDEBAR":
      return scene(
        <div className="ml-auto flex items-center pr-2">
          <div className="flex flex-col gap-0 px-1.5 py-1" style={{ background: "rgba(0,0,0,0.3)" }}>
            <div className="text-center" style={{ background: "rgba(0,0,0,0.1)" }}>
              <span style={{ color: "#FFFFFF" }}>nordtal</span>
            </div>
            <div>{text()}</div>
          </div>
        </div>,
      )
    case "GUI":
      return scene(
        <div className="flex w-full items-center justify-center p-3" style={{ background: "rgba(16,16,16,0.75)" }}>
          <McTooltip className="max-w-full">{text()}</McTooltip>
        </div>,
      )
    case "HOLOGRAM":
      return scene(
        <div className="flex w-full items-center justify-center p-3 text-center">
          <span className="px-1" style={{ background: "rgba(0,0,0,0.25)" }}>
            {text()}
          </span>
        </div>,
      )
    case "KICK_SCREEN":
      return scene(
        <div className="flex w-full flex-col items-center justify-center gap-4 p-3 text-center">
          <div style={{ color: "#AAAAAA" }}>Connection lost</div>
          <div>{text()}</div>
          <div
            className="w-full max-w-[400px] rounded-[2px] py-1 text-center"
            style={{ background: "#6f6f6f", boxShadow: "inset 0 0 0 2px #000", color: "#FFFFFF" }}
          >
            Back to server list
          </div>
        </div>,
        "#1f1a14",
      )
    case "SERVER_LIST":
      return scene(
        <div className="flex w-full items-center justify-center p-3">
          <div className="flex w-full max-w-[610px] gap-2 p-1" style={{ boxShadow: "inset 0 0 0 2px #808080" }}>
            <div className="size-16 shrink-0 rounded-[2px]" style={{ background: "#4a63d8" }} />
            <div className="min-w-0 flex-1">
              <div style={{ color: "#FFFFFF" }}>nordtal</div>
              <div>{text("#AAAAAA")}</div>
            </div>
          </div>
        </div>,
        "#1f1a14",
      )
    default:
      return scene(
        <div className="mt-auto w-full p-2 pb-10">
          <div className="max-w-[640px] px-1 py-0.5 break-words" style={{ background: "rgba(0,0,0,0.5)" }}>
            {text()}
          </div>
        </div>,
      )
  }
}

function Hotbar() {
  return (
    <div className="grid grid-cols-9 gap-0" style={{ boxShadow: "0 0 0 2px #000" }}>
      {Array.from({ length: 9 }, (_, slot) => (
        <span key={slot} className="size-8" style={{ background: "#8b8b8b80", boxShadow: "inset 0 0 0 2px #373737" }} />
      ))}
    </div>
  )
}

// --- Discord -------------------------------------------------------------------------------

/** How long Discord lets a text be where it is shown, measured with the example values filled in. */
export function discordLimit(shown: string | undefined, key: string): number | null {
  switch (shown) {
    case "DISCORD_MESSAGE":
      return 2000
    case "DISCORD_EMBED":
      return /title/i.test(key) ? 256 : 4096
    case "DISCORD_BUTTON":
      return 80
    case "DISCORD_MODAL":
      return 45
    case "DISCORD_SELECT":
      return /placeholder|choose/i.test(key) ? 150 : 100
    case "DISCORD_CHANNEL":
      return 100
    case "DISCORD_COMMAND":
      return 100
    default:
      return null
  }
}

function DiscordRuns({ runs, fill }: { runs: Run[]; fill: Fill }) {
  return (
    <>
      {runs.map((run, index) => {
        if (run.kind === "break") return <br key={index} />
        if (run.kind === "raw" || run.kind === "glyph") return null
        const style = run.style
        const value = run.kind === "text" ? run.text : fill(run.name)
        const mention = run.kind === "placeholder" && value.startsWith("@")
        let node: ReactNode = (
          <span
            key={index}
            className={cn(
              style.bold && "font-semibold",
              style.italic && "italic",
              style.underlined && "underline",
              style.strikethrough && "line-through",
              mention && "rounded-[3px] px-0.5 font-medium",
              style.code && "rounded-[4px] px-1 py-0.5 font-mono text-[0.85em]",
            )}
            style={{
              ...(mention ? { background: "rgba(88,101,242,0.3)", color: "#c9cdfb" } : {}),
              ...(style.code ? { background: "#2b2d31", border: "1px solid #1e1f22" } : {}),
            }}
          >
            {value}
          </span>
        )
        if (style.click?.action === "open_url") {
          node = (
            <span key={index} style={{ color: "#00a8fc" }} className="hover:underline">
              {node}
            </span>
          )
        }
        return node
      })}
    </>
  )
}

function DiscordPreview({
  runs,
  shown,
  keyName,
  fill,
  className,
}: {
  runs: Run[]
  shown: string
  keyName: string
  fill: Fill
  className?: string
}) {
  const limit = discordLimit(shown, keyName)
  const length = plainText(runs, fill).length
  const body = <DiscordRuns runs={runs} fill={fill} />
  const surface = (children: ReactNode) => (
    <div
      className={cn("relative flex w-full flex-col gap-2 rounded-md p-4 text-[15px] leading-[1.375]", className)}
      style={{ background: "#313338", color: "#dbdee1" }}
    >
      {children}
      {limit ? (
        <span
          className={cn(
            "absolute right-2 bottom-1 text-xs tabular-nums",
            length > limit ? "text-destructive" : "opacity-50",
          )}
        >
          {length}/{limit}
        </span>
      ) : null}
    </div>
  )
  const author = (children: ReactNode) => (
    <div className="flex gap-3">
      <div className="size-10 shrink-0 rounded-full" style={{ background: "#4a63d8" }} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1.5">
          <span className="font-medium text-white">Nordtal</span>
          <span className="rounded-[3px] px-1 text-[10px] font-semibold text-white" style={{ background: "#5865f2" }}>
            APP
          </span>
          <span className="text-xs" style={{ color: "#949ba4" }}>
            Today at 21:04
          </span>
        </div>
        {children}
      </div>
    </div>
  )
  switch (shown) {
    case "DISCORD_EMBED": {
      const title = /title/i.test(keyName)
      return surface(
        author(
          <div
            className="mt-1 max-w-[432px] rounded-[4px] border-l-4 py-2 pr-4 pl-3"
            style={{ background: "#2b2d31", borderColor: "#4a63d8" }}
          >
            {title ? (
              <div className="font-semibold text-white">{body}</div>
            ) : (
              <div className="text-sm whitespace-pre-wrap">{body}</div>
            )}
          </div>,
        ),
      )
    }
    case "DISCORD_BUTTON":
      return surface(
        author(
          <div className="mt-2 flex flex-wrap gap-2">
            <span
              className="inline-flex h-8 items-center rounded-[3px] px-4 text-sm font-medium text-white"
              style={{ background: "#5865f2" }}
            >
              {body}
            </span>
          </div>,
        ),
      )
    case "DISCORD_MODAL":
      return surface(
        <div className="mx-auto w-full max-w-[440px] rounded-md p-4" style={{ background: "#2b2d31" }}>
          <div className="text-xl font-semibold text-white">{body}</div>
          <div className="mt-4 h-10 rounded-[3px]" style={{ background: "#1e1f22" }} />
        </div>,
      )
    case "DISCORD_SELECT":
      return surface(
        author(
          <div
            className="mt-2 flex h-10 max-w-[400px] items-center justify-between rounded-[4px] px-3 text-sm"
            style={{ background: "#1e1f22", color: "#949ba4" }}
          >
            <span className="truncate">{body}</span>
            <CaretDownIcon aria-hidden className="size-4 shrink-0" />
          </div>,
        ),
      )
    case "DISCORD_CHANNEL":
      return surface(
        <div className="w-full max-w-60 rounded-[4px] p-2" style={{ background: "#2b2d31" }}>
          <div
            className="flex items-center gap-1.5 rounded-[4px] px-2 py-1.5"
            style={{ background: "#404249", color: "#ffffff" }}
          >
            <HashIcon aria-hidden className="size-5 shrink-0" style={{ color: "#80848e" }} />
            <span className="truncate">{plainText(runs, fill).toLowerCase().replace(/\s+/g, "-")}</span>
          </div>
        </div>,
      )
    case "DISCORD_COMMAND":
      return surface(
        <div className="rounded-[4px] p-2" style={{ background: "#2b2d31" }}>
          <span className="text-white">/nordtal</span> <span style={{ color: "#949ba4" }}>{body}</span>
        </div>,
      )
    default:
      return surface(author(<div className="whitespace-pre-wrap">{body}</div>))
  }
}
