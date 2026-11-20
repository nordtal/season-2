import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  api,
  type Npc,
  type NpcArtworkUploaded,
  type NpcMenu,
  type NpcSkinUploaded,
  type NpcTextMeasureRequest,
  type NpcTextMeasurement,
  type NpcVersion,
  type NpcView,
} from "@/lib/api"
import { LONGEST_TEXT } from "@/lib/npc"
import { retryUnless404 } from "@/lib/queries-settings"
import { keys } from "@/lib/query-keys"

/** The NPC editor's hooks, one per endpoint. No topic tells of another admin's publication, so a stale draft meets a 409. */

export function useNpcs(enabled = true) {
  return useQuery({ queryKey: keys.npcs, queryFn: () => api<NpcView[]>("/api/npcs"), enabled })
}

/** One NPC as published. A 404 is an NPC that was never published, or one that was deleted. */
export function useNpc(id: string) {
  return useQuery({
    queryKey: keys.npc(id),
    queryFn: () => api<NpcView>(`/api/npcs/${id}`),
    retry: retryUnless404,
  })
}

export function useNpcVersions(id: string, enabled: boolean) {
  return useQuery({
    queryKey: keys.npcVersions(id),
    queryFn: () => api<NpcVersion[]>(`/api/npcs/${id}/versions`),
    enabled,
  })
}

/** The menus a step may open, which smp registers when it starts. */
export function useNpcMenus() {
  return useQuery({ queryKey: keys.npcMenus, queryFn: () => api<NpcMenu[]>("/api/npc-menus") })
}

/** The most texts one measurement takes, as the route refuses more. */
const MEASURED_AT_ONCE = 64

/** How one text is looked up among the measurements: by its place on the panel and the text itself. */
export function measureKey(request: NpcTextMeasureRequest): string {
  return `${request.kind}:${request.answers}:${request.text.slice(0, LONGEST_TEXT)}`
}

/**
 * Measures texts as the panel wraps them, each text once, and answers them by `measureKey`. While a changed text is
 * measured the answer of the texts before it stays.
 */
export function useNpcMeasurements(requests: NpcTextMeasureRequest[]) {
  // A text longer than the route takes is measured by its start, which is already far more than the panel shows.
  const unique = [
    ...new Map(
      requests.map((request) => [measureKey(request), { ...request, text: request.text.slice(0, LONGEST_TEXT) }]),
    ).values(),
  ]
  return useQuery({
    queryKey: keys.npcMeasure(JSON.stringify(unique)),
    queryFn: async () => {
      const measured = new Map<string, NpcTextMeasurement>()
      for (let at = 0; at < unique.length; at += MEASURED_AT_ONCE) {
        const part = unique.slice(at, at + MEASURED_AT_ONCE)
        const answers = await api<NpcTextMeasurement[]>("/api/npc-text/measure", { method: "POST", body: part })
        answers.forEach((answer, index) => measured.set(measureKey(part[index]), answer))
      }
      return measured
    },
    placeholderData: keepPreviousData,
    staleTime: Infinity,
    enabled: unique.length > 0,
  })
}

/** Publishes a draft against the version its editor read. The answer is the NPC as the game now has it. */
export function usePublishNpc() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ npc, version }: { npc: Npc; version: number }) =>
      api<NpcView>(`/api/npcs/${npc.id}`, { method: "PUT", body: { npc, version } }),
    onSuccess: async (view) => {
      // A read that was on its way holds the version before this one.
      await client.cancelQueries({ queryKey: keys.npc(view.npc.id) })
      client.setQueryData(keys.npc(view.npc.id), view)
      void client.invalidateQueries({ queryKey: keys.npcs, exact: true })
      void client.invalidateQueries({ queryKey: keys.npcVersions(view.npc.id) })
      void client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

export function useRemoveNpc() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/npcs/${id}`, { method: "DELETE" }),
    onSuccess: (_, id) => {
      client.removeQueries({ queryKey: keys.npc(id) })
      void client.invalidateQueries({ queryKey: keys.npcs, exact: true })
      void client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

/** Stores a skin's PNG with the arms it was drawn for. */
export function useUploadSkin() {
  return useMutation({
    mutationFn: ({ file, slim }: { file: Blob; slim: boolean }) =>
      api<NpcSkinUploaded>(`/api/npc-skins?model=${slim ? "SLIM" : "CLASSIC"}`, { method: "POST", body: file }),
  })
}

/** Stores a square PNG as a portrait picture, which an NPC then names. */
export function useUploadArtwork() {
  return useMutation({
    mutationFn: (file: Blob) => api<NpcArtworkUploaded>("/api/npc-artworks", { method: "POST", body: file }),
  })
}

/** Where the browser loads a portrait picture from. */
export function artworkAddress(id: string): string {
  return `/api/npc-artworks/${encodeURIComponent(id)}`
}

/** Copies the skin a Minecraft account wears now, by its name or UUID. */
export function useCopySkin() {
  return useMutation({
    mutationFn: (player: string) => api<NpcSkinUploaded>("/api/npc-skins/copy", { method: "POST", body: { player } }),
  })
}
