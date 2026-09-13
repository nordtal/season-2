import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import type { UseQueryOptions } from "@tanstack/react-query"

import {
  api,
  rememberCsrf,
  type Backup,
  type AdminCommand,
  type CommandRun,
  type ConfigChanges,
  type ConfigDocument,
  type ConfigLocation,
  type DeployerJob,
  type DeployerState,
  type Grant,
  type Host,
  type JournalEntry,
  type LogSearch,
  type Me,
  type Metrics,
  type Payment,
  type Person,
  type Run,
  type Season,
  type Service,
  type ServiceTable,
} from "@/lib/api"
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
  host: ["host"] as const,
  backups: ["backups"] as const,
  runs: (limit: number) => ["runs", limit] as const,
  run: (id: string) => ["run", id] as const,
  metrics: (subject: string, metric: string, hours: number) =>
    ["metrics", subject, metric, hours] as const,
  season: ["season"] as const,
  people: ["people"] as const,
  payments: ["payments"] as const,
  grants: (discordId: string) => ["grants", discordId] as const,
  journal: (action: string, subject: string) => ["journal", action, subject] as const,
  settings: ["settings"] as const,
  commands: ["commands"] as const,
  commandRun: (id: string) => ["command-run", id] as const,
  configs: ["configs"] as const,
  deployer: ["deployer"] as const,
  deployerJob: (id: string) => ["deployer-job", id] as const,
  config: (file: string) => ["config", file] as const,
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
 * "Lauf live" of the plan: no socket, no stream, just a row that keeps changing.
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
 * The Ampel's two adjustable thresholds, out of steward-ui.yml.
 *
 * They live on the server rather than in this browser because the same Ampel has to fire into the
 * Discord admin channel, and a threshold kept in somebody's localStorage cannot be read by
 * anything that is not that browser.
 */
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
    refetchInterval: (query) => (query.state.data?.state === "RUNNING" ? SECOND : false),
  })
}

export function useSettings(enabled = true) {
  return useQuery({
    queryKey: keys.settings,
    queryFn: () => api<Thresholds>("/api/settings"),
    staleTime: 5 * 60 * SECOND,
    enabled,
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
    mutationFn: (ask: { kind: "UPDATE" | "BACKUP" | "RESTART"; delaySeconds?: number }) =>
      api<Run>("/api/updates", { method: "POST", body: ask }),
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

export function useLogSearch(service: string) {
  return useMutation({
    mutationFn: (pattern: string) =>
      api<LogSearch>(
        `/api/services/${encodeURIComponent(service)}/logs/search?q=${encodeURIComponent(pattern)}`,
      ),
  })
}

export function useSaveConfig(file: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (changes: ConfigChanges) =>
      api<ConfigDocument>(`/api/config/${encodePath(file)}`, { method: "PUT", body: { changes } }),
    onSuccess: (document) => {
      // The answer IS the file as it now reads, so the form redraws from what was written rather
      // than from what it hoped was written. A value the backend quoted or refused to canonicalise
      // is then visible immediately instead of on the next reload.
      client.setQueryData(keys.config(file), document)
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

/** Re-export so a page can narrow a query's options without importing TanStack itself. */
export type { UseQueryOptions }
