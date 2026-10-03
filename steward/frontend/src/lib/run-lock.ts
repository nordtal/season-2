import type { Run } from "@/lib/api"
import { useActiveRun } from "@/lib/queries"
import { runKind } from "@/components/steward/status"
import { t } from "@/lib/texts"

/**
 * One run in the network at a time: while one is open, buttons that would start another are locked.
 *
 * Their title names the open run. An unanswered `/api/updates/active` locks nothing; the backend still refuses.
 */
export function useRunLock(): { run: Run | null; locked: boolean; title: string | undefined } {
  const active = useActiveRun()
  const run = active.data?.run ?? null
  return { run, locked: run !== null, title: run ? lockTitle(run) : undefined }
}

export function lockTitle(run: Run): string {
  return t("steward.operations.under-way", { kind: runKind(run.kind), run: run.id })
}

/** Whether a run stops or starts this service. An empty scope is the whole network. */
export function touches(run: Run, service: string): boolean {
  return run.scope.length === 0 || run.scope.includes(service)
}
