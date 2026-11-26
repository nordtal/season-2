import {
  Background,
  BackgroundVariant,
  type Connection,
  NodeChangeset,
  ReactFlow,
  ReactFlowProvider,
  useNodesStore,
  useReactFlow,
  useReactFlowStoreApi,
  useViewport,
} from "@xyflow/react"
import {
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react"

import type { Dialogue, NpcMenu } from "@/lib/api"
import {
  type Language,
  NODE_WIDTH,
  type NodeSize,
  type NodeSizes,
  missingCount,
  nodeHeight,
  ordered,
  stepNumbers,
  stepOf,
} from "@/lib/npc"
import { t } from "@/lib/texts"
import {
  CanvasToolbar,
  MAX_ZOOM,
  MIN_ZOOM,
  buildFlowEdges,
  clampViewportToNodes,
  computeTranslateExtent,
  eventPoint,
  parseEdgeId,
  parseSourcePort,
  prefersReducedMotion,
} from "@/components/steward/npc/canvas-toolbar"
import {
  CanvasInteractionContext,
  type SourcePort,
  StepEdge,
  type StepFlowNode,
  StepNode,
} from "@/components/steward/npc/step-node"
import { stepOptionLabel } from "@/components/steward/npc/step-panel"
import { TERM_ICON } from "@/components/steward/term-icons"

/**
 * The dialogue steps on a pan/zoom React Flow canvas on desktop (`xl` and up) and as an indented list below `xl`.
 */

const FIT_MIN_ZOOM = 0.75
const INSPECTOR_WIDTH = 400
const DEFAULT_TOOLBAR_CLEARANCE = 56
const FREE_MARGIN = 16
const MIN_PORT_DRAG_PX = 8

export type TooLong = Set<string>
export { parseEdgeId }
export type { SourcePort }

const NODE_TYPES = { step: StepNode }
const EDGE_TYPES = { step: StepEdge }

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
  onFocusSelectedText: (stepId: string) => void
  onPlace: (id: string, x: number, y: number, sizes: NodeSizes) => void
  onLink: (sourceId: string, port: SourcePort, targetId: string) => void
  onCreateFromPort: (sourceId: string, port: SourcePort, x: number, y: number, sizes: NodeSizes) => void
  onAddLinked: (sourceId: string, port: SourcePort) => void
  onCreateAt: (x: number, y: number, sizes: NodeSizes) => void
  onMoveStart: (targetId: string) => void
  onAddStep: () => void
  onArrange: (sizes: NodeSizes) => void
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
  onFocusSelectedText,
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
  const toolbarRef = useRef<HTMLDivElement>(null)
  const { setViewport, getViewport, screenToFlowPosition } = useReactFlow()
  const store = useReactFlowStoreApi()
  const { x: viewportX, y: viewportY, zoom } = useViewport()

  const [toolbarClearance, setToolbarClearance] = useState(DEFAULT_TOOLBAR_CLEARANCE)
  const [containerSize, setContainerSize] = useState({ width: 0, height: 0 })
  const [hoverTarget, setHoverTarget] = useState<string | null>(null)
  const [connectingFrom, setConnectingFrom] = useState<string | null>(null)
  const [startGhost, setStartGhost] = useState<{ x: number; y: number } | null>(null)

  const hoverTargetRef = useRef<string | null>(null)
  const connectingRef = useRef<{
    nodeId: string
    handleId: string | null
    startX: number
    startY: number
    cancelled: boolean
    cleanup: () => void
  } | null>(null)
  const didConnectRef = useRef(false)
  const fittedOnceRef = useRef(false)
  const measuredSizesRef = useRef<Map<string, NodeSize>>(new Map())

  const setHover = useCallback((id: string | null) => {
    hoverTargetRef.current = id
    setHoverTarget((prev) => (prev === id ? prev : id))
  }, [])

  const numbers = useMemo(() => stepNumbers(dialogue), [dialogue])
  const linkedMap = useMemo(() => new Map(ordered(dialogue).map((entry) => [entry.step.id, entry.linked])), [dialogue])

  useEffect(() => {
    const updateSizes = () => {
      const container = containerRef.current
      if (container) {
        const rect = container.getBoundingClientRect()
        setContainerSize((prev) =>
          prev.width === rect.width && prev.height === rect.height ? prev : { width: rect.width, height: rect.height },
        )
      }
      const toolbar = toolbarRef.current
      if (toolbar && container) {
        const cRect = container.getBoundingClientRect()
        const tRect = toolbar.getBoundingClientRect()
        const clearance = Math.ceil(tRect.bottom - cRect.top)
        if (clearance > 0) setToolbarClearance((prev) => (prev === clearance ? prev : clearance))
      }
    }
    updateSizes()
    const observer = new ResizeObserver(updateSizes)
    if (containerRef.current) observer.observe(containerRef.current)
    if (toolbarRef.current) observer.observe(toolbarRef.current)
    return () => observer.disconnect()
  }, [])

  const findStepAtPoint = useCallback((clientX: number, clientY: number): string | null => {
    const element = document.elementFromPoint(clientX, clientY)
    const card = element?.closest<HTMLElement>("[data-step]")
    return card?.dataset.step ?? null
  }, [])

  const isInsideFreeCanvas = useCallback((clientX: number, clientY: number): boolean => {
    const box = containerRef.current?.getBoundingClientRect()
    if (!box) return false
    if (clientX < box.left || clientX > box.right || clientY < box.top || clientY > box.bottom) return false
    if (clientX >= box.right - INSPECTOR_WIDTH) return false
    const toolbarBox = toolbarRef.current?.getBoundingClientRect()
    if (
      toolbarBox &&
      clientX >= toolbarBox.left &&
      clientX <= toolbarBox.right &&
      clientY >= toolbarBox.top &&
      clientY <= toolbarBox.bottom
    ) {
      return false
    }
    const element = document.elementFromPoint(clientX, clientY)
    if (element?.closest("[data-canvas-toolbar], aside")) return false
    return true
  }, [])

  const onStartDrag = useCallback(
    (stepId: string, event: ReactPointerEvent<HTMLDivElement>) => {
      if (event.button !== 0) return
      event.stopPropagation()
      event.preventDefault()
      const container = containerRef.current
      const previousCursor = document.body.style.cursor
      document.body.style.cursor = "grabbing"
      const toLocal = (clientX: number, clientY: number) => {
        const rect = container?.getBoundingClientRect()
        return rect ? { x: clientX - rect.left, y: clientY - rect.top } : { x: clientX, y: clientY }
      }
      setStartGhost(toLocal(event.clientX, event.clientY))

      let cancelled = false
      const stop = () => {
        window.removeEventListener("pointermove", onMove)
        window.removeEventListener("pointerup", onUp)
        window.removeEventListener("pointercancel", onCancel)
        window.removeEventListener("keydown", onKeyDown, true)
        document.body.style.cursor = previousCursor
        setStartGhost(null)
        setHover(null)
      }
      const onMove = (moveEvent: PointerEvent) => {
        setStartGhost(toLocal(moveEvent.clientX, moveEvent.clientY))
        const over = findStepAtPoint(moveEvent.clientX, moveEvent.clientY)
        setHover(over && over !== stepId ? over : null)
      }
      const onKeyDown = (keyEvent: KeyboardEvent) => {
        if (keyEvent.key !== "Escape") return
        keyEvent.preventDefault()
        keyEvent.stopPropagation()
        cancelled = true
        stop()
      }
      const onCancel = () => {
        cancelled = true
        stop()
      }
      const onUp = (upEvent: PointerEvent) => {
        const target = hoverTargetRef.current ?? findStepAtPoint(upEvent.clientX, upEvent.clientY)
        stop()
        if (!cancelled && target && target !== stepId) onMoveStart(target)
      }
      window.addEventListener("pointermove", onMove)
      window.addEventListener("pointerup", onUp)
      window.addEventListener("pointercancel", onCancel)
      window.addEventListener("keydown", onKeyDown, true)
    },
    [findStepAtPoint, onMoveStart, setHover],
  )

  const [flowNodes, setFlowNodes] = useState<StepFlowNode[]>(() =>
    dialogue.steps.map((step) => {
      const number = numbers.get(step.id) ?? 1
      return {
        id: step.id,
        type: "step",
        position: { x: step.x, y: step.y },
        selected: selected === step.id,
        ariaLabel: stepOptionLabel(step, number),
        data: {
          step,
          number,
          isStart: dialogue.start === step.id,
          linked: linkedMap.get(step.id) ?? false,
          missing: missingCount(step),
          tooLong: tooLong.has(step.id),
          language,
          menus,
          thenTargetNumber: step.next !== undefined ? numbers.get(step.next) : undefined,
          onAddLinked,
          onStartDrag,
        },
      }
    }),
  )

  // Keep React Flow node identity and `measured` state intact across edits, selection and hover.
  useEffect(() => {
    setFlowNodes((prev) => {
      const byId = new Map(prev.map((node) => [node.id, node]))
      let changed = prev.length !== dialogue.steps.length
      const nextNodes = dialogue.steps.map((step, index) => {
        const existing = byId.get(step.id)
        const number = numbers.get(step.id) ?? 1
        const isStart = dialogue.start === step.id
        const linked = linkedMap.get(step.id) ?? false
        const missing = missingCount(step)
        const stepTooLong = tooLong.has(step.id)
        const isSelected = selected === step.id
        const thenTargetNumber = step.next !== undefined ? numbers.get(step.next) : undefined
        const ariaLabel = stepOptionLabel(step, number)

        if (existing) {
          const samePosition = existing.dragging || (existing.position.x === step.x && existing.position.y === step.y)
          const prevData = existing.data
          const sameData =
            prevData.step === step &&
            prevData.number === number &&
            prevData.isStart === isStart &&
            prevData.linked === linked &&
            prevData.missing === missing &&
            prevData.tooLong === stepTooLong &&
            prevData.language === language &&
            prevData.menus === menus &&
            prevData.thenTargetNumber === thenTargetNumber &&
            prevData.onAddLinked === onAddLinked &&
            prevData.onStartDrag === onStartDrag
          if (samePosition && sameData && existing.selected === isSelected && existing.ariaLabel === ariaLabel) {
            if (prev[index] !== existing) changed = true
            return existing
          }
          changed = true
          return {
            ...existing,
            position: existing.dragging ? existing.position : { x: step.x, y: step.y },
            selected: isSelected,
            ariaLabel,
            data: sameData
              ? prevData
              : {
                  step,
                  number,
                  isStart,
                  linked,
                  missing,
                  tooLong: stepTooLong,
                  language,
                  menus,
                  thenTargetNumber,
                  onAddLinked,
                  onStartDrag,
                },
          }
        }
        changed = true
        return {
          id: step.id,
          type: "step" as const,
          position: { x: step.x, y: step.y },
          selected: isSelected,
          ariaLabel,
          data: {
            step,
            number,
            isStart,
            linked,
            missing,
            tooLong: stepTooLong,
            language,
            menus,
            thenTargetNumber,
            onAddLinked,
            onStartDrag,
          },
        }
      })
      return changed ? nextNodes : prev
    })
  }, [dialogue.start, dialogue.steps, language, linkedMap, menus, numbers, onAddLinked, onStartDrag, selected, tooLong])

  const getNodeSize = useCallback((stepId: string, fallbackStep?: Dialogue["steps"][number]): NodeSize => {
    const measured = measuredSizesRef.current.get(stepId)
    if (measured && measured.width > 0 && measured.height > 0) return measured
    return { width: NODE_WIDTH, height: fallbackStep ? nodeHeight(fallbackStep) : 124 }
  }, [])

  const handleNodesChange = useCallback((changes: NodeChangeset<StepFlowNode>) => {
    for (const change of changes.getByType("dimensions")) {
      if (change.dimensions) {
        measuredSizesRef.current.set(change.id, {
          width: change.dimensions.width,
          height: change.dimensions.height,
        })
      }
    }
    const allowed = changes.toArray().filter((change) => change.type !== "remove")
    if (allowed.length === 0) return
    setFlowNodes((prev) => new NodeChangeset(allowed).applyTo(prev))
  }, [])

  const edges = useMemo(
    () => buildFlowEdges(dialogue, numbers, selected, selectedEdge),
    [dialogue, numbers, selected, selectedEdge],
  )

  const sourceHandleSignature = useNodesStore((state) => {
    const parts: string[] = []
    for (const [id, node] of state.nodeLookup) {
      const source = node.internals.handleBounds?.source
      if (source) parts.push(`${id}:${source.map((handle) => handle.id ?? "").join(",")}`)
    }
    return parts.join("|")
  })

  // Withhold an edge only while its node's measured handle set has not yet refreshed after a Then/Answers switch.
  const readyEdges = useMemo(() => {
    void sourceHandleSignature
    const { nodeLookup } = store.nodesStore.getState()
    return edges.filter((edge) => {
      const bounds = nodeLookup.get(edge.source)?.internals.handleBounds?.source
      if (!bounds || bounds.length === 0) return true
      return bounds.some((handle) => handle.id === edge.sourceHandle)
    })
  }, [edges, sourceHandleSignature, store])

  const fitFreeArea = useCallback(
    (animate = true) => {
      const box = containerRef.current?.getBoundingClientRect()
      if (!box || box.width === 0 || box.height === 0 || dialogue.steps.length === 0) return
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
      // Reserve 28px above the highest node so compact back-link and self-link lanes stay below the toolbar.
      const edgeTopAllowance = 28
      const boundsMinY = minY - edgeTopAllowance
      const pad = 24
      const freeW = Math.max(240, box.width - INSPECTOR_WIDTH - pad * 2)
      const freeH = Math.max(200, box.height - toolbarClearance - pad * 2)
      const contentW = Math.max(NODE_WIDTH, maxX - minX)
      const contentH = Math.max(120, maxY - boundsMinY)
      const rawZoom = Math.min(MAX_ZOOM, Math.min(freeW / contentW, freeH / contentH))
      const duration = animate && !prefersReducedMotion() ? 200 : 0

      if (rawZoom >= FIT_MIN_ZOOM) {
        const fitZoom = Math.max(MIN_ZOOM, rawZoom)
        const x = pad + (freeW - contentW * fitZoom) / 2 - minX * fitZoom
        const y = toolbarClearance + pad + (freeH - contentH * fitZoom) / 2 - boundsMinY * fitZoom
        void setViewport({ x, y, zoom: fitZoom }, { duration })
        return
      }
      const startStep = stepOf(dialogue, dialogue.start) ?? dialogue.steps[0]
      const x = pad - startStep.x * FIT_MIN_ZOOM
      const y = toolbarClearance + pad - (startStep.y - edgeTopAllowance) * FIT_MIN_ZOOM
      void setViewport({ x, y, zoom: FIT_MIN_ZOOM }, { duration })
    },
    [dialogue, getNodeSize, setViewport, toolbarClearance],
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
    const size = getNodeSize(target.id, target)
    const vp = getViewport()
    const left = target.x * vp.zoom + vp.x
    const top = target.y * vp.zoom + vp.y
    const right = (target.x + size.width) * vp.zoom + vp.x
    const bottom = (target.y + size.height) * vp.zoom + vp.y
    const freeLeft = FREE_MARGIN
    const freeRight = Math.max(freeLeft + 160, box.width - INSPECTOR_WIDTH - FREE_MARGIN)
    const freeTop = toolbarClearance + FREE_MARGIN
    const freeBottom = box.height - FREE_MARGIN
    if (left >= freeLeft && right <= freeRight && top >= freeTop && bottom <= freeBottom) return
    let dx = 0
    if (right - left <= freeRight - freeLeft) {
      if (left < freeLeft) dx = freeLeft - left
      else if (right > freeRight) dx = freeRight - right
    } else if (left < freeLeft) {
      dx = freeLeft - left
    }
    let dy = 0
    if (bottom - top <= freeBottom - freeTop) {
      if (top < freeTop) dy = freeTop - top
      else if (bottom > freeBottom) dy = freeBottom - bottom
    } else if (top < freeTop) {
      dy = freeTop - top
    }
    if (dx === 0 && dy === 0) return
    void setViewport({ x: vp.x + dx, y: vp.y + dy, zoom: vp.zoom }, { duration: prefersReducedMotion() ? 0 : 200 })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [panSignal])

  const translateExtent = useMemo(
    () => computeTranslateExtent(dialogue, containerSize, INSPECTOR_WIDTH, toolbarClearance, zoom, getNodeSize),
    [containerSize, dialogue, getNodeSize, toolbarClearance, zoom],
  )

  const handleConnect = useCallback(
    (connection: Connection) => {
      if (connectingRef.current?.cancelled) return
      if (!connection.source || !connection.target || connection.source === connection.target) return
      didConnectRef.current = true
      onLink(connection.source, parseSourcePort(connection.sourceHandle), connection.target)
    },
    [onLink],
  )

  const handleConnectStart = useCallback(
    (event: MouseEvent | TouchEvent, params: { nodeId: string | null; handleId: string | null }) => {
      if (!params.nodeId) return
      const start = eventPoint(event) ?? { x: 0, y: 0 }
      didConnectRef.current = false
      setConnectingFrom(params.nodeId)

      const onPointerMove = (moveEvent: PointerEvent) => {
        const over = findStepAtPoint(moveEvent.clientX, moveEvent.clientY)
        setHover(over && over !== params.nodeId ? over : null)
      }
      const onKeyDown = (keyEvent: KeyboardEvent) => {
        if (keyEvent.key !== "Escape") return
        keyEvent.preventDefault()
        keyEvent.stopPropagation()
        if (connectingRef.current) {
          connectingRef.current.cancelled = true
          connectingRef.current.cleanup()
        }
        setConnectingFrom(null)
        setHover(null)
        store.optionsStore.getState().cancelConnection()
      }
      const cleanup = () => {
        window.removeEventListener("pointermove", onPointerMove)
        window.removeEventListener("keydown", onKeyDown, true)
      }
      connectingRef.current = {
        nodeId: params.nodeId,
        handleId: params.handleId,
        startX: start.x,
        startY: start.y,
        cancelled: false,
        cleanup,
      }
      window.addEventListener("pointermove", onPointerMove)
      window.addEventListener("keydown", onKeyDown, true)
    },
    [findStepAtPoint, setHover, store],
  )

  const handleConnectEnd = useCallback(
    (event: MouseEvent | TouchEvent) => {
      const started = connectingRef.current
      connectingRef.current = null
      started?.cleanup()
      const trackedHover = hoverTargetRef.current
      setHover(null)
      setConnectingFrom(null)
      if (!started || started.cancelled || didConnectRef.current) return
      const client = eventPoint(event)
      if (!client) return
      if (Math.hypot(client.x - started.startX, client.y - started.startY) < MIN_PORT_DRAG_PX) return
      if (!isInsideFreeCanvas(client.x, client.y)) return
      const directStep = findStepAtPoint(client.x, client.y)
      if (directStep === started.nodeId) return
      const pos = screenToFlowPosition({ x: client.x, y: client.y })
      const sourceStep = stepOf(dialogue, started.nodeId)
      if (sourceStep) {
        const sourceSize = getNodeSize(sourceStep.id, sourceStep)
        if (
          pos.x >= sourceStep.x &&
          pos.x <= sourceStep.x + sourceSize.width &&
          pos.y >= sourceStep.y &&
          pos.y <= sourceStep.y + sourceSize.height
        ) {
          return
        }
      }
      const targetStep = directStep ?? trackedHover
      const port = parseSourcePort(started.handleId)
      if (targetStep && targetStep !== started.nodeId) {
        onLink(started.nodeId, port, targetStep)
        return
      }
      onCreateFromPort(started.nodeId, port, pos.x, pos.y, measuredSizesRef.current)
    },
    [
      dialogue,
      findStepAtPoint,
      getNodeSize,
      isInsideFreeCanvas,
      onCreateFromPort,
      onLink,
      screenToFlowPosition,
      setHover,
    ],
  )

  const handlePaneClick = useCallback(
    (event: ReactMouseEvent) => {
      if (event.detail === 2) {
        const pos = screenToFlowPosition({ x: event.clientX, y: event.clientY })
        onCreateAt(pos.x, pos.y, measuredSizesRef.current)
        return
      }
      onSelect(null)
      onSelectEdge(null)
    },
    [onCreateAt, onSelect, onSelectEdge, screenToFlowPosition],
  )

  useLayoutEffect(() => {
    if (dialogue.steps.length === 0 || containerSize.width === 0 || containerSize.height === 0) return
    const rects = dialogue.steps.map((step) => {
      const size = getNodeSize(step.id, step)
      return { x: step.x, y: step.y, width: size.width, height: size.height }
    })
    const clamped = clampViewportToNodes({ x: viewportX, y: viewportY, zoom }, rects, {
      left: 0,
      right: Math.max(160, containerSize.width - INSPECTOR_WIDTH),
      top: toolbarClearance,
      bottom: Math.max(toolbarClearance + 120, containerSize.height),
    })
    if (clamped) void setViewport(clamped, { duration: 0 })
  }, [
    containerSize.height,
    containerSize.width,
    dialogue.steps,
    getNodeSize,
    setViewport,
    toolbarClearance,
    viewportX,
    viewportY,
    zoom,
  ])

  const handleCanvasKeyDownCapture = useCallback((event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (
      event.key !== "ArrowUp" &&
      event.key !== "ArrowDown" &&
      event.key !== "ArrowLeft" &&
      event.key !== "ArrowRight"
    ) {
      return
    }
    const target = event.target
    if (!(target instanceof HTMLElement)) return
    if (target.closest("button, a[href], input, textarea, select, [role='button']")) return
    if (!target.closest(".react-flow__node")) return
    event.preventDefault()
    event.stopPropagation()
  }, [])

  const handleCanvasKeyDown = useCallback(
    (event: ReactKeyboardEvent<HTMLDivElement>) => {
      const target = event.target
      if (!(target instanceof HTMLElement)) return
      if (target.closest("button, a[href], input, textarea, select, [role='button']")) return
      const nodeEl = target.closest<HTMLElement>(".react-flow__node")
      const nodeId = nodeEl?.dataset.id
      if (!nodeId) return
      if (event.key !== "Enter" && event.key !== " ") return
      event.preventDefault()
      event.stopPropagation()
      if (selected !== nodeId) {
        onSelectEdge(null)
        onSelect(nodeId)
      } else if (event.key === "Enter") {
        onFocusSelectedText(nodeId)
      }
    },
    [onFocusSelectedText, onSelect, onSelectEdge, selected],
  )

  const interactionValue = useMemo(() => ({ hoverTarget, connectingFrom }), [connectingFrom, hoverTarget])
  const ariaLabelConfig = useMemo(
    () => ({
      "node.a11yDescription.default": t("steward.npcs.node-hint"),
      "node.a11yDescription.keyboardDisabled": t("steward.npcs.node-hint"),
    }),
    [],
  )

  return (
    <CanvasInteractionContext.Provider value={interactionValue}>
      <div
        ref={containerRef}
        tabIndex={-1}
        className="npc-flow relative size-full outline-none"
        role="group"
        aria-label={t("steward.npcs.graph")}
        onKeyDownCapture={handleCanvasKeyDownCapture}
        onKeyDown={handleCanvasKeyDown}
      >
        <CanvasToolbar
          toolbarRef={toolbarRef}
          language={language}
          canUndo={canUndo}
          canRedo={canRedo}
          onAddStep={onAddStep}
          onArrange={() => onArrange(measuredSizesRef.current)}
          onUndo={onUndo}
          onRedo={onRedo}
          onLanguage={onLanguage}
          onFit={() => fitFreeArea(true)}
        />
        <ReactFlow
          nodes={flowNodes}
          edges={readyEdges}
          nodeTypes={NODE_TYPES}
          edgeTypes={EDGE_TYPES}
          minZoom={MIN_ZOOM}
          maxZoom={MAX_ZOOM}
          translateExtent={translateExtent}
          autoPanOnConnect={false}
          panOnDrag
          panOnScroll
          zoomOnScroll={false}
          zoomOnPinch
          zoomOnDoubleClick={false}
          nodesDraggable
          nodesConnectable
          elementsSelectable
          edgesFocusable={false}
          ariaLabelConfig={ariaLabelConfig}
          deleteKeyCode={null}
          connectionLineStyle={{ stroke: "var(--primary)", strokeWidth: 2 }}
          proOptions={{ hideAttribution: true }}
          onNodesChange={handleNodesChange}
          onNodeClick={(_event, node) => {
            onSelectEdge(null)
            onSelect(node.id)
          }}
          onNodeDragStop={(_event, node) => {
            onPlace(node.id, node.position.x, node.position.y, measuredSizesRef.current)
            onSelectEdge(null)
            onSelect(node.id)
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
        {startGhost ? (
          <div
            data-start-ghost
            aria-hidden="true"
            className="pointer-events-none absolute z-20 inline-flex -translate-x-1/2 -translate-y-1/2 items-center gap-1 rounded-sm border border-primary bg-popover px-2 py-1 text-xs font-medium text-popover-foreground shadow-md"
            style={{ left: startGhost.x, top: startGhost.y }}
          >
            <TERM_ICON.start aria-hidden className="size-3 shrink-0 text-primary" />
            {t("steward.npcs.start")}
          </div>
        ) : null}
      </div>
    </CanvasInteractionContext.Provider>
  )
}

export function StepCanvas(props: StepCanvasProps) {
  return (
    <ReactFlowProvider>
      <StepCanvasInner {...props} />
    </ReactFlowProvider>
  )
}
