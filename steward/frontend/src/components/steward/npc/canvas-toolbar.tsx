import { ArrowCounterClockwiseIcon, LayoutIcon, MinusIcon, PlusIcon } from "@/components/ui/icons"
import { type CoordinateExtent, MarkerType, useReactFlow, useViewport } from "@xyflow/react"
import type { RefObject } from "react"

import type { Dialogue } from "@/lib/api"
import { type Language, LANGUAGES, type NodeSize, stepOf } from "@/lib/npc"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"
import type { SourcePort, StepFlowEdge } from "@/components/steward/npc/step-node"
import { Hint } from "@/components/steward/status"
import { Button } from "@/components/ui/button"

/** Floating single-row toolbar and pointer helpers for the desktop NPC dialogue canvas. */

export const MIN_ZOOM = 0.4
export const MAX_ZOOM = 1

export function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches
}

export function parseSourcePort(handleId: string | null | undefined): SourcePort {
  if (handleId && handleId.startsWith("answer-")) {
    const index = Number(handleId.slice(7))
    if (Number.isInteger(index) && index >= 0) return index
  }
  return "then"
}

export function parseEdgeId(edgeId: string): { stepId: string; port: SourcePort } | undefined {
  const sep = edgeId.lastIndexOf(":")
  if (sep <= 0) return undefined
  const stepId = edgeId.slice(0, sep)
  const raw = edgeId.slice(sep + 1)
  if (raw === "then") return { stepId, port: "then" }
  const index = Number(raw)
  return Number.isInteger(index) && index >= 0 ? { stepId, port: index } : undefined
}

export function eventPoint(event: MouseEvent | TouchEvent): { x: number; y: number } | null {
  if ("changedTouches" in event) {
    const touch = event.changedTouches[0] ?? event.touches[0]
    return touch ? { x: touch.clientX, y: touch.clientY } : null
  }
  return { x: event.clientX, y: event.clientY }
}

export type CanvasRect = { x: number; y: number; width: number; height: number }

export type FreeArea = { left: number; right: number; top: number; bottom: number }

/**
 * Clamps a viewport so at least one node intersects the free canvas area by `minOverlap` pixels.
 * Returns `null` when the viewport already keeps a node in view.
 */
export function clampViewportToNodes(
  viewport: { x: number; y: number; zoom: number },
  nodes: readonly CanvasRect[],
  freeArea: FreeArea,
  minOverlap = 48,
): { x: number; y: number; zoom: number } | null {
  if (nodes.length === 0) return null
  const freeW = freeArea.right - freeArea.left
  const freeH = freeArea.bottom - freeArea.top
  if (freeW <= 0 || freeH <= 0) return null
  const { x: vx, y: vy, zoom } = viewport
  let bestX = vx
  let bestY = vy
  let bestDist2 = Infinity
  for (const node of nodes) {
    const kx = Math.min(minOverlap, node.width * zoom, freeW / 2)
    const ky = Math.min(minOverlap, node.height * zoom, freeH / 2)
    const minVx = freeArea.left + kx - (node.x + node.width) * zoom
    const maxVx = freeArea.right - kx - node.x * zoom
    const minVy = freeArea.top + ky - (node.y + node.height) * zoom
    const maxVy = freeArea.bottom - ky - node.y * zoom
    const cx = Math.min(maxVx, Math.max(minVx, vx))
    const cy = Math.min(maxVy, Math.max(minVy, vy))
    const dist2 = (cx - vx) ** 2 + (cy - vy) ** 2
    if (dist2 <= 0.25) return null
    if (dist2 < bestDist2) {
      bestDist2 = dist2
      bestX = cx
      bestY = cy
    }
  }
  return { x: bestX, y: bestY, zoom }
}

/** Builds React Flow edges for every linked Then or answer port in the dialogue. */
export function buildFlowEdges(
  dialogue: Dialogue,
  numbers: Map<string, number>,
  selected: string | null,
  selectedEdge: string | null,
): StepFlowEdge[] {
  const list: StepFlowEdge[] = []
  for (const step of dialogue.steps) {
    const fromNum = numbers.get(step.id) ?? 1
    if (step.answers.length === 0) {
      if (step.next !== undefined && stepOf(dialogue, step.next) !== undefined) {
        const id = `${step.id}:then`
        const highlighted = selectedEdge === id || selected === step.id || selected === step.next
        const toNum = numbers.get(step.next) ?? 1
        list.push({
          id,
          type: "step",
          source: step.id,
          sourceHandle: "then",
          target: step.next,
          selected: selectedEdge === id,
          ariaLabel: t("steward.npcs.edge-label", { from: fromNum, to: toNum }),
          markerEnd: {
            type: MarkerType.ArrowClosed,
            color: highlighted ? "var(--primary)" : "var(--muted-foreground)",
            width: 16,
            height: 16,
          },
          data: { highlighted },
        })
      }
    } else {
      step.answers.forEach((answer, index) => {
        if (answer.next === undefined || stepOf(dialogue, answer.next) === undefined) return
        const id = `${step.id}:${index}`
        const highlighted = selectedEdge === id || selected === step.id || selected === answer.next
        const toNum = numbers.get(answer.next) ?? 1
        list.push({
          id,
          type: "step",
          source: step.id,
          sourceHandle: `answer-${index}`,
          target: answer.next,
          selected: selectedEdge === id,
          ariaLabel: t("steward.npcs.edge-label", { from: fromNum, to: toNum }),
          markerEnd: {
            type: MarkerType.ArrowClosed,
            color: highlighted ? "var(--primary)" : "var(--muted-foreground)",
            width: 16,
            height: 16,
          },
          data: { highlighted },
        })
      })
    }
  }
  return list
}

/** Computes React Flow's bounding box translateExtent around all dialogue steps. */
export function computeTranslateExtent(
  dialogue: Dialogue,
  containerSize: { width: number; height: number },
  inspectorWidth: number,
  toolbarClearance: number,
  zoom: number,
  getNodeSize: (stepId: string, fallbackStep?: Dialogue["steps"][number]) => NodeSize,
): CoordinateExtent | undefined {
  if (dialogue.steps.length === 0 || containerSize.width === 0 || containerSize.height === 0) return undefined
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const step of dialogue.steps) {
    const size = getNodeSize(step.id, step)
    minX = Math.min(minX, step.x)
    minY = Math.min(minY, step.y)
    maxX = Math.max(maxX, step.x + size.width)
    maxY = Math.max(maxY, step.y + size.height)
  }
  const z = Math.max(MIN_ZOOM, zoom)
  const visibleW = Math.max(160, containerSize.width - inspectorWidth)
  const visibleH = Math.max(160, containerSize.height - toolbarClearance)
  const keepVisible = 96
  const padLeft = Math.max(80, (visibleW - keepVisible) / z)
  const padRight = Math.max(80, (containerSize.width - keepVisible) / z)
  const padTop = Math.max(80, (containerSize.height - keepVisible) / z)
  const padBottom = Math.max(80, (visibleH - keepVisible) / z)
  return [
    [minX - padLeft, minY - padTop],
    [maxX + padRight, maxY + padBottom],
  ]
}

export type CanvasToolbarProps = {
  toolbarRef: RefObject<HTMLDivElement | null>
  language: Language
  canUndo: boolean
  canRedo: boolean
  onAddStep: () => void
  onArrange: () => void
  onUndo: () => void
  onRedo: () => void
  onLanguage: (language: Language) => void
  onFit: () => void
}

export function CanvasToolbar({
  toolbarRef,
  language,
  canUndo,
  canRedo,
  onAddStep,
  onArrange,
  onUndo,
  onRedo,
  onLanguage,
  onFit,
}: CanvasToolbarProps) {
  const { zoomIn, zoomOut } = useReactFlow()
  const { zoom } = useViewport()
  const duration = prefersReducedMotion() ? 0 : 150
  return (
    <div
      ref={toolbarRef}
      data-canvas-toolbar
      className="absolute top-3 left-3 z-10 flex max-w-[calc(100%-var(--spacing-npc-inspector)-(--spacing(6)))] flex-nowrap items-center gap-0.5 rounded-lg border bg-popover p-1 text-popover-foreground shadow-md"
    >
      <div className="flex shrink-0 items-center gap-0.5">
        <Button type="button" variant="ghost" size="sm" className="gap-1 px-1.5" onClick={onAddStep}>
          <PlusIcon aria-hidden />
          {t("steward.npcs.add-step")}
        </Button>
        <Button type="button" variant="ghost" size="sm" className="gap-1 px-1.5" onClick={onArrange}>
          <LayoutIcon aria-hidden />
          {t("steward.npcs.arrange")}
        </Button>
      </div>
      <span className="mx-0.5 h-4 w-px shrink-0 bg-border" aria-hidden />
      <div className="flex shrink-0 items-center">
        <Hint tip={t("steward.npcs.undo")}>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            disabled={!canUndo}
            aria-label={t("steward.npcs.undo")}
            onClick={onUndo}
          >
            <ArrowCounterClockwiseIcon aria-hidden />
          </Button>
        </Hint>
        <Hint tip={t("steward.npcs.redo")}>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            disabled={!canRedo}
            aria-label={t("steward.npcs.redo")}
            onClick={onRedo}
          >
            <ArrowCounterClockwiseIcon aria-hidden className="-scale-x-100" />
          </Button>
        </Hint>
      </div>
      <span className="mx-0.5 h-4 w-px shrink-0 bg-border" aria-hidden />
      <div
        role="group"
        aria-label={t("steward.npcs.canvas-language")}
        className="flex shrink-0 items-center rounded-md bg-muted p-0.5"
      >
        {LANGUAGES.map((code) => (
          <button
            key={code}
            type="button"
            aria-pressed={language === code}
            className={cn(
              "rounded-sm px-1.5 py-1 text-xs font-medium outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring",
              language === code ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground",
            )}
            onClick={() => onLanguage(code)}
          >
            {t("steward.npcs.language", { language: code })}
          </button>
        ))}
      </div>
      <span className="mx-0.5 h-4 w-px shrink-0 bg-border" aria-hidden />
      <div className="flex shrink-0 items-center">
        <Hint tip={t("steward.npcs.zoom-out")}>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            disabled={zoom <= MIN_ZOOM + 0.01}
            aria-label={t("steward.npcs.zoom-out")}
            onClick={() => void zoomOut({ duration })}
          >
            <MinusIcon aria-hidden />
          </Button>
        </Hint>
        <span className="w-8 text-center text-xs text-muted-foreground tnum">{Math.round(zoom * 100)}%</span>
        <Hint tip={t("steward.npcs.zoom-in")}>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            disabled={zoom >= MAX_ZOOM - 0.01}
            aria-label={t("steward.npcs.zoom-in")}
            onClick={() => void zoomIn({ duration })}
          >
            <PlusIcon aria-hidden />
          </Button>
        </Hint>
        <Button type="button" variant="ghost" size="sm" className="px-1.5" onClick={onFit}>
          {t("steward.npcs.fit")}
        </Button>
      </div>
    </div>
  )
}
