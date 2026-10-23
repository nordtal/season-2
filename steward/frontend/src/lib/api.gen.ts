/** Steward's API as its Java records declare it; written by `./gradlew :steward:generateApiTypes`. */
import type { MessageRef } from "@/lib/texts"

export type AlertPreferences = Record<AlertType, Record<AlertChannel, boolean>>

export type MessageExamples = Record<string, Record<string, string>>

export type LiveEvent = {
  topic: Topic
  version: string
}

export type GuildList = {
  available: boolean
  reason?: MessageRef
  entries: GuildEntry[]
}

export type Me = {
  signedIn: boolean
  id?: string
  name?: string
  csrf?: string
  signedInAt?: string
  expiresAt?: string
  signInUnavailable?: string
  keys?: SecurityKey[]
  authenticator?: Authenticator
  authenticatorOffered: boolean
  verified?: boolean
  verifiedAt?: string
  relyingPartyId?: string
  stepUpUntil?: string
  stepUpHolds: StepUpHold[]
  discordAvatarUrl?: string
}

export type WebPushPublicKey = {
  publicKey: string
}

export type PushDevice = {
  endpoint: string
  system?: string
  browser?: string
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
  reason?: MessageRef
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
  stage?: ReportStage
  steps: RunStep[]
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

export type PlayerInventories = {
  linked: boolean
  online?: string
  states: InventoryState[]
}

export type StackRemoval = {
  server: string
  mode: StateMode
  container: StateContainer
  slot: number
  item: string
  amount: number
}

export type CommandRun = {
  id: string
  status: InboxStatus
  result?: MessageRef
  reason?: string
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
  aura: number
  spins: number
  online: boolean
  lastSeen?: string
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
  tabRefusal?: TabRefusal
  tabFailed?: string
  tabCancelled?: string
  matchedCents?: number
  matchedBy?: PaymentMatch
}

export type JournalEntry = {
  id: string
  occurred: string
  action: JournalAction
  actor: Actor
  subject?: string
  mcUuid?: string
  line: MessageRef
}

export type KeyRegistered = {
  label: string
  userVerified: boolean
  backedUp: boolean
}

export type KeyHeld = {
  label: string
  userVerified: boolean
}

export type KeyRenamed = {
  label: string
}

export type KeyRemoved = {
  removed: string
  left: number
}

export type AuthenticatorEnrolment = {
  secret: string
  uri: string
  qr: QrCode
}

export type AdminGranted = {
  outcome: GrantOutcome
}

export type AdminRevoked = {
  outcome: RevokeOutcome
  removed: string[]
}

export type Exempted = {
  outcome: ExemptionOutcome
}

export type PageSettings = {
  minecraftHeadBaseUrl: string
}

export type GameData = {
  version?: string
  datapacks: string[]
  registries: Record<string, GameEntry[]>
  tags: Record<string, GameTag[]>
  icons?: GameIcons
  worlds: Record<string, string[]>
}

export type ConsoleTree = {
  nodes: CommandNode[]
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
  offsite: boolean
  inBackup: boolean
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
  steps: PlannedStep[]
}

export type Action = {
  kind: string
  occurred: string
  label: MessageRef
  extent: MessageRef
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

export type PluginFiles = {
  service: string
  mounted: boolean
  files: PluginFile[]
}

export type PluginFileView = {
  file: PluginFile
  text: string
  revision: string
  entries?: ConfigEntry[]
}

export type PluginFileSaved = {
  file: PluginFile
  revision: string
  reload: ReloadOutcome
}

export type PluginFileEdit = {
  text?: string
  revision: string
  changes?: Record<string, unknown>
}

export type ConfigLocation = {
  service: string
  name: string
  path: string
  label: string
  live: boolean
  problem?: string
  readable: boolean
  writable: boolean
}

export type ConfigDocument = {
  service: string
  name: string
  path: string
  label: string
  live: boolean
  problem?: string
  readable: boolean
  writable: boolean
  revision: string
  entries: ConfigEntry[]
  reload?: ReloadOutcome
}

export type PluginDescriptor = {
  service: string
  id: string
  editors: Record<string, string>
}

export type MessageTexts = {
  writable: boolean
  texts: MessageText[]
  languages: string[]
  colours: Record<string, Record<string, string>>
  places: Record<string, MessageSurface>
}

export type MessageSaveResult = {
  texts: MessageTexts
  warnings: Warning[]
  reload: ReloadOutcome
}

export type MessageFallback = {
  bundle: string
  key: string
  language: string
  reason: MessageFallbackReason
  override: string[]
  original?: string[]
  packaged: string[]
  problems: MessageRef[]
}

export type MessageSyntax = {
  tones: Record<string, string>
  kinds: Record<string, string[]>
}

export type DocumentList = {
  writable: boolean
  documents: DocumentView[]
  languages: string[]
  sharedParts: SharedPartView[]
}

export type DocumentDraft = {
  declaration: DocumentDeclaration
  document: Document
  language: string
}

export type DocumentDrawing = {
  discord?: Record<string, unknown>
  menu?: MenuDrawing
  game?: string[]
  components: number
  most: number
  problems: DocumentProblem[]
}

export type Uploaded = {
  id: number
  address: string
}

export type DocumentVersion = {
  id: number
  document?: Document
  actor: Actor
  saved: string
}

export type SharedPartChange = {
  declaration: DocumentDeclaration
  game: boolean
  before: Document
  after: Document
  problems: string[]
}

export type MessageProblem = {
  error: boolean
  text: MessageRef
}

export type PluginAdded = {
  service: string
  artifact: string
  filePrefix: string
  fileName: string
  version: string
}

export type ConsoleSent = {
  sent: string
  where: string
}

export type NetworkMap = {
  services: NetworkBox[]
}

export type AlertType = "service" | "backup" | "disk" | "memory" | "drift" | "pack" | "run" | "payment" | "bot"

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
  | "GAME_DATA"
  | "COMMANDS"

export type GuildEntry = {
  id: string
  name: string
  type: number
}

export type SecurityKey = {
  id: string
  label: string
  registeredAt: string
  lastUsedAt?: string
  transports?: string[]
  backedUp?: boolean
}

export type Authenticator = {
  addedAt: string
  lastUsedAt?: string
}

export type StepUpHold = {
  hold: StepUpLength
  minutes: number
}

export type AlertLevel = "ok" | "warn" | "down"

export type Alert = {
  type: AlertType
  level: AlertLevel
  subject: string
  title: MessageRef
  detail: MessageRef[]
  measured: Measure[]
  path: string
}

export type RecentAlert = {
  id: string
  raised: string
  raisedBy: string
  type: AlertType
  level: AlertLevel
  subject: string
  title: MessageRef
  detail: MessageRef[]
  measured: Measure[]
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
  | "INSTALL"

export type UpdateStatus = "PENDING" | "RUNNING" | "DONE" | "FAILED" | "CANCELLED"

export type ActorKind = "PERSON" | "STEWARD" | "HOST" | "BOT"

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

export type RunStep = {
  seq: number
  step: StepKind
  service?: string
  started?: string
  finished?: string
  outcome?: StepOutcome
  reason?: StepReason
  lines: MessageRef[]
  changes: RunChange[]
  saved: RunArchive[]
  pruned: RunArchive[]
}

export type SeasonPhase = "PRE_LAUNCH" | "PRE_EVENT" | "START_EVENT" | "SMP" | "MAINTENANCE"

export type SmpMilestone = {
  key: string
  state: string
  unlocked?: string
  objectives: SmpObjective[]
}

export type InventoryState = {
  server: string
  mode: StateMode
  status: StateStatus
  level: number
  progress: number
  saved: string
  stacks: InventoryStack[]
}

export type StateMode = "SURVIVAL" | "CREATIVE"

export type StateContainer = "INVENTORY" | "ENDER_CHEST"

export type InboxStatus = "PENDING" | "RUNNING" | "DONE" | "REFUSED" | "FAILED" | "EXPIRED" | "CANCELLED"

export type SettleOutcome = "BOOKED" | "NOT_OPEN" | "UNKNOWN"

export type AccessSource = "PURCHASE" | "ADMIN"

export type PaymentRequestStatus = "OPEN" | "PAID" | "EXPIRED" | "CANCELLED" | "SUPERSEDED"

export type TabRefusal =
  | "NOT_CONFIGURED"
  | "UNREACHABLE"
  | "BAD_REQUEST"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "NOT_FOUND"
  | "TOO_MANY_REQUESTS"
  | "BUNQ_ERROR"
  | "UNKNOWN"

export type PaymentMatch = "TAB" | "REFERENCE" | "MANUAL"

export type JournalAction =
  | "ADMIN_ROOT"
  | "GRANT_ADMIN"
  | "REVOKE_ADMIN"
  | "ENFORCE_PACK"
  | "EXEMPT_PACK"
  | "GRANT_ACCESS"
  | "REVOKE_ACCESS"
  | "SET_PLAYTIME"
  | "LINK"
  | "UNLINK"
  | "SETTLE"
  | "SET_PHASE"
  | "SET_LAUNCH"
  | "SET_SMP_START"
  | "REGISTER_KEY"
  | "HELD_KEY"
  | "RENAME_KEY"
  | "REMOVE_KEY"
  | "ADD_AUTHENTICATOR"
  | "USED_AUTHENTICATOR"
  | "REMOVE_AUTHENTICATOR"
  | "FORGET_FACTORS"
  | "WEB_PUSH_SUBSCRIBE"
  | "WEB_PUSH_UNSUBSCRIBE"
  | "WEB_PUSH_TEST"
  | "SET_ALERT_PREFERENCE"
  | "CONSOLE"
  | "SAVE_SETTINGS"
  | "SAVE_MESSAGES"
  | "ADD_PLUGIN"
  | "REMOVE_PLUGIN"
  | "REQUEST_RUN"
  | "CANCEL_RUN"
  | "DOWNLOAD_BACKUP"
  | "ANNOUNCE"
  | "COMPLETE_OBJECTIVE"
  | "UNLOCK_MILESTONE"
  | "START_GAME"
  | "UNANCHORED_GRANT"
  | "LINK_CODES_EXHAUSTED"
  | "SET_AURA"
  | "SET_SPINS"
  | "REMOVE_STACK"
  | "SERVICE_DOWN"
  | "BACKUP_STALE"
  | "DISK_FULL"
  | "MEMORY_FULL"
  | "IMAGE_DRIFT"
  | "PACK_DEAD"
  | "RUN_FAILED"
  | "PAYMENT_NEEDS_LOOK"
  | "LINK_REFUSED"
  | "LINK_FAILED"
  | "PURCHASE_FAILED"
  | "DM_NOT_DELIVERED"
  | "ROLE_NOT_CHANGED"
  | "ROLE_AMBIGUOUS"
  | "ROLE_NOT_CREATED"
  | "LOCK_NOT_KEPT"
  | "LOCK_WITHOUT_CHANNEL"

export type Actor = {
  kind: ActorKind
  person?: string
}

export type QrCode = {
  size: number
  path: string
}

export type GrantOutcome = "GRANTED" | "ACTOR_NOT_ADMIN" | "ALREADY_ADMIN" | "NOT_A_MEMBER" | "RATE_LIMITED"

export type RevokeOutcome = "REVOKED" | "ACTOR_NOT_ADMIN" | "SELF" | "NOT_BELOW"

export type ExemptionOutcome = "CHANGED" | "UNCHANGED" | "ACTOR_NOT_ADMIN" | "UNKNOWN"

export type GameEntry = {
  id: string
  key?: string
  text?: string
  parent?: string
  description?: string
  frame?: string
  icon?: string
  hidden?: boolean
  subject?: string
}

export type GameTag = {
  id: string
  values: string[]
}

export type GameIcons = {
  url: string
  columns: number
  slots: Record<string, number>
}

export type CommandNode = {
  name: string
  argument?: boolean
  executes?: boolean
  children?: number[]
  redirect?: number
}

export type Service = {
  service: string
  containerId: string
  image?: string
  state: string
  status?: string
  hasConsole: boolean
  drift: ImageState
  localBuild?: LocalBuild
  players?: number
  roster?: Connected[]
  standby?: boolean
  hold?: Hold
  oneShot?: boolean
  lastRun?: LastRun
  health?: string
  alert?: AlertLevel
  startedAt?: string
  restarts?: number
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
  addedBy?: Actor
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
  reason?: MessageRef
}

export type PlannedStep = {
  step: string
  service?: string
  outcome?: string
  reason?: string
  lines: MessageRef[]
}

export type PluginFile = {
  service: string
  plugin: string
  name?: string
  iconUrl?: string
  path: string
  size: number
  readable: boolean
  writable: boolean
}

export type ConfigEntry = {
  path: string
  key: string
  label: string
  explanation: string
  noExplanationNeeded: boolean
  kind: ConfigShape
  type: SettingType
  editable: boolean
  secret: boolean
  environmentOverridden: boolean
  filled: boolean
  value?: string
  items?: string[]
  template?: ConfigEntry[]
  sections?: ConfigEntry[][]
  choices?: ConfigChoices
  protectedEntry?: ConfigProtectedEntry
  refers?: ConfigReference
  appliesWhen?: ConfigCondition
}

export type ReloadOutcome = {
  status: ReloadStatus
  message: MessageRef
}

export type MessageText = {
  entry: MessageEntry
  path: string
  services: string[]
  previews: Record<string, MessagePreviewTarget>
}

export type MessageSurface = "GAME" | "DISCORD" | "STEWARD"

export type Warning = {
  bundle: string
  key: string
  language: string
  text: MessageRef
}

export type MessageFallbackReason = "STALE" | "REFUSED"

export type DocumentView = {
  declaration: DocumentDeclaration
  named: Record<string, DocumentValue>
  services: string[]
  game: boolean
  packaged: Document
  hash: string
  written?: Document
  outdated: boolean
  adapted: DocumentAdaptation[]
}

export type SharedPartView = {
  name: string
  part: DocumentPart
  documents: string[]
}

export type DocumentDeclaration = {
  namespace: string
  name: string
  area: string
  shape: DocumentShape
  values: Record<string, DocumentValue>
  slots: DocumentSlot[]
}

export type Document = {
  parts: DocumentPart[]
}

export type MenuDrawing = {
  title: string
  parts: MenuPart[]
}

export type DocumentProblem = {
  error: boolean
  text: string
}

export type NetworkBox = {
  name: string
  section: string
  entry: boolean
  reaches: string[]
  storesIn: string[]
}

export type StepUpLength = "QUARTER_HOUR" | "ONE_HOUR" | "SIX_HOURS"

export type Measure = {
  gauge: Gauge
  subject: string
  value?: number
  limit?: number
  state?: string
}

export type Resolution = "RAW" | "HOUR"

export type StepKind =
  | "RUNTIME"
  | "SCOPE"
  | "RESOLVE"
  | "HAND_OVER"
  | "ROOM_CHECK"
  | "PRUNE"
  | "DUMP_DATABASE"
  | "STANDBY_UP"
  | "COUNTDOWN"
  | "PLAYERS_MOVED"
  | "STOP"
  | "SAVE_VOLUME"
  | "HOLD"
  | "UNHOLD"
  | "MIGRATE"
  | "INSTALL"
  | "RESTORE"
  | "START"
  | "VERIFY"
  | "PLAYERS_BACK"
  | "STANDBY_DOWN"
  | "CLEANUP"
  | "OFFSITE_COPY"

export type StepOutcome = "DONE" | "SKIPPED" | "WARNING" | "FAILED"

export type StepReason =
  | "UNREACHABLE"
  | "UNEXPECTED"
  | "CANCELLED"
  | "INTERRUPTED"
  | "NO_CONTAINER"
  | "REFUSED"
  | "NOT_HEALTHY"
  | "UNVERIFIED_STOP"
  | "PLAYERS_STAYED"
  | "PLAYERS_UNKNOWN"
  | "NO_ROOM"
  | "TOOL_FAILED"
  | "NO_REPOSITORY"
  | "REFUSED_LOCAL_BUILD"
  | "RELEASED_MEANWHILE"
  | "OLDER_RELEASE"
  | "HELD"
  | "NOT_IN_SCOPE"
  | "NOTHING_HELD"
  | "UNNAMED"
  | "NEVER_DOWN"
  | "SELF"
  | "UNKNOWN"
  | "NOT_A_VOLUME"
  | "NO_VOLUMES"
  | "NOTHING_TO_REMOVE"
  | "NOT_SAVED"
  | "LEFT_DOWN"
  | "SOURCE_FAILED"
  | "NOT_IN_RELEASE"
  | "UNCLAIMED"
  | "UNVERIFIABLE"
  | "LOCAL_BUILD"
  | "FOREIGN_NEWER"
  | "VELOCITY_AHEAD"

export type RunChange = {
  artefact: string
  from?: string
  to?: string
  image?: ChangeImage
}

export type RunArchive = {
  volume: string
  file: string
  bytes: number
  tookMillis: number
  stopVerified: boolean
  rule?: PruneRule
}

export type SmpObjective = {
  key: string
  type: string
  amount: number
  target: number
  completed: boolean
  completedAt?: string
}

export type StateStatus = "STORED" | "AWAY" | "HELD"

export type InventoryStack = {
  container: StateContainer
  slot: number
  item: string
  amount: number
}

export type ImageState = "OUTDATED" | "UP_TO_DATE" | "LOCAL" | "UNKNOWN"

export type LocalBuild = {
  image?: string
  jars: string[]
}

export type Connected = {
  uuid: string
  name: string
}

export type Hold = {
  since: string
}

export type LastRun = {
  startedAt?: string
  finishedAt: string
  exitCode: number
}

export type PluginGroup = "nordtal" | "preinstalled" | "added"

export type ConfigShape = "SCALAR" | "LIST" | "MAP" | "SECTIONS"

export type SettingType = "STRING" | "INTEGER" | "DECIMAL" | "BOOLEAN"

export type ConfigChoices = {
  values: string[]
  strict: boolean
  names?: Record<string, string>
  icons?: Record<string, string>
}

export type ConfigProtectedEntry = {
  field: string
  value: string
}

export type ConfigReference = {
  to: ReferenceKind
  dependsOn?: string
  optional: boolean
  except?: string[]
}

export type ConfigCondition = {
  key: string
  values: string[]
}

export type ReloadStatus = "APPLIED" | "RESTART_REQUIRED"

export type MessageEntry = {
  key: string
  bundle: string
  texts: Record<string, string[]>
  overrides: Record<string, string[]>
  inBundle: boolean
  name?: string
  description?: string
  args: MessageArg[]
  section: (string | null)[]
  format?: string
  shown: string[]
  limit: number
}

export type MessagePreviewTarget = "GAME" | "DISCORD"

export type DocumentValue = {
  kind: string
  example: string
  takes?: string[]
}

export type DocumentAdaptation = {
  change: DocumentChange
  subject: string
}

export type DocumentPart = {
  type: DocumentPartType
  when?: string
  accent?: string
  parts?: DocumentPart[]
  lines?: DocumentLine[]
  button?: DocumentItem
  thumbnail?: string
  media?: string[]
  items?: DocumentItem[]
  slot?: string
  shared?: string
}

export type DocumentShape =
  | "MESSAGE"
  | "MODAL"
  | "PLAIN"
  | "CHANNEL_NAME"
  | "MENU"
  | "CHAT"
  | "TAB_LIST"
  | "KICK_SCREEN"
  | "TITLE"
  | "BOSS_BAR"
  | "SERVER_LIST"
  | "ACTION_BAR"
  | "HOLOGRAM"

export type DocumentSlot = {
  id: string
  type: DocumentSlotType
  required: boolean
  values: Record<string, DocumentValue>
}

export type MenuPart = {
  slot: string
  type: DocumentPartType
  lines: string[]
}

export type Gauge =
  | "DISK_PERCENT"
  | "MEMORY_PERCENT"
  | "BACKUP_AGE_HOURS"
  | "CONTAINER_STATE"
  | "HEALTH"
  | "RESTARTS"
  | "EXIT_CODE"
  | "OLDER_IMAGE"
  | "REGISTRY"
  | "PACK"

export type ChangeImage = "PULLED" | "KEPT"

export type PruneRule = "RETENTION" | "OVER_BUDGET"

export type ReferenceKind =
  | "ITEM"
  | "BLOCK"
  | "ENTITY_TYPE"
  | "ADVANCEMENT"
  | "STATISTIC"
  | "SUBJECT"
  | "ENCHANTMENT"
  | "BIOME"
  | "MOB_EFFECT"
  | "SOUND_EVENT"
  | "DAMAGE_TYPE"
  | "COLOUR"
  | "DISCORD_CHANNEL"
  | "DISCORD_USER"
  | "WORLD"

export type MessageArg = {
  name: string
  kind?: string
  type?: string
  global: boolean
  example?: string
  action: boolean
  exampleWords: Record<string, string>
}

export type DocumentChange = "SLOT_ADDED" | "SLOT_REMOVED" | "SET_ASIDE"

export type DocumentPartType =
  | "CONTAINER"
  | "TEXT"
  | "SECTION"
  | "SEPARATOR"
  | "GALLERY"
  | "FILE"
  | "ROW"
  | "LANGUAGES"
  | "ITEM"
  | "LABEL"

export type DocumentLine = {
  text: Record<string, string | string[]>
  style?: DocumentLineStyle
  when?: string
  list?: string
}

export type DocumentItem = {
  slot?: string
  link?: string
  label?: Record<string, string | string[]>
  placeholder?: Record<string, string | string[]>
  option?: Record<string, string | string[]>
  when?: string
}

export type DocumentSlotType = "BUTTON" | "SELECT" | "LIST" | "LANGUAGES" | "INPUT" | "ITEM" | "LABEL"

export type DocumentLineStyle = "TITLE" | "HEADING"
