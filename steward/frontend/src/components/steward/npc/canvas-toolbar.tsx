import { ArrowCounterClockwiseIcon, LayoutIcon, MinusIcon, PlusIcon } from "@/components/ui/icons"
import { useReactFlow, useViewport } from "@xyflow/react"
import type { RefObject } from "react"

import { type Language, LANGUAGES } from "@/lib/npc"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"
import type { SourcePort } from "@/components/steward/npc/step-node"
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
