import { ArrowCounterClockwiseIcon, LayoutIcon, MinusIcon, PlusIcon } from "@/components/ui/icons"
import {
  Background,
  BackgroundVariant,
  type Connection,
  MarkerType,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  useViewport,
} from "@xyflow/react"
import {
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react"

import type { Dialogue, NpcMenu } from "@/lib/api"
import { type Language, LANGUAGES, NODE_WIDTH, missingCount, nodeHeight, ordered, stepNumbers, stepOf } from "@/lib/npc"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"
import {
  type SourcePort,
  StepEdge,
  type StepFlowEdge,
  type StepFlowNode,
  StepNode,
} from "@/components/steward/npc/step-node"
import { Hint } from "@/components/steward/status"
import { Button } from "@/components/ui/button"

/**
 * The dialogue steps on a pan/zoom React Flow canvas on desktop (`xl` and up) and as an indented list below `xl`.
 */

const MIN_ZOOM = 0.4
const MAX_ZOOM = 1
const INSPECTOR_WIDTH = 400
const TOOLBAR_CLEARANCE = 64

export type TooLong = Set<string>
export type { SourcePort }

const NODE_TYPES = { step: StepNode }
const EDGE_TYPES = { step: StepEdge }

function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches
}

function parseSourcePort(handleId: string | null | undefined): SourcePort {
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

type CanvasToolbarProps = {
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

function CanvasToolbar({
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
    <div className="absolute top-3 left-3 z-10 flex max-w-[calc(100%-var(--spacing-npc-inspector)-(--spacing(6)))] flex-wrap items-center gap-1 rounded-lg border bg-popover p-1 text-popover-foreground shadow-md">
      <Button type="button" variant="ghost" size="sm" onClick={onAddStep}>
        <PlusIcon aria-hidden />
        {t("steward.npcs.add-step")}
      </Button>
      <Button type="button" variant="ghost" size="sm" onClick={onArrange}>
        <LayoutIcon aria-hidden />
        {t("steward.npcs.arrange")}
      </Button>
      <span className="mx-0.5 h-4 w-px bg-border" aria-hidden />
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
      <span className="mx-0.5 h-4 w-px bg-border" aria-hidden />
      <div
        role="group"
        aria-label={t("steward.npcs.canvas-language")}
        className="flex items-center rounded-md bg-muted p-0.5"
      >
        {LANGUAGES.map((code) => (
          <button
            key={code}
            type="button"
            aria-pressed={language === code}
            className={cn(
              "rounded-sm px-2 py-1 text-xs font-medium outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring",
              language === code ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground",
            )}
            onClick={() => onLanguage(code)}
          >
            {t("steward.npcs.language", { language: code })}
          </button>
        ))}
      </div>
      <span className="mx-0.5 h-4 w-px bg-border" aria-hidden />
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
      <span className="w-10 text-center text-xs text-muted-foreground tnum">{Math.round(zoom * 100)}%</span>
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
      <Button type="button" variant="ghost" size="sm" onClick={onFit}>
        {t("steward.npcs.fit")}
      </Button>
    </div>
  )
}

export type StepCanvasProps = {
  dialogue: Dialogue
  menus: NpcMenu[]
  selected: string | null
  selectedEdge: string | null
  tooLong: TooLong
  language: Language
  canUndo: boolean
  canRedo: boolean
  fitSignal: number
  panSignal: number
  onSelect: (id: string | null) => void
  onSelectEdge: (edgeId: string | null) => void
  onPlace: (id: string, x: number, y: number) => void
  onLink: (sourceId: string, port: SourcePort, targetId: string) => void
  onCreateFromPort: (sourceId: string, port: SourcePort, x: number, y: number) => void
  onAddLinked: (sourceId: string, port: SourcePort) => void
  onCreateAt: (x: number, y: number) => void
  onMoveStart: (targetId: string) => void
  onAddStep: () => void
  onArrange: () => void
  onUndo: () => void
  onRedo: () => void
  onLanguage: (language: Language) => void
}

function StepCanvasInner({
  dialogue,
  menus,
  selected,
  selectedEdge,
  tooLong,
  language,
  canUndo,
  canRedo,
  fitSignal,
  panSignal,
  onSelect,
  onSelectEdge,
  onPlace,
  onLink,
  onCreateFromPort,
  onAddLinked,
  onCreateAt,
  onMoveStart,
  onAddStep,
  onArrange,
  onUndo,
  onRedo,
  onLanguage,
}: StepCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const { setViewport, getViewport, screenToFlowPosition } = useReactFlow()
  const [draggingPositions, setDraggingPositions] = useState<Map<string, { x: number; y: number }>>(new Map())
  const [hoverTarget, setHoverTarget] = useState<string | null>(null)
  const connectingRef = useRef<{ nodeId: string; handleId: string | null } | null>(null)
  const didConnectRef = useRef(false)
  const fittedOnceRef = useRef(false)

  const numbers = useMemo(() => stepNumbers(dialogue), [dialogue])
  const linkedMap = useMemo(() => new Map(ordered(dialogue).map((entry) => [entry.step.id, entry.linked])), [dialogue])

  const findStepAtPoint = useCallback((clientX: number, clientY: number): string | null => {
    const element = document.elementFromPoint(clientX, clientY)
    const card = element?.closest<HTMLElement>("[data-step]")
    return card?.dataset.step ?? null
  }, [])

  const onStartDrag = useCallback(
    (stepId: string, event: ReactPointerEvent<HTMLDivElement>) => {
      if (event.button !== 0) return
      event.stopPropagation()
      event.preventDefault()
      const onMove = (moveEvent: PointerEvent) => {
        const over = findStepAtPoint(moveEvent.clientX, moveEvent.clientY)
        setHoverTarget(over && over !== stepId ? over : null)
      }
      const onUp = (upEvent: PointerEvent) => {
        window.removeEventListener("pointermove", onMove)
        window.removeEventListener("pointerup", onUp)
        window.removeEventListener("pointercancel", onUp)
        const target = findStepAtPoint(upEvent.clientX, upEvent.clientY)
        setHoverTarget(null)
        if (target && target !== stepId) onMoveStart(target)
      }
      window.addEventListener("pointermove", onMove)
      window.addEventListener("pointerup", onUp)
      window.addEventListener("pointercancel", onUp)
    },
    [findStepAtPoint, onMoveStart],
  )

  const fitFreeArea = useCallback(
    (animate = true) => {
      const box = containerRef.current?.getBoundingClientRect()
      if (!box || box.width === 0 || box.height === 0 || dialogue.steps.length === 0) return
      let minX = Infinity
      let minY = Infinity
      let maxX = -Infinity
      let maxY = -Infinity
      for (const step of dialogue.steps) {
        minX = Math.min(minX, step.x)
        minY = Math.min(minY, step.y)
        maxX = Math.max(maxX, step.x + NODE_WIDTH)
        maxY = Math.max(maxY, step.y + nodeHeight(step))
      }
      const pad = 32
      const freeW = Math.max(240, box.width - INSPECTOR_WIDTH - pad * 2)
      const freeH = Math.max(200, box.height - TOOLBAR_CLEARANCE - pad * 2)
      const contentW = Math.max(NODE_WIDTH, maxX - minX)
      const contentH = Math.max(120, maxY - minY)
      const zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Math.min(freeW / contentW, freeH / contentH)))
      const x = pad + (freeW - contentW * zoom) / 2 - minX * zoom
      const y = TOOLBAR_CLEARANCE + pad + (freeH - contentH * zoom) / 2 - minY * zoom
      void setViewport({ x, y, zoom }, { duration: animate && !prefersReducedMotion() ? 200 : 0 })
    },
    [dialogue.steps, setViewport],
  )

  useEffect(() => {
    const id = requestAnimationFrame(() => {
      fitFreeArea(fittedOnceRef.current)
      fittedOnceRef.current = true
    })
    return () => cancelAnimationFrame(id)
    // Fit runs on open, after Arrange, and after loading a version via fitSignal.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitSignal])

  useEffect(() => {
    if (!selected || panSignal === 0) return
    const target = stepOf(dialogue, selected)
    const box = containerRef.current?.getBoundingClientRect()
    if (!target || !box || box.width === 0 || box.height === 0) return
    const vp = getViewport()
    const left = target.x * vp.zoom + vp.x
    const top = target.y * vp.zoom + vp.y
    const right = (target.x + NODE_WIDTH) * vp.zoom + vp.x
    const bottom = (target.y + nodeHeight(target)) * vp.zoom + vp.y
    const freeLeft = 24
    const freeRight = Math.max(freeLeft + 200, box.width - INSPECTOR_WIDTH - 24)
    const freeTop = TOOLBAR_CLEARANCE + 12
    const freeBottom = box.height - 24
    if (left >= freeLeft && right <= freeRight && top >= freeTop && bottom <= freeBottom) return
    let dx = 0
    if (left < freeLeft) dx = freeLeft - left
    else if (right > freeRight) dx = freeRight - right
    let dy = 0
    if (top < freeTop) dy = freeTop - top
    else if (bottom > freeBottom) dy = freeBottom - bottom
    void setViewport({ x: vp.x + dx, y: vp.y + dy, zoom: vp.zoom }, { duration: prefersReducedMotion() ? 0 : 200 })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [panSignal])

  const nodes = useMemo<StepFlowNode[]>(() => {
    return dialogue.steps.map((step) => {
      const livePos = draggingPositions.get(step.id)
      return {
        id: step.id,
        type: "step",
        position: livePos ?? { x: step.x, y: step.y },
        selected: selected === step.id,
        data: {
          step,
          number: numbers.get(step.id) ?? 1,
          isStart: dialogue.start === step.id,
          linked: linkedMap.get(step.id) ?? false,
          missing: missingCount(step),
          tooLong: tooLong.has(step.id),
          language,
          menus,
          dropTarget: hoverTarget === step.id,
          onAddLinked,
          onStartDrag,
        },
      }
    })
  }, [
    dialogue.start,
    dialogue.steps,
    draggingPositions,
    hoverTarget,
    language,
    linkedMap,
    menus,
    numbers,
    onAddLinked,
    onStartDrag,
    selected,
    tooLong,
  ])

  const edges = useMemo<StepFlowEdge[]>(() => {
    const list: StepFlowEdge[] = []
    for (const step of dialogue.steps) {
      if (step.answers.length === 0) {
        if (step.next !== undefined && stepOf(dialogue, step.next) !== undefined) {
          const id = `${step.id}:then`
          const highlighted = selectedEdge === id || selected === step.id || selected === step.next
          list.push({
            id,
            type: "step",
            source: step.id,
            sourceHandle: "then",
            target: step.next,
            selected: selectedEdge === id,
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
          list.push({
            id,
            type: "step",
            source: step.id,
            sourceHandle: `answer-${index}`,
            target: answer.next,
            selected: selectedEdge === id,
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
  }, [dialogue, selected, selectedEdge])

  const handleConnect = useCallback(
    (connection: Connection) => {
      if (!connection.source || !connection.target) return
      didConnectRef.current = true
      onLink(connection.source, parseSourcePort(connection.sourceHandle), connection.target)
    },
    [onLink],
  )

  const handleConnectStart = useCallback(
    (_event: MouseEvent | TouchEvent, params: { nodeId: string | null; handleId: string | null }) => {
      if (!params.nodeId) return
      didConnectRef.current = false
      connectingRef.current = { nodeId: params.nodeId, handleId: params.handleId }
      const onPointerMove = (moveEvent: PointerEvent) => {
        setHoverTarget(findStepAtPoint(moveEvent.clientX, moveEvent.clientY))
      }
      const cleanup = () => {
        window.removeEventListener("pointermove", onPointerMove)
        window.removeEventListener("pointerup", cleanup)
        window.removeEventListener("pointercancel", cleanup)
        setHoverTarget(null)
      }
      window.addEventListener("pointermove", onPointerMove)
      window.addEventListener("pointerup", cleanup)
      window.addEventListener("pointercancel", cleanup)
    },
    [findStepAtPoint],
  )

  const handleConnectEnd = useCallback(
    (event: MouseEvent | TouchEvent) => {
      const started = connectingRef.current
      connectingRef.current = null
      setHoverTarget(null)
      if (!started || didConnectRef.current) return
      const client = "changedTouches" in event ? event.changedTouches[0] : event
      if (!client) return
      const targetStep = findStepAtPoint(client.clientX, client.clientY)
      const port = parseSourcePort(started.handleId)
      if (targetStep) {
        onLink(started.nodeId, port, targetStep)
        return
      }
      const pos = screenToFlowPosition({ x: client.clientX, y: client.clientY })
      onCreateFromPort(started.nodeId, port, pos.x, pos.y)
    },
    [findStepAtPoint, onCreateFromPort, onLink, screenToFlowPosition],
  )

  const handlePaneClick = useCallback(
    (event: ReactMouseEvent) => {
      if (event.detail === 2) {
        const pos = screenToFlowPosition({ x: event.clientX, y: event.clientY })
        onCreateAt(pos.x, pos.y)
        return
      }
      onSelect(null)
      onSelectEdge(null)
    },
    [onCreateAt, onSelect, onSelectEdge, screenToFlowPosition],
  )

  return (
    <div ref={containerRef} className="npc-flow relative size-full" role="group" aria-label={t("steward.npcs.graph")}>
      <CanvasToolbar
        language={language}
        canUndo={canUndo}
        canRedo={canRedo}
        onAddStep={onAddStep}
        onArrange={onArrange}
        onUndo={onUndo}
        onRedo={onRedo}
        onLanguage={onLanguage}
        onFit={() => fitFreeArea(true)}
      />
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        edgeTypes={EDGE_TYPES}
        minZoom={MIN_ZOOM}
        maxZoom={MAX_ZOOM}
        panOnDrag
        panOnScroll
        zoomOnScroll={false}
        zoomOnPinch
        zoomOnDoubleClick={false}
        nodesDraggable
        nodesConnectable
        elementsSelectable
        deleteKeyCode={null}
        proOptions={{ hideAttribution: true }}
        onNodesChange={(changes) => {
          setDraggingPositions((prev) => {
            let next: Map<string, { x: number; y: number }> | undefined
            for (const change of changes) {
              if (change.type === "position" && change.dragging && change.position) {
                next ??= new Map(prev)
                next.set(change.id, change.position)
              }
            }
            return next ?? prev
          })
        }}
        onNodeClick={(_event, node) => {
          onSelectEdge(null)
          onSelect(node.id)
        }}
        onNodeDragStop={(_event, node) => {
          setDraggingPositions((prev) => {
            if (!prev.has(node.id)) return prev
            const next = new Map(prev)
            next.delete(node.id)
            return next
          })
          onPlace(node.id, node.position.x, node.position.y)
        }}
        onEdgeClick={(_event, edge) => {
          onSelect(null)
          onSelectEdge(edge.id)
        }}
        onConnect={handleConnect}
        onConnectStart={handleConnectStart}
        onConnectEnd={handleConnectEnd}
        onPaneClick={handlePaneClick}
      >
        <Background variant={BackgroundVariant.Dots} gap={24} size={1} />
      </ReactFlow>
    </div>
  )
}

export function StepCanvas(props: StepCanvasProps) {
  return (
    <ReactFlowProvider>
      <StepCanvasInner {...props} />
    </ReactFlowProvider>
  )
}
