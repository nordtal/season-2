import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import type { UseQueryOptions } from "@tanstack/react-query"

import {
  api,
  ApiError,
  rememberCsrf,
  stepUpFirst,
  type AuthenticatorEnrolment,
  type StepUpLength,
  type Backup,
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
import { live } from "@/lib/live"
import type { CreationOptionsJson } from "@/lib/webauthn"
import { SECOND, keys } from "@/lib/query-keys"
import { t } from "@/lib/texts"
import { retryUnless404 } from "@/lib/queries-settings"

/** One hook per endpoint. A hook's live topics are decided here and not at the call site. */

/**
 * Who is signed in, and the CSRF token that every write needs. The token goes where `api()` can read it synchronously.
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
 * Registers a security key in one mutation, because a challenge is single-use and a half-done ceremony must not outlive a re-render.
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
        /** A plain Error, so the form prints one sentence and not "NotAllowedError". */
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

const askForRegistration = () => api<CreationOptionsJson>("/auth/webauthn/register/start", { method: "POST" })

/** A further key needs a step-up, so a refusal opens the sheet once and asks again. */
const startRegistration = () => afterAStepUp(askForRegistration)

/**
 * An `/auth/` request that adds a further factor, asked again once after the step-up sheet if it was refused for want of one. The sheet offers whichever factor the account has.
 */
async function afterAStepUp<T>(ask: () => Promise<T>): Promise<T> {
  try {
    return await ask()
  } catch (refused) {
    if (!(refused instanceof ApiError) || !refused.needsTheKeyAgain) {
      throw refused
    }
    await stepUpFirst()
    return await ask()
  }
}

export function useStartEnrolment() {
  return useMutation({
    mutationFn: () => afterAStepUp(() => api<AuthenticatorEnrolment>("/auth/totp/enrol/start", { method: "POST" })),
  })
}

export function useFinishEnrolment() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (code: string) => {
      await afterAStepUp(() => api<void>("/auth/totp/enrol/finish", { method: "POST", body: { code } }))
      await client.invalidateQueries({ queryKey: keys.me })
    },
  })
}

/**
 * A step-up, or the first factor, with the app's code instead of the key. `length` is what the key's split button chooses.
 */
export function useEnterCode() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async ({ code, length }: { code: string; length?: StepUpLength }) => {
      await api<void>("/auth/totp/verify", { method: "POST", body: length ? { code, hold: length } : { code } })
      await client.invalidateQueries({ queryKey: keys.me })
    },
  })
}

export function useRemoveAuthenticator() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      await api<void>("/api/authenticator", { method: "DELETE" })
      await client.invalidateQueries({ queryKey: keys.me })
    },
  })
}

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

/** Ends this session's step-up now, so the next change asks for the key. */
export function useEndStepUp() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      await api<void>("/api/step-up", { method: "DELETE" })
      await client.invalidateQueries({ queryKey: keys.me })
    },
  })
}

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

/** The services that the network page draws, as the labels in compose.yml place and wire them. */
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
    ...live("RUNS"),
    enabled,
  })
}

/**
 * What a run would do, without a run. There is no refetch interval, because steward caches the answer for six hours.
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
 * Asks every source again and writes the answer into the cache. It is a mutation so the busy state belongs to this press. It can take up to two minutes.
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

/** The one open run, or none. An open run locks Update, Take down and Recreate everywhere. */
export function useActiveRun(enabled = true) {
  return useQuery({
    queryKey: keys.activeRun,
    queryFn: () => api<ActiveRun>("/api/updates/active"),
    ...live("RUNS"),
    enabled,
  })
}

/**
 * One run, read again on every change of the runs while it is open, because its report carries the progress.
 */
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

export function useSmpTrack() {
  return useQuery({
    queryKey: keys.smpTrack,
    queryFn: () => api<SmpTrack>("/api/smp/track"),
    ...live("GAMES"),
  })
}

export function useHungerGamesRound() {
  return useQuery({
    queryKey: keys.hungerGamesRound,
    queryFn: () => api<HungerGamesRound>("/api/hunger-games/round"),
    ...live("GAMES"),
  })
}

export function useGameAction() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ path, body }: { path: string; body: unknown }) => api<CommandAsked>(path, { method: "POST", body }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

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

/**
 * The guild's channels. Fresh for five minutes with no refetch on focus, so a page with eleven pickers sends one request.
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

export function useAgent(enabled = true) {
  return useQuery({
    queryKey: keys.agent,
    queryFn: () => api<AgentState>("/api/agent"),
    staleTime: 60 * SECOND,
    enabled,
  })
}

/**
 * Asks for a run by writing a row into steward's inbox. The run can be cancelled and never touches a container.
 */
export function useAskForRun() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (ask: {
      kind: "UPDATE" | "BACKUP" | "RESTART" | "DOWN" | "START" | "RECREATE" | "DEPLOY"
      delaySeconds?: number
      /** Without it the run is for the whole network. */
      services?: string[]
      /** For an update: whether it may replace the builds made on the host. */
      replaceLocal?: boolean
    }) => api<Run>("/api/updates", { method: "POST", body: ask }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["runs"] })
    },
  })
}

/** Takes back the pending run. A 409 means that the countdown ran out first. */
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

export function useCommandTree(service: string) {
  return useQuery({
    queryKey: keys.commandTree(service),
    queryFn: () => api<ConsoleTree>(`/api/services/${encodeURIComponent(service)}/commands`),
    ...live("COMMANDS"),
  })
}

export function usePlugins(service: string) {
  return useQuery({
    queryKey: keys.plugins(service),
    queryFn: () => api<ServicePlugins>(`/api/services/${encodeURIComponent(service)}/plugins`),
    ...live("RUNS"),
    retry: retryUnless404,
  })
}

export function usePluginSearch(service: string, query: string, enabled: boolean) {
  return useQuery({
    queryKey: keys.pluginSearch(service, query),
    queryFn: () =>
      api<PluginSearch>(`/api/services/${encodeURIComponent(service)}/plugins/search?q=${encodeURIComponent(query)}`),
    enabled,
    staleTime: 5 * 60 * SECOND,
    /** The list does not flash skeletons on every letter. */
    placeholderData: keepPreviousData,
  })
}

export function useInstallPlugin(service: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (hit: { projectId: string; slug: string; title: string; iconUrl?: string }) =>
      api<PluginAdded>(`/api/services/${encodeURIComponent(service)}/plugins`, { method: "POST", body: hit }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.plugins(service) })
      // The search rows carry `added`, so they are wrong once this succeeds.
      void client.invalidateQueries({ queryKey: ["plugin-search", service] })
    },
  })
}

/**
 * Asks for a RESTORE run of one archive. The confirmation is the text of what the run replaces, typed back.
 */
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

/** Asks for a REMOVE_PLUGIN run: the server stops, and the row, the jar and the data folder go. */
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
    /** `at: null` removes the date, which the countdown reads as "none announced". */
    mutationFn: (change: { which: "launch" | "smpStart"; at: string | null }) =>
      api<DateChange>("/api/season/date", { method: "POST", body: change }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.season })
      void client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

export type { UseQueryOptions }

export * from "@/lib/queries-access"
export * from "@/lib/queries-alerts"
export * from "@/lib/queries-commands"
export * from "@/lib/queries-settings"
