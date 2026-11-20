import type { Npc, NpcStep, NpcTextMeasurement, NpcTextMeasureRequest } from "@/lib/api"
import type { Language } from "@/lib/npc"
import { measureKey } from "@/lib/queries-npcs"
import { t } from "@/lib/texts"

/**
 * A step as the game draws it: the dialogue panel's own art from the pack, the lines as Steward measured them, and the
 * NPC behind it in its skin. Every number is a pixel of the game's interface, as `DialogueScreen` places it.
 */

/** The smallest screen the game scales its interface to. */
const SCREEN = { width: 480, height: 270 }
const PANEL = { width: 262, margin: 7, text: 36, portrait: 24, bottom: 34 }
const BAR = 38
const LINES = [18, 27, 36]
const PLATE = { top: 38, step: 20, width: 248, height: 17, label: 4, text: 5 }
const HOLD = { width: 5, top: 41 }
/** The panel's height with no answer, with one or two, and with three. */
const HEIGHTS = { speak: 51, two: 82, three: 102 }
const ART = "/glyphs/npc"

/** The size at which the browser's face draws a capital 7 pixels high, as the game's does, and how far a letter then moves on. */
const FACE = 9
const ADVANCE = 6

const INK = "#404040"
const SOFT = "#555555"
const ON_ACCENT = "#ffffff"

/** The requests that measure one step in one language: the name, the text and each answer. */
export function requestsOf(npc: Npc, step: NpcStep, language: Language): NpcTextMeasureRequest[] {
  return [
    { text: npc.name[language], kind: "NAME", answers: 0 },
    { text: step.text[language], kind: "BODY", answers: step.answers.length },
    ...step.answers.map((answer) => ({ text: answer.text[language], kind: "ANSWER" as const, answers: 0 })),
  ]
}

/** The lines the panel shows of a text: as many as it has room for, the last ending in an ellipsis when more follow. */
function shown(measurement: NpcTextMeasurement | undefined, text: string): string[] {
  if (measurement === undefined) return text === "" ? [] : [text]
  const lines = measurement.lines.slice(0, measurement.lineLimit)
  if (measurement.lines.length > measurement.lineLimit) {
    lines[lines.length - 1] = `${lines[lines.length - 1].trimEnd()}…`
  }
  return lines
}

/** A line in the game's face. It is pressed into `room` when the browser's face sets it wider than the game's does. */
function Line({
  x,
  y,
  room,
  fill,
  end,
  children,
}: {
  x: number
  y: number
  room: number
  fill: string
  end?: boolean
  children: string
}) {
  const pressed = children.length * ADVANCE > room
  return (
    <text
      x={x}
      y={y + 7}
      fill={fill}
      fontSize={FACE}
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

export function DialoguePreview({
  npc,
  step,
  language,
  measured,
}: {
  npc: Npc
  step: NpcStep
  language: Language
  measured: Map<string, NpcTextMeasurement> | undefined
}) {
  const [name, body, ...answers] = requestsOf(npc, step, language)
  const lines = shown(measured?.get(measureKey(body)), body.text)
  const count = answers.length
  const height = count === 0 ? HEIGHTS.speak : count === 3 ? HEIGHTS.three : HEIGHTS.two
  const left = (SCREEN.width - PANEL.width) / 2
  const top = SCREEN.height - PANEL.bottom - height
  const art = count === 0 ? "panel_speak" : count === 3 ? "panel_3" : "panel_2"
  const skin = skinAddress(npc)
  const scale = 6
  return (
    <svg
      viewBox={`0 0 ${SCREEN.width} ${SCREEN.height}`}
      role="img"
      aria-label={t("steward.npcs.preview")}
      className="block w-full rounded-lg border"
    >
      <defs>
        <linearGradient id="npc-preview-sky" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#6f9be0" />
          <stop offset="1" stopColor="#c3d6f2" />
        </linearGradient>
      </defs>
      <rect width={SCREEN.width} height={SCREEN.height * 0.62} fill="url(#npc-preview-sky)" />
      <rect y={SCREEN.height * 0.62} width={SCREEN.width} height={SCREEN.height * 0.38} fill="#5d8f3f" />
      <Figure skin={skin} slim={npc.skinModel === "SLIM"} x={SCREEN.width / 2 - 4 * scale} y={BAR + 14} scale={scale} />
      {[0, 255].map((x) => (
        <g key={x}>
          <Art name="top_bar_4" x={x} y={0} width={256} height={BAR} />
          <Art name="bar_4" x={x} y={SCREEN.height - BAR} width={256} height={BAR} />
        </g>
      ))}
      <Art name={`${art}_0`} x={left} y={top} width={256} height={height} />
      <Art name={`${art}_1`} x={left + 256} y={top} width={PANEL.width - 256} height={height} />
      {skin === undefined ? (
        <Art
          name="portrait_speak"
          x={left + PANEL.margin}
          y={top + PANEL.margin}
          width={PANEL.portrait}
          height={PANEL.portrait}
        />
      ) : (
        <Limb
          skin={skin}
          base={[8, 8, 8, 8]}
          over={[40, 8, 8, 8]}
          x={left + PANEL.margin}
          y={top + PANEL.margin}
          scale={PANEL.portrait / 8}
        />
      )}
      <Line x={left + PANEL.text} y={top + PANEL.margin} room={219} fill={INK}>
        {name.text}
      </Line>
      {lines.map((line, index) => (
        <Line key={index} x={left + PANEL.text} y={top + LINES[index]} room={210} fill={INK}>
          {line}
        </Line>
      ))}
      {answers.map((answer, index) => {
        const y = top + PLATE.top + index * PLATE.step
        const x = left + PANEL.margin
        return (
          <g key={index}>
            <Art name={index === 0 ? "plate_on_2" : "plate_2"} x={x} y={y} width={PLATE.width} height={PLATE.height} />
            <Line x={x + PLATE.label} y={y + PLATE.text} room={228} fill={index === 0 ? ON_ACCENT : INK}>
              {answer.text}
            </Line>
            <Line
              x={x + PLATE.width - PLATE.label}
              y={y + PLATE.text}
              room={8}
              fill={index === 0 ? ON_ACCENT : SOFT}
              end
            >
              {String(index + 1)}
            </Line>
          </g>
        )
      })}
      {count === 0 ? (
        <Art
          name="hold"
          x={left + PANEL.width - PANEL.margin - HOLD.width}
          y={top + HOLD.top}
          width={HOLD.width}
          height={3}
        />
      ) : null}
      <HintMarks answers={count > 0} right={left + PANEL.width - PANEL.margin} />
    </svg>
  )
}

/** The marks of the inputs the game asks for on the bottom bar: the wheel and a left-click with answers, a right-click without. */
function HintMarks({ answers, right }: { answers: boolean; right: number }) {
  const y = SCREEN.height - 21
  return answers ? (
    <>
      <Art name="mouse_left" x={right - 9} y={y} width={9} height={11} />
      <Art name="mouse_wheel" x={right - 9 - 10 - 15} y={y} width={15} height={11} />
    </>
  ) : (
    <Art name="mouse_right" x={right - 9} y={y} width={9} height={11} />
  )
}
