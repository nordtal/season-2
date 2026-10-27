import { keepPreviousData, useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  api,
  ApiError,
  shapedAs,
  isGlyphInfoList,
  type ConfigChanges,
  type ConfigDocument,
  type ConfigSaved,
  type ConfigLocation,
  type Document,
  type DocumentDeclaration,
  type DocumentDrawing,
  type SharedPartChange,
  type DocumentList,
  type DocumentPart,
  type DocumentVersion,
  type Uploaded,
  type MessageChanges,
  type MessageSaveResult,
  type MessageTexts,
  type MessageExamples,
  type MessageSyntax,
  type MessageFallback,
  type MessageProblem,
  type TranslationHit,
  type PluginDescriptor,
  type PluginFiles,
  type PluginFileSaved,
  type PluginFileView,
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
      api<ConfigSaved>(`/api/setting-groups/${encodePath(file)}`, {
        method: "PUT",
        body: { revision, changes },
      }),
    onSuccess: (saved) => {
      client.setQueryData(keys.config(file), saved.document)
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

function pluginFilesPath(service: string): string {
  return `/api/services/${encodeURIComponent(service)}/plugin-files`
}

function pluginFilePath(service: string, plugin: string, path: string): string {
  const query = new URLSearchParams({ plugin, path })
  return `/api/services/${encodeURIComponent(service)}/plugin-file?${query}`
}

/** A 404 means no plugins folder. Every other failure gets one retry. */
export function retryUnless404(count: number, error: Error): boolean {
  return !(error instanceof ApiError && error.status === 404) && count < 1
}

export function usePluginFiles(service: string, enabled = true) {
  return useQuery({
    queryKey: keys.pluginFiles(service),
    queryFn: () => api<PluginFiles>(pluginFilesPath(service)),
    ...live("RUNS"),
    retry: retryUnless404,
    enabled,
  })
}

export function usePluginFilesOf(services: string[], enabled: boolean) {
  return useQueries({
    queries: services.map((service) => ({
      queryKey: keys.pluginFiles(service),
      queryFn: () => api<PluginFiles>(pluginFilesPath(service)),
      staleTime: 5 * 60 * SECOND,
      retry: retryUnless404,
      enabled,
    })),
  })
}

export function usePluginFile(service: string, plugin: string, path: string) {
  return useQuery({
    queryKey: keys.pluginFile(service, plugin, path),
    queryFn: () => api<PluginFileView>(pluginFilePath(service, plugin, path)),
  })
}

/** The whole text or the form's changes. A save against an older revision is answered with 409 and does not overwrite. */
export function useSavePluginFile(service: string, plugin: string, path: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (edit: { revision: string; changes?: ConfigChanges; text?: string }) =>
      api<PluginFileSaved>(pluginFilePath(service, plugin, path), { method: "PUT", body: edit }),
    onSuccess: () => void client.invalidateQueries({ queryKey: keys.pluginFiles(service) }),
    onError: (failure) => {
      if (failure instanceof ApiError && failure.status === 409) {
        void client.invalidateQueries({ queryKey: keys.pluginFile(service, plugin, path) })
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

/** Every text and document the server finds for `query`, the best first. While the next answer is read, the last stays. */
export function useTranslationSearch(query: string, enabled = true) {
  const trimmed = query.trim()
  return useQuery({
    queryKey: keys.translationSearch(trimmed),
    queryFn: () => api<TranslationHit[]>(`/api/translation-search?query=${encodeURIComponent(trimmed)}`),
    enabled: enabled && trimmed.length > 0,
    placeholderData: keepPreviousData,
  })
}

export function useDocuments(enabled = true) {
  return useQuery({
    queryKey: keys.documents,
    queryFn: () => api<DocumentList>("/api/documents"),
    enabled,
  })
}

/** Draws a draft with the bot's renderer. While the next draft is drawn, the last drawing stays. */
export function useDocumentDrawing(declaration: DocumentDeclaration, document: Document, language: string) {
  const draft = JSON.stringify(document)
  return useQuery({
    queryKey: keys.documentDrawing(`${declaration.namespace}/${declaration.name}`, language, draft),
    queryFn: () =>
      api<DocumentDrawing>("/api/documents/render", { method: "POST", body: { declaration, document, language } }),
    placeholderData: keepPreviousData,
    staleTime: Infinity,
  })
}

/** Every save of a document, the newest first. */
export function useDocumentVersions(declaration: DocumentDeclaration, enabled: boolean) {
  const path = documentPath(declaration)
  return useQuery({
    queryKey: keys.documentVersions(path),
    queryFn: () => api<DocumentVersion[]>(`/api/documents/${path}/versions`),
    enabled,
  })
}

/**
 * Saves an admin's document over the packaged one, or with `null` makes the packaged one live again. The answer replaces
 * the list, the save is a new version, and the bot edits its messages.
 */
export function useSaveDocument() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ declaration, document }: { declaration: DocumentDeclaration; document: Document | null }) =>
      api<DocumentList>(
        `/api/documents/${documentPath(declaration)}`,
        document ? { method: "PUT", body: document } : { method: "DELETE" },
      ),
    onSuccess: (list, { declaration }) => {
      client.setQueryData(keys.documents, list)
      void client.invalidateQueries({ queryKey: keys.documentVersions(documentPath(declaration)) })
    },
  })
}

/** Each admin's document that names the shared part, drawn before and after `part` is saved as it. */
export function useSharedPartChanges(name: string, part: DocumentPart, enabled: boolean) {
  const body = JSON.stringify(part)
  return useQuery({
    queryKey: keys.sharedPartChanges(name, body),
    queryFn: () =>
      api<SharedPartChange[]>(`/api/shared-parts/${encodeURIComponent(name)}/changes`, { method: "POST", body: part }),
    enabled,
  })
}

/**
 * Saves `part` as the shared part, which every document that names it draws from then on, or with `null` removes it.
 * The answer replaces the list.
 */
export function useSaveSharedPart() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ name, part }: { name: string; part: DocumentPart | null }) =>
      api<DocumentList>(
        `/api/shared-parts/${encodeURIComponent(name)}`,
        part ? { method: "PUT", body: part } : { method: "DELETE" },
      ),
    onSuccess: (list) => {
      client.setQueryData(keys.documents, list)
      void client.invalidateQueries({ queryKey: ["shared-part-changes"] })
    },
  })
}

function documentPath(declaration: DocumentDeclaration): string {
  return `${encodeURIComponent(declaration.namespace)}/${encodeURIComponent(declaration.name)}`
}

/** Stores an image and answers its `upload://` address. */
export function useUploadImage() {
  return useMutation({
    mutationFn: (file: File) => api<Uploaded>("/api/document-images", { method: "POST", body: file }),
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
