import type { Npc } from "@/lib/api"
import { clearDraft, setDraftValue, useDirtyFiles, useDraft } from "@/lib/drafts"

/**
 * The unpublished draft of each NPC, kept beside the other unsaved edits, so it survives a change of page and a reload asks first.
 */

/** A draft and the version it was read at, 0 for an NPC that was never published. */
export type NpcDraft = { npc: Npc; base: number }

const PREFIX = "npc:"

function file(id: string): string {
  return PREFIX + id
}

function isDraftRecord(value: unknown): value is Record<string, NpcDraft> {
  return typeof value === "object" && value !== null
}

export function useNpcDraft(id: string): NpcDraft | undefined {
  return useDraft(file(id), isDraftRecord).draft
}

export function setNpcDraft(id: string, draft: NpcDraft | undefined) {
  if (draft === undefined) clearDraft(file(id))
  else setDraftValue(file(id), "draft", draft)
}

/** The ids of the NPCs that have a draft, in the order the drafts were begun. */
export function useNpcDraftIds(): string[] {
  return useDirtyFiles()
    .filter((name) => name.startsWith(PREFIX))
    .map((name) => name.slice(PREFIX.length))
}
