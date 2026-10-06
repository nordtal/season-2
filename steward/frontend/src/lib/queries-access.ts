import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  api,
  type AccessRequestRun,
  type AdminGranted,
  type AdminRevoked,
  type Exempted,
  type Settled,
  type Grant,
  type JournalEntry,
  type Payment,
  type Person,
} from "@/lib/api"
import { live, nextChange } from "@/lib/live"
import { keys } from "@/lib/query-keys"
import { t } from "@/lib/texts"

/** The roster, its payments, periods and journal, and every change an admin makes to somebody's access. */

/**
 * Asks the bot for an access change and reads its row again on every change of the requests until it has settled.
 *
 * Only the bot can apply the role, send the direct message and post the admin note.
 */
async function askTheBot(path: string, body: unknown): Promise<Record<string, unknown>> {
  const asked = await api<AccessRequestRun>(path, { method: "POST", body })
  for (;;) {
    const change = nextChange("REQUESTS")
    try {
      const row = await api<AccessRequestRun>(`/api/access/requests/${asked.id}`)
      if (row.status === "DONE") return row.result ?? {}
      if (row.status === "FAILED") {
        const said = row.result?.error
        throw new Error(typeof said === "string" ? said : t("steward.failure.bot-failed"))
      }
      if (row.status === "EXPIRED") {
        // EXPIRED means the bot never picked the row up, so nothing changed.
        throw new Error(t("steward.failure.bot-silent"))
      }
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
 * The payment requests still waiting to be paid, for settling one by hand.
 *
 * Not a filter over `usePayments`, which is a page with a limit.
 */
export function useOpenPayments(enabled = true) {
  return useQuery({
    queryKey: keys.openPayments,
    queryFn: () => api<Payment[]>("/api/payments/open"),
    ...live("PEOPLE"),
    enabled,
  })
}

/** One person's requests, from their own route, so a page of everybody's cannot cut theirs off. */
export function usePersonPayments(discordId: string) {
  return useQuery({
    queryKey: keys.personPayments(discordId),
    queryFn: () => api<Payment[]>(`/api/people/${encodeURIComponent(discordId)}/payments`),
    ...live("PEOPLE"),
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

export function useJournal(action: string, subject: string, enabled = true) {
  return useQuery({
    queryKey: keys.journal(action, subject),
    queryFn: () => {
      const query = new URLSearchParams({ limit: "200" })
      if (action) query.set("action", action)
      if (subject) query.set("subject", subject)
      return api<JournalEntry[]>(`/api/journal?${query}`)
    },
    ...live("JOURNAL"),
    enabled,
  })
}

/** Everything an access change can move: the roster, the periods, the payments and the journal. */
function afterAccessChange(client: ReturnType<typeof useQueryClient>) {
  void client.invalidateQueries({ queryKey: keys.people })
  void client.invalidateQueries({ queryKey: ["grants"] })
  void client.invalidateQueries({ queryKey: keys.payments })
  void client.invalidateQueries({ queryKey: ["journal"] })
}

/** A grant, at most 365 days. Resolves with the end of the period the bot wrote. */
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

/** Breaks the link between a Discord account and its Minecraft account. */
export function useUnlink() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (discordId: string) => {
      const result = await askTheBot("/api/access/unlink", { discordId })
      return { unlinked: result.unlinked === "true" }
    },
    onSettled: () => afterAccessChange(client),
  })
}

/** Makes a member of the guild an admin below the one signed in. Decided here, not by the bot. */
export function useGrantAdmin() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (discordId: string) => api<AdminGranted>("/api/admins/grant", { method: "POST", body: { discordId } }),
    onSettled: () => client.invalidateQueries({ queryKey: keys.people }),
  })
}

/** Takes admin from somebody below the one signed in, and from everybody below them. */
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

/** Lets one player through without the resource pack, from their next login on. */
export function useExemptFromPack() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (discordId: string) =>
      api<Exempted>("/api/pack-exemptions/exempt", { method: "POST", body: { discordId } }),
    onSettled: () => client.invalidateQueries({ queryKey: keys.people }),
  })
}

/** Makes the resource pack required for that player again, from their next login on. */
export function useEnforcePack() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (discordId: string) =>
      api<Exempted>("/api/pack-exemptions/enforce", { method: "POST", body: { discordId } }),
    onSettled: () => client.invalidateQueries({ queryKey: keys.people }),
  })
}

/** Books a payment by hand; `outcome` is `BOOKED`, `NOT_OPEN` (then `was` says what it is) or `UNKNOWN`. */
export function useSettle() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (reference: string) => {
      // steward books it in one transaction and answers; the bot is told and only reacts.
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

/** Sets an account's total play time outright, in seconds as the column holds it. */
export function useSetPlaytime() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async ({ discordId, seconds }: { discordId: string; seconds: number }) => {
      const result = await askTheBot(`/api/people/${discordId}/playtime`, { seconds })
      return { discordId, seconds: Number(result.seconds ?? seconds) }
    },
    onSettled: () => afterAccessChange(client),
  })
}
