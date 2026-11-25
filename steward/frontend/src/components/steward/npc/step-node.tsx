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
import type { PointerEvent as ReactPointerEvent } from "react"

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

export type StepNodeData = {
  step: NpcStep
  number: number
  isStart: boolean
  linked: boolean
  missing: number
  tooLong: boolean
  language: Language
  menus: NpcMenu[]
  dropTarget: boolean
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

/** A 12px port dot inside a control-size hit area on the right edge of a row. */
function OutputPort({ id, open, onAdd }: { id: string; open: boolean; onAdd?: () => void }) {
  return (
    <div className="nodrag relative flex shrink-0 items-center">
      {open && onAdd ? (
        <Hint tip={t("steward.npcs.add-linked-step")}>
          <button
            type="button"
            aria-label={t("steward.npcs.add-linked-step")}
            className="mr-1 flex size-5 items-center justify-center rounded-sm text-muted-foreground outline-none hover:bg-accent hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
            onClick={(event) => {
              event.stopPropagation()
              onAdd()
            }}
          >
            <PlusIcon aria-hidden className="size-3" />
          </button>
        </Hint>
      ) : null}
      <div className="relative -mr-5 flex size-control items-center justify-center">
        <Handle
          id={id}
          type="source"
          position={Position.Right}
          className={cn(
            "!static !size-3 !transform-none !rounded-full !border-2 transition-colors",
            open ? "!border-muted-foreground !bg-card" : "!border-primary !bg-primary",
          )}
        />
      </div>
    </div>
  )
}

export function StepNode({ data, selected }: NodeProps<StepFlowNode>) {
  const { step, number, isStart, linked, missing, tooLong, language, menus, dropTarget, onAddLinked, onStartDrag } =
    data
  const body = step.pages[0]?.[language]?.trim() ?? ""
  const hasAnswers = step.answers.length > 0
  const thenOpen = !hasAnswers && step.next === undefined && step.menu === undefined
  const thenLabel =
    step.menu !== undefined ? menuName(menus, step.menu) : step.next === undefined ? t("steward.npcs.end") : ""

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
        className="!top-5 !size-3 !rounded-full !border-2 !border-muted-foreground !bg-card"
      />
      <div className="flex items-center gap-1.5 px-3 pt-3 pb-1.5">
        <span className="shrink-0 text-xs font-medium font-heading text-muted-foreground tnum">
          {t("steward.npcs.step-number", { number })}
        </span>
        {isStart ? (
          <Hint tip={t("steward.npcs.drag-start")}>
            <div
              role="button"
              tabIndex={-1}
              data-start-flag={step.id}
              onPointerDown={(event) => onStartDrag(step.id, event)}
              className="nodrag inline-flex cursor-grab items-center gap-1 rounded-sm bg-secondary px-1.5 py-0.5 text-badge font-medium text-secondary-foreground active:cursor-grabbing"
            >
              <TERM_ICON.start aria-hidden className="size-3 shrink-0 text-primary" />
              {t("steward.npcs.start")}
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
            return (
              <div
                key={index}
                className="flex min-h-8 items-center justify-between gap-2 rounded-md bg-muted/50 pr-1 pl-2.5 text-sm"
              >
                <span lang={language} className={cn("min-w-0 flex-1 truncate", text === "" && "text-muted-foreground")}>
                  {text || t("steward.npcs.no-text")}
                </span>
                {open ? <span className="shrink-0 text-xs text-muted-foreground">{t("steward.npcs.end")}</span> : null}
                <OutputPort id={`answer-${index}`} open={open} onAdd={() => onAddLinked(step.id, index)} />
              </div>
            )
          })
        ) : (
          <div className="flex min-h-8 items-center justify-between gap-2 rounded-md bg-muted/40 pr-1 pl-2.5 text-xs">
            <span className={cn("min-w-0 flex-1 truncate", thenOpen && "text-muted-foreground")}>{thenLabel}</span>
            <OutputPort id="then" open={thenOpen} onAdd={() => onAddLinked(step.id, "then")} />
          </div>
        )}
      </div>
    </div>
  )
}

/** Smooth bezier forward edge, or a clear loop path for back-links and self-links. */
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
    const loopX = sourceX + 48
    const topY = Math.min(sourceY, targetY) - 72
    edgePath = `M ${sourceX} ${sourceY} C ${loopX} ${sourceY}, ${loopX} ${topY}, ${(sourceX + targetX) / 2} ${topY} C ${targetX - 48} ${topY}, ${targetX - 48} ${targetY}, ${targetX} ${targetY}`
  } else if (targetX <= sourceX + 24) {
    const midY = Math.min(sourceY, targetY) - 64
    edgePath = `M ${sourceX} ${sourceY} C ${sourceX + 64} ${sourceY}, ${sourceX + 64} ${midY}, ${(sourceX + targetX) / 2} ${midY} C ${targetX - 64} ${midY}, ${targetX - 64} ${targetY}, ${targetX} ${targetY}`
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
