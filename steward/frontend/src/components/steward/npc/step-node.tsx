import { PlusIcon } from "@/components/ui/icons"
import {
  BaseEdge,
  type Edge,
  type EdgeProps,
  Handle,
  type Node,
  type NodeProps,
  Position,
  getBezierPath,
} from "@xyflow/react"
import { createContext, memo, useContext, type PointerEvent as ReactPointerEvent } from "react"

import type { NpcMenu, NpcStep } from "@/lib/api"
import type { Language } from "@/lib/npc"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"
import { menuName } from "@/components/steward/npc/step-panel"
import { Hint, StatusBadge } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Badge } from "@/components/ui/badge"

/** The custom React Flow node card, output ports, status badge, and edge path for dialogue steps. */

export type SourcePort = "then" | number

export type CanvasInteraction = {
  hoverTarget: string | null
  connectingFrom: string | null
}

/** Transient drag state kept outside React Flow's `nodes` array so hovering never rebuilds nodes. */
export const CanvasInteractionContext = createContext<CanvasInteraction>({
  hoverTarget: null,
  connectingFrom: null,
})

export type StepNodeData = {
  step: NpcStep
  number: number
  isStart: boolean
  linked: boolean
  missing: number
  tooLong: boolean
  language: Language
  menus: NpcMenu[]
  thenTargetNumber?: number
  onAddLinked: (stepId: string, port: SourcePort) => void
  onStartDrag: (stepId: string, event: ReactPointerEvent<HTMLDivElement>) => void
}

export type StepFlowNode = Node<StepNodeData, "step">

export type StepEdgeData = {
  highlighted: boolean
}

export type StepFlowEdge = Edge<StepEdgeData, "step">

/** Worst-state status badge: missing texts first, then too long, then not linked. At most one badge renders. */
export function StepBadge({ missing, tooLong, linked }: { missing: number; tooLong: boolean; linked: boolean }) {
  if (missing > 0) {
    return (
      <StatusBadge tone="down" className="px-1.5 text-badge">
        {t("steward.npcs.missing-count", { count: missing })}
      </StatusBadge>
    )
  }
  if (tooLong) {
    return (
      <StatusBadge tone="warn" className="px-1.5 text-badge">
        {t("steward.npcs.too-long-short")}
      </StatusBadge>
    )
  }
  if (!linked) {
    return (
      <StatusBadge tone="idle" className="px-1.5 text-badge">
        {t("steward.npcs.unlinked")}
      </StatusBadge>
    )
  }
  return null
}

/** A 12px port dot inside a 32px hit area on the right edge of a row, plus a 32px add button when open. */
function OutputPort({ id, open, onAdd }: { id: string; open: boolean; onAdd?: () => void }) {
  return (
    <div className="nodrag relative flex shrink-0 items-center">
      {open && onAdd ? (
        <Hint tip={t("steward.npcs.add-linked-step")}>
          <button
            type="button"
            aria-label={t("steward.npcs.add-linked-step")}
            className="flex size-8 items-center justify-center rounded-sm text-muted-foreground outline-none hover:bg-accent hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
            onClick={(event) => {
              event.stopPropagation()
              onAdd()
            }}
          >
            <PlusIcon aria-hidden className="size-3.5" />
          </button>
        </Hint>
      ) : null}
      <div className="relative -mr-5 flex size-8 items-center justify-center">
        <Handle
          id={id}
          type="source"
          position={Position.Right}
          className={cn(
            "!static !size-3 !transform-none !rounded-full !border-2 transition-colors after:absolute after:top-1/2 after:left-1/2 after:size-8 after:-translate-x-1/2 after:-translate-y-1/2 after:content-['']",
            open ? "!border-muted-foreground !bg-card" : "!border-primary !bg-primary",
          )}
        />
      </div>
    </div>
  )
}

export const StepNode = memo(function StepNode({ data, selected }: NodeProps<StepFlowNode>) {
  const {
    step,
    number,
    isStart,
    linked,
    missing,
    tooLong,
    language,
    menus,
    thenTargetNumber,
    onAddLinked,
    onStartDrag,
  } = data
  const { hoverTarget, connectingFrom } = useContext(CanvasInteractionContext)
  const dropTarget = hoverTarget === step.id
  const showTargetHandle = connectingFrom !== null && connectingFrom !== step.id
  const body = step.pages[0]?.[language]?.trim() ?? ""
  const hasAnswers = step.answers.length > 0
  const thenOpen = !hasAnswers && step.next === undefined && step.menu === undefined
  const thenLabel =
    step.menu !== undefined
      ? menuName(menus, step.menu)
      : step.next !== undefined
        ? t("steward.npcs.step-number", { number: thenTargetNumber ?? 1 })
        : t("steward.npcs.end")

  return (
    <div
      data-step={step.id}
      className={cn(
        "relative flex w-npc-node flex-col rounded-lg border bg-card text-left text-card-foreground shadow-sm transition-shadow",
        selected && "border-primary ring-1 ring-primary",
        dropTarget && "border-primary ring-2 ring-primary",
      )}
    >
      <Handle
        type="target"
        position={Position.Left}
        className={cn(
          "!top-5 !size-3 !rounded-full !border-2 !border-primary !bg-card transition-opacity",
          showTargetHandle ? "opacity-100" : "pointer-events-none opacity-0",
        )}
      />
      <div className="flex items-center gap-1.5 px-3 pt-3 pb-1.5">
        <span className="shrink-0 text-xs font-medium font-heading text-muted-foreground tnum">
          {t("steward.npcs.step-number", { number })}
        </span>
        {isStart ? (
          <Hint tip={t("steward.npcs.drag-start")}>
            <div
              aria-hidden="true"
              data-start-flag={step.id}
              onPointerDown={(event) => onStartDrag(step.id, event)}
              className="nodrag -mx-1 -my-2 inline-flex min-h-8 min-w-8 cursor-grab items-center justify-center px-1 py-2 active:cursor-grabbing"
            >
              <span className="inline-flex items-center gap-1 rounded-sm bg-secondary px-1.5 py-0.5 text-badge font-medium text-secondary-foreground">
                <TERM_ICON.start aria-hidden className="size-3 shrink-0 text-primary" />
                {t("steward.npcs.start")}
              </span>
            </div>
          </Hint>
        ) : null}
        {step.pages.length > 1 ? (
          <Badge variant="outline" className="px-1.5 text-badge tnum">
            {t("steward.npcs.page-count", { count: step.pages.length })}
          </Badge>
        ) : null}
        <span className="flex min-w-0 flex-1 justify-end">
          <StepBadge missing={missing} tooLong={tooLong} linked={linked} />
        </span>
      </div>

      <div className="px-3 pb-2.5">
        <p lang={language} className={cn("line-clamp-3 text-sm leading-snug", body === "" && "text-muted-foreground")}>
          {body || t("steward.npcs.no-text")}
        </p>
      </div>

      <div className="flex flex-col gap-1 border-t border-border/60 px-3 py-2">
        {hasAnswers ? (
          step.answers.map((answer, index) => {
            const text = answer.text[language].trim()
            const open = answer.next === undefined
            const labelEl = (
              <span lang={language} className={cn("min-w-0 flex-1 truncate", text === "" && "text-muted-foreground")}>
                {text || t("steward.npcs.no-text")}
              </span>
            )
            return (
              <div
                key={index}
                className="flex min-h-8 items-center justify-between gap-2 rounded-md bg-muted/50 pr-1 pl-2.5 text-sm"
              >
                {text ? <Hint tip={text}>{labelEl}</Hint> : labelEl}
                {open ? (
                  <>
                    <span className="sr-only">, </span>
                    <span className="shrink-0 text-xs text-muted-foreground">{t("steward.npcs.end")}</span>
                  </>
                ) : null}
                <OutputPort id={`answer-${index}`} open={open} onAdd={() => onAddLinked(step.id, index)} />
              </div>
            )
          })
        ) : (
          <div className="flex min-h-8 items-center justify-between gap-2 rounded-md bg-muted/40 pr-1 pl-2.5 text-xs">
            <Hint tip={thenLabel}>
              <span className={cn("min-w-0 flex-1 truncate tnum", thenOpen && "text-muted-foreground")}>
                {thenLabel}
              </span>
            </Hint>
            <OutputPort id="then" open={thenOpen} onAdd={() => onAddLinked(step.id, "then")} />
          </div>
        )}
      </div>
    </div>
  )
})

/** Orthogonal SVG path with quadratic rounded corners so back-links and self-links never overshoot their lane. */
function roundedPath(points: ReadonlyArray<readonly [number, number]>, radius = 10): string {
  if (points.length < 2) return ""
  const parts: string[] = [`M ${points[0][0]} ${points[0][1]}`]
  for (let index = 1; index < points.length - 1; index++) {
    const [px, py] = points[index - 1]
    const [cx, cy] = points[index]
    const [nx, ny] = points[index + 1]
    const d1 = Math.hypot(cx - px, cy - py)
    const d2 = Math.hypot(nx - cx, ny - cy)
    const r = Math.min(radius, d1 / 2, d2 / 2)
    const bx = cx - ((cx - px) / d1) * r
    const by = cy - ((cy - py) / d1) * r
    const ax = cx + ((nx - cx) / d2) * r
    const ay = cy + ((ny - cy) / d2) * r
    parts.push(`L ${bx} ${by}`, `Q ${cx} ${cy} ${ax} ${ay}`)
  }
  const last = points[points.length - 1]
  parts.push(`L ${last[0]} ${last[1]}`)
  return parts.join(" ")
}

/** Smooth bezier forward edge, or a compact orthogonal loop for back-links and self-links. */
export function StepEdge(props: EdgeProps<StepFlowEdge>) {
  const {
    id,
    source,
    target,
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    selected,
    data,
    markerEnd,
  } = props
  const highlighted = selected || data?.highlighted === true
  let edgePath: string
  if (source === target) {
    const stub = 20
    const topY = targetY - 34
    edgePath = roundedPath([
      [sourceX, sourceY],
      [sourceX + stub, sourceY],
      [sourceX + stub, topY],
      [targetX - stub, topY],
      [targetX - stub, targetY],
      [targetX, targetY],
    ])
  } else if (targetX <= sourceX + 24) {
    const stub = 24
    const laneY = Math.min(sourceY, targetY) - 40
    edgePath = roundedPath([
      [sourceX, sourceY],
      [sourceX + stub, sourceY],
      [sourceX + stub, laneY],
      [targetX - stub, laneY],
      [targetX - stub, targetY],
      [targetX, targetY],
    ])
  } else {
    ;[edgePath] = getBezierPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition })
  }
  return (
    <BaseEdge
      id={id}
      path={edgePath}
      markerEnd={markerEnd}
      style={{
        stroke: highlighted ? "var(--primary)" : "var(--muted-foreground)",
        strokeWidth: highlighted ? 2 : 1.5,
      }}
    />
  )
}
