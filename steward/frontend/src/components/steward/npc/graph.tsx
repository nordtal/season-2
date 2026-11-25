import { ChatTextIcon, ListIcon, SquareIcon } from "@/components/ui/icons"
import { useEffect, useRef, useState, type CSSProperties, type PointerEvent, type ReactNode } from "react"

import type { Dialogue, NpcMenu, NpcStep } from "@/lib/api"
import {
  type Language,
  LANGUAGES,
  END_HEIGHT,
  MENU_HEIGHT,
  NODE_WIDTH,
  TAIL_GAP,
  edgesOf,
  extentOf,
  labelOf,
  nodeHeight,
  ordered,
  pathOf,
  stepOf,
  tailOf,
} from "@/lib/npc"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"
import { StatusBadge } from "@/components/steward/status"
import { Badge } from "@/components/ui/badge"

/**
 * The steps of a dialogue: a canvas of boxes and lines from a desktop's width, a list in the order a player meets the
 * steps below it. A menu a step opens and the end of the dialogue are drawn as boxes of their own under their step.
 */

/** The languages in which a step's texts are too long for the panel. */
export type TooLong = Map<string, Language[]>

type Shared = {
  dialogue: Dialogue
  menus: NpcMenu[]
  selected: string
  tooLong: TooLong
  missing: Map<string, Language[]>
  onSelect: (id: string) => void
}

/** The name of a menu as the picker lists it: its hint in English, or its key while smp has not registered it. */
export function menuName(menus: NpcMenu[], key: string): string {
  return menus.find((menu) => menu.key === key)?.hint.en.trim() || key
}

const BOX = "rounded-lg border bg-background text-left outline-none"
const CHOSEN = "border-primary ring-1 ring-primary"
const FOCUS = "focus-visible:ring-2 focus-visible:ring-ring"

function KindLine({ icon, children, badges }: { icon: ReactNode; children: string; badges?: ReactNode }) {
  return (
    <span className="flex h-5 min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
      {icon}
      <span className="shrink-0">{children}</span>
      <span className="flex min-w-0 flex-1 justify-end gap-1 overflow-hidden">{badges}</span>
    </span>
  )
}

function Badges({ step, linked, shared }: { step: NpcStep; linked: boolean; shared: Shared }) {
  const missing = shared.missing.get(step.id) ?? []
  const tooLong = (shared.tooLong.get(step.id) ?? []).filter((language) => !missing.includes(language))
  return (
    <>
      {missing.map((language) => (
        <StatusBadge key={`missing-${language}`} tone="down" className="px-1.5 text-badge">
          {t("steward.npcs.missing", { language })}
        </StatusBadge>
      ))}
      {tooLong.map((language) => (
        <StatusBadge key={`long-${language}`} tone="warn" className="px-1.5 text-badge">
          {t("steward.npcs.too-long", { language })}
        </StatusBadge>
      ))}
      {step.pages.length > 1 ? (
        <Badge variant="outline" className="px-1.5 text-badge">
          {t("steward.npcs.page-count", { count: step.pages.length })}
        </Badge>
      ) : null}
      {shared.dialogue.start === step.id ? (
        <Badge variant="secondary">{t("steward.npcs.start")}</Badge>
      ) : linked ? null : (
        <StatusBadge tone="idle">{t("steward.npcs.unlinked")}</StatusBadge>
      )}
    </>
  )
}

/** A step's box: its kind, its two texts, and its answers, each a way to the step it leads to. */
function StepBox({
  step,
  linked,
  shared,
  fixed,
  className,
  style,
}: {
  step: NpcStep
  linked: boolean
  shared: Shared
  /** On the canvas a box has the height its lines are drawn to. */
  fixed: boolean
  className?: string
  style?: CSSProperties
}) {
  const chosen = shared.selected === step.id
  const blank = labelOf(step) === ""
  return (
    <div
      className={cn(BOX, "flex flex-col", chosen && CHOSEN, className)}
      style={fixed ? { ...style, width: NODE_WIDTH, height: nodeHeight(step) } : style}
      data-step={step.id}
    >
      <button
        type="button"
        aria-pressed={chosen}
        className={cn("flex min-w-0 flex-col gap-2 rounded-lg p-3 text-left outline-none", FOCUS)}
        onClick={() => shared.onSelect(step.id)}
      >
        <KindLine
          icon={<ChatTextIcon aria-hidden className="size-4 shrink-0" />}
          badges={<Badges step={step} linked={linked} shared={shared} />}
        >
          {t("steward.npcs.kind", { kind: step.answers.length > 0 ? "answers" : "text" })}
        </KindLine>
        {blank ? (
          <span className="h-5 truncate text-sm text-muted-foreground">{t("steward.npcs.no-text")}</span>
        ) : (
          LANGUAGES.map((language) => (
            <span
              key={language}
              lang={language}
              className={cn("h-5 truncate text-sm", language === "en" && "text-muted-foreground")}
            >
              {step.pages[0]?.[language] ?? ""}
            </span>
          ))
        )}
      </button>
      {step.answers.length > 0 ? (
        <div className="flex flex-col gap-1 px-3 pb-3">
          {step.answers.map((answer, index) => {
            const next = stepOf(shared.dialogue, answer.next)
            const words = (
              <span lang="de" className="min-w-0 flex-1 truncate">
                {answer.text.de || answer.text.en}
              </span>
            )
            const row = "flex h-8 min-w-0 items-center gap-2 rounded-md bg-muted/50 px-2 text-left text-sm"
            return next === undefined ? (
              <div key={index} className={row}>
                {words}
                <span className="shrink-0 text-muted-foreground">{t("steward.npcs.end")}</span>
              </div>
            ) : (
              <button
                key={index}
                type="button"
                className={cn(row, "outline-none hover:bg-muted", FOCUS)}
                onClick={() => shared.onSelect(next.id)}
              >
                {words}
                <span className="max-w-20 shrink-0 truncate text-primary">{labelOf(next) || next.id}</span>
              </button>
            )
          })}
        </div>
      ) : null}
    </div>
  )
}

/** The menu a step opens, or the end of the dialogue after it. A click picks its step, which holds the choice. */
function TailBox({
  step,
  shared,
  className,
  style,
}: {
  step: NpcStep
  shared: Shared
  className?: string
  style?: CSSProperties
}) {
  const tail = tailOf(step)
  if (tail === undefined) return null
  return (
    <button
      type="button"
      className={cn(BOX, FOCUS, "flex flex-col gap-2 p-3", className)}
      style={style}
      onClick={() => shared.onSelect(step.id)}
    >
      {tail === "menu" ? (
        <>
          <KindLine icon={<ListIcon aria-hidden className="size-4 shrink-0" />}>
            {t("steward.npcs.kind", { kind: "menu" })}
          </KindLine>
          <span className="h-5 truncate text-sm">{menuName(shared.menus, step.menu ?? "")}</span>
        </>
      ) : (
        <KindLine icon={<SquareIcon aria-hidden className="size-4 shrink-0" />}>
          {t("steward.npcs.kind", { kind: "end" })}
        </KindLine>
      )}
    </button>
  )
}

/** The steps as a list, each step's menu or end after it. */
export function StepList(props: Shared & { className?: string }) {
  return (
    <ol aria-label={t("steward.npcs.graph")} className={cn("flex min-w-0 flex-col gap-2.5", props.className)}>
      {ordered(props.dialogue).map(({ step, linked }) => (
        <li key={step.id} className="flex min-w-0 flex-col gap-2.5">
          <StepBox step={step} linked={linked} shared={props} fixed={false} />
          <TailBox step={step} shared={props} />
        </li>
      ))}
    </ol>
  )
}

/** How far a pointer moves before a press on a step is a drag and not a click. */
const DRAG_FROM = 4
const GRID = 8

type Drag = { id: string; pointer: number; startX: number; startY: number; x: number; y: number; moved: boolean }

/**
 * The steps as boxes on a canvas, joined by lines: a text to the step after it, an answer to the step it leads to.
 * A step is dragged to where it should stand, and lands on a grid.
 */
export function StepCanvas({
  onMove,
  className,
  ...shared
}: Shared & { onMove: (id: string, x: number, y: number) => void; className?: string }) {
  const [drag, setDrag] = useState<Drag | null>(null)
  const dragging = useRef<Drag | null>(null)
  const canvas = useRef<HTMLDivElement>(null)
  // A step picked from elsewhere, such as an answer's row or a problem's toast, is brought into view.
  useEffect(() => {
    canvas.current
      ?.querySelector(`[data-step="${CSS.escape(shared.selected)}"]`)
      ?.scrollIntoView({ block: "nearest", inline: "nearest" })
  }, [shared.selected])
  const steps = shared.dialogue.steps.map((step) =>
    drag?.moved && drag.id === step.id ? { ...step, x: drag.x, y: drag.y } : step,
  )
  const dialogue = { ...shared.dialogue, steps }
  const extent = extentOf(dialogue)
  const linked = new Map(ordered(dialogue).map((entry) => [entry.step.id, entry.linked]))

  const press = (event: PointerEvent<HTMLDivElement>, step: NpcStep) => {
    if (event.button !== 0) return
    dragging.current = {
      id: step.id,
      pointer: event.pointerId,
      startX: event.clientX - step.x,
      startY: event.clientY - step.y,
      x: step.x,
      y: step.y,
      moved: false,
    }
  }
  const move = (event: PointerEvent<HTMLDivElement>) => {
    const held = dragging.current
    if (held === null || held.pointer !== event.pointerId) return
    // A press that was let go outside the canvas never reached it.
    if (event.buttons === 0) {
      dragging.current = null
      setDrag(null)
      return
    }
    const x = Math.max(0, event.clientX - held.startX)
    const y = Math.max(0, event.clientY - held.startY)
    if (!held.moved && Math.hypot(x - held.x, y - held.y) < DRAG_FROM) return
    // From here the press is a drag: the canvas keeps the pointer, so no box under it takes the click.
    if (!held.moved) event.currentTarget.setPointerCapture(event.pointerId)
    dragging.current = { ...held, x, y, moved: true }
    setDrag(dragging.current)
  }
  const release = (event: PointerEvent<HTMLDivElement>) => {
    const held = dragging.current
    if (held === null || held.pointer !== event.pointerId) return
    dragging.current = null
    setDrag(null)
    if (held.moved) onMove(held.id, Math.round(held.x / GRID) * GRID, Math.round(held.y / GRID) * GRID)
  }

  return (
    <div className={cn("relative min-h-list rounded-lg bg-card", className)}>
      <div
        ref={canvas}
        role="group"
        aria-label={t("steward.npcs.graph")}
        className="absolute inset-0 overflow-auto rounded-inherit"
        onPointerMove={move}
        onPointerUp={release}
        onPointerCancel={release}
      >
        <div
          className="relative min-h-full min-w-full"
          style={{
            width: extent.width,
            height: extent.height,
            backgroundImage: "radial-gradient(var(--border) 1px, transparent 1px)",
            backgroundSize: "24px 24px",
          }}
        >
          <svg aria-hidden className="pointer-events-none absolute inset-0 size-full overflow-visible text-primary">
            {edgesOf(dialogue).map((edge) => (
              <path key={edge.key} d={pathOf(edge)} fill="none" stroke="currentColor" strokeWidth={1.5} />
            ))}
          </svg>
          {steps.map((step) => {
            const tail = tailOf(step)
            return (
              <div key={step.id}>
                <div
                  className={cn(
                    "absolute touch-none",
                    drag?.moved && drag.id === step.id ? "cursor-grabbing" : "cursor-grab",
                  )}
                  style={{ left: step.x, top: step.y }}
                  onPointerDown={(event) => press(event, step)}
                >
                  <StepBox step={step} linked={linked.get(step.id) ?? false} shared={shared} fixed />
                </div>
                <TailBox
                  step={step}
                  shared={shared}
                  className="absolute"
                  style={{
                    left: step.x,
                    top: step.y + nodeHeight(step) + TAIL_GAP,
                    width: NODE_WIDTH,
                    height: tail === "menu" ? MENU_HEIGHT : END_HEIGHT,
                  }}
                />
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
