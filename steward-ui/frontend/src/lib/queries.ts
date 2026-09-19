import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query"
import type { UseQueryOptions } from "@tanstack/react-query"

import {
  api,
  ApiError,
  rememberCsrf,
  type Action,
  type Backup,
  type AdminCommand,
  type CommandRun,
  type ConfigChanges,
  type ConfigDocument,
  type ConfigLocation,
  type DeployerJob,
  type DeployerState,
  type Grant,
  type GuildList,
  type Host,
  type JournalEntry,
  type LogSearch,
  type Me,
  type MessageBundle,
  type MessageBundleLocation,
  type MessageChanges,
  type MessageSaveResult,
  type Metrics,
  type Payment,
  type Person,
  type PluginSearch,
  type ServicePlugins,
  type RawConfigSaveResult,
  type ReloadAwareConfigDocument,
  type Available,
  type Run,
  type Schedule,
  type Season,
  type Service,
  type ServiceTable,
  type AlertTypeKey,
  type PushDevice,
  type WebPushPreferences,
  type WebPushPublicKey,
} from "@/lib/api"
import { browserHasSecurityKeys, createSecurityKey, whyTheKeyFailed } from "@/lib/webauthn"
import { holdTheKey } from "@/lib/hold-key"
import { currentPushEndpoint, subscribeToPush, unsubscribeFromPush } from "@/lib/push"
import type { CreationOptionsJson } from "@/lib/webauthn"
import type { Thresholds } from "@/lib/health"

/**
 * One hook per endpoint, and the refresh interval of each decided here rather than at the call
 * site.
 *
 * The intervals are not taste. Measured on this host on 2026-09-13: one
 * `/containers/{id}/stats?stream=false` costs **1.03 s** of daemon time, and the service table asks
 * for nine of them. The worker now reads them in parallel, so the table costs about a second of
 * daemon time per refresh - which is affordable every ten seconds and would not be affordable every
 * two. A dashboard that refreshes faster than the thing it measures is a load generator.
 *
 * Curves come from Postgres and not from Docker (concept §10c), so they are cheap and may be asked
 * for more often; they are still on a slow interval because a 30-second sampler cannot produce a
 * new point faster than every 30 seconds.
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
  run: (id: string) => ["run", id] as const,
  available: ["available"] as const,
  metrics: (subject: string, metric: string, hours: number) =>
    ["metrics", subject, metric, hours] as const,
  season: ["season"] as const,
  people: ["people"] as const,
  payments: ["payments"] as const,
  openPayments: ["payments", "open"] as const,
  grants: (discordId: string) => ["grants", discordId] as const,
  journal: (action: string, subject: string) => ["journal", action, subject] as const,
  actions: (limit: number) => ["actions", limit] as const,
  settings: ["settings"] as const,
  commands: ["commands"] as const,
  commandRun: (id: string) => ["command-run", id] as const,
  configs: ["configs"] as const,
  deployer: ["deployer"] as const,
  deployerJob: (id: string) => ["deployer-job", id] as const,
  config: (file: string) => ["config", file] as const,
  guildRoles: ["guild-roles"] as const,
  guildChannels: ["guild-channels"] as const,
  messageBundles: ["message-bundles"] as const,
  messageBundle: (path: string) => ["message-bundle", path] as const,
  webPushPublicKey: ["web-push-public-key"] as const,
  webPushSubscription: ["web-push-subscription"] as const,
  webPushDevices: ["web-push-devices"] as const,
  webPushPreferences: ["web-push-preferences"] as const,
}

/**
 * Who is signed in - and the CSRF token, which is why this one is special.
 *
 * Every write in the interface needs the token, and the token only exists once this has answered.
 * So it is fetched once at the top of the shell, kept fresh for an hour, and its `onSuccess` puts
 * the token where `api()` can read it synchronously.
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
 * Registers a security key: two round trips and a dialog between them.
 *
 * **The three steps are one mutation on purpose.** A challenge is single-use and lives ten
 * minutes; splitting this into "start" and "finish" hooks would let a component hold a half-done
 * ceremony across a re-render, and the half that is already spent is the half nobody can see. One
 * function, one outcome, and `/api/me` refetched at the end because the whole shell hangs off it -
 * the key that was just registered is what opens every other page.
 */
export function useRegisterKey() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (label: string) => {
      if (!browserHasSecurityKeys()) {
        throw new Error("This browser cannot use security keys, so it cannot sign in to Steward."
          + " Every current browser can; one in a private window or an old WebView may not.")
      }
      // The server's answer is handed to the browser untouched - it is the library's own JSON and
      // this end does not get an opinion about its contents.
      const started = await api<CreationOptionsJson>("/auth/webauthn/register/start",
        { method: "POST" })
      let credential: string
      try {
        credential = await createSecurityKey(started)
      } catch (refused) {
        // The browser's DOMException, turned into something a person can act on. Rethrown as a
        // plain Error so the form prints one sentence rather than "NotAllowedError".
        throw new Error(whyTheKeyFailed(refused))
      }
      const registered = await api<{ label: string; backedUp: boolean }>(
        "/auth/webauthn/register/finish",
        { method: "POST", body: { label, credential } },
      )
      await client.invalidateQueries({ queryKey: keys.me })
      return registered
    },
  })
}

/**
 * Holds the key: the ceremony, and then `/api/me` again.
 *
 * The refetch is not housekeeping. `verified` and `verifiedAt` are what the shell decides what to
 * draw from and what the step-up dialog closes on, so a successful ceremony that left `/api/me`
 * stale would be a person holding their key and watching nothing happen.
 */
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
      const renamed = await api<{ label: string }>(`/api/keys/${encodeURIComponent(id)}`,
        { method: "PUT", body: { label } })
      await client.invalidateQueries({ queryKey: keys.me })
      return renamed
    },
  })
}

/** Removes one registered key. Removing the last one is allowed - the server says why. */
export function useRemoveKey() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) => {
      const removed = await api<{ removed: string; left: number }>(
        `/api/keys/${encodeURIComponent(id)}`, { method: "DELETE" })
      await client.invalidateQueries({ queryKey: keys.me })
      return removed
    },
  })
}

export function useServices(enabled = true) {
  return useQuery({
    queryKey: keys.services,
    queryFn: () => api<ServiceTable>("/api/services"),
    refetchInterval: 10 * SECOND,
    enabled,
  })
}

export function useService(name: string, enabled = true) {
  return useQuery({
    queryKey: keys.service(name),
    queryFn: () => api<Service>(`/api/services/${encodeURIComponent(name)}`),
    refetchInterval: 10 * SECOND,
    enabled,
  })
}

export function useHost(enabled = true) {
  return useQuery({
    queryKey: keys.host,
    queryFn: () => api<Host>("/api/host"),
    refetchInterval: 10 * SECOND,
    enabled,
  })
}

/**
 * The worker's nightly clock.
 *
 * An hour of cache and no polling: `backup.at` changes when somebody edits a config file and
 * restarts the worker, not while a dialog is open.
 */
export function useSchedule(enabled = true) {
  return useQuery({
    queryKey: keys.schedule,
    queryFn: () => api<Schedule>("/api/schedule"),
    staleTime: 60 * 60 * SECOND,
    enabled,
  })
}

export function useBackups(enabled = true) {
  return useQuery({
    queryKey: keys.backups,
    queryFn: () => api<Backup[]>("/api/backups"),
    // A backup appears once a night. Thirty seconds is already generous and exists only so that a
    // run started by hand shows its archive without a reload.
    refetchInterval: 30 * SECOND,
    enabled,
  })
}

/**
 * What a run would do, without a run (season-2-ops/128).
 *
 * **No refetch interval**, and that is the point: the worker holds the answer for six hours and
 * asks Modrinth, GitHub and the Fill API behind whoever opened the page. Polling it would ask this
 * container more often without the answer changing any faster, and the staleness the page cares
 * about is on `checkedAt`, which is drawn.
 */
export function useAvailable(enabled = true) {
  return useQuery({
    queryKey: keys.available,
    queryFn: () => api<Available>("/api/updates/available"),
    staleTime: 5 * 60 * SECOND,
    enabled,
  })
}

export function useRuns(limit = 20, enabled = true) {
  return useQuery({
    queryKey: keys.runs(limit),
    queryFn: () => api<Run[]>(`/api/updates?limit=${limit}`),
    refetchInterval: 10 * SECOND,
    enabled,
  })
}

/**
 * One run.
 *
 * While it is running the report grows row by row - the worker writes progress into the same
 * column - so this polls quickly until the run has finished and then stops. That is the whole
 * "run live" of the plan: no socket, no stream, just a row that keeps changing.
 */
export function useRun(id: string, enabled = true) {
  return useQuery({
    queryKey: keys.run(id),
    queryFn: () => api<Run>(`/api/updates/${encodeURIComponent(id)}`),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === "DONE" || status === "FAILED" || status === "CANCELLED" ? false : 2 * SECOND
    },
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
    // The sampler writes every 30 seconds; asking more often can only return the same points.
    refetchInterval: 30 * SECOND,
    enabled,
  })
}

export function useSeason(enabled = true) {
  return useQuery({
    queryKey: keys.season,
    queryFn: () => api<Season>("/api/season"),
    staleTime: 60 * SECOND,
    enabled,
  })
}

export function usePeople(enabled = true) {
  return useQuery({
    queryKey: keys.people,
    queryFn: () => api<Person[]>("/api/people"),
    staleTime: 30 * SECOND,
    enabled,
  })
}

export function usePayments(enabled = true) {
  return useQuery({
    queryKey: keys.payments,
    queryFn: () => api<Payment[]>("/api/payments"),
    staleTime: 30 * SECOND,
    enabled,
  })
}

/**
 * The payment requests still waiting to be paid.
 *
 * Separate from `usePayments` and not a filter over it: that one is a page with a limit, and this
 * is the list somebody picks a reference out of before settling one by hand. A short stale time
 * because a request can be paid while the dropdown is open, and a settled one in the list is a
 * click that will come back "not open".
 */
export function useOpenPayments(enabled = true) {
  return useQuery({
    queryKey: keys.openPayments,
    queryFn: () => api<Payment[]>("/api/payments/open"),
    staleTime: 15 * SECOND,
    enabled,
  })
}

export function useGrants(discordId: string | null) {
  return useQuery({
    queryKey: keys.grants(discordId ?? ""),
    queryFn: () => api<Grant[]>(`/api/people/${encodeURIComponent(discordId ?? "")}/grants`),
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
    staleTime: 15 * SECOND,
    enabled,
  })
}

/**
 * The unified "latest actions" feed (steward/82) - the newest few rows across `update_request` and
 * `audit_log`, already merged and sorted by steward-worker's own `/api/actions`. See that endpoint's
 * javadoc for why this is one query rather than this file sorting {@link useJournal} together with
 * a second call of its own.
 */
export function useActions(limit = 5, enabled = true) {
  return useQuery({
    queryKey: keys.actions(limit),
    queryFn: () => api<Action[]>(`/api/actions?limit=${limit}`),
    staleTime: 15 * SECOND,
    enabled,
  })
}

/** Which admin commands this interface may ask for - the declarations carrying Surface.WEB. */
export function useCommands(enabled = true) {
  return useQuery({
    queryKey: keys.commands,
    queryFn: () => api<AdminCommand[]>("/api/commands"),
    staleTime: 60 * 60 * SECOND,
    enabled,
  })
}

/**
 * What became of one request.
 *
 * Polled every second while it is unsettled and not at all afterwards. A command travels to another
 * process and back through one row, so there is nothing to subscribe to - and a second a spinner
 * sits still is a second an operator spends wondering whether the click registered.
 */
export function useCommandRun(id: string | null) {
  return useQuery({
    queryKey: keys.commandRun(id ?? ""),
    queryFn: () => api<CommandRun>(`/api/commands/${id}`),
    enabled: Boolean(id),
    refetchInterval: (query) => {
      // A failed poll stops the polling. `retry: 1` means the query has already asked twice by the
      // time the error lands, and a request that keeps going every second against a service that is
      // not answering is a second failure being manufactured once a second. The row shows the error
      // and a button; asking again is the operator's decision from there.
      if (query.state.error) return false
      const status = query.state.data?.status
      return status === undefined || status === "PENDING" || status === "RUNNING" ? SECOND : false
    },
  })
}

// --- steward-deployer -------------------------------------------------------------------------
//
// A recreate is NOT an update and is deliberately not on the same hook. An update is a row in
// `update_request` that steward-worker claims, counts down in front of every player online and
// writes a report for; this is one compose operation on one container, carried out by the only
// process allowed to create one. They look alike on screen and are not alike at all.

/** Whether the deployer has a secret and answers - asked before the button is drawn. */
/**
 * The guild's roles and channels, for the pickers in the configuration editor.
 *
 * Five minutes, and no refetch on focus. The server already caches Discord's answer for a minute;
 * this is the second half of the same argument - a role created while somebody is looking at the
 * page is a reload away, and a page with eleven pickers on it must not be eleven requests every
 * time the tab regains focus.
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

export function useDeployer(enabled = true) {
  return useQuery({
    queryKey: keys.deployer,
    queryFn: () => api<DeployerState>("/api/deployer"),
    staleTime: 60 * SECOND,
    enabled,
  })
}

/**
 * Recreate one service's container from the image already on the host.
 *
 * The answer is the job, not the result: compose takes seconds to a minute and the caller follows
 * it with `useDeployerJob`. Nothing is invalidated here - the service table refreshes on its own
 * ten-second interval, and doing it now would show the container mid-recreate.
 */
export function useRecreate() {
  return useMutation({
    mutationFn: (service: string) =>
      api<DeployerJob>(`/api/deployer/recreate/${encodeURIComponent(service)}`, { method: "POST" }),
  })
}

/** One job, polled while it runs. `lines` is compose's own output and arrives with it. */
export function useDeployerJob(id: string | null) {
  const client = useQueryClient()
  return useQuery({
    queryKey: keys.deployerJob(id ?? ""),
    queryFn: async () => {
      const job = await api<DeployerJob>(`/api/deployer/jobs/${encodeURIComponent(id ?? "")}`)
      if (job.state !== "RUNNING") {
        // The container is new, so everything about it is: state, uptime, image digest. Asked for
        // once the job is over rather than while it runs, when the answer would be a container
        // that is being taken down.
        client.invalidateQueries({ queryKey: keys.services })
        client.invalidateQueries({ queryKey: ["service"] })
      }
      return job
    },
    enabled: Boolean(id),
    // A failed poll stops it, for the same reason as `useCommandRun`: the last answer says RUNNING
    // and would keep this asking every second while nothing answers. The dialog shows the failure
    // and offers to ask again.
    refetchInterval: (query) =>
      !query.state.error && query.state.data?.state === "RUNNING" ? SECOND : false,
  })
}

/**
 * The traffic light's two adjustable thresholds, out of steward-ui.yml.
 *
 * They live on the server rather than in this browser because the same traffic light has to fire into the
 * Discord admin channel, and a threshold kept in somebody's localStorage cannot be read by
 * anything that is not that browser.
 */
export function useSettings(enabled = true) {
  return useQuery({
    queryKey: keys.settings,
    queryFn: () => api<Thresholds>("/api/settings"),
    staleTime: 5 * 60 * SECOND,
    enabled,
  })
}

/**
 * The one other thing `/api/settings` carries: where a Minecraft head is composed from.
 *
 * Same endpoint and same cache entry as {@link useSettings} - two hooks reading one response
 * rather than two requests - but typed on its own rather than added to {@link Thresholds}, which
 * belongs to the traffic light and is not this component's to widen.
 */
export function useAvatarBaseUrl(enabled = true) {
  return useQuery({
    queryKey: keys.settings,
    queryFn: () => api<Thresholds & { minecraftHeadBaseUrl: string }>("/api/settings"),
    staleTime: 5 * 60 * SECOND,
    enabled,
    select: (settings) => settings.minecraftHeadBaseUrl,
  })
}

/**
 * The VAPID public key, fetched well before any button that needs it is tapped.
 *
 * **Why this is its own query and not read inside the subscribe button's handler.** iOS only opens
 * the permission dialog `pushManager.subscribe()` shows while the tap that asked for it is still on
 * the call stack - an `await` on a fetch first can spend that "user activation" before the browser
 * ever sees the request. Loading this ahead of time, cached for the length of the session, is what
 * lets the button's own handler go straight to `subscribeToPush` with no network call in between.
 * See `lib/push.ts`'s module note for the rest of this reasoning.
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

/** Whether THIS browser is currently subscribed, and to what - the settings button's own state. */
export function useWebPushSubscription(enabled = true) {
  return useQuery({
    queryKey: keys.webPushSubscription,
    queryFn: () => currentPushEndpoint(),
    staleTime: 0,
    enabled,
  })
}

/**
 * Subscribes this browser: the browser-level ceremony first, then telling the server about it.
 *
 * **Called with the public key already in hand** (see {@link useWebPushPublicKey}) so that nothing
 * here awaits a fetch before `subscribeToPush` reaches `pushManager.subscribe()`.
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
      client.invalidateQueries({ queryKey: keys.webPushSubscription })
      client.invalidateQueries({ queryKey: keys.webPushDevices })
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
      client.invalidateQueries({ queryKey: keys.webPushSubscription })
      client.invalidateQueries({ queryKey: keys.webPushDevices })
    },
  })
}

/**
 * One browser of this account, gone - the device list's own remove.
 *
 * When the endpoint happens to be this browser's own, the push subscription is torn down here as
 * well: deleting only the row would leave the browser holding a subscription nothing will ever send
 * to, and its own button would still read "On".
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
      client.invalidateQueries({ queryKey: keys.webPushSubscription })
      client.invalidateQueries({ queryKey: keys.webPushDevices })
    },
  })
}

/** Every browser this ACCOUNT has subscribed - not just this one. */
export function useWebPushDevices(enabled = true) {
  return useQuery({
    queryKey: keys.webPushDevices,
    queryFn: () => api<PushDevice[]>("/api/web-push/devices"),
    enabled,
  })
}

/**
 * Which kinds of alert this account wants.
 *
 * The server answers every type with its effective value, defaults included, so this side never
 * has to know what a default is - see `AlertType` for where they live and why.
 */
export function useWebPushPreferences(enabled = true) {
  return useQuery({
    queryKey: keys.webPushPreferences,
    queryFn: () => api<WebPushPreferences>("/api/web-push/preferences"),
    enabled,
  })
}

/**
 * One switch.
 *
 * The answer is written into the cache rather than triggering a refetch: a switch that springs back
 * for half a second while a GET is in flight reads as "that did not work".
 */
export function useSetWebPushPreference() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (choice: { type: AlertTypeKey; enabled: boolean }) =>
      api<{ type: AlertTypeKey; enabled: boolean }>("/api/web-push/preferences", {
        method: "PUT",
        body: choice,
      }),
    onSuccess: (saved) =>
      client.setQueryData(keys.webPushPreferences, (current?: WebPushPreferences) =>
        current ? { ...current, [saved.type]: saved.enabled } : current,
      ),
  })
}

/**
 * One notification of one type, to one of this account's own browsers, now.
 *
 * A dead subscription answers 404 and the server has already removed the row by then, so the device
 * list is refreshed either way.
 */
export function useTestWebPush() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (what: { endpoint: string; type: AlertTypeKey }) =>
      api<void>("/api/web-push/test", { method: "POST", body: what }),
    onSettled: () => {
      client.invalidateQueries({ queryKey: keys.webPushDevices })
      client.invalidateQueries({ queryKey: keys.webPushSubscription })
    },
  })
}

export function useConfigs(enabled = true) {
  return useQuery({
    queryKey: keys.configs,
    queryFn: () => api<ConfigLocation[]>("/api/config"),
    staleTime: 5 * 60 * SECOND,
    enabled,
  })
}

/**
 * One config file.
 *
 * `file` is the `path` out of the listing, slashes and all. Each segment is encoded on its own -
 * `encodeURIComponent` on the whole string would turn the separators into `%2F` and the route
 * would stop matching.
 */
export function useConfig(file: string, enabled = true) {
  return useQuery({
    queryKey: keys.config(file),
    queryFn: () => api<ConfigDocument>(`/api/config/${encodePath(file)}`),
    enabled: enabled && Boolean(file),
  })
}

function encodePath(file: string): string {
  return file.split("/").map(encodeURIComponent).join("/")
}

// --- the writes ------------------------------------------------------------------------------

/**
 * Asking for a run.
 *
 * It writes a row into `update_request` - the same row `/update` in Discord writes - and never
 * touches a container. That is what makes it cancellable and what puts a countdown in front of
 * every player before anything stops.
 */
export function useAskForRun() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (ask: {
      kind: "UPDATE" | "BACKUP" | "RESTART" | "DOWN" | "START"
      delaySeconds?: number
      /**
       * Which compose services this run is for (season-2-ops/127). Left off for the whole network,
       * which is what every button on /operations means. A scoped run follows the same procedure -
       * countdown, limbo, health, report - it simply touches less.
       */
      services?: string[]
    }) => api<Run>("/api/updates", { method: "POST", body: ask }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["runs"] })
    },
  })
}

export function useConsole(service: string) {
  return useMutation({
    mutationFn: (command: string) =>
      api<{ sent: string; where: string }>(
        `/api/services/${encodeURIComponent(service)}/console`,
        { method: "POST", body: { command } },
      ),
  })
}

/**
 * The plugins on one Minecraft server (season-2-ops/129).
 *
 * Refetched on a slow interval rather than on focus alone: the interesting transition is
 * pre-booked turning into running, and that happens when an update run finishes, which is minutes
 * after somebody stopped looking at this page.
 */
export function usePlugins(service: string) {
  return useQuery({
    queryKey: keys.plugins(service),
    queryFn: () => api<ServicePlugins>(`/api/services/${encodeURIComponent(service)}/plugins`),
    refetchInterval: 30 * SECOND,
    // A 404 is the answer for a service with no plugins folder - the bot, postgres, caddy - and
    // asking again changes nothing. Every other failure is worth one retry.
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 1,
  })
}

/**
 * The Modrinth search, as a query keyed on what was typed.
 *
 * A query and not a mutation, unlike the log search one card below, and the difference is what
 * each one costs: the log search greps up to 50 MB inside the daemon and is therefore a button,
 * this is one small call to somebody else's API and should follow the box as it is typed in. The
 * debounce lives in the component, because it is about the keyboard and not about the request.
 */
export function usePluginSearch(service: string, query: string, enabled: boolean) {
  return useQuery({
    queryKey: keys.pluginSearch(service, query),
    queryFn: () =>
      api<PluginSearch>(
        `/api/services/${encodeURIComponent(service)}/plugins/search?q=${encodeURIComponent(query)}`,
      ),
    enabled,
    // Somebody typing back over a word they just deleted should not wait for the same answer
    // twice.
    staleTime: 5 * 60 * SECOND,
  })
}

/** Installs a plugin, which means writing the row the next update run reads. */
export function useInstallPlugin(service: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (hit: { projectId: string; slug: string; title: string; iconUrl?: string }) =>
      api<{ artifact: string; fileName: string; version: string }>(
        `/api/services/${encodeURIComponent(service)}/plugins`,
        { method: "POST", body: hit },
      ),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.plugins(service) })
      // The search rows carry `added`, so they are wrong the moment this succeeds.
      void client.invalidateQueries({ queryKey: ["plugin-search", service] })
    },
  })
}

/** Removes a plugin: the row, the jar and the data folder. */
export function useRemovePlugin(service: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (artifact: string) =>
      api<{ artifact: string; deleted: string[] }>(
        `/api/services/${encodeURIComponent(service)}/plugins/${encodeURIComponent(artifact)}`,
        { method: "DELETE" },
      ),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.plugins(service) })
      void client.invalidateQueries({ queryKey: ["plugin-search", service] })
    },
  })
}

export function useLogSearch(service: string) {
  return useMutation({
    mutationFn: (pattern: string) =>
      api<LogSearch>(
        `/api/services/${encodeURIComponent(service)}/logs/search?q=${encodeURIComponent(pattern)}`,
      ),
  })
}

/**
 * Saves a config file, and says which version of it the form was drawn from.
 *
 * The revision is not optional: the backend refuses a PUT without one. It is the whole of the
 * protection against two open forms - the second save is answered 409 instead of overwriting the
 * first, and the page redraws from the file as it then stands.
 */
export function useSaveConfig(file: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ revision, changes }: { revision: string; changes: ConfigChanges }) =>
      // Never `raw`: a raw file offers no save button in the first place (steward/56), so a PUT's
      // answer is always a form again - now carrying `reload`, which only a save produces
      // (steward/59).
      api<ReloadAwareConfigDocument>(`/api/config/${encodePath(file)}`, {
        method: "PUT",
        body: { revision, changes },
      }),
    onSuccess: (document) => {
      // The answer IS the file as it now reads, so the form redraws from what was written rather
      // than from what it hoped was written. A value the backend quoted or refused to canonicalise
      // is then visible immediately instead of on the next reload.
      client.setQueryData(keys.config(file), document)
    },
    onError: (failure) => {
      // 409 is the other browser having been faster. Nothing was written, and the copy in this
      // cache is now provably out of date - including its revision, so a second attempt with it
      // would be refused for the same reason. Re-reading is what lets the operator see what the
      // file says and decide whether their change is still the one they want.
      if (failure instanceof ApiError && failure.status === 409) {
        client.invalidateQueries({ queryKey: keys.config(file) })
      }
    },
  })
}

/**
 * Saves the exact text typed into the raw editor (steward/60).
 *
 * Mirrors {@link useSaveConfig}'s shape - a revision that has to match, an answer that replaces
 * the cache entry directly rather than triggering a refetch - but posts to the raw file's own
 * route, because its body is text and a revision, never a `changes` map. A syntax warning in the
 * answer is never a reason this promise rejects: {@code warnings} rides along on the same 200 a
 * clean save gets, the same way {@link useSaveMessageBundle}'s does.
 */
export function useSaveRawConfig(file: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ revision, content }: { revision: string; content: string }) =>
      api<RawConfigSaveResult>(`/api/config-raw/${encodePath(file)}`, {
        method: "PUT",
        body: { revision, content },
      }),
    onSuccess: (document) => {
      client.setQueryData(keys.config(file), document)
    },
    onError: (failure) => {
      // The other browser was faster - the same 409 handling useSaveConfig gives the parsed path.
      if (failure instanceof ApiError && failure.status === 409) {
        client.invalidateQueries({ queryKey: keys.config(file) })
      }
    },
  })
}

export function useGrantAccess() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (grant: { discordId: string; days: number }) =>
      api<Grant>("/api/access/grant", { method: "POST", body: grant }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: keys.people })
      client.invalidateQueries({ queryKey: ["grants"] })
      client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

export function useRevokeAccess() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (discordId: string) =>
      api<{ revoked: number }>("/api/access/revoke", { method: "POST", body: { discordId } }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: keys.people })
      client.invalidateQueries({ queryKey: ["grants"] })
      client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

/**
 * Sets an account's total play time outright (steward/119).
 *
 * Seconds and not hours, because seconds is what the column holds; the dialog does the arithmetic
 * so that the wire and the database agree on a unit. The journal is invalidated as well - this is a
 * write with an admin's name on it, the same as a grant.
 */
export function useSetPlaytime() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ discordId, seconds }: { discordId: string; seconds: number }) =>
      api<{ discordId: string; seconds: number }>(`/api/people/${discordId}/playtime`, {
        method: "POST",
        body: { seconds },
      }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: keys.people })
      client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

export function useSetPhase() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (change: { phase: string; reason: string }) =>
      api<Season>("/api/season/phase", { method: "POST", body: change }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: keys.season })
      client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

export function useSetSeasonDate() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (change: { which: "launch" | "smpStart"; at: string }) =>
      api<Season>("/api/season/date", { method: "POST", body: change }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: keys.season })
      client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

/** The five admin commands of §10b, each written as a `command_request` row. */
export function useAdminCommand() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (command: { name: string; arguments?: Record<string, string> }) =>
      api<CommandRun>("/api/commands", { method: "POST", body: command }),
    onSuccess: () => {
      // The row names who asked, so it is a journal entry whether or not the command succeeds.
      client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

// --- message bundles (steward/48) ---------------------------------------------------------

/**
 * Every message bundle steward-worker found - one row per module's `messages/` directory,
 * without opening a single jar. `ServiceMessages` filters this by `service` itself, the same way
 * `useConfigs` is filtered by `ServiceConfiguration`, so one listing serves every service's page.
 */
export function useMessageBundles(enabled = true) {
  return useQuery({
    queryKey: keys.messageBundles,
    queryFn: () => api<MessageBundleLocation[]>("/api/messages"),
    staleTime: 5 * 60 * SECOND,
    enabled,
  })
}

/**
 * One bundle's packaged text and operator overrides, in both languages at once - the en/de toggle
 * is drawn client-side rather than as two requests, since a bundle is one file's worth of JSON
 * either way.
 */
export function useMessageBundle(path: string, enabled = true) {
  return useQuery({
    queryKey: keys.messageBundle(path),
    queryFn: () => api<MessageBundle>(`/api/messages/${encodePath(path)}`),
    enabled: enabled && Boolean(path),
  })
}

/**
 * Saves a set of overrides for one language of one bundle.
 *
 * Unlike {@link useSaveConfig} there is no revision to carry - an override is a change to one key
 * at a time rather than a whole file rewritten under a form, and two admins editing the same line
 * a minute apart is "the second edit wins", the same way it already is for the override file if
 * somebody edited it by hand. The answer is the bundle as it now reads, plus any placeholder
 * warnings, and both replace this bundle's cache entry directly rather than triggering a refetch.
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

// --- settings search (steward/58) -----------------------------------------------------------

/**
 * Every one of the given files' documents, fetched only while `enabled` - a search box that has
 * something typed into it, or a command palette that is open, never a search box merely mounted.
 *
 * Each query shares its key with {@link useConfig}, so a file already open on the page (or already
 * found by an earlier search) costs nothing a second time, and closing the search again leaves
 * nothing subscribed. `files` is expected to be referentially stable across renders where possible
 * - a new array of the same paths still works, it just makes `useQueries` throw the old results
 * away and re-fetch from cache-or-network once more than strictly needed.
 */
export function useConfigDocuments(files: string[], enabled: boolean) {
  return useQueries({
    queries: files.map((file) => ({
      queryKey: keys.config(file),
      queryFn: () => api<ConfigDocument>(`/api/config/${encodePath(file)}`),
      staleTime: 5 * 60 * SECOND,
      enabled,
    })),
  })
}

/**
 * Every one of the given bundles' documents, fetched only while `enabled` - the message-bundle
 * twin of {@link useConfigDocuments}, for steward/87's search over the bundles as well as the
 * files. Each query shares its key with {@link useMessageBundle}, so a bundle already open on a
 * service's page costs nothing a second time to a search that also wants it.
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
