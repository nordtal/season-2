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

/** The settings groups and the texts: reading them, searching them and saving them. */

export function useConfigs(enabled = true) {
  return useQuery({
    queryKey: keys.configs,
    queryFn: () => api<ConfigLocation[]>("/api/setting-groups"),
    ...live("SETTINGS"),
    enabled,
  })
}

/** One group of settings, by the listing's `path` with each segment encoded on its own. */
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

/**
 * Saves a group of settings, naming the revision the form was drawn from.
 *
 * A save against an older revision is answered 409 instead of overwriting.
 */
export function useSaveConfig(file: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ revision, changes }: { revision: string; changes: ConfigChanges }) =>
      api<ConfigDocument>(`/api/setting-groups/${encodePath(file)}`, {
        method: "PUT",
        body: { revision, changes },
      }),
    onSuccess: (document) => {
      /** The answer is the group as stored, so the form redraws from it. */
      client.setQueryData(keys.config(file), document)
      /** A save of steward's own group may move both clocks. */
      void client.invalidateQueries({ queryKey: keys.schedule })
    },
    onError: (failure) => {
      /** A 409 means the cached copy and its revision are stale, so the group is read again. */
      if (failure instanceof ApiError && failure.status === 409) {
        void client.invalidateQueries({ queryKey: keys.config(file) })
      }
    },
  })
}

/** What each jar of ours says of itself: its name, its logo and the custom editor of a group, if any. */
export function useDescriptors(enabled = true) {
  return useQuery({
    queryKey: keys.descriptors,
    queryFn: () => api<PluginDescriptor[]>("/api/descriptors"),
    staleTime: 5 * 60 * SECOND,
    enabled,
  })
}

/** Every text of every bundle the network's jars ship, each once, with the services that show it. */
export function useMessageTexts(enabled = true) {
  return useQuery({
    queryKey: keys.messageTexts,
    queryFn: () => api<MessageTexts>("/api/messages"),
    enabled,
  })
}

/**
 * Saves overrides of any texts of any bundles at once; the second of two edits wins.
 *
 * The answer is every text as it now reads, with placeholder warnings and `reload`, and replaces the cache entry.
 */
export function useSaveMessageTexts() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: MessageChanges) => api<MessageSaveResult>("/api/messages", { method: "PUT", body }),
    onSuccess: (saved) => {
      client.setQueryData(keys.messageTexts, saved.texts)
      /** A save over a fallen-back override takes it over. */
      void client.invalidateQueries({ queryKey: keys.messageFallbacks })
    },
  })
}

/** An example value per placeholder type and property, read from real data by the server. */
export function useMessageExamples() {
  return useQuery({
    queryKey: keys.messageExamples,
    queryFn: () => api<MessageExamples>("/api/message-examples"),
    staleTime: 5 * 60 * SECOND,
  })
}

/** The tones a text names by tag with their default colours, and the styles each value kind offers. */
export function useMessageSyntax() {
  return useQuery({
    queryKey: keys.messageSyntax,
    queryFn: () => api<MessageSyntax>("/api/message-syntax"),
    staleTime: Infinity,
  })
}

/** Every override no process shows, because a release changed its original or the validator refuses it. */
export function useMessageFallbacks(enabled = true) {
  return useQuery({
    queryKey: keys.messageFallbacks,
    queryFn: () => api<MessageFallback[]>("/api/message-fallbacks"),
    ...live("SETTINGS"),
    enabled,
  })
}

/** What the one validator says of `text` as `key` of `bundle`; the editor asks once typing pauses. */
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

/** The resource pack's named glyphs; static files next to the page, so no API call and no session. */
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

/**
 * Every one of the given groups of settings, fetched only while `enabled`.
 *
 * Keys are shared with {@link useConfig}, so a group already loaded costs nothing a second time.
 */
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

/**
 * What the servers know of the game, for the pickers: about a megabyte, so it is read again only when it changes.
 *
 * Only the live topic refreshes it, never a timer or a focus.
 */
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
