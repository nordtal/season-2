import { useEffect, useId, useState } from "react"
import type { Npc, NpcStep, NpcTextMeasurement, NpcTextMeasureRequest, PlateColour, PlateIcon } from "@/lib/api"
import type { Language } from "@/lib/npc"
import { artworkAddress, measureKey } from "@/lib/queries-npcs"
import { t } from "@/lib/texts"

/**
 * A step as the game draws it: the dialogue panel's own art from the pack and the selected page of its text as Steward
 * measured and fitted it. Every number is a pixel of the game's interface, as `DialogueScreen` places it.
 */

/** The panel: its width, its bottom above the screen's, and where the portrait, the tab and the text stand in it. */
const PANEL = { width: 300, bottom: 16, portrait: 6, tab: 56, text: 60, mark: 288 }
/** Even padding around the cropped panel, portrait overhang and bottom input hint marks, in game pixels. */
const FRAME_PADDING = 8
/** How far the portrait overhangs above the panel's top edge. */
const PORTRAIT_OVERHANG = 10
/** Bottom row of the input hint marks above the screen's bottom (top at 11, height 8). */
const HINT_BOTTOM = 3
/** The dark backdrop behind the cropped panel, matching the lower game screen above the bottom band. */
const BACKDROP = "#181a20"
/** The width of the panel art's first piece; a second piece draws the rest of the panel. */
const TILE = 256
/** The answers' rows: the strip, the chevron of the selected answer, its label and its number. */
const ANSWER = { strip: 56, chevron: 60, label: 68, numberEnd: 291, step: 12, lowest: 36 }
/** The name tab: its height and the space on each side of the name. */
const TAB = { height: 13, padding: 6 }
/** The height of the dark band along the bottom of the screen. */
const BAND = 58
const ART = "/glyphs/npc"

/** The size at which the browser's face draws a capital 7 pixels high, as the game's does, and how far a letter then moves on. */
const FACE = 9
const ADVANCE = 6
/** The same for the five-pixel letters of the hints, the page count and the answer numbers. */
const SMALL_FACE = 6.4
const SMALL_ADVANCE = 4

const INK = "#2b2b2b"
const SOFT = "#404040"
const FAINT = "#505050"
const ACCENT = "#284894"
/** The tab icon's width and the space after it, before the name. */
const ICON = { width: 7, gap: 4 }

/** The panel heights: speaking up to six lines, and a question by its lines and answers. */
function heightOf(lines: number, answers: number): number {
  const count = Math.max(1, Math.min(answers === 0 ? 6 : 4, lines))
  if (answers === 0) return Math.max(48, 31 + 10 * (count - 1))
  return Math.max(48, 46 + 10 * (count - 1) + 12 * (answers - 1))
}

/** First spoken line's top row above the screen's bottom, matching DialogueScreen.firstLineBottom. */
function firstLineBottom(height: number): number {
  return PANEL.bottom + height - 12
}

/** Returns `hex` with every channel times `factor`, or moved `factor` of the way to white when `lighter`. */
function shade(hex: string, factor: number, lighter = false): string {
  const value = Number.parseInt(hex.slice(1), 16)
  const channels = [16, 8, 0].map((shift) => (value >> shift) & 0xff)
  const moved = channels.map((channel) => Math.round(lighter ? channel + (255 - channel) * factor : channel * factor))
  return `rgb(${moved.join(",")})`
}

/** The requests that measure one step in one language: the name, each page and each answer. */
export function requestsOf(npc: Npc, step: NpcStep, language: Language): NpcTextMeasureRequest[] {
  return [
    { text: npc.name[language], kind: "NAME", answers: 0 },
    ...step.pages.map((page, index) => ({
      text: page[language],
      kind: "BODY" as const,
      answers: index === step.pages.length - 1 ? step.answers.length : 0,
    })),
    ...step.answers.map((answer) => ({ text: answer.text[language], kind: "ANSWER" as const, answers: 0 })),
  ]
}

/** The lines the panel shows for one page: the fitted ones from Steward, or the raw text until they arrive. */
function linesOf(measurement: NpcTextMeasurement | undefined, text: string, limit: number): string[] {
  if (measurement === undefined) return text === "" ? [] : [text]
  return measurement.fitted.slice(0, limit)
}

/** A line in the game's face. It is pressed into `room` when the browser's face sets it wider than the game's does. */
/** The columns the speaker's name takes in its tab, as DialogueRoom.NAME in the game. */
const NAME_ROOM = 210

function Line({
  x,
  y,
  room,
  fill,
  end,
  bold,
  small,
  children,
}: {
  x: number
  y: number
  room: number
  fill: string
  end?: boolean
  bold?: boolean
  small?: boolean
  children: string
}) {
  const pressed = children.length * (small ? SMALL_ADVANCE : ADVANCE) > room
  return (
    <text
      x={x}
      y={y + (small ? 5 : 7)}
      fill={fill}
      fontSize={small ? SMALL_FACE : FACE}
      fontWeight={bold ? "bold" : undefined}
      textAnchor={end ? "end" : undefined}
      className="font-mono"
      {...(pressed ? { textLength: room, lengthAdjust: "spacingAndGlyphs" } : {})}
    >
      {children}
    </text>
  )
}

function Art({ name, x, y, width, height }: { name: string; x: number; y: number; width: number; height: number }) {
  return (
    <image
      href={`${ART}/${name}.png`}
      x={x}
      y={y}
      width={width}
      height={height}
      preserveAspectRatio="none"
      style={{ imageRendering: "pixelated" }}
    />
  )
}

/** One rectangle of a skin's 64 by 64 sheet, drawn at `x`, `y` in the size given. */
function SkinPart({
  skin,
  from,
  x,
  y,
  width,
  height,
}: {
  skin: string
  from: [number, number, number, number]
  x: number
  y: number
  width: number
  height: number
}) {
  return (
    <svg x={x} y={y} width={width} height={height} viewBox={from.join(" ")} preserveAspectRatio="none">
      <image href={skin} width={64} height={64} style={{ imageRendering: "pixelated" }} />
    </svg>
  )
}

/** A body part and the layer the skin wears over it. */
function Limb({
  skin,
  base,
  over,
  x,
  y,
  scale,
}: {
  skin: string
  base: [number, number, number, number]
  over: [number, number, number, number]
  x: number
  y: number
  scale: number
}) {
  const size = { x, y, width: base[2] * scale, height: base[3] * scale }
  return (
    <>
      <SkinPart skin={skin} from={base} {...size} />
      <SkinPart skin={skin} from={over} {...size} />
    </>
  )
}

/**
 * The NPC from the front, `scale` pixels per pixel of its skin, with its head's left edge at `x`. Without a skin it is
 * the plain figure the game shows until the pack holds one.
 */
export function Figure({
  skin,
  slim,
  x,
  y,
  scale,
}: {
  skin: string | undefined
  slim: boolean
  x: number
  y: number
  scale: number
}) {
  const arm = slim ? 3 : 4
  const head = 8 * scale
  const torso = y + head
  const legs = torso + 12 * scale
  if (skin === undefined) {
    return (
      <g>
        <rect x={x} y={y} width={head} height={head} fill="#b7b7b7" />
        <rect x={x} y={torso} width={head} height={12 * scale} fill="#8f8f8f" />
        <rect x={x - 4 * scale} y={torso} width={4 * scale} height={12 * scale} fill="#a3a3a3" />
        <rect x={x + head} y={torso} width={4 * scale} height={12 * scale} fill="#a3a3a3" />
        <rect x={x} y={legs} width={head} height={12 * scale} fill="#7a7a7a" />
      </g>
    )
  }
  return (
    <g>
      <Limb skin={skin} base={[4, 20, 4, 12]} over={[4, 36, 4, 12]} x={x} y={legs} scale={scale} />
      <Limb skin={skin} base={[20, 52, 4, 12]} over={[4, 52, 4, 12]} x={x + 4 * scale} y={legs} scale={scale} />
      <Limb skin={skin} base={[20, 20, 8, 12]} over={[20, 36, 8, 12]} x={x} y={torso} scale={scale} />
      <Limb skin={skin} base={[44, 20, arm, 12]} over={[44, 36, arm, 12]} x={x - arm * scale} y={torso} scale={scale} />
      <Limb skin={skin} base={[36, 52, arm, 12]} over={[52, 52, arm, 12]} x={x + head} y={torso} scale={scale} />
      <Limb skin={skin} base={[8, 8, 8, 8]} over={[40, 8, 8, 8]} x={x} y={y} scale={scale} />
    </g>
  )
}

/** The NPC alone, from head to feet, as the settings show its skin. */
export function FigureCard({ npc, className }: { npc: Npc; className?: string }) {
  return (
    <svg viewBox="0 0 64 104" aria-hidden className={className}>
      <Figure skin={skinAddress(npc)} slim={npc.skinModel === "SLIM"} x={20} y={4} scale={3} />
    </svg>
  )
}

/** Where a skin's PNG is read, or nothing for the default skin. */
export function skinAddress(npc: Npc): string | undefined {
  return npc.skin === undefined ? undefined : `/api/npc-skins/${npc.skin}`
}

/** An SVG filter that multiplies every colour channel by `factor` and keeps alpha. */
function Shade({ id, factor }: { id: string; factor: number }) {
  return (
    <filter id={id} colorInterpolationFilters="sRGB">
      <feColorMatrix type="matrix" values={`${factor} 0 0 0 0 0 ${factor} 0 0 0 0 0 ${factor} 0 0 0 0 0 1 0`} />
    </filter>
  )
}

/**
 * The NPC's bust in the portrait's well, as steward-agent draws it from the skin: the head three times as large, the
 * upper body and arms twice, the arms and body shaded, the head's top row lit and its bottom row shaded, and a dark
 * outline on every clear pixel beside the figure.
 */
function Bust({ skin, slim, x, y }: { skin: string; slim: boolean; x: number; y: number }) {
  const arm = slim ? 3 : 4
  const id = `npc-bust-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`
  const figure = `${id}-figure`
  return (
    <svg x={x} y={y} width={36} height={36} viewBox="0 0 36 36" overflow="hidden">
      <defs>
        <Shade id={`${id}-right`} factor={0.78} />
        <Shade id={`${id}-left`} factor={0.7} />
        <Shade id={`${id}-torso`} factor={0.88} />
        <Shade id={`${id}-lit`} factor={1.12} />
        <Shade id={`${id}-dim`} factor={0.8} />
        {/* The four offsets of the figure's stepped alpha mark its four-way neighbours, as NpcPack.touchesInk does. */}
        <filter
          id={`${id}-outline`}
          filterUnits="userSpaceOnUse"
          x={0}
          y={0}
          width={36}
          height={36}
          colorInterpolationFilters="sRGB"
        >
          <feComponentTransfer in="SourceAlpha" result="ink">
            <feFuncA type="linear" slope={255} />
          </feComponentTransfer>
          <feOffset in="ink" dx={1} result="east" />
          <feOffset in="ink" dx={-1} result="west" />
          <feOffset in="ink" dy={1} result="south" />
          <feOffset in="ink" dy={-1} result="north" />
          <feMerge result="near">
            <feMergeNode in="east" />
            <feMergeNode in="west" />
            <feMergeNode in="south" />
            <feMergeNode in="north" />
          </feMerge>
          <feComposite in="near" in2="ink" operator="out" result="ring" />
          <feFlood floodColor="#101014" />
          <feComposite in2="ring" operator="in" result="outline" />
          <feMerge>
            <feMergeNode in="outline" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
        <clipPath id={`${id}-rest`}>
          <rect width={36} height={2} />
          <rect y={3} width={36} height={22} />
          <rect y={26} width={36} height={10} />
        </clipPath>
        <clipPath id={`${id}-top`}>
          <rect y={2} width={36} height={1} />
        </clipPath>
        <clipPath id={`${id}-bottom`}>
          <rect y={25} width={36} height={1} />
        </clipPath>
        <g id={figure}>
          <g filter={`url(#${id}-right)`}>
            <Limb skin={skin} base={[44, 20, arm, 6]} over={[44, 36, arm, 6]} x={10 - 2 * arm} y={26} scale={2} />
          </g>
          <g filter={`url(#${id}-left)`}>
            <Limb skin={skin} base={[36, 52, arm, 6]} over={[52, 52, arm, 6]} x={26} y={26} scale={2} />
          </g>
          <g filter={`url(#${id}-torso)`}>
            <Limb skin={skin} base={[20, 20, 8, 6]} over={[20, 36, 8, 6]} x={10} y={25} scale={2} />
          </g>
          <Limb skin={skin} base={[8, 8, 8, 8]} over={[40, 8, 8, 8]} x={6} y={2} scale={3} />
        </g>
      </defs>
      <g filter={`url(#${id}-outline)`}>
        <use href={`#${figure}`} clipPath={`url(#${id}-rest)`} />
        <use href={`#${figure}`} clipPath={`url(#${id}-top)`} filter={`url(#${id}-lit)`} />
        <use href={`#${figure}`} clipPath={`url(#${id}-bottom)`} filter={`url(#${id}-dim)`} />
      </g>
    </svg>
  )
}

/**
 * The portrait's empty well at 38 by 38, as vanilla's slot bevel draws it around the 36 by 36 picture interior.
 * It hides the default bust of the pack's portrait.
 */
function Well({ x, y }: { x: number; y: number }) {
  return (
    <g>
      <rect x={x} y={y} width={38} height={38} fill="#8b8b8b" />
      <rect x={x} y={y} width={37} height={1} fill="#373737" />
      <rect x={x} y={y} width={1} height={37} fill="#373737" />
      <rect x={x + 1} y={y + 37} width={37} height={1} fill="#ffffff" />
      <rect x={x + 37} y={y + 1} width={1} height={37} fill="#ffffff" />
    </g>
  )
}

/**
 * The NPC's uploaded picture inside the portrait's well at 36 by 36, as the pack draws it.
 * A small picture keeps its pixels; a larger one is smoothed, closer to the pack's average of each block.
 */
function Artwork({ id, x, y }: { id: string; x: number; y: number }) {
  const address = artworkAddress(id)
  const [small, setSmall] = useState(true)
  useEffect(() => {
    const picture = new Image()
    const loaded = () => setSmall(picture.naturalWidth <= 80)
    picture.addEventListener("load", loaded)
    picture.src = address
    return () => picture.removeEventListener("load", loaded)
  }, [address])
  return (
    <image
      href={address}
      x={x}
      y={y}
      width={36}
      height={36}
      preserveAspectRatio="none"
      style={{ imageRendering: small ? "pixelated" : "auto" }}
    />
  )
}

export function DialoguePreview({
  npc,
  step,
  pageIndex = 0,
  language,
  measured,
}: {
  npc: Npc
  step: NpcStep
  pageIndex?: number
  language: Language
  measured: Map<string, NpcTextMeasurement> | undefined
}) {
  const requests = requestsOf(npc, step, language)
  const name = requests[0]
  const pageCount = Math.max(1, step.pages.length)
  const shownIndex = Math.max(0, Math.min(pageIndex, pageCount - 1))
  const body = requests[1 + shownIndex] ?? { text: "", kind: "BODY" as const, answers: 0 }
  const answers = requests.slice(1 + step.pages.length)
  const last = shownIndex === pageCount - 1
  const rows = last ? answers.length : 0
  const limit = rows === 0 ? 6 : 4
  const lines = linesOf(measured?.get(measureKey(body)), body.text, limit)
  const height = heightOf(lines.length, rows)
  const left = FRAME_PADDING
  const viewWidth = PANEL.width + 2 * FRAME_PADDING
  const top = PANEL.bottom + height
  const topEdge = top + PORTRAIT_OVERHANG + FRAME_PADDING
  const viewHeight = topEdge - (HINT_BOTTOM - FRAME_PADDING)
  /** The cropped frame's row of a row `b` above the screen's bottom. */
  const row = (b: number) => topEdge - b
  const skin = skinAddress(npc)
  // The tab takes the plate's colour and icon and fits the bold name; letters stand in until it is measured.
  const colour = PLATE_RGB[npc.plate.colour]
  const icon = npc.plate.icon === undefined ? 0 : ICON.width + ICON.gap
  const nameMeasurement = measured?.get(measureKey(name))
  const shownName = nameMeasurement?.fitted[0] ?? name.text
  const nameWidth = Math.min(NAME_ROOM, nameMeasurement?.width ?? name.text.length * (ADVANCE + 1))
  const tab = TAB.padding + icon + nameWidth + TAB.padding
  return (
    <svg
      viewBox={`0 0 ${viewWidth} ${viewHeight}`}
      role="img"
      aria-label={t("steward.npcs.preview")}
      className="block w-full rounded-lg border"
    >
      <rect width={viewWidth} height={viewHeight} fill={BACKDROP} />
      {[0, TILE].map((x) => (
        <Art key={x} name="band_4" x={x} y={viewHeight - BAND} width={x === 0 ? TILE + 0.5 : TILE} height={BAND} />
      ))}
      <Art name={`panel_${height}_0`} x={left} y={row(top)} width={TILE + 0.5} height={height} />
      <Art name={`panel_${height}_1`} x={left + TILE} y={row(top)} width={PANEL.width - TILE} height={height} />
      <Art name="portrait" x={left + PANEL.portrait} y={row(top + 10)} width={48} height={64} />
      {npc.artwork !== undefined ? (
        <>
          <Well x={left + PANEL.portrait + 5} y={row(top + 10) + 5} />
          <Artwork id={npc.artwork} x={left + PANEL.portrait + 6} y={row(top + 10) + 6} />
        </>
      ) : skin === undefined ? null : (
        <>
          <Well x={left + PANEL.portrait + 5} y={row(top + 10) + 5} />
          <Bust skin={skin} slim={npc.skinModel === "SLIM"} x={left + PANEL.portrait + 6} y={row(top + 10) + 6} />
        </>
      )}
      <g>
        <rect
          x={left + PANEL.tab}
          y={row(top + 7) + 1}
          width={tab}
          height={TAB.height - 2}
          fill={shade(colour, 0.45)}
        />
        <rect
          x={left + PANEL.tab + 1}
          y={row(top + 7)}
          width={tab - 2}
          height={TAB.height}
          fill={shade(colour, 0.45)}
        />
        <rect x={left + PANEL.tab + 1} y={row(top + 7) + 1} width={tab - 2} height={TAB.height - 2} fill={colour} />
        <rect
          x={left + PANEL.tab + 1}
          y={row(top + 7) + 1}
          width={tab - 2}
          height={1}
          fill={shade(colour, 0.25, true)}
        />
        {npc.plate.icon === undefined ? null : (
          <PlateSymbol icon={npc.plate.icon} x={left + PANEL.tab + TAB.padding} y={row(top + 4)} colour="#ffffff" />
        )}
        <Line
          x={left + PANEL.tab + TAB.padding + icon}
          y={row(top + 4)}
          room={Math.max(1, nameWidth)}
          fill="#ffffff"
          bold
        >
          {shownName}
        </Line>
      </g>
      {lines.map((line, index) => (
        <Line key={index} x={left + PANEL.text} y={row(firstLineBottom(height) - 10 * index)} room={226} fill={INK}>
          {line}
        </Line>
      ))}
      {answers.slice(0, rows).map((answer, index) => {
        const b = ANSWER.lowest + ANSWER.step * (rows - 1 - index)
        const on = index === 0
        const label = measured?.get(measureKey(answer))?.fitted[0] ?? answer.text
        return (
          <g key={index}>
            {on ? (
              <>
                <Art name="strip" x={left + ANSWER.strip} y={row(b)} width={239} height={64} />
                <Art name="chevron" x={left + ANSWER.chevron} y={row(b - 2)} width={4} height={64} />
              </>
            ) : null}
            <Line x={left + ANSWER.label} y={row(b - 2)} room={213} fill={on ? ACCENT : SOFT} bold={on}>
              {label}
            </Line>
            <Line x={left + ANSWER.numberEnd} y={row(b - 3)} room={8} fill={on ? ACCENT : FAINT} end small>
              {String(index + 1)}
            </Line>
          </g>
        )
      })}
      {pageCount > 1 && rows === 0 ? (
        <Line x={left + 284} y={row(27)} room={16} fill={FAINT} end small>
          {`${shownIndex + 1}/${pageCount}`}
        </Line>
      ) : null}
      {rows === 0 ? <Art name="more" x={left + PANEL.mark} y={row(26)} width={5} height={3} /> : null}
      <HintMarks answers={rows > 0} right={left + 298} y={row(11)} />
    </svg>
  )
}

/** The marks of the inputs the game asks for under the panel: the wheel and a left-click with answers, a right-click without. */
function HintMarks({ answers, right, y }: { answers: boolean; right: number; y: number }) {
  return answers ? (
    <>
      <Art name="mouse_left" x={right - 7} y={y} width={7} height={8} />
      <Art name="mouse_wheel" x={right - 7 - 9 - 7} y={y} width={7} height={8} />
    </>
  ) : (
    <Art name="mouse_right" x={right - 7} y={y} width={7} height={8} />
  )
}

/** The plate colours as the pack draws them, in the order Steward offers them. */
export const PLATE_RGB: Readonly<Record<PlateColour, string>> = {
  BLUE: "#345AB4",
  RED: "#A83232",
  AMBER: "#8A5A00",
  GREEN: "#2E7032",
  TEAL: "#00707A",
  VIOLET: "#6B44A8",
  ROSE: "#A63A6E",
  SLATE: "#4A5468",
}
/** The symbols a plate can show, in the order of the pack's sheet of tab icons. */
export const PLATE_ICONS: readonly PlateIcon[] = [
  "STAR",
  "PIN",
  "RAIL",
  "MEDAL",
  "FLAG",
  "WHEEL",
  "SWORDS",
  "SHIELD",
  "HAND_IN",
  "CHEST",
  "EYE",
  "PICKAXE",
  "BOX",
  "HEART",
]

/**
 * One 7 by 7 symbol of the pack's sheet of tab icons, at `x`, `y` inside an SVG or `size` pixels square on its own,
 * in `colour`; the sheet draws them white.
 */
export function PlateSymbol({
  icon,
  colour = "currentColor",
  x,
  y,
  size,
}: {
  icon: PlateIcon
  colour?: string
  x?: number
  y?: number
  size?: number
}) {
  const tint = `npc-symbol-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`
  const side = size ?? ICON.width
  return (
    <svg
      x={x}
      y={y}
      width={side}
      height={side}
      viewBox={`${PLATE_ICONS.indexOf(icon) * ICON.width} 0 ${ICON.width} ${ICON.width}`}
      aria-hidden
      className="shrink-0"
      style={{ color: colour === "currentColor" ? undefined : colour }}
    >
      <filter id={tint}>
        <feFlood floodColor="currentColor" />
        <feComposite in2="SourceAlpha" operator="in" />
      </filter>
      <image
        href={`${ART}/icons.png`}
        width={98}
        height={64}
        filter={`url(#${tint})`}
        style={{ imageRendering: "pixelated" }}
      />
    </svg>
  )
}

/** The outline of the plate, as the pack draws it. */
const OUTLINE = "#0D0F15"

/** A sketch of the plate over the NPC's head: a pill in its colour around the symbol and the name. */
export function PlateSketch({ npc }: { npc: Npc }) {
  const icon = npc.plate.icon
  return (
    <div
      className="flex h-6.5 max-w-full items-center rounded-full px-1 pl-2.5"
      style={{ boxShadow: `0 0 0 2px ${OUTLINE}`, background: PLATE_RGB[npc.plate.colour] }}
    >
      {icon === undefined ? null : <PlateSymbol icon={icon} colour="#ffffff" size={SKETCH_SYMBOL} />}
      <span className="truncate px-2 text-sm text-white">{npc.name.en.trim() || t("steward.npcs.unnamed")}</span>
    </div>
  )
}

/** The sketch's symbols, twice the game's 7 pixels. */
const SKETCH_SYMBOL = 14
