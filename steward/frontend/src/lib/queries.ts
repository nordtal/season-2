import { keepPreviousData, useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query"
import type { UseQueryOptions } from "@tanstack/react-query"

import {
  api,
  ApiError,
  rememberCsrf,
  shapedAs,
  isGlyphInfoList,
  type Action,
  type Backup,
  type AccessRequestRun,
  type CommandRun,
  type SmpTrack,
  type Announcements,
  type HungerGamesRound,
  type ConfigChanges,
  type KeyRegistered,
  type KeyRenamed,
  type KeyRemoved,
  type CommandAsked,
  type AnnouncementsAsked,
  type PageSettings,
  type ConsoleSent,
  type PluginAdded,
  type RestoreAsked,
  type RemovalAsked,
  type AdminGranted,
  type AdminRevoked,
  type Exempted,
  type Settled,
  type ConfigDocument,
  type ConfigLocation,
  type AgentState,
  type Grant,
  type GuildList,
  type Host,
  type JournalEntry,
  type Me,
  type MessageBundle,
  type MessageBundleLocation,
  type MessageChanges,
  type MessageSaveResult,
  type MessageExamples,
  type Metrics,
  type Payment,
  type Person,
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
  type AlertPreference,
  type AlertType,
  type PushDevice,
  type AlertPreferences,
  type Alerts,
  type WebPushPublicKey,
} from "@/lib/api"
import { browserHasSecurityKeys, createSecurityKey, whyTheKeyFailed } from "@/lib/webauthn"
import { holdTheKey } from "@/lib/hold-key"
import { live, nextChange, RECONCILE, type Topic } from "@/lib/live"
import { currentPushEndpoint, subscribeToPush, unsubscribeFromPush } from "@/lib/push"
import type { CreationOptionsJson } from "@/lib/webauthn"

/**
 * One hook per endpoint, with the live topics it follows decided here rather than at the call site.
 *
 * A live query is read again when steward's stream announces one of its topics, and once a minute anyway.
 */

const SECOND = 1000

export const keys = {
  me: ["me"] as const,
  services: ["services"] as const,
  service: (name: string) => ["service", name] as const,
  plugins: (name: string) => ["plugins", name] as const,
  pluginSearch: (name: string, query: string) => ["plugin-search", name, query] as const,
  host: ["host"] as const,
  backups: ["backups"] as const,
  schedule: ["schedule"] as const,
  runs: (limit: number) => ["runs", limit] as const,
  /** Under "runs", so asking for a run and cancelling one refresh it with the list. */
  activeRun: ["runs", "active"] as const,
  run: (id: string) => ["run", id] as const,
  available: ["available"] as const,
  metrics: (subject: string, metric: string, hours: number) => ["metrics", subject, metric, hours] as const,
  season: ["season"] as const,
  people: ["people"] as const,
  payments: ["payments"] as const,
  openPayments: ["payments", "open"] as const,
  grants: (discordId: string) => ["grants", discordId] as const,
  journal: (action: string, subject: string) => ["journal", action, subject] as const,
  actions: (limit: number) => ["actions", limit] as const,
  settings: ["settings"] as const,
  commandRun: (id: string) => ["command-run", id] as const,
  smpTrack: ["smp-track"] as const,
  announcements: ["announcements"] as const,
  hungerGamesRound: ["hunger-games-round"] as const,
  configs: ["configs"] as const,
  agent: ["agent"] as const,
  config: (file: string) => ["config", file] as const,
  guildRoles: ["guild-roles"] as const,
  guildChannels: ["guild-channels"] as const,
  messageBundles: ["message-bundles"] as const,
  messageBundle: (path: string) => ["message-bundle", path] as const,
  messageExamples: ["message-examples"] as const,
  glyphs: ["glyphs"] as const,
  webPushPublicKey: ["web-push-public-key"] as const,
  webPushSubscription: ["web-push-subscription"] as const,
  webPushDevices: ["web-push-devices"] as const,
  alerts: ["alerts"] as const,
  alertPreferences: ["alert-preferences"] as const,
}

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
        throw new Error(
          "This browser cannot use security keys, so it cannot sign in to Steward." +
            " Every current browser can; one in a private window or an old WebView may not.",
        )
      }
      const started = await api<CreationOptionsJson>("/auth/webauthn/register/start", { method: "POST" })
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

export function useMetrics(subject: string, metric: string, hours: number, enabled = true) {
  return useQuery({
    queryKey: keys.metrics(subject, metric, hours),
    queryFn: () =>
      api<Metrics>(
        `/api/metrics?subject=${encodeURIComponent(subject)}&metric=${encodeURIComponent(metric)}&hours=${hours}`,
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

/** The latest announcements, by the SMP and by admins, newest first. */
export function useAnnouncements() {
  return useQuery({
    queryKey: keys.announcements,
    queryFn: () => api<Announcements>("/api/announcements"),
    ...live("REQUESTS"),
  })
}

/** One announcement, one text per language; the answer is one row id per language. */
export function useSendAnnouncement() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (texts: Record<string, string>) =>
      api<AnnouncementsAsked>("/api/announcements", { method: "POST", body: { texts } }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.announcements })
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
 * The guild's roles and channels, for the pickers in the configuration editor.
 *
 * Five minutes and no refetch on focus, so a page with eleven pickers is not eleven requests per focus.
 */
export function useGuildRoles() {
  return useQuery({
    queryKey: keys.guildRoles,
    queryFn: () => api<GuildList>("/api/discord/roles"),
    staleTime: 5 * 60 * SECOND,
    refetchOnWindowFocus: false,
  })
}

export function useGuildChannels() {
  return useQuery({
    queryKey: keys.guildChannels,
    queryFn: () => api<GuildList>("/api/discord/channels"),
    staleTime: 5 * 60 * SECOND,
    refetchOnWindowFocus: false,
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

/** What steward found wrong, judged on the server against the one set of thresholds. */
export function useAlerts(enabled = true) {
  return useQuery({
    queryKey: keys.alerts,
    queryFn: () => api<Alerts>("/api/alerts"),
    ...live("ALERTS"),
    enabled,
  })
}

/** Where a Minecraft head is composed from. */
export function useAvatarBaseUrl(enabled = true) {
  return useQuery({
    queryKey: keys.settings,
    queryFn: () => api<PageSettings>("/api/settings"),
    staleTime: 5 * 60 * SECOND,
    enabled,
    select: (settings) => settings.minecraftHeadBaseUrl,
  })
}

/**
 * The VAPID public key, fetched before any button that needs it is tapped.
 *
 * iOS opens the permission dialog only while the tap is on the call stack; see `lib/push.ts`.
 */
export function useWebPushPublicKey(enabled = true) {
  return useQuery({
    queryKey: keys.webPushPublicKey,
    queryFn: () => api<WebPushPublicKey>("/api/web-push/public-key"),
    staleTime: Infinity,
    retry: false,
    enabled,
  })
}

/** Whether this browser is subscribed, and to what endpoint. */
export function useWebPushSubscription(enabled = true) {
  return useQuery({
    queryKey: keys.webPushSubscription,
    queryFn: () => currentPushEndpoint(),
    staleTime: 0,
    enabled,
  })
}

/**
 * Subscribes this browser to push, then registers the subscription with the server.
 *
 * Takes the key from {@link useWebPushPublicKey}, so nothing awaits a fetch first.
 */
export function useSubscribeWebPush() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (publicKey: string) => {
      const subscription = await subscribeToPush(publicKey)
      await api<void>("/api/web-push/subscribe", { method: "POST", body: subscription })
      return subscription
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.webPushSubscription })
      void client.invalidateQueries({ queryKey: keys.webPushDevices })
    },
  })
}

/** Unsubscribes this browser, both from the push service and from Steward's own table. */
export function useUnsubscribeWebPush() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const endpoint = await unsubscribeFromPush()
      if (endpoint) {
        await api<void>("/api/web-push/subscribe", { method: "DELETE", body: { endpoint } })
      }
      return endpoint
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.webPushSubscription })
      void client.invalidateQueries({ queryKey: keys.webPushDevices })
    },
  })
}

/**
 * Forgets one browser of this account.
 *
 * When it is this browser, its push subscription is torn down too, or its button would still read "On".
 */
export function useForgetWebPushDevice() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (endpoint: string) => {
      if ((await currentPushEndpoint()) === endpoint) {
        await unsubscribeFromPush()
      }
      await api<void>("/api/web-push/subscribe", { method: "DELETE", body: { endpoint } })
      return endpoint
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.webPushSubscription })
      void client.invalidateQueries({ queryKey: keys.webPushDevices })
    },
  })
}

/** Every browser this account has subscribed, not just this one. */
export function useWebPushDevices(enabled = true) {
  return useQuery({
    queryKey: keys.webPushDevices,
    queryFn: () => api<PushDevice[]>("/api/web-push/devices"),
    enabled,
  })
}

/** On which channels this account wants each kind of alert, defaults included. */
export function useAlertPreferences(enabled = true) {
  return useQuery({
    queryKey: keys.alertPreferences,
    queryFn: () => api<AlertPreferences>("/api/alerts/preferences"),
    enabled,
  })
}

/** Sets one switch, writing the answer into the cache so the switch does not spring back during a refetch. */
export function useSetAlertPreference() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (choice: AlertPreference) =>
      api<AlertPreference>("/api/alerts/preferences", {
        method: "PUT",
        body: choice,
      }),
    onSuccess: (saved) =>
      client.setQueryData(keys.alertPreferences, (current?: AlertPreferences) =>
        current ? { ...current, [saved.type]: { ...current[saved.type], [saved.channel]: saved.enabled } } : current,
      ),
  })
}

/**
 * Sends one notification of one type to one of this account's browsers, now.
 *
 * A dead subscription answers 404 after the server removed its row, so the device list is refreshed either way.
 */
export function useTestWebPush() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (what: { endpoint: string; type: AlertType }) =>
      api<void>("/api/web-push/test", { method: "POST", body: what }),
    onSettled: () => {
      void client.invalidateQueries({ queryKey: keys.webPushDevices })
      void client.invalidateQueries({ queryKey: keys.webPushSubscription })
    },
  })
}

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
        throw new Error(typeof said === "string" ? said : "The bot could not carry this out.")
      }
      if (row.status === "EXPIRED") {
        // EXPIRED means the bot never picked the row up, so nothing changed.
        throw new Error("The bot did not pick this up within two minutes. Nothing was changed.")
      }
      await change.arrived
    } finally {
      change.stop()
    }
  }
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

/** Re-export so a page can narrow a query's options without importing TanStack itself. */
export type { UseQueryOptions }
