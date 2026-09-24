import type { Run } from "@/lib/api"
import { useActiveRun } from "@/lib/queries"
import { RUN_KIND } from "@/components/steward/status"

/**
 * One run in the whole network at a time: while one is open, a second is refused wherever it is
 * asked for. So the buttons that would ask say so before they are pressed, with the run that is
 * in the way as their title. An unanswered `/api/updates/active` locks nothing - the backend still
 * refuses, and a button greyed out for a slow query would be the worse lie.
 */
export function useRunLock(): { run: Run | null; locked: boolean; title: string | undefined } {
  const active = useActiveRun()
  const run = active.data?.run ?? null
  return { run, locked: run !== null, title: run ? lockTitle(run) : undefined }
}

export function lockTitle(run: Run): string {
  return `${RUN_KIND[run.kind] ?? run.kind} #${run.id} is under way. One run at a time.`
}

/** Whether a run stops or starts this service. An empty scope is the whole network. */
export function touches(run: Run, service: string): boolean {
  return run.scope.length === 0 || run.scope.includes(service)
}
