import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query"

import {
  api,
  type AdminGranted,
  type AdminRevoked,
  type CommandAsked,
  type CommandRun,
  type Exempted,
  type Settled,
  type Grant,
  type JournalPage,
  type Payment,
  type Person,
  type PlayerInventories,
  type StackRemoval,
} from "@/lib/api"
import { live } from "@/lib/live"
import { finalRun } from "@/lib/queries-commands"
import { keys } from "@/lib/query-keys"
import { message, t } from "@/lib/texts"

/**
 * Posts a request into a server's inbox and answers its run once it is final. The server that carries it out is the
 * bot or the SMP, which `failed` and `silent` name when it fails or never picks the request up.
 */
async function untilFinal(
  client: QueryClient,
  path: string,
  body: unknown,
  failed: "steward.failure.bot-failed" | "steward.failure.smp-failed",
  silent: "steward.failure.bot-silent" | "steward.failure.smp-silent",
): Promise<CommandRun> {
  const asked = await api<CommandAsked>(path, { method: "POST", body })
  const row = await finalRun(client, asked.id)
  if (row.status === "DONE") return row
  if (row.status === "EXPIRED") throw new Error(t(silent))
  throw new Error(row.result ? message(row.result) : t(failed))
}

/**
 * Asks the bot for an access change and answers its typed answer. Only the bot can apply the role, send the direct
 * message and post the admin note.
 */
async function askTheBot(client: QueryClient, path: string, body: unknown): Promise<Record<string, unknown>> {
  const run = await untilFinal(client, path, body, "steward.failure.bot-failed", "steward.failure.bot-silent")
  return run.answer ?? {}
}

/** Asks the SMP for a change of one person. */
async function askTheSmp(client: QueryClient, path: string, body: unknown): Promise<void> {
  await untilFinal(client, path, body, "steward.failure.smp-failed", "steward.failure.smp-silent")
}

export function usePeople(enabled = true) {
  return useQuery({
    queryKey: keys.people,
    queryFn: () => api<Person[]>("/api/people"),
    ...live("PEOPLE"),
    enabled,
  })
}

export function usePayments(enabled = true) {
  return useQuery({
    queryKey: keys.payments,
    queryFn: () => api<Payment[]>("/api/payments"),
    ...live("PEOPLE"),
    enabled,
  })
}

/**
 * Returns the payment requests that still wait to be paid. It is not a filter over `usePayments`, which is a page with a limit.
 */
export function useOpenPayments(enabled = true) {
  return useQuery({
    queryKey: keys.openPayments,
    queryFn: () => api<Payment[]>("/api/payments/open"),
    ...live("PEOPLE"),
    enabled,
  })
}

/**
 * Returns one person's requests from their own route, so a page of everybody's cannot cut them off.
 */
export function usePersonPayments(discordId: string) {
  return useQuery({
    queryKey: keys.personPayments(discordId),
    queryFn: () => api<Payment[]>(`/api/people/${encodeURIComponent(discordId)}/payments`),
    ...live("PEOPLE"),
  })
}

/** Each read asks the server that holds the player for a fresh copy, so it does not follow window focus. */
export function useInventories(discordId: string) {
  return useQuery({
    queryKey: keys.inventories(discordId),
    queryFn: () => api<PlayerInventories>(`/api/people/${encodeURIComponent(discordId)}/inventories`),
    refetchOnWindowFocus: false,
  })
}

export function useRemoveStack(discordId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (removal: StackRemoval) =>
      api<void>(`/api/people/${encodeURIComponent(discordId)}/inventories/remove`, { method: "POST", body: removal }),
    onSettled: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: keys.inventories(discordId) }),
        client.invalidateQueries({ queryKey: ["journal"] }),
      ]),
  })
}

export function useGrants(discordId: string | null) {
  return useQuery({
    queryKey: keys.grants(discordId ?? ""),
    queryFn: () => api<Grant[]>(`/api/people/${encodeURIComponent(discordId ?? "")}/grants`),
    ...live("PEOPLE"),
    enabled: Boolean(discordId),
  })
}

/** The journal's stream under the query `q`, one page of `limit` items at a time, newest first. */
export function useJournal(q: string, limit = 50, enabled = true) {
  return useInfiniteQuery({
    queryKey: keys.journal(q, limit),
    queryFn: ({ pageParam }) => {
      const query = new URLSearchParams({ limit: String(limit) })
      if (q.trim() !== "") query.set("q", q.trim())
      if (pageParam) query.set("after", pageParam)
      return api<JournalPage>(`/api/journal?${query}`)
    },
    initialPageParam: "",
    getNextPageParam: (page) => page.next,
    ...live("JOURNAL", "RUNS"),
    enabled,
  })
}

function afterAccessChange(client: ReturnType<typeof useQueryClient>) {
  void client.invalidateQueries({ queryKey: keys.people })
  void client.invalidateQueries({ queryKey: ["grants"] })
  void client.invalidateQueries({ queryKey: keys.payments })
  void client.invalidateQueries({ queryKey: ["journal"] })
}

export function useGrantAccess() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (grant: { discordId: string; days: number }) => {
      const result = await askTheBot(client, "/api/access/grant", grant)
      return { until: typeof result.until === "string" ? result.until : "" }
    },
    onSettled: () => afterAccessChange(client),
  })
}

export function useRevokeAccess() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (discordId: string) => {
      const result = await askTheBot(client, "/api/access/revoke", { discordId })
      return { revoked: Number(result.revoked ?? 0) }
    },
    onSettled: () => afterAccessChange(client),
  })
}

export function useUnlink() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (discordId: string) => {
      const result = await askTheBot(client, "/api/access/unlink", { discordId })
      return { unlinked: result.unlinked === true }
    },
    onSettled: () => afterAccessChange(client),
  })
}

/**
 * Makes a member of the guild an admin below the one who is signed in. Steward decides this, not the bot.
 */
export function useGrantAdmin() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (discordId: string) => api<AdminGranted>("/api/admins/grant", { method: "POST", body: { discordId } }),
    onSettled: () => client.invalidateQueries({ queryKey: keys.people }),
  })
}

export function useRevokeAdmin() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (discordId: string) =>
      api<AdminRevoked>("/api/admins/revoke", {
        method: "POST",
        body: { discordId },
      }),
    onSettled: () => client.invalidateQueries({ queryKey: keys.people }),
  })
}

export function useExemptFromPack() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (discordId: string) =>
      api<Exempted>("/api/pack-exemptions/exempt", { method: "POST", body: { discordId } }),
    onSettled: () => client.invalidateQueries({ queryKey: keys.people }),
  })
}

export function useEnforcePack() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (discordId: string) =>
      api<Exempted>("/api/pack-exemptions/enforce", { method: "POST", body: { discordId } }),
    onSettled: () => client.invalidateQueries({ queryKey: keys.people }),
  })
}

/**
 * Books a payment by hand. `outcome` is `BOOKED`, `NOT_OPEN` (`was` says the state) or `UNKNOWN`.
 */
export function useSettle() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (reference: string) => {
      // Steward books the payment in one transaction. The bot is told and only reacts.
      const result = await api<Settled>("/api/access/settle", { method: "POST", body: { reference } })
      return {
        outcome: result.outcome ?? "UNKNOWN",
        days: result.days ?? 0,
        until: result.until,
        was: result.was,
      }
    },
    onSettled: () => afterAccessChange(client),
  })
}

export function useSetAura() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ discordId, aura }: { discordId: string; aura: number }) =>
      askTheSmp(client, `/api/people/${discordId}/aura`, { aura }),
    onSettled: () => afterAccessChange(client),
  })
}

export function useSetSpins() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ discordId, spins }: { discordId: string; spins: number }) =>
      askTheSmp(client, `/api/people/${discordId}/spins`, { spins }),
    onSettled: () => afterAccessChange(client),
  })
}

export function useSetPlaytime() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async ({ discordId, seconds }: { discordId: string; seconds: number }) => {
      await askTheBot(client, `/api/people/${discordId}/playtime`, { seconds })
      return { discordId, seconds }
    },
    onSettled: () => afterAccessChange(client),
  })
}
