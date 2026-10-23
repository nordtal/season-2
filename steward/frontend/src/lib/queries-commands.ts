import { QueryObserver, queryOptions, useQuery, type QueryClient } from "@tanstack/react-query"

import { api, type CommandAsked, type CommandRun } from "@/lib/api"
import { RECONCILE, type Topic } from "@/lib/live"
import { keys } from "@/lib/query-keys"
import { message, t } from "@/lib/texts"

/** What became of one request, read again on every change of the requests until it is final. */
function commandRunQuery(id: string) {
  return queryOptions({
    queryKey: keys.commandRun(id),
    queryFn: () => api<CommandRun>(`/api/commands/${id}`),
    meta: { topics: ["REQUESTS"] satisfies Topic[] },
    // A mutation waits on this while its tab may be in the background.
    refetchIntervalInBackground: true,
    refetchInterval: (query) => {
      /** A failed read stops the reconciliation. */
      if (query.state.error) return false
      return isFinal(query.state.data) ? false : RECONCILE
    },
  })
}

function isFinal(run: CommandRun | undefined): run is CommandRun {
  return run !== undefined && run.status !== "PENDING" && run.status !== "RUNNING"
}

export function useCommandRun(id: string | null) {
  return useQuery({ ...commandRunQuery(id ?? ""), enabled: Boolean(id) })
}

/** Follows request `id` through the query `useCommandRun` reads, and answers its run once it is final. */
export function finalRun(client: QueryClient, id: string): Promise<CommandRun> {
  const observer = new QueryObserver(client, commandRunQuery(id))
  return new Promise((resolve, reject) => {
    const stop = observer.subscribe((result) => {
      if (result.error) reject(result.error)
      else if (isFinal(result.data)) resolve(result.data)
      else return
      stop()
    })
  })
}

/** A request that the server refused, failed or never read; its message is the reason in words. */
export class RunNotDone extends Error {
  readonly run: CommandRun

  constructor(run: CommandRun, reason: string) {
    super(reason)
    this.run = run
  }
}

/** The service that reads a request, by the prefix of its id. */
const SERVICE: Record<string, string> = {
  smp: "smp",
  hunger_games: "hunger-games",
  bot: "discord-bot",
  announce: "discord-bot",
}

/** Answers the run of request `id` once it is done, and throws `RunNotDone` once it ends any other way. */
export async function doneRun(client: QueryClient, id: string): Promise<CommandRun> {
  const run = await finalRun(client, id)
  if (run.status === "DONE") return run
  const service = SERVICE[id.split(":")[0]] ?? id
  throw new RunNotDone(
    run,
    run.result
      ? message(run.result)
      : run.status === "FAILED"
        ? t("steward.failure.server-failed", { service })
        : t("steward.failure.server-silent", { service }),
  )
}

/** Posts a request into a server's inbox and answers its run once the server has done it. */
export async function askUntilDone(client: QueryClient, path: string, body: unknown): Promise<CommandRun> {
  const asked = await api<CommandAsked>(path, { method: "POST", body })
  return doneRun(client, asked.id)
}
