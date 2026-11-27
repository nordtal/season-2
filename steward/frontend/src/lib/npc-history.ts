import { useCallback, useEffect, useRef, useState } from "react"
import { useQueryClient } from "@tanstack/react-query"

import type { Npc, NpcTextMeasureRequest, NpcView } from "@/lib/api"
import { LANGUAGES, sameNpc } from "@/lib/npc"
import { readNpcDraft, setNpcDraft } from "@/lib/npc-drafts"
import { keys } from "@/lib/query-keys"
import { requestsOf } from "@/components/steward/npc/preview"

/** Matches Tailwind's `xl` breakpoint (1280px) so only one of canvas and list mounts at a time. */
const DESKTOP_CANVAS_QUERY = "(min-width: 80rem)"

export function useIsDesktopCanvas(): boolean {
  const [desktop, setDesktop] = useState(() =>
    typeof window === "undefined" ? true : window.matchMedia(DESKTOP_CANVAS_QUERY).matches,
  )
  useEffect(() => {
    const media = window.matchMedia(DESKTOP_CANVAS_QUERY)
    const update = () => setDesktop(media.matches)
    update()
    media.addEventListener("change", update)
    return () => media.removeEventListener("change", update)
  }, [])
  return desktop
}

/** Preview open state: persisted on desktop, collapsed by default in narrow dialogs and sheets. */
export function usePreviewOpen(isDesktop: boolean): [boolean, () => void] {
  const [desktopOpen, setDesktopOpen] = useState(() => {
    try {
      return window.localStorage.getItem("steward.npc.preview-open") !== "false"
    } catch {
      return true
    }
  })
  const [narrowOpen, setNarrowOpen] = useState(false)
  const toggle = useCallback(() => {
    if (isDesktop) {
      setDesktopOpen((open) => {
        const next = !open
        try {
          window.localStorage.setItem("steward.npc.preview-open", String(next))
        } catch {
          // Storage may be unavailable in private browsing.
        }
        return next
      })
    } else {
      setNarrowOpen((open) => !open)
    }
  }, [isDesktop])
  return [isDesktop ? desktopOpen : narrowOpen, toggle]
}

export function isEditableElement(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable
}

const INTERACTIVE_CONTROL =
  "button, a[href], input, textarea, select, [contenteditable='true'], [role='button'], [role='link'], [role='tab'], [role='combobox'], [role='menuitem'], [role='option'], [role='dialog'], [role='alertdialog'], [data-canvas-toolbar], aside"

/** Whether a keyboard event target belongs to the canvas frame, a node, an edge or document body. */
export function isCanvasShortcutTarget(target: EventTarget | null): boolean {
  if (target === null) return false
  if (target === document.body || target === document.documentElement) return true
  if (!(target instanceof Element)) return false
  if (target.closest(".react-flow__edge") !== null) return true
  if (target.closest(INTERACTIVE_CONTROL) !== null) return false
  return target.closest(".npc-flow") !== null
}

/** Every text of the NPC as the panel measures it: the names, and each step's text and answers, in both languages. */
export function everyNpcText(npc: Npc): NpcTextMeasureRequest[] {
  return LANGUAGES.flatMap((language) => [
    { text: npc.name[language], kind: "NAME" as const, answers: 0 },
    ...npc.dialogue.steps.flatMap((step) => requestsOf(npc, step, language).slice(1)),
  ]).filter((request) => request.text !== "")
}

/**
 * Undo/redo history over an NPC draft, applying every change through the latest draft so late answers
 * (such as an uploaded skin) land on the current draft rather than an old render snapshot.
 */

const COALESCE_MS = 1500
const MAX_HISTORY = 100

type HistoryState = {
  past: Npc[]
  future: Npc[]
  lastKey?: string
  lastAt?: number
}

export type EditOptions = { coalesce?: string; record?: boolean }

export function useNpcHistory({
  npc,
  base,
  published,
  sending,
}: {
  npc: Npc
  base: number
  published: NpcView | undefined
  sending: React.RefObject<boolean>
}) {
  const client = useQueryClient()
  const [history, setHistory] = useState<HistoryState>({ past: [], future: [] })
  const historyRef = useRef(history)
  const npcRef = useRef(npc)

  useEffect(() => {
    historyRef.current = history
  }, [history])

  useEffect(() => {
    npcRef.current = npc
  }, [npc])

  const clearHistory = useCallback(() => {
    const empty: HistoryState = { past: [], future: [] }
    historyRef.current = empty
    setHistory(empty)
  }, [])

  const applyEdit = useCallback(
    (change: (current: Npc) => Npc, options?: EditOptions) => {
      const currentNpc = npcRef.current
      const held = readNpcDraft(currentNpc.id)
      const live = client.getQueryData<NpcView>(keys.npc(currentNpc.id))
      // A late answer for an NPC that was deleted meanwhile starts no new draft.
      if (held === undefined && live === undefined && published !== undefined) return
      const from = held ?? (live ? { npc: live.npc, base: live.version } : { npc: currentNpc, base })
      const next = change(from.npc)
      if (sameNpc(from.npc, next)) return
      if (options?.record !== false) {
        const now = Date.now()
        const prev = historyRef.current
        const shouldCoalesce =
          options?.coalesce !== undefined &&
          prev.lastKey === options.coalesce &&
          prev.lastAt !== undefined &&
          now - prev.lastAt < COALESCE_MS &&
          prev.past.length > 0
        const updated: HistoryState = shouldCoalesce
          ? { past: prev.past, future: [], lastKey: options.coalesce, lastAt: now }
          : {
              past: [...prev.past.slice(-(MAX_HISTORY - 1)), from.npc],
              future: [],
              lastKey: options?.coalesce,
              lastAt: now,
            }
        historyRef.current = updated
        setHistory(updated)
      }
      // While a publication is on its way the draft stays, even one that equals what was live: the answer decides.
      const untouched = !sending.current && live !== undefined && from.base === live.version && sameNpc(next, live.npc)
      setNpcDraft(currentNpc.id, untouched ? undefined : { npc: next, base: from.base })
    },
    [base, client, published, sending],
  )

  const edit = useCallback(
    (change: (current: Npc) => Npc, options?: { coalesce?: string }) => applyEdit(change, options),
    [applyEdit],
  )

  const undo = useCallback(() => {
    const prev = historyRef.current
    if (prev.past.length === 0) return
    const target = prev.past[prev.past.length - 1]
    const updated: HistoryState = {
      past: prev.past.slice(0, -1),
      future: [npcRef.current, ...prev.future.slice(0, MAX_HISTORY - 1)],
    }
    historyRef.current = updated
    setHistory(updated)
    applyEdit(() => target, { record: false })
  }, [applyEdit])

  const redo = useCallback(() => {
    const prev = historyRef.current
    if (prev.future.length === 0) return
    const target = prev.future[0]
    const updated: HistoryState = {
      past: [...prev.past.slice(-(MAX_HISTORY - 1)), npcRef.current],
      future: prev.future.slice(1),
    }
    historyRef.current = updated
    setHistory(updated)
    applyEdit(() => target, { record: false })
  }, [applyEdit])

  return {
    edit,
    applyEdit,
    undo,
    redo,
    clearHistory,
    canUndo: history.past.length > 0,
    canRedo: history.future.length > 0,
    npcRef,
  }
}
