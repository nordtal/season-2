import type { Run } from "@/lib/api"

/**
 * Returns where a run's page is: a backup's page is under Backups, and every other kind's page is under Updates, DOWN and START included.
 */
export function runPath(run: Pick<Run, "id" | "kind">) {
  return run.kind === "BACKUP"
    ? ({ to: "/operations/backups/$id", params: { id: String(run.id) } } as const)
    : ({ to: "/operations/updates/$id", params: { id: String(run.id) } } as const)
}
