import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  api,
  ApiError,
  shapedAs,
  isGlyphInfoList,
  type ConfigChanges,
  type ConfigDocument,
  type ConfigLocation,
  type MessageBundle,
  type MessageBundleLocation,
  type MessageChanges,
  type MessageSaveResult,
  type MessageExamples,
  type PluginDescriptor,
} from "@/lib/api"
import { live } from "@/lib/live"
import { SECOND, keys } from "@/lib/query-keys"

/** The settings groups and message bundles: reading them, searching them and saving them. */

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

/** Every message bundle steward found, one per module's `messages/` directory; pages filter it by service. */
export function useMessageBundles(enabled = true) {
  return useQuery({
    queryKey: keys.messageBundles,
    queryFn: () => api<MessageBundleLocation[]>("/api/messages"),
    staleTime: 5 * 60 * SECOND,
    enabled,
  })
}

/** One bundle's packaged text and overrides, in both languages at once. */
export function useMessageBundle(path: string, enabled = true) {
  return useQuery({
    queryKey: keys.messageBundle(path),
    queryFn: () => api<MessageBundle>(`/api/messages/${encodePath(path)}`),
    enabled: enabled && Boolean(path),
  })
}

/**
 * Saves overrides for one language of one bundle; the second of two edits wins.
 *
 * The answer is the bundle as it now reads, with placeholder warnings and `reload`, and replaces the cache entry.
 */
export function useSaveMessageBundle(path: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: MessageChanges) =>
      api<MessageSaveResult>(`/api/messages/${encodePath(path)}`, { method: "PUT", body }),
    onSuccess: (document) => {
      client.setQueryData(keys.messageBundle(path), document)
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
 * Every one of the given message bundles, fetched only while `enabled`, with keys shared with {@link useMessageBundle}.
 */
export function useMessageDocuments(paths: string[], enabled: boolean) {
  return useQueries({
    queries: paths.map((path) => ({
      queryKey: keys.messageBundle(path),
      queryFn: () => api<MessageBundle>(`/api/messages/${encodePath(path)}`),
      staleTime: 5 * 60 * SECOND,
      enabled,
    })),
  })
}
