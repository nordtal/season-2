import type { Run } from "@/lib/api"

/** Where a run's page is: a backup's under Backups, every other kind's under Updates, DOWN and START included. */
export function runPath(run: Pick<Run, "id" | "kind">) {
  return run.kind === "BACKUP"
    ? ({ to: "/operations/backups/$id", params: { id: String(run.id) } } as const)
    : ({ to: "/operations/updates/$id", params: { id: String(run.id) } } as const)
}
