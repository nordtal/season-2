import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  api,
  type AccessRequestRun,
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
import { live, nextChange } from "@/lib/live"
import { keys } from "@/lib/query-keys"
import { message, t } from "@/lib/texts"

/**
 * Asks the bot for an access change and reads its row again until the request is final. Only the bot can apply the role, send the direct message and post the admin note.
 */
async function askTheBot(path: string, body: unknown): Promise<Record<string, unknown>> {
  const asked = await api<AccessRequestRun>(path, { method: "POST", body })
  for (;;) {
    const change = nextChange("REQUESTS")
    try {
      const row = await api<AccessRequestRun>(`/api/access/requests/${asked.id}`)
      if (row.status === "DONE") return row.result ?? {}
      if (row.status === "FAILED") throw new Error(t("steward.failure.bot-failed"))
      if (row.status === "EXPIRED") {
        // EXPIRED means that the bot never picked the row up.
        throw new Error(t("steward.failure.bot-silent"))
      }
      await change.arrived
    } finally {
      change.stop()
    }
  }
}

/** Asks the SMP for a change of one person and reads its row again until the request is final. */
async function askTheSmp(path: string, body: unknown): Promise<void> {
  const asked = await api<CommandAsked>(path, { method: "POST", body })
  for (;;) {
    const change = nextChange("REQUESTS")
    try {
      const row = await api<CommandRun>(`/api/commands/${asked.id}`)
      if (row.status === "DONE") return
      if (row.status === "REFUSED" || row.status === "FAILED" || row.status === "CANCELLED") {
        throw new Error(row.result ? message(row.result) : t("steward.failure.smp-failed"))
      }
      // EXPIRED means that the SMP never picked the row up.
      if (row.status === "EXPIRED") throw new Error(t("steward.failure.smp-silent"))
      await change.arrived
    } finally {
      change.stop()
    }
  }
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
      const result = await askTheBot("/api/access/grant", grant)
      return { until: typeof result.until === "string" ? result.until : "" }
    },
    onSettled: () => afterAccessChange(client),
  })
}

export function useRevokeAccess() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (discordId: string) => {
      const result = await askTheBot("/api/access/revoke", { discordId })
      return { revoked: Number(result.revoked ?? 0) }
    },
    onSettled: () => afterAccessChange(client),
  })
}

export function useUnlink() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (discordId: string) => {
      const result = await askTheBot("/api/access/unlink", { discordId })
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
      askTheSmp(`/api/people/${discordId}/aura`, { aura }),
    onSettled: () => afterAccessChange(client),
  })
}

export function useSetSpins() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ discordId, spins }: { discordId: string; spins: number }) =>
      askTheSmp(`/api/people/${discordId}/spins`, { spins }),
    onSettled: () => afterAccessChange(client),
  })
}

export function useSetPlaytime() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async ({ discordId, seconds }: { discordId: string; seconds: number }) => {
      await askTheBot(`/api/people/${discordId}/playtime`, { seconds })
      return { discordId, seconds }
    },
    onSettled: () => afterAccessChange(client),
  })
}
