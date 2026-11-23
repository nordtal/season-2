import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { api, type BackupDestinations, type GoogleStart, type RecoveryDownload } from "@/lib/api"
import { live } from "@/lib/live"
import { keys } from "@/lib/query-keys"

/** The Google accounts the backups copy to, and how each series copied last. A backup run ends with a copy. */
export function useBackupDestinations(enabled = true) {
  return useQuery({
    queryKey: keys.backupDestinations,
    queryFn: () => api<BackupDestinations>("/api/backup-destinations"),
    ...live("RUNS"),
    enabled,
  })
}

/** Starts a Google consent and returns the address the browser goes to. */
export function useConnectGoogle() {
  return useMutation({
    mutationFn: () => api<GoogleStart>("/api/backup-destinations/google/start", { method: "POST" }),
  })
}

export function useDisconnectBackupAccount() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (accountId: string) =>
      api<void>(`/api/backup-destinations/accounts/${encodeURIComponent(accountId)}/disconnect`, { method: "POST" }),
    onSuccess: () => void client.invalidateQueries({ queryKey: keys.backupDestinations }),
  })
}

/** Names the account a series copies to, or none. */
export function useChooseBackupAccount() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ series, accountId }: { series: string; accountId: string | null }) =>
      api<void>(`/api/backup-destinations/series/${encodeURIComponent(series)}`, {
        method: "PUT",
        body: { accountId },
      }),
    onSuccess: () => void client.invalidateQueries({ queryKey: keys.backupDestinations }),
  })
}

/** Takes the recovery file of one repository, which steward hands out once. */
export function useRecoveryFile() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ accountId, series }: { accountId: string; series: string }) =>
      api<RecoveryDownload>(
        `/api/backup-destinations/accounts/${encodeURIComponent(accountId)}/repositories/${encodeURIComponent(series)}/recovery`,
        { method: "POST" },
      ),
    onSettled: () => void client.invalidateQueries({ queryKey: keys.backupDestinations }),
  })
}
