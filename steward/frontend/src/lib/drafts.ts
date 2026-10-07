import { useSyncExternalStore } from "react"
import { shapedAs } from "@/lib/api"

/**
 * Unsaved edits of the settings and the texts, one record per file, and which tree branches are open.
 *
 * Module level, so a draft survives a tab switch but not a reload; a non-empty record means a draft.
 */
const drafts = new Map<string, Record<string, unknown>>()
const opened = new Map<string, Record<string, boolean>>()
const listeners = new Set<() => void>()
let dirty: string[] = []

const NOTHING: Record<string, never> = Object.freeze({})

function changed() {
  dirty = [...drafts.keys()]
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Sets one key of a file's draft, or removes it with `undefined`. */
export function setDraftValue(file: string, key: string, value: unknown) {
  const next = { ...drafts.get(file) }
  if (value === undefined) delete next[key]
  else next[key] = value
  if (Object.keys(next).length === 0) drafts.delete(file)
  else drafts.set(file, next)
  changed()
}

export function clearDraft(file: string) {
  if (!drafts.delete(file)) return
  changed()
}

export function useDraft<T>(file: string, isValue: (value: unknown) => value is Record<string, T>): Record<string, T> {
  return useSyncExternalStore(subscribe, () => shapedAs(drafts.get(file) ?? NOTHING, isValue, file))
}

/** Every file that holds a draft. */
export function useDirtyFiles(): string[] {
  return useSyncExternalStore(subscribe, () => dirty)
}

export function setOpened(file: string, branch: string, open: boolean) {
  opened.set(file, { ...opened.get(file), [branch]: open })
  changed()
}

/** The branches somebody opened or closed by hand; the rest follow the file's default. */
export function useOpened(file: string): Record<string, boolean> {
  return useSyncExternalStore(subscribe, () => opened.get(file) ?? NOTHING)
}

/** For tests: forget everything, as a reload would. */
export function resetDrafts() {
  drafts.clear()
  opened.clear()
  changed()
}

if (typeof window !== "undefined") {
  window.addEventListener("beforeunload", (event) => {
    if (drafts.size === 0) return
    event.preventDefault()
    event.returnValue = ""
  })
}
