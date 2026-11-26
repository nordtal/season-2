import { ClockCounterClockwiseIcon, SpinnerIcon, XIcon } from "@/components/ui/icons"
import { useQueryClient } from "@tanstack/react-query"
import { useNavigate } from "@tanstack/react-router"
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { toast } from "sonner"

import { api, ApiError, errorMessage, type CommandAsked, type Npc, type NpcView } from "@/lib/api"
import {
  type Language,
  LANGUAGES,
  type NodeSizes,
  addStep,
  addStepAt,
  arranged,
  deleteStep,
  ordered,
  overlapping,
  placeStep,
  problemsOf,
  sameNpc,
  setAnswerThen,
  setStart,
  setThen,
  stepNumbers,
  stepOf,
  warningsOf,
} from "@/lib/npc"
import { readNpcDraft, setNpcDraft, useNpcDraft } from "@/lib/npc-drafts"
import {
  everyNpcText,
  isCanvasShortcutTarget,
  isEditableElement,
  useIsDesktopCanvas,
  useNpcHistory,
  usePreviewOpen,
} from "@/lib/npc-history"
import { NPCS_PATH } from "@/lib/npc-paths"
import { finalRun } from "@/lib/queries-commands"
import { keys } from "@/lib/query-keys"
import { measureKey, useNpc, useNpcMeasurements, useNpcMenus, usePublishNpc, useRemoveNpc } from "@/lib/queries-npcs"
import { message, t } from "@/lib/texts"
import { useDebounced } from "@/lib/use-debounced"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { EditorHead } from "@/components/steward/npc/editor-head"
import { type SourcePort, StepCanvas, type TooLong, parseEdgeId } from "@/components/steward/npc/graph"
import { requestsOf } from "@/components/steward/npc/preview"
import { type IssueTarget, ProblemsPopover } from "@/components/steward/npc/problems"
import { NpcSettings } from "@/components/steward/npc/settings"
import { NarrowDialogueView } from "@/components/steward/npc/step-list"
import { InspectorEmpty, InspectorPreview, StepPanel } from "@/components/steward/npc/step-panel"
import { NpcVersions } from "@/components/steward/npc/versions"
import { QueryState, Skeleton } from "@/components/steward/query-state"
import { Hint, StatusBadge } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"

/**
 * The editor of one NPC. Every change goes into a browser draft with undo history; Publish sends the whole draft
 * against the version it was read at, and a draft that equals what is published is no draft.
 */

type Tab = "dialogue" | "settings"

/** How long typing rests before texts are measured again. */
const MEASURE_AFTER_MS = 250

/** The NPC's name as the page calls it: the English one, as Steward is English. */
export function npcTitle(npc: Npc): string {
  return npc.name.en.trim() || npc.name.de.trim() || t("steward.npcs.unnamed")
}

export function NpcEditor({ id }: { id: string }) {
  const query = useNpc(id)
  const draft = useNpcDraft(id)
  // An NPC that was never published answers 404 and lives in its draft alone, so a draft shows whatever the read says.
  const shown = draft ? { published: query.data } : query.data ? { published: query.data } : undefined
  return (
    <QueryState
      query={{
        data: shown,
        error: draft ? null : query.error,
        isPending: shown === undefined && query.isPending,
        fetchStatus: query.fetchStatus,
        refetch: () => void query.refetch(),
      }}
    >
      {(open) => {
        const npc = draft?.npc ?? open?.published?.npc
        return open === undefined || npc === undefined ? (
          <Waiting />
        ) : (
          <Editor
            key={id}
            npc={npc}
            base={draft?.base ?? open.published?.version ?? 0}
            published={open.published}
            dirty={draft !== undefined}
          />
        )
      }}
    </QueryState>
  )
}

/** The editor before its NPC is read: title, tabs, then canvas placeholder. */
function Waiting() {
  return (
    <div className="flex flex-col gap-3" role="status" aria-busy="true">
      <span className="sr-only">{t("steward.failure.loading")}</span>
      <Skeleton className="h-8 w-56" />
      <Skeleton className="h-8 w-40" />
      <Skeleton className="h-npc-canvas w-full" />
    </div>
  )
}

function Editor({
  npc,
  base,
  published,
  dirty,
}: {
  npc: Npc
  /** The version the draft was read at, 0 for an NPC that was never published. */
  base: number
  published: NpcView | undefined
  dirty: boolean
}) {
  const client = useQueryClient()
  const navigate = useNavigate()
  const isDesktop = useIsDesktopCanvas()
  const menus = useNpcMenus()
  const publish = usePublishNpc()
  const remove = useRemoveNpc()
  const [tab, setTab] = useState<Tab>("dialogue")
  const [picked, setPicked] = useState<string | null>(npc.dialogue.start)
  const [selectedEdge, setSelectedEdge] = useState<string | null>(null)
  const [mobileInspectorOpen, setMobileInspectorOpen] = useState(false)
  const [previewPage, setPreviewPage] = useState(0)
  const [previewOpen, togglePreview] = usePreviewOpen(isDesktop)
  const [language, setLanguage] = useState<Language>("en")
  const [problemsOpen, setProblemsOpen] = useState(false)
  const [versions, setVersions] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [playing, setPlaying] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)
  const [fitSignal, setFitSignal] = useState(0)
  const [panSignal, setPanSignal] = useState(0)
  const play = useRef<string | null>(null)
  const sending = useRef(false)

  const { edit, applyEdit, undo, redo, clearHistory, canUndo, canRedo, npcRef } = useNpcHistory({
    npc,
    base,
    published,
    sending,
  })

  const selectedStep = picked === null ? undefined : stepOf(npc.dialogue, picked)
  const title = npcTitle(npc)

  const focusField = useCallback((selectorKey: string) => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        const el = document.querySelector<HTMLElement>(`[data-focus-field="${CSS.escape(selectorKey)}"]`)
        el?.focus()
      })
    })
  }, [])

  const selectAndFocusStep = useCallback(
    (stepId: string, fieldKey?: string, pan = true) => {
      setPicked(stepId)
      setSelectedEdge(null)
      setPreviewPage(0)
      if (!isDesktop) setMobileInspectorOpen(true)
      if (pan) setPanSignal((n) => n + 1)
      if (fieldKey) focusField(fieldKey)
    },
    [focusField, isDesktop],
  )

  const texts = useDebounced(
    useMemo(() => everyNpcText(npc), [npc]),
    MEASURE_AFTER_MS,
  )
  const measured = useNpcMeasurements(texts).data

  // Keep the previous too-long marks while new measurements arrive.
  const freshTooLong = useMemo(() => {
    const ids: string[] = []
    for (const each of npc.dialogue.steps) {
      let over = false
      for (const code of LANGUAGES) {
        for (const request of requestsOf(npc, each, code).slice(1)) {
          if (request.text === "") continue
          const measurement = measured?.get(measureKey(request))
          if (measurement === undefined) return undefined
          if (!measurement.fits) over = true
        }
      }
      if (over) ids.push(each.id)
    }
    return JSON.stringify(ids)
  }, [npc, measured])
  const [tooLongJson, setTooLongJson] = useState("[]")
  if (freshTooLong !== undefined && freshTooLong !== tooLongJson) setTooLongJson(freshTooLong)
  const tooLong = useMemo<TooLong>(() => {
    const parsed: unknown = JSON.parse(tooLongJson)
    return new Set(Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [])
  }, [tooLongJson])

  const loadedMenus = menus.isSuccess ? menus.data : undefined
  const problems = useMemo(() => problemsOf(npc, measured, loadedMenus), [npc, measured, loadedMenus])
  const warnings = useMemo(() => warningsOf(npc, measured), [npc, measured])

  const handleDeleteStep = useCallback(
    (id: string) => {
      const current = npcRef.current
      if (current.dialogue.steps.length <= 1) return
      const beforeOrder = ordered(current.dialogue).map((entry) => entry.step.id)
      const deletedIndex = beforeOrder.indexOf(id)
      const fallbackId = deletedIndex > 0 ? beforeOrder[deletedIndex - 1] : (beforeOrder[1] ?? null)
      const numbersBefore = stepNumbers(current.dialogue)
      const deletedNumber = numbersBefore.get(id) ?? 1
      const wasStart = current.dialogue.start === id
      edit((draft) => deleteStep(draft, id))
      setPicked((prev) => (prev === id ? fallbackId : prev))
      setSelectedEdge(null)
      setMobileInspectorOpen(false)
      setPreviewPage(0)
      toast(
        wasStart ? t("steward.npcs.step-deleted-start") : t("steward.npcs.step-deleted", { number: deletedNumber }),
        {
          action: {
            label: t("steward.npcs.undo"),
            onClick: () => undo(),
          },
        },
      )
    },
    [edit, npcRef, undo],
  )

  const handleUnlinkEdge = useCallback(
    (edgeId: string) => {
      const parsed = parseEdgeId(edgeId)
      if (!parsed) return
      edit((draft) =>
        parsed.port === "then"
          ? setThen(draft, parsed.stepId, { to: "end" })
          : setAnswerThen(draft, parsed.stepId, parsed.port, { to: "end" }),
      )
      setSelectedEdge(null)
      toast(t("steward.npcs.link-removed"), {
        action: {
          label: t("steward.npcs.undo"),
          onClick: () => undo(),
        },
      })
    },
    [edit, undo],
  )

  const handleLinkPort = useCallback(
    (sourceId: string, port: SourcePort, targetId: string) => {
      edit((draft) =>
        port === "then"
          ? setThen(draft, sourceId, { to: "step", step: targetId })
          : setAnswerThen(draft, sourceId, port, { to: "step", step: targetId }),
      )
    },
    [edit],
  )

  const handleCreateFromPort = useCallback(
    (sourceId: string, port: SourcePort, x: number, y: number, sizes?: NodeSizes) => {
      let createdId = ""
      edit((draft) => {
        const baseDraft = overlapping(draft.dialogue, sizes) ? arranged(draft, sizes) : draft
        const added = addStepAt(baseDraft, x, y, sizes)
        createdId = added.id
        return port === "then"
          ? setThen(added.npc, sourceId, { to: "step", step: added.id })
          : setAnswerThen(added.npc, sourceId, port, { to: "step", step: added.id })
      })
      if (createdId) selectAndFocusStep(createdId, `step-text:${createdId}:0:en`, true)
    },
    [edit, selectAndFocusStep],
  )

  const handleAddLinked = useCallback(
    (sourceId: string, port: SourcePort) => {
      let createdId = ""
      edit((draft) => {
        const baseDraft = overlapping(draft.dialogue) ? arranged(draft) : draft
        const added = addStep(baseDraft, sourceId)
        createdId = added.id
        return port === "then"
          ? setThen(added.npc, sourceId, { to: "step", step: added.id })
          : setAnswerThen(added.npc, sourceId, port, { to: "step", step: added.id })
      })
      if (createdId) selectAndFocusStep(createdId, `step-text:${createdId}:0:en`, true)
    },
    [edit, selectAndFocusStep],
  )

  const handleCreateAt = useCallback(
    (x: number, y: number, sizes?: NodeSizes) => {
      let createdId = ""
      edit((draft) => {
        const baseDraft = overlapping(draft.dialogue, sizes) ? arranged(draft, sizes) : draft
        const added = addStepAt(baseDraft, x, y, sizes)
        createdId = added.id
        return added.npc
      })
      if (createdId) selectAndFocusStep(createdId, `step-text:${createdId}:0:en`, true)
    },
    [edit, selectAndFocusStep],
  )

  const handleAddStepToolbar = useCallback(() => {
    let createdId = ""
    edit((draft) => {
      const baseDraft = overlapping(draft.dialogue) ? arranged(draft) : draft
      const added = addStep(baseDraft, picked ?? baseDraft.dialogue.start)
      createdId = added.id
      return added.npc
    })
    if (createdId) selectAndFocusStep(createdId, `step-text:${createdId}:0:en`, true)
  }, [edit, picked, selectAndFocusStep])

  const handleQuickNext = useCallback(() => {
    if (!selectedStep) return
    if (selectedStep.answers.length > 0) {
      const openIndex = selectedStep.answers.findIndex((answer) => answer.next === undefined)
      if (openIndex >= 0) handleAddLinked(selectedStep.id, openIndex)
      else handleAddStepToolbar()
      return
    }
    if (selectedStep.next === undefined && selectedStep.menu === undefined) {
      handleAddLinked(selectedStep.id, "then")
    } else {
      handleAddStepToolbar()
    }
  }, [handleAddLinked, handleAddStepToolbar, selectedStep])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (isEditableElement(event.target)) return
      const mod = event.metaKey || event.ctrlKey
      if (mod && event.key.toLowerCase() === "z") {
        event.preventDefault()
        if (event.shiftKey) redo()
        else undo()
        return
      }
      if (mod && event.key.toLowerCase() === "y") {
        event.preventDefault()
        redo()
        return
      }
      if (tab !== "dialogue") return
      if (!isCanvasShortcutTarget(event.target)) return
      if (event.key === "Escape") {
        if (event.defaultPrevented || problemsOpen || versions || conflict) return
        setPicked(null)
        setSelectedEdge(null)
        return
      }
      if (event.key === "Delete" || event.key === "Backspace") {
        if (selectedEdge) {
          event.preventDefault()
          handleUnlinkEdge(selectedEdge)
        } else if (picked && npcRef.current.dialogue.steps.length > 1) {
          const focusedNode =
            event.target instanceof Element ? event.target.closest<HTMLElement>(".react-flow__node") : null
          if (focusedNode && focusedNode.dataset.id !== picked) return
          event.preventDefault()
          handleDeleteStep(picked)
        }
        return
      }
      if (event.key === "Enter" && picked) {
        event.preventDefault()
        focusField(`step-text:${picked}:0:en`)
      }
    }
    window.addEventListener("keydown", onKeyDown)
    return () => window.removeEventListener("keydown", onKeyDown)
  }, [
    conflict,
    focusField,
    handleDeleteStep,
    handleUnlinkEdge,
    npcRef,
    picked,
    problemsOpen,
    redo,
    selectedEdge,
    tab,
    undo,
    versions,
  ])

  const handleSelectIssue = useCallback(
    (target: IssueTarget) => {
      if (target.kind === "problem") {
        const item = target.item
        if (item.problem === "name") {
          setTab("settings")
          focusField(`npc-name:${item.language}`)
          return
        }
        if (item.problem === "place") {
          setTab("settings")
          focusField("npc-place")
          return
        }
        setTab("dialogue")
        setPicked(item.step)
        setSelectedEdge(null)
        if (!isDesktop) setMobileInspectorOpen(true)
        setPanSignal((n) => n + 1)
        if (item.problem === "step-text" || item.problem === "step-tall") {
          setPreviewPage(item.page)
          focusField(`step-text:${item.step}:${item.page}:${item.language}`)
        } else if (item.problem === "answer-text") {
          focusField(`answer-text:${item.step}:${item.answer}:${item.language}`)
        } else {
          focusField(`step-then:${item.step}`)
        }
        return
      }
      const item = target.item
      setTab("dialogue")
      setPicked(item.step)
      setSelectedEdge(null)
      if (!isDesktop) setMobileInspectorOpen(true)
      setPanSignal((n) => n + 1)
      if (item.warning === "step-wide") {
        setPreviewPage(item.page)
        focusField(`step-text:${item.step}:${item.page}:${item.language}`)
      } else if (item.warning === "answer-wide") {
        focusField(`answer-text:${item.step}:${item.answer}:${item.language}`)
      }
    },
    [focusField, isDesktop],
  )

  const send = (version: number) => {
    if (problems.length > 0) {
      setProblemsOpen(true)
      return
    }
    sending.current = true
    publish.mutateAsync({ npc, version }).then(
      (view) => {
        sending.current = false
        const held = readNpcDraft(npc.id)
        setNpcDraft(npc.id, held && !sameNpc(held.npc, npc) ? { npc: held.npc, base: view.version } : undefined)
        setConflict(false)
        toast.success(t("steward.npcs.published", { name: npcTitle(view.npc), version: view.version }))
      },
      (error: unknown) => {
        sending.current = false
        if (error instanceof ApiError && error.code === "NPC_CHANGED") {
          void client.invalidateQueries({ queryKey: keys.npcs }).then(() => {
            const read = client.getQueryState(keys.npc(npc.id))?.error
            if (read instanceof ApiError && read.status === 404) toast.error(t("steward.answer.no-npc"))
            else setConflict(true)
          })
        } else {
          toast.error(t("steward.npcs.not-published-yet"), { description: errorMessage(error) })
        }
      },
    )
  }

  const ended = (id: string) => {
    if (play.current !== id) return false
    play.current = null
    setPlaying(null)
    return true
  }

  const startPlaying = async () => {
    if (problems.length > 0) {
      setProblemsOpen(true)
      return
    }
    setStarting(true)
    try {
      const asked = await api<CommandAsked>("/api/npc-preview", { method: "POST", body: { npc, language } })
      setStarting(false)
      play.current = asked.id
      setPlaying(asked.id)
      const run = await finalRun(client, asked.id)
      if (!ended(asked.id) || run.status === "DONE" || run.status === "CANCELLED") return
      if (run.status === "REFUSED" || run.status === "EXPIRED") toast.error(t("steward.answer.take-in-offline"))
      else toast.error(t("steward.npcs.not-played"), { description: run.result ? message(run.result) : undefined })
    } catch (error) {
      setStarting(false)
      play.current = null
      setPlaying(null)
      toast.error(t("steward.npcs.not-played"), { description: errorMessage(error) })
    }
  }

  const cancelPlaying = () => {
    const id = playing
    if (id === null || !ended(id)) return
    api<void>(`/api/commands/${id}`, { method: "DELETE" }).catch((error: unknown) =>
      toast.error(t("steward.npcs.not-played"), { description: errorMessage(error) }),
    )
  }

  const displayDialogue = useMemo(() => (overlapping(npc.dialogue) ? arranged(npc).dialogue : npc.dialogue), [npc])
  const live = published?.version ?? 0
  const selectedNumber = selectedStep ? (stepNumbers(npc.dialogue).get(selectedStep.id) ?? 1) : 1

  const inspectorBody = selectedStep ? (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
      <ScrollArea className="min-h-0 flex-1">
        <div className="px-3 py-1.5">
          <StepPanel
            npc={npc}
            step={selectedStep}
            menus={menus.data ?? []}
            measured={measured}
            edit={edit}
            onSelectPage={setPreviewPage}
            onDeleteStep={handleDeleteStep}
            onQuickNext={handleQuickNext}
            onCreatedStep={(id) => selectAndFocusStep(id, `step-text:${id}:0:en`, true)}
            onFocusField={focusField}
          />
        </div>
      </ScrollArea>
      <InspectorPreview
        npc={npc}
        step={selectedStep}
        pageIndex={previewPage}
        language={language}
        measured={measured}
        open={previewOpen}
        onToggle={togglePreview}
        onSelectPage={setPreviewPage}
      />
    </div>
  ) : (
    <InspectorEmpty npc={npc} />
  )

  const statusBadge =
    live === 0 ? (
      <StatusBadge tone="warn">{t("steward.npcs.draft-unpublished")}</StatusBadge>
    ) : dirty ? (
      <StatusBadge tone="warn">{t("steward.npcs.changed")}</StatusBadge>
    ) : (
      <Badge variant="secondary">{t("steward.npcs.live", { version: live })}</Badge>
    )

  return (
    <div className="flex flex-col gap-2 sm:gap-3">
      <EditorHead
        title={title}
        status={statusBadge}
        actions={
          <>
            {dirty ? (
              <AskThenAct
                trigger={
                  <Button type="button" variant="ghost" size="sm" className="shrink-0">
                    {t("steward.npcs.discard")}
                  </Button>
                }
                title={t("steward.npcs.discard-ask", { name: title })}
                description={live > 0 ? t("steward.npcs.discard-note", { version: base }) : undefined}
                action={t("steward.npcs.discard")}
                destructive
                act={() => {
                  clearHistory()
                  setNpcDraft(npc.id, undefined)
                  if (live === 0) void navigate({ ...NPCS_PATH, replace: true })
                }}
              />
            ) : null}
            {live > 0 ? (
              <Hint tip={t("steward.npcs.versions")}>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  aria-label={t("steward.npcs.versions")}
                  className="shrink-0 max-sm:px-2"
                  onClick={() => setVersions(true)}
                >
                  <ClockCounterClockwiseIcon aria-hidden />
                  <span className="max-sm:sr-only">{t("steward.npcs.versions")}</span>
                </Button>
              </Hint>
            ) : null}
            {playing === null ? (
              <Button
                type="button"
                variant="secondary"
                size="sm"
                disabled={starting}
                className="shrink-0"
                onClick={() => void startPlaying()}
              >
                <TERM_ICON.start aria-hidden />
                {t("steward.npcs.play")}
              </Button>
            ) : (
              <Button type="button" variant="destructive" size="sm" className="shrink-0" onClick={cancelPlaying}>
                <XIcon aria-hidden />
                {t("steward.npcs.cancel-play")}
              </Button>
            )}
            <ProblemsPopover
              npc={npc}
              problems={problems}
              warnings={warnings}
              open={problemsOpen}
              onOpenChange={setProblemsOpen}
              onSelect={handleSelectIssue}
            />
            <Button
              type="button"
              size="sm"
              className="shrink-0"
              disabled={!dirty || publish.isPending}
              onClick={() => send(base)}
            >
              {publish.isPending ? <SpinnerIcon aria-hidden className="animate-spin" /> : null}
              {publish.isPending ? t("steward.npcs.publishing") : t("steward.npcs.publish")}
            </Button>
          </>
        }
      />

      <Tabs value={tab} onValueChange={(value) => setTab(value === "settings" ? "settings" : "dialogue")}>
        <TabsList>
          <TabsTrigger value="dialogue">{t("steward.npcs.tab", { tab: "dialogue" })}</TabsTrigger>
          <TabsTrigger value="settings">{t("steward.npcs.tab", { tab: "settings" })}</TabsTrigger>
        </TabsList>

        <TabsContent value="dialogue" className="mt-2 sm:mt-3">
          {isDesktop ? (
            <div className="relative h-npc-canvas min-h-96 w-full overflow-hidden rounded-lg border bg-background">
              <StepCanvas
                dialogue={displayDialogue}
                menus={menus.data ?? []}
                selected={selectedStep?.id ?? null}
                selectedEdge={selectedEdge}
                tooLong={tooLong}
                language={language}
                canUndo={canUndo}
                canRedo={canRedo}
                fitSignal={fitSignal}
                panSignal={panSignal}
                onSelect={(id) => {
                  setPicked(id)
                  setPreviewPage(0)
                  if (id !== null) setPanSignal((n) => n + 1)
                }}
                onSelectEdge={setSelectedEdge}
                onFocusSelectedText={(id) => focusField(`step-text:${id}:0:en`)}
                onPlace={(id, x, y, sizes) =>
                  edit((draft) =>
                    placeStep(overlapping(draft.dialogue, sizes) ? arranged(draft, sizes) : draft, id, x, y, sizes),
                  )
                }
                onLink={handleLinkPort}
                onCreateFromPort={handleCreateFromPort}
                onAddLinked={handleAddLinked}
                onCreateAt={handleCreateAt}
                onMoveStart={(targetId) => edit((draft) => setStart(draft, targetId))}
                onAddStep={handleAddStepToolbar}
                onArrange={(sizes) => {
                  edit((draft) => arranged(draft, sizes))
                  setFitSignal((n) => n + 1)
                }}
                onUndo={undo}
                onRedo={redo}
                onLanguage={setLanguage}
              />
              <aside
                aria-label={
                  selectedStep ? t("steward.npcs.step-number", { number: selectedNumber }) : t("steward.npcs.graph")
                }
                className="absolute inset-y-0 right-0 z-10 flex w-npc-inspector max-w-full flex-col border-l bg-popover text-popover-foreground shadow-lg"
              >
                {inspectorBody}
              </aside>
            </div>
          ) : (
            <NarrowDialogueView
              dialogue={npc.dialogue}
              menus={menus.data ?? []}
              selected={selectedStep?.id ?? null}
              selectedNumber={selectedNumber}
              tooLong={tooLong}
              language={language}
              canUndo={canUndo}
              canRedo={canRedo}
              inspectorOpen={mobileInspectorOpen}
              inspectorBody={inspectorBody}
              onSelect={(id) => {
                setPicked(id)
                setPreviewPage(0)
                setMobileInspectorOpen(true)
              }}
              onInspectorOpenChange={setMobileInspectorOpen}
              onAddStep={handleAddStepToolbar}
              onUndo={undo}
              onRedo={redo}
              onLanguage={setLanguage}
            />
          )}
        </TabsContent>

        <TabsContent value="settings" className="mt-4">
          <NpcSettings
            npc={npc}
            published={published?.npc}
            measured={measured}
            edit={edit}
            onDelete={() =>
              remove.mutateAsync(npc.id).then(() => {
                clearHistory()
                setNpcDraft(npc.id, undefined)
                toast.success(t("steward.npcs.deleted", { name: title }))
                return navigate({ ...NPCS_PATH, replace: true })
              })
            }
          />
        </TabsContent>
      </Tabs>

      {live > 0 ? (
        <NpcVersions
          id={npc.id}
          name={title}
          open={versions}
          onOpenChange={setVersions}
          onLoad={(version) => {
            const restored = version.npc ?? (version.dialogue ? { ...npc, dialogue: version.dialogue } : undefined)
            if (restored === undefined) return
            clearHistory()
            applyEdit(() => restored, { record: false })
            setPicked(restored.dialogue.start)
            setSelectedEdge(null)
            setPreviewPage(0)
            setVersions(false)
            setFitSignal((n) => n + 1)
            toast.success(t("steward.npcs.loaded", { number: version.number }))
          }}
        />
      ) : null}

      <AskThenAct
        open={conflict}
        onOpenChange={setConflict}
        title={t("steward.npcs.conflict", { name: title })}
        description={t("steward.npcs.conflict-note", { live, read: base })}
        action={t("steward.npcs.load-live", { live })}
        act={() => {
          clearHistory()
          setNpcDraft(npc.id, undefined)
        }}
      >
        <Button
          type="button"
          variant="outline"
          disabled={publish.isPending || live === base}
          onClick={() => send(live)}
        >
          {t("steward.npcs.overwrite", { live })}
        </Button>
      </AskThenAct>
    </div>
  )
}
