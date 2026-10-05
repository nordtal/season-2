import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import type { UseQueryOptions } from "@tanstack/react-query"

import {
  api,
  ApiError,
  rememberCsrf,
  type Action,
  type Backup,
  type CommandRun,
  type SmpTrack,
  type HungerGamesRound,
  type KeyRegistered,
  type KeyRenamed,
  type KeyRemoved,
  type CommandAsked,
  type AnnouncementsAsked,
  type ConsoleSent,
  type ConsoleTree,
  type PluginAdded,
  type RestoreAsked,
  type RemovalAsked,
  type AgentState,
  type GuildList,
  type Host,
  type Me,
  type Metrics,
  type PluginSearch,
  type ServicePlugins,
  type Available,
  type Run,
  type ActiveRun,
  type Schedule,
  type Season,
  type PhaseChange,
  type DateChange,
  type Service,
  type ServiceTable,
  type NetworkMap,
} from "@/lib/api"
import { browserHasSecurityKeys, createSecurityKey, whyTheKeyFailed } from "@/lib/webauthn"
import { holdTheKey } from "@/lib/hold-key"
import { live, RECONCILE, type Topic } from "@/lib/live"
import type { CreationOptionsJson } from "@/lib/webauthn"
import { SECOND, keys } from "@/lib/query-keys"
import { t } from "@/lib/texts"

/**
 * One hook per endpoint, with the live topics it follows decided here rather than at the call site.
 *
 * A live query is read again when steward's stream announces one of its topics, and once a minute anyway.
 */

/**
 * Who is signed in, and the CSRF token that every write needs.
 *
 * Kept for an hour; the token goes where `api()` can read it synchronously.
 */
export function useMe() {
  return useQuery({
    queryKey: keys.me,
    queryFn: async () => {
      const me = await api<Me>("/api/me")
      rememberCsrf(me.csrf)
      return me
    },
    staleTime: 60 * 60 * SECOND,
    retry: false,
  })
}

/**
 * Registers a security key: two round trips with the browser's dialog between them.
 *
 * One mutation, since a challenge is single-use and a half-done ceremony must not outlive a re-render.
 */
export function useRegisterKey() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (label: string) => {
      if (!browserHasSecurityKeys()) {
        throw new Error(t("steward.keys.browser-cannot"))
      }
      const started = await startRegistration()
      let credential: string
      try {
        credential = await createSecurityKey(started)
      } catch (refused) {
        /** Rethrown as a plain Error, so the form prints one sentence rather than "NotAllowedError". */
        throw new Error(whyTheKeyFailed(refused), { cause: refused })
      }
      const registered = await api<KeyRegistered>("/auth/webauthn/register/finish", {
        method: "POST",
        body: { label, credential },
      })
      await client.invalidateQueries({ queryKey: keys.me })
      return registered
    },
  })
}

/** Asks for a registration challenge once. */
const askForRegistration = () => api<CreationOptionsJson>("/auth/webauthn/register/start", { method: "POST" })

/** The registration challenge; a further key needs the key held recently, so a refusal holds it once and asks again. */
async function startRegistration(): Promise<CreationOptionsJson> {
  try {
    return await askForRegistration()
  } catch (refused) {
    if (!(refused instanceof ApiError) || !refused.needsTheKeyAgain) {
      throw refused
    }
    await holdTheKey()
    return await askForRegistration()
  }
}

/** Holds the key, then refetches `/api/me`, whose `verifiedAt` the shell and the step-up dialog read. */
export function useHoldKey() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const held = await holdTheKey()
      await client.invalidateQueries({ queryKey: keys.me })
      return held
    },
  })
}

/** Renames one registered key. The list lives in `/api/me`, so that is what is invalidated. */
export function useRenameKey() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, label }: { id: string; label: string }) => {
      const renamed = await api<KeyRenamed>(`/api/keys/${encodeURIComponent(id)}`, {
        method: "PUT",
        body: { label },
      })
      await client.invalidateQueries({ queryKey: keys.me })
      return renamed
    },
  })
}

/** Removes one registered key. Removing the last one is allowed; the server says why. */
export function useRemoveKey() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) => {
      const removed = await api<KeyRemoved>(`/api/keys/${encodeURIComponent(id)}`, {
        method: "DELETE",
      })
      await client.invalidateQueries({ queryKey: keys.me })
      return removed
    },
  })
}

/** The services the network page draws and the sidebar lists, as compose.yml's labels place and wire them. */
export function useTopology(enabled = true) {
  return useQuery({
    queryKey: keys.topology,
    queryFn: () => api<NetworkMap>("/api/topology"),
    ...live("TOPOLOGY"),
    enabled,
  })
}

export function useServices(enabled = true) {
  return useQuery({
    queryKey: keys.services,
    queryFn: () => api<ServiceTable>("/api/services"),
    ...live("SERVICES"),
    enabled,
  })
}

export function useService(name: string, enabled = true) {
  return useQuery({
    queryKey: keys.service(name),
    queryFn: () => api<Service>(`/api/services/${encodeURIComponent(name)}`),
    ...live("SERVICES"),
    enabled,
  })
}

export function useHost(enabled = true) {
  return useQuery({
    queryKey: keys.host,
    queryFn: () => api<Host>("/api/host"),
    ...live("HOST"),
    enabled,
  })
}

/** Steward's nightly clock, which moves when its settings are saved. */
export function useSchedule(enabled = true) {
  return useQuery({
    queryKey: keys.schedule,
    queryFn: () => api<Schedule>("/api/schedule"),
    ...live("SETTINGS"),
    enabled,
  })
}

export function useBackups(enabled = true) {
  return useQuery({
    queryKey: keys.backups,
    queryFn: () => api<Backup[]>("/api/backups"),
    /** An archive is written by a run, so a finished run is when the list moves. */
    ...live("RUNS"),
    enabled,
  })
}

/**
 * What a run would do, without a run.
 *
 * No refetch interval: steward caches the answer for six hours, so polling would not make it any fresher.
 */
export function useAvailable(enabled = true) {
  return useQuery({
    queryKey: keys.available,
    queryFn: () => api<Available>("/api/updates/available"),
    staleTime: 5 * 60 * SECOND,
    enabled,
  })
}

/**
 * Asks every source again, now, and writes the answer straight into the cache.
 *
 * A mutation, so the button's busy state belongs to this press. It can take up to two minutes.
 */
export function useRefreshAvailable() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<Available>("/api/updates/available?refresh"),
    onSuccess: (fresh) => client.setQueryData(keys.available, fresh),
  })
}

export function useRuns(limit = 20, enabled = true) {
  return useQuery({
    queryKey: keys.runs(limit),
    queryFn: () => api<Run[]>(`/api/updates?limit=${limit}`),
    ...live("RUNS"),
    enabled,
  })
}

/** The one open run, or none, which locks Update, Take down and Recreate everywhere. */
export function useActiveRun(enabled = true) {
  return useQuery({
    queryKey: keys.activeRun,
    queryFn: () => api<ActiveRun>("/api/updates/active"),
    ...live("RUNS"),
    enabled,
  })
}

/** One run, read again on every change of the runs while it is open, since its report carries the progress. */
export function useRun(id: string, enabled = true) {
  return useQuery({
    queryKey: keys.run(id),
    queryFn: () => api<Run>(`/api/updates/${encodeURIComponent(id)}`),
    ...live("RUNS"),
    enabled,
  })
}

export function useMetrics(subject: string, metric: string, minutes: number, enabled = true) {
  return useQuery({
    queryKey: keys.metrics(subject, metric, minutes),
    queryFn: () =>
      api<Metrics>(
        `/api/metrics?subject=${encodeURIComponent(subject)}&metric=${encodeURIComponent(metric)}&minutes=${minutes}`,
      ),
    ...live("METRICS"),
    enabled,
  })
}

export function useSeason(enabled = true) {
  return useQuery({
    queryKey: keys.season,
    queryFn: () => api<Season>("/api/season"),
    ...live("SEASON"),
    enabled,
  })
}

/** The newest actions across steward's inbox and `audit_log`, merged and sorted by steward. */
export function useActions(limit = 5, enabled = true) {
  return useQuery({
    queryKey: keys.actions(limit),
    queryFn: () => api<Action[]>(`/api/actions?limit=${limit}`),
    ...live("RUNS", "JOURNAL"),
    enabled,
  })
}

/** The SMP's active milestones and their objectives, for the actions on its service page. */
export function useSmpTrack() {
  return useQuery({
    queryKey: keys.smpTrack,
    queryFn: () => api<SmpTrack>("/api/smp/track"),
    ...live("GAMES"),
  })
}

/** The open hunger games round, if there is one. */
export function useHungerGamesRound() {
  return useQuery({
    queryKey: keys.hungerGamesRound,
    queryFn: () => api<HungerGamesRound>("/api/hunger-games/round"),
    ...live("GAMES"),
  })
}

/** One action on the smp or hunger-games page, as a request in that server's inbox; the answer names it. */
export function useGameAction() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ path, body }: { path: string; body: unknown }) => api<CommandAsked>(path, { method: "POST", body }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

/** One announcement, one text per language; the answer is one row id per language. */
export function useSendAnnouncement() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (texts: Record<string, string>) =>
      api<AnnouncementsAsked>("/api/announcements", { method: "POST", body: { texts } }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

/** What became of one request, read again on every change of the requests until it has settled. */
export function useCommandRun(id: string | null) {
  return useQuery({
    queryKey: keys.commandRun(id ?? ""),
    queryFn: () => api<CommandRun>(`/api/commands/${id}`),
    enabled: Boolean(id),
    meta: { topics: ["REQUESTS"] satisfies Topic[] },
    refetchInterval: (query) => {
      /** A failed read stops the reconciliation; the row shows the error and a button to ask again. */
      if (query.state.error) return false
      const status = query.state.data?.status
      return status === undefined || status === "PENDING" || status === "RUNNING" ? RECONCILE : false
    },
  })
}

/**
 * The guild's channels, for the pickers in the configuration editor.
 *
 * Five minutes and no refetch on focus, so a page with eleven pickers is not eleven requests per focus.
 */
export function useGuildChannels(enabled = true) {
  return useQuery({
    queryKey: keys.guildChannels,
    queryFn: () => api<GuildList>("/api/discord/channels"),
    staleTime: 5 * 60 * SECOND,
    refetchOnWindowFocus: false,
    enabled,
  })
}

/** Whether the agent has a secret and answers, asked before the recreate button is drawn. */
export function useAgent(enabled = true) {
  return useQuery({
    queryKey: keys.agent,
    queryFn: () => api<AgentState>("/api/agent"),
    staleTime: 60 * SECOND,
    enabled,
  })
}

/** Asks for a run by writing a row into steward's inbox, which is cancellable and never touches a container. */
export function useAskForRun() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (ask: {
      kind: "UPDATE" | "BACKUP" | "RESTART" | "DOWN" | "START" | "RECREATE" | "DEPLOY"
      delaySeconds?: number
      /** Which compose services the run is for; left off for the whole network. */
      services?: string[]
    }) => api<Run>("/api/updates", { method: "POST", body: ask }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["runs"] })
    },
  })
}

/**
 * Takes back the pending run, whether its countdown is ticking now or it is entered for tonight.
 *
 * A 409 means the countdown ran out first.
 */
export function useCancelRun() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<Run>("/api/updates/cancel", { method: "POST" }),
    onSuccess: (cancelled) => {
      void client.invalidateQueries({ queryKey: ["runs"] })
      void client.invalidateQueries({ queryKey: keys.run(String(cancelled.id)) })
    },
  })
}

export function useConsole(service: string) {
  return useMutation({
    mutationFn: (command: string) =>
      api<ConsoleSent>(`/api/services/${encodeURIComponent(service)}/console`, {
        method: "POST",
        body: { command },
      }),
  })
}

/** The commands one server published of itself; they change with its plugins, so a few minutes old is fresh enough. */
export function useCommandTree(service: string) {
  return useQuery({
    queryKey: keys.commandTree(service),
    queryFn: () => api<ConsoleTree>(`/api/services/${encodeURIComponent(service)}/commands`),
    staleTime: 5 * 60 * SECOND,
  })
}

/** The plugins on one Minecraft server, which change when a run installs or removes one. */
export function usePlugins(service: string) {
  return useQuery({
    queryKey: keys.plugins(service),
    queryFn: () => api<ServicePlugins>(`/api/services/${encodeURIComponent(service)}/plugins`),
    ...live("RUNS"),
    /** A 404 means the service has no plugins folder; every other failure gets one retry. */
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 1,
  })
}

/** The Modrinth search, as a query keyed on what was typed; the component debounces it. */
export function usePluginSearch(service: string, query: string, enabled: boolean) {
  return useQuery({
    queryKey: keys.pluginSearch(service, query),
    queryFn: () =>
      api<PluginSearch>(`/api/services/${encodeURIComponent(service)}/plugins/search?q=${encodeURIComponent(query)}`),
    enabled,
    staleTime: 5 * 60 * SECOND,
    /** Keeps the previous hits while a new key loads, so the list does not flash skeletons on every letter. */
    placeholderData: keepPreviousData,
  })
}

/** Installs a plugin, which means writing the row the next update run reads. */
export function useInstallPlugin(service: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (hit: { projectId: string; slug: string; title: string; iconUrl?: string }) =>
      api<PluginAdded>(`/api/services/${encodeURIComponent(service)}/plugins`, { method: "POST", body: hit }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.plugins(service) })
      // The search rows carry `added`, so they are wrong the moment this succeeds.
      void client.invalidateQueries({ queryKey: ["plugin-search", service] })
    },
  })
}

/** Asks for a RESTORE run of one archive, with what it replaces typed back as the confirmation. */
export function useRestore() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ archive, confirm }: { archive: string; confirm: string }) =>
      api<RestoreAsked>(`/api/backups/${encodeURIComponent(archive)}/restore`, {
        method: "POST",
        body: { confirm },
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["runs"] })
    },
  })
}

/** Asks for a REMOVE_PLUGIN run: the server is stopped, and the row, the jar and the data folder go. */
export function useRemovePlugin(service: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (artifact: string) =>
      api<RemovalAsked>(`/api/services/${encodeURIComponent(service)}/plugins/${encodeURIComponent(artifact)}`, {
        method: "DELETE",
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["runs"] })
    },
  })
}

export function useSetPhase() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (change: { phase: string; reason: string }) =>
      api<PhaseChange>("/api/season/phase", { method: "POST", body: change }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.season })
      void client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

export function useSetSeasonDate() {
  const client = useQueryClient()
  return useMutation({
    /** `at: null` removes the date: "none announced" is a state the countdown reads. */
    mutationFn: (change: { which: "launch" | "smpStart"; at: string | null }) =>
      api<DateChange>("/api/season/date", { method: "POST", body: change }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.season })
      void client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

/** Re-export so a page can narrow a query's options without importing TanStack itself. */
export type { UseQueryOptions }

export * from "@/lib/queries-access"
export * from "@/lib/queries-alerts"
export * from "@/lib/queries-settings"
