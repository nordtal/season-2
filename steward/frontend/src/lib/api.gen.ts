/** Steward's API as its Java records declare it; written by `./gradlew :steward:generateApiTypes`. */

export type AlertPreferences = Record<AlertType, Record<AlertChannel, boolean>>

export type LiveEvent = {
  topic: Topic
  version: string
}

export type Me = {
  signedIn: boolean
  id?: string
  name?: string
  csrf?: string
  signedInAt?: string
  expiresAt?: string
  signInUnavailable?: string
  webauthn: string
  keys?: SecurityKey[]
  verified?: boolean
  verifiedAt?: string
  relyingPartyId?: string
  stepUpMinutes: number
  discordAvatarUrl?: string
}

export type WebPushPublicKey = {
  publicKey: string
}

export type PushDevice = {
  endpoint: string
  device?: string
  subscribedAt: string
  lastSentAt?: string
}

export type Alerts = {
  checkedAt?: string
  unreadable?: string
  level: AlertLevel
  alerts: Alert[]
  recent: RecentAlert[]
}

export type AlertPreference = {
  type: AlertType
  channel: AlertChannel
  enabled: boolean
}

export type Metrics = {
  subject: string
  metric: string
  from: string
  points: MetricPoint[]
}

export type AgentState = {
  available: boolean
  reason?: string
  reachable?: boolean
}

export type Run = {
  id: number
  kind: UpdateKind
  status: UpdateStatus
  actorKind: ActorKind
  actorId: string
  scope: string[]
  requested: string
  scheduledFor: string
  countdownEnd?: string
  moving: string[]
  started?: string
  finished?: string
  report?: Report
  savedSomething?: boolean
  resultText?: string
}

export type ActiveRun = {
  run?: Run
}

export type Season = {
  phase: SeasonPhase
  launch?: string
  smpStart?: string
}

export type PhaseChange = {
  previous: SeasonPhase
  current: SeasonPhase
  at?: string
}

export type DateChange = {
  previous?: string
  current?: string
  grants: number
  accounts: number
}

export type SmpTrack = {
  milestones: SmpMilestone[]
}

export type HungerGamesRound = {
  state?: string
  registered?: number
}

export type CommandAsked = {
  id: string
  status: string
}

export type CommandRun = {
  id: string
  status: InboxStatus
  result?: string
  reason?: string
}

export type Announcements = {
  recent: Announcement[]
}

export type AnnouncementsAsked = {
  ids: Record<string, string>
}

export type Settled = {
  outcome: SettleOutcome
  days?: number
  until?: string
  was?: string
}

export type AccessRequestRun = {
  id: string
  kind: string
  status: InboxStatus
  result?: Record<string, unknown>
}

export type Person = {
  discordId: string
  memberState: string
  donor: boolean
  admin: boolean
  locale: string
  updated: string
  minecraftUuid?: string
  linked?: string
  accessUntil?: string
  accessActive: boolean
  discordUsername?: string
  discordUsernameUpdated?: string
  discordDisplayName?: string
  discordDisplayNameUpdated?: string
  discordAvatarUrl?: string
  discordAvatarUrlUpdated?: string
  mcName?: string
  mcNameUpdated?: string
  playtimeSeconds?: number
  adminGrantedBy?: string
  adminGrantedAt?: string
  packExemptBy?: string
  packExemptAt?: string
}

export type Grant = {
  id: string
  discordId: string
  validFrom: string
  validUntil: string
  source: AccessSource
  paymentRequestId?: string
  revoked?: string
  created: string
}

export type Payment = {
  id: string
  reference: string
  discordId: string
  days: number
  amountCents: number
  donationCents: number
  status: PaymentRequestStatus
  bunqTabId?: number
  shareUrl?: string
  bunqPaymentId?: number
  created: string
  expires: string
  settled?: string
  tabFailed?: string
  tabCancelled?: string
  matchedCents?: number
  matchedBy?: PaymentMatch
}

export type JournalEntry = {
  id: string
  occurred: string
  action: string
  actor: Actor
  subject?: string
  mcUuid?: string
  facts: Record<string, unknown>
}

export type ServiceTable = {
  services: Service[]
  drift: DriftReading
}

export type Host = {
  load1?: number
  cpus?: number
  cpuPercent?: number
  memoryTotalBytes?: number
  memoryAvailableBytes?: number
  diskTotalBytes?: number
  diskUsedBytes?: number
  imagesBytes?: number
  volumesBytes?: number
  unreadable?: string
  dockerDiskUnreadable?: string
  containerLimits: string
}

export type Schedule = {
  backupAt?: string
  backupDays: string[]
  zone: string
  nextBackupAt?: string
  updateAt?: string
  updateDays: string[]
  nextUpdateAt?: string
}

export type Backup = {
  name: string
  bytes: number
  human: string
  modified: string
  partial: boolean
  restoresInto?: string
}

export type ServicePlugins = {
  service: string
  loader: string
  gameVersion: string
  mounted: boolean
  plugins: ServicePlugin[]
}

export type PluginSearch = {
  service: string
  loader: string
  gameVersion: string
  query: string
  hits: PluginHit[]
}

export type Available = {
  resolvedAt: string
  seasonTag?: string
  seasonPrerelease: boolean
  hasWork: boolean
  hasFailures: boolean
  changes: AvailableChange[]
  unclaimed: Unclaimed[]
  notes: string[]
}

export type Action = {
  kind: string
  occurred: string
  extent: string
  actorKind: ActorKind
  actorId: string
}

export type RestoreAsked = {
  id: number
  kind: UpdateKind
  archive: string
}

export type RemovalAsked = {
  id: number
  kind: UpdateKind
  artifact: string
}

export type AlertType = "service" | "backup" | "disk" | "memory" | "drift" | "run" | "payment" | "bot"

export type AlertChannel = "push" | "discord"

export type Topic =
  | "RUNS"
  | "REQUESTS"
  | "JOURNAL"
  | "PEOPLE"
  | "SEASON"
  | "GAMES"
  | "SETTINGS"
  | "SERVICES"
  | "HOST"
  | "METRICS"
  | "ALERTS"
  | "TOPOLOGY"

export type SecurityKey = {
  id: string
  label: string
  registeredAt: string
  lastUsedAt?: string
  transports?: string[]
  backedUp?: boolean
}

export type AlertLevel = "ok" | "warn" | "down"

export type Alert = {
  type: AlertType
  level: AlertLevel
  subject: string
  title: string
  detail: string
  path: string
}

export type RecentAlert = {
  id: number
  raised: string
  raisedBy: string
  type: AlertType
  level: AlertLevel
  subject: string
  title: string
  detail: string
  path: string
}

export type MetricPoint = {
  at: string
  value: number
  resolution: Resolution
}

export type UpdateKind =
  | "UPDATE"
  | "RESTART"
  | "BACKUP"
  | "DOWN"
  | "START"
  | "RESTORE"
  | "RECREATE"
  | "DEPLOY"
  | "REMOVE_PLUGIN"

export type UpdateStatus = "PENDING" | "RUNNING" | "DONE" | "FAILED" | "CANCELLED"

export type ActorKind = "PERSON" | "STEWARD" | "HOST"

export type Report = {
  stage: ReportStage
  services: ReportLine[]
  notes: string[]
}

export type SeasonPhase = "PRE_LAUNCH" | "PRE_EVENT" | "START_EVENT" | "SMP" | "MAINTENANCE"

export type SmpMilestone = {
  key: string
  state: string
  unlocked?: string
  objectives: SmpObjective[]
}

export type InboxStatus = "PENDING" | "RUNNING" | "DONE" | "REFUSED" | "FAILED" | "EXPIRED" | "CANCELLED"

export type Announcement = {
  id: string
  language: string
  text: string
  actorKind: ActorKind
  actorId: string
  requested: string
  status: InboxStatus
  result?: string
}

export type SettleOutcome = "BOOKED" | "NOT_OPEN" | "UNKNOWN"

export type AccessSource = "PURCHASE" | "ADMIN"

export type PaymentRequestStatus = "OPEN" | "PAID" | "EXPIRED" | "CANCELLED" | "SUPERSEDED"

export type PaymentMatch = "TAB" | "REFERENCE" | "MANUAL"

export type Actor = {
  kind: ActorKind
  person?: string
}

export type Service = {
  service: string
  containerId: string
  image?: string
  state: string
  status?: string
  hasConsole: boolean
  drift: ImageState
  players?: number
  roster?: Connected[]
  standby?: boolean
  hold?: Hold
  health?: string
  startedAt?: string
  memoryBytes?: number
  memoryLimitBytes?: number
  cpuPercent?: number
  digests?: string[]
  hasPlugins?: boolean
  logCapacity?: number
  diskBytes?: number
  diskMeasuredAt?: string
}

export type DriftReading = {
  checkedAt: string
  reached: boolean
  unverifiable: string[]
  reason?: string
  message?: string
}

export type ServicePlugin = {
  name: string
  group: PluginGroup
  rank?: number
  running: boolean
  removable: boolean
  filePrefix?: string
  fileName?: string
  version?: string
  release?: string
  dataFolder?: string
  artifact?: string
  projectId?: string
  added?: string
  addedBy?: string
  iconUrl?: string
  pageUrl?: string
}

export type PluginHit = {
  projectId: string
  slug: string
  title: string
  description?: string
  iconUrl?: string
  pageUrl: string
  downloads: number
  added: boolean
  fixed: boolean
}

export type AvailableChange = {
  service?: string
  artifact: string
  status: string
  work: boolean
  failure: boolean
  held: boolean
  installed?: string
  version?: string
  fileName?: string
  note?: string
}

export type Unclaimed = {
  service: string
  fileName: string
}

export type Resolution = "RAW" | "HOUR"

export type ReportStage =
  | "RESOLVING"
  | "PLANNED"
  | "COUNTDOWN"
  | "STOPPING"
  | "BACKING_UP"
  | "INSTALLING"
  | "STARTING"
  | "VERIFYING"
  | "DONE"
  | "NOTHING_TO_DO"
  | "FAILED"
  | "CANCELLED"

export type ReportLine = {
  service: string
  state: LineState
  changes: ReportChange[]
  detail?: string
}

export type SmpObjective = {
  key: string
  type: string
  amount: number
  target: number
  completed: boolean
  completedAt?: string
}

export type ImageState = "OUTDATED" | "UP_TO_DATE" | "LOCAL" | "UNKNOWN"

export type Connected = {
  uuid: string
  name: string
}

export type Hold = {
  since: string
}

export type PluginGroup = "nordtal" | "preinstalled" | "added"

export type LineState = "UNCHANGED" | "PLANNED" | "STOPPED" | "INSTALLED" | "SAVED" | "STARTING" | "HEALTHY" | "FAILED"

export type ReportChange = {
  artefact: string
  from?: string
  to: string
  state: ChangeState
}

export type ChangeState = "MOVING" | "UNSUPPORTED"
