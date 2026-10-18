import type { Run } from "@/lib/api"
import { useActiveRun } from "@/lib/queries"
import { runKind } from "@/components/steward/status"
import { t } from "@/lib/texts"

/**
 * One run in the network at a time. While one is open, the buttons that would start another are locked and their title names it. An unanswered `/api/updates/active` locks nothing, and the backend still refuses.
 */
export function useRunLock(): { run: Run | null; locked: boolean; title: string | undefined } {
  const active = useActiveRun()
  const run = active.data?.run ?? null
  return { run, locked: run !== null, title: run ? lockTitle(run) : undefined }
}

export function lockTitle(run: Run): string {
  return t("steward.operations.under-way", { kind: runKind(run.kind), run: run.id })
}

/** An empty scope is the whole network. */
export function touches(run: Run, service: string): boolean {
  return run.scope.length === 0 || run.scope.includes(service)
}
