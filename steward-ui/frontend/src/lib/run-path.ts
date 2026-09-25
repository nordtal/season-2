import type { Run } from "@/lib/api"

/**
 * Where a run's own page is: a backup's under Backups, every other kind's under Updates.
 *
 * DOWN and START have no list of their own any more, but each such run still has its page - the
 * report is the same shape whatever the kind.
 */
export function runPath(run: Pick<Run, "id" | "kind">) {
  return run.kind === "BACKUP"
    ? ({ to: "/operations/backups/$id", params: { id: String(run.id) } } as const)
    : ({ to: "/operations/updates/$id", params: { id: String(run.id) } } as const)
}
