import { keepPreviousData, useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  api,
  ApiError,
  shapedAs,
  isGlyphInfoList,
  type ConfigChanges,
  type ConfigDocument,
  type ConfigLocation,
  type MessageChanges,
  type MessageSaveResult,
  type MessageTexts,
  type MessageExamples,
  type MessageSyntax,
  type MessageFallback,
  type MessageProblem,
  type PluginDescriptor,
  type GameData,
} from "@/lib/api"
import { live } from "@/lib/live"
import { SECOND, keys } from "@/lib/query-keys"

export function useConfigs(enabled = true) {
  return useQuery({
    queryKey: keys.configs,
    queryFn: () => api<ConfigLocation[]>("/api/setting-groups"),
    ...live("SETTINGS"),
    enabled,
  })
}

export function useConfig(file: string, enabled = true) {
  return useQuery({
    queryKey: keys.config(file),
    queryFn: () => api<ConfigDocument>(`/api/setting-groups/${encodePath(file)}`),
    ...live("SETTINGS"),
    enabled: enabled && Boolean(file),
  })
}

function encodePath(file: string): string {
  return file.split("/").map(encodeURIComponent).join("/")
}

/** A save against an older revision is answered with 409 and does not overwrite. */
export function useSaveConfig(file: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ revision, changes }: { revision: string; changes: ConfigChanges }) =>
      api<ConfigDocument>(`/api/setting-groups/${encodePath(file)}`, {
        method: "PUT",
        body: { revision, changes },
      }),
    onSuccess: (document) => {
      client.setQueryData(keys.config(file), document)
      void client.invalidateQueries({ queryKey: keys.schedule })
    },
    onError: (failure) => {
      /** A 409 means that the cached copy is stale, so the group is read again. */
      if (failure instanceof ApiError && failure.status === 409) {
        void client.invalidateQueries({ queryKey: keys.config(file) })
      }
    },
  })
}

export function useDescriptors(enabled = true) {
  return useQuery({
    queryKey: keys.descriptors,
    queryFn: () => api<PluginDescriptor[]>("/api/descriptors"),
    staleTime: 5 * 60 * SECOND,
    enabled,
  })
}

export function useMessageTexts(enabled = true) {
  return useQuery({
    queryKey: keys.messageTexts,
    queryFn: () => api<MessageTexts>("/api/messages"),
    enabled,
  })
}

/** Saves overrides of any texts of any bundles at once. The answer replaces the cache entry. */
export function useSaveMessageTexts() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: MessageChanges) => api<MessageSaveResult>("/api/messages", { method: "PUT", body }),
    onSuccess: (saved) => {
      client.setQueryData(keys.messageTexts, saved.texts)
      void client.invalidateQueries({ queryKey: keys.messageFallbacks })
    },
  })
}

export function useMessageExamples() {
  return useQuery({
    queryKey: keys.messageExamples,
    queryFn: () => api<MessageExamples>("/api/message-examples"),
    staleTime: 5 * 60 * SECOND,
  })
}

export function useMessageSyntax() {
  return useQuery({
    queryKey: keys.messageSyntax,
    queryFn: () => api<MessageSyntax>("/api/message-syntax"),
    staleTime: Infinity,
  })
}

export function useMessageFallbacks(enabled = true) {
  return useQuery({
    queryKey: keys.messageFallbacks,
    queryFn: () => api<MessageFallback[]>("/api/message-fallbacks"),
    ...live("SETTINGS"),
    enabled,
  })
}

export function useMessageCheck(bundle: string, key: string, text: string | null) {
  return useQuery({
    queryKey: keys.messageCheck(bundle, key, text ?? ""),
    queryFn: () => {
      const query = new URLSearchParams({ bundle, key, text: text ?? "" })
      return api<MessageProblem[]>(`/api/message-check?${query}`)
    },
    enabled: text !== null,
    staleTime: Infinity,
    placeholderData: keepPreviousData,
  })
}

/** The resource pack's named glyphs, static files with no API call and no session. */
export function useGlyphs() {
  return useQuery({
    queryKey: keys.glyphs,
    queryFn: async () => {
      const response = await fetch("/glyphs/manifest.json")
      if (!response.ok) throw new Error(`${response.status} ${response.statusText}`)
      return shapedAs(await response.json(), isGlyphInfoList, "/glyphs/manifest.json")
    },
    staleTime: Infinity,
  })
}

/** The keys are shared with {@link useConfig}, so a loaded group costs nothing again. */
export function useConfigDocuments(files: string[], enabled: boolean) {
  return useQueries({
    queries: files.map((file) => ({
      queryKey: keys.config(file),
      queryFn: () => api<ConfigDocument>(`/api/setting-groups/${encodePath(file)}`),
      staleTime: 5 * 60 * SECOND,
      enabled,
    })),
  })
}

/** It is about a megabyte, so only the live topic refreshes it, never a timer or a focus event. */
export function useGameData(enabled = true) {
  return useQuery({
    queryKey: keys.gameData,
    queryFn: () => api<GameData>("/api/game-data"),
    meta: { topics: ["GAME_DATA"] },
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    enabled,
  })
}
