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

export type Resolution = "RAW" | "HOUR"

export type ImageState = "OUTDATED" | "UP_TO_DATE" | "LOCAL" | "UNKNOWN"

export type Connected = {
  uuid: string
  name: string
}

export type Hold = {
  since: string
}

export type PluginGroup = "nordtal" | "preinstalled" | "added"
