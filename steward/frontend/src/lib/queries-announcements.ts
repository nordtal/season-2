import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  api,
  type AnnouncementCreated,
  type AnnouncementDraft,
  type AnnouncementList,
  type AnnouncementsAsked,
} from "@/lib/api"
import { live } from "@/lib/live"
import { doneRun } from "@/lib/queries-commands"
import { keys } from "@/lib/query-keys"

/** The drafts and the sent announcements. A bot's answer changes how far each language got, so requests refresh it. */
export function useAnnouncements(enabled = true) {
  return useQuery({
    queryKey: keys.announcements,
    queryFn: () => api<AnnouncementList>("/api/announcements"),
    ...live("REQUESTS"),
    enabled,
  })
}

function useAnnouncementWrite<T, V>(write: (variables: V) => Promise<T>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: write,
    onSettled: () => {
      void client.invalidateQueries({ queryKey: keys.announcements })
    },
  })
}

export function useCreateAnnouncement() {
  return useAnnouncementWrite((draft: AnnouncementDraft) =>
    api<AnnouncementCreated>("/api/announcements", { method: "POST", body: draft }),
  )
}

export function useSaveAnnouncement() {
  return useAnnouncementWrite(({ id, draft }: { id: number; draft: AnnouncementDraft }) =>
    api<void>(`/api/announcements/${id}`, { method: "PUT", body: draft }),
  )
}

export function useDeleteAnnouncement() {
  return useAnnouncementWrite((id: number) => api<void>(`/api/announcements/${id}`, { method: "DELETE" }))
}

/**
 * Sends an announcement, or posts again the languages a send could not post, and waits until the bot answered each.
 * It fails with the reason of the first language the bot did not post.
 */
export function useSendAnnouncement() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (id: number) => {
      const asked = await api<AnnouncementsAsked>(`/api/announcements/${id}/send`, { method: "POST" })
      void client.invalidateQueries({ queryKey: keys.announcements })
      const runs = await Promise.allSettled(Object.values(asked.ids).map((line) => doneRun(client, line)))
      const failed = runs.find((run) => run.status === "rejected")
      if (failed) throw failed.reason
    },
    onSettled: () => {
      void client.invalidateQueries({ queryKey: keys.announcements })
      void client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}
