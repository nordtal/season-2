import type { Npc } from "@/lib/api"
import { clearDraft, draftValue, setDraftValue, useDirtyFiles, useDraft } from "@/lib/drafts"

/**
 * The unpublished draft of each NPC, kept beside the other unsaved edits, so it survives a change of page and a reload asks first.
 */

/** A draft and the version it was read at, 0 for an NPC that was never published. */
export type NpcDraft = { npc: Npc; base: number }

const PREFIX = "npc:"

function file(id: string): string {
  return PREFIX + id
}

function hasPages(value: unknown): boolean {
  if (typeof value !== "object" || value === null || !("dialogue" in value)) return false
  const dialogue = value.dialogue
  if (typeof dialogue !== "object" || dialogue === null || !("steps" in dialogue) || !Array.isArray(dialogue.steps)) {
    return false
  }
  return dialogue.steps.every(
    (step: unknown) => typeof step === "object" && step !== null && "pages" in step && Array.isArray(step.pages),
  )
}

function isDraft(value: unknown): value is NpcDraft {
  return typeof value === "object" && value !== null && "npc" in value && "base" in value && hasPages(value.npc)
}

function isDraftRecord(value: unknown): value is Record<string, NpcDraft> {
  return typeof value === "object" && value !== null && (!("draft" in value) || isDraft(value.draft))
}

export function useNpcDraft(id: string): NpcDraft | undefined {
  return useDraft(file(id), isDraftRecord).draft
}

/** The draft as it is now, outside a render. */
export function readNpcDraft(id: string): NpcDraft | undefined {
  const held = draftValue(file(id), "draft")
  return isDraft(held) ? held : undefined
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
