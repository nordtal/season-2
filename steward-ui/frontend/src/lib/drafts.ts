import { useSyncExternalStore } from "react"

/**
 * What has been typed into the Settings & Translations tab and not saved yet, one record per file.
 *
 * Module level on purpose: a draft outlives switching to another file or another tab (the tab's
 * content unmounts), and nothing outlives a reload. Which branches of a file's tree are open lives
 * here too, under the same rule.
 *
 * A record only ever holds real changes - whoever writes into it drops a value that equals what is
 * saved - so "this file has a draft" is simply "its record is not empty".
 */
const drafts = new Map<string, Record<string, unknown>>()
const opened = new Map<string, Record<string, boolean>>()
const listeners = new Set<() => void>()
let dirty: string[] = []

const NOTHING: Record<string, never> = Object.freeze({}) as Record<string, never>

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
  const next = { ...(drafts.get(file) ?? {}) }
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

export function useDraft<T>(file: string): Record<string, T> {
  return useSyncExternalStore(subscribe, () => (drafts.get(file) ?? NOTHING) as Record<string, T>)
}

/** Every file that holds a draft. */
export function useDirtyFiles(): string[] {
  return useSyncExternalStore(subscribe, () => dirty)
}

export function setOpened(file: string, branch: string, open: boolean) {
  opened.set(file, { ...(opened.get(file) ?? {}), [branch]: open })
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
