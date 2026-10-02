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
