import { QueryObserver, queryOptions, useQuery, type QueryClient } from "@tanstack/react-query"

import { api, type CommandRun } from "@/lib/api"
import { RECONCILE, type Topic } from "@/lib/live"
import { keys } from "@/lib/query-keys"

/** What became of one request, read again on every change of the requests until it is final. */
function commandRunQuery(id: string) {
  return queryOptions({
    queryKey: keys.commandRun(id),
    queryFn: () => api<CommandRun>(`/api/commands/${id}`),
    meta: { topics: ["REQUESTS"] satisfies Topic[] },
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
