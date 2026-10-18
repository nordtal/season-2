/** The cache key of every query, so a write can name exactly what it makes stale. */

export const SECOND = 1000

/** What the journal is narrowed to. An absent or empty part matches every entry. */
export type JournalFilter = {
  action?: string
  subject?: string
  reference?: string
  minDays?: string
}

export const keys = {
  me: ["me"] as const,
  services: ["services"] as const,
  topology: ["topology"] as const,
  service: (name: string) => ["service", name] as const,
  plugins: (name: string) => ["plugins", name] as const,
  commandTree: (name: string) => ["command-tree", name] as const,
  pluginSearch: (name: string, query: string) => ["plugin-search", name, query] as const,
  host: ["host"] as const,
  backups: ["backups"] as const,
  schedule: ["schedule"] as const,
  runs: (limit: number) => ["runs", limit] as const,
  /** Under "runs", so asking for and cancelling a run refresh the list too. */
  activeRun: ["runs", "active"] as const,
  run: (id: string) => ["run", id] as const,
  available: ["available"] as const,
  metrics: (subject: string, metric: string, minutes: number) => ["metrics", subject, metric, minutes] as const,
  season: ["season"] as const,
  people: ["people"] as const,
  payments: ["payments"] as const,
  openPayments: ["payments", "open"] as const,
  personPayments: (discordId: string) => ["payments", "person", discordId] as const,
  grants: (discordId: string) => ["grants", discordId] as const,
  journal: (filter: JournalFilter) => ["journal", filter] as const,
  actions: (limit: number) => ["actions", limit] as const,
  settings: ["settings"] as const,
  commandRun: (id: string) => ["command-run", id] as const,
  smpTrack: ["smp-track"] as const,
  hungerGamesRound: ["hunger-games-round"] as const,
  configs: ["configs"] as const,
  agent: ["agent"] as const,
  config: (file: string) => ["config", file] as const,
  guildChannels: ["guild-channels"] as const,
  messageTexts: ["message-texts"] as const,
  documents: ["documents"] as const,
  /** A draft as the bot draws it, by the draft itself, so an answer never stands for another draft. */
  documentDrawing: (id: string, language: string, draft: string) => ["document-drawing", id, language, draft] as const,
  descriptors: ["descriptors"] as const,
  gameData: ["game-data"] as const,
  messageExamples: ["message-examples"] as const,
  messageSyntax: ["message-syntax"] as const,
  messageFallbacks: ["message-fallbacks"] as const,
  messageCheck: (bundle: string, key: string, text: string) => ["message-check", bundle, key, text] as const,
  glyphs: ["glyphs"] as const,
  webPushPublicKey: ["web-push-public-key"] as const,
  webPushSubscription: ["web-push-subscription"] as const,
  webPushDevices: ["web-push-devices"] as const,
  alerts: ["alerts"] as const,
  alertPreferences: ["alert-preferences"] as const,
}
