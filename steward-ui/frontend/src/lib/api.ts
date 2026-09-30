/**
 * The one door to the backend: every request goes through here, and nothing else calls `fetch`.
 *
 * A write carries the CSRF token, a 401 means the session is gone, and an error names which service is down.
 */

/** Which of the three services answered badly: a stopped worker hides the stack, a stopped deployer freezes it. */
export type Where = "steward-ui" | "steward-worker" | "steward-deployer"

export class ApiError extends Error {
  readonly status: number
  readonly where: Where
  readonly detail: string
  /** The backend's machine-readable name for this refusal, matched instead of its English sentence. */
  readonly code: string

  constructor(status: number, message: string, where: Where, detail = "", code = "") {
    super(message)
    this.name = "ApiError"
    this.status = status
    this.where = where
    this.detail = detail
    this.code = code
  }

  /** The session ran out or was never there; the shell turns this into the sign-in page. */
  get isSignedOut(): boolean {
    return this.status === 401
  }

  /** Signed in, and one key registration short of being allowed in, so not {@link isSignedOut}. */
  get needsASecurityKey(): boolean {
    return this.status === 403 && this.code === "SECOND_FACTOR_MISSING"
  }

  /**
   * Signed in with a key that has not been held recently enough for this.
   *
   * Told apart from {@link needsASecurityKey}, since one needs a setup page and the other a tap.
   */
  get needsTheKeyAgain(): boolean {
    return this.status === 403 && this.code === "SECOND_FACTOR_REQUIRED"
  }
}

/**
 * The one place a refused request is turned back into a working one, installed by the shell.
 *
 * A handler resolves once the key is held and rejects otherwise; with none installed, a 403 stays a 403.
 */
type StepUp = () => Promise<unknown>

let stepUp: StepUp | null = null

export function onSecondFactorRequired(handler: StepUp | null): void {
  stepUp = handler
}

/** Whether a refusal of this request may be recovered by holding the key; never for the ceremony's own two `POST`s. */
function mayStepUp(path: string): boolean {
  return stepUp !== null && !path.startsWith("/auth/")
}

/**
 * The CSRF token of this session, handed out by `/api/me` and sent back on every write.
 *
 * A module variable rather than the query cache, so a mutation can read it synchronously.
 */
let csrfToken: string | null = null

export function rememberCsrf(token: string | null | undefined): void {
  csrfToken = token ?? null
}

export function currentCsrf(): string | null {
  return csrfToken
}

type Options = {
  method?: "GET" | "POST" | "PUT" | "DELETE"
  body?: unknown
  signal?: AbortSignal
}

export async function api<T>(path: string, options: Options = {}): Promise<T> {
  try {
    return await send<T>(path, options)
  } catch (refusal) {
    /**
     * One tap, not two: a request refused for want of a fresh key is retried once after the key is held.
     *
     * A second refusal is something else, and retrying again would hide it behind a loop.
     */
    if (refusal instanceof ApiError && refusal.needsTheKeyAgain && mayStepUp(path)) {
      await stepUp!()
      return await send<T>(path, options)
    }
    throw refusal
  }
}

async function send<T>(path: string, options: Options = {}): Promise<T> {
  const method = options.method ?? "GET"
  const headers: Record<string, string> = {}
  if (options.body !== undefined) headers["Content-Type"] = "application/json"
  if (method !== "GET" && csrfToken) headers["X-Steward-CSRF"] = csrfToken

  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: "same-origin",
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (cause) {
    /** The request never arrived: this interface, a proxy or the browser is offline, never the worker. */
    throw new ApiError(0, "The interface cannot be reached.", "steward-ui", String(cause))
  }

  if (response.status === 204) {
    return shapedAs(undefined, (value): value is T => value === undefined, path)
  }

  const text = await response.text()
  const parsed = text ? safeJson(text) : null

  if (!response.ok) {
    const body: Record<string, unknown> | null = isRecord(parsed) ? parsed : null
    /** The backend names whichever service did not answer, which tells a silent daemon from a throwing service. */
    const where: Where =
      body?.where === "steward-worker" || body?.where === "steward-deployer" ? body.where : "steward-ui"
    const message = (body && messageOf(body)) ?? `${response.status} ${response.statusText}`
    const detail = body ? (typeof body.detail === "string" ? body.detail : "") : text
    const code = typeof body?.code === "string" ? body.code : ""
    throw new ApiError(response.status, message, where, detail, code)
  }

  return shapedAs(parsed, (value): value is T => value !== undefined, path)
}

/**
 * Narrows an untyped value to `T` wherever one enters typed code, by a guard rather than a cast.
 *
 * What `guard` proves is up to the caller; `send` only knows that a 204 is empty.
 */
export function shapedAs<T>(value: unknown, guard: (value: unknown) => value is T, context: string): T {
  if (!guard(value)) {
    throw new Error(`${context}: not the shape this interface expects`)
  }
  return value
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object"
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

/** Javalin's own error bodies use `title`, ours use `error`; both are one sentence. */
function messageOf(body: Record<string, unknown>): string | null {
  for (const key of ["error", "title", "message"]) {
    const value = body[key]
    if (typeof value === "string" && value.trim()) return value
  }
  return null
}

/** What the endpoints answer, written out since the backend builds these maps by hand. */

/** One registered security key, as `/api/me` lists it. */
export type SecurityKey = {
  /** The credential id, base64url, which names this key when it is renamed or removed; not a secret. */
  id: string
  label: string
  registeredAt: string
  lastUsedAt?: string
  transports?: string[]
  /**
   * Whether this key is synced somewhere, as an iCloud passkey is and a YubiKey is not.
   *
   * Absent when the authenticator did not say, which is not "no".
   */
  backedUp?: boolean
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
  /** The security keys of this account, oldest first; absent rather than empty when nobody is signed in. */
  keys?: SecurityKey[]
  /** Whether a key has been held in this session. */
  verified?: boolean
  verifiedAt?: string
  /** The domain keys are registered against, for the page to name rather than guess. */
  relyingPartyId?: string
  /** How many minutes one touch of the key covers, as this service enforces it. */
  stepUpMinutes?: number
  /**
   * The picture Discord holds for this account, when the access list carries one.
   *
   * Absent is the ordinary case; the island then draws initials.
   */
  discordAvatarUrl?: string
}

/** `GET /api/web-push/public-key`'s body: the VAPID public key, base64url. */
export type WebPushPublicKey = {
  publicKey: string
}

/**
 * What a notification can be about, spelled as `AlertType`'s own keys.
 *
 * A union, so a server type added without a label here is a type error.
 */
export type AlertTypeKey = "service" | "backup" | "disk" | "memory" | "drift"

/** `GET /api/web-push/preferences`: every type, with this account's effective answer. */
export type WebPushPreferences = Record<AlertTypeKey, boolean>

/**
 * One row of `GET /api/web-push/devices`, a browser this account has subscribed.
 *
 * `device` is absent without a User-Agent; `endpoint` is how this browser finds its own row.
 */
export type PushDevice = {
  endpoint: string
  device?: string
  subscribedAt: string
  lastSentAt?: string
}

/** Docker's own words, passed through: `state` is the container state, `status` its sentence. */
export type Service = {
  service: string
  containerId: string
  image: string
  state: string
  status: string
  hasConsole: boolean
  drift: string
  health?: string
  startedAt?: string
  memoryBytes?: number
  memoryLimitBytes?: number
  cpuPercent?: number
  /**
   * How many people are connected, on the four services that can say.
   *
   * Absent is not zero, so never write `players ?? 0`.
   */
  players?: number
  /** Who is connected: the whole network on `proxy`, its own on a backend. Absent is not empty. */
  roster?: Array<{ uuid?: string; name?: string }>
  /**
   * Set when somebody stopped this service on purpose and it must stay stopped.
   *
   * Absent is not false: a stopped container without one fell over, which only `service_hold` can say.
   */
  hold?: { since: string }
  /**
   * Set on the `standby` profile's services, which are meant to be stopped.
   *
   * Absent is not false; without it a healthy stack would show two faults.
   */
  standby?: true
  unreadable?: string
  /** Only on the single-service endpoint. */
  digests?: string[]
  /** Only on the single-service endpoint: one of the four with a plugins folder. */
  hasPlugins?: boolean
  /** Lines the console can fill, Docker plus the archived runs, capped at 10000. */
  logCapacity?: number
  /** What the service's volume takes on disk; absent for a service without one. */
  diskBytes?: number
  diskMeasuredAt?: string
}

/**
 * The service table, with the age of the drift comparison beside it.
 *
 * `checkedAt` is how old the image comparison is, not the rest of the row.
 */
export type ServiceTable = {
  services: Service[]
  drift: {
    checkedAt: string | null
    reached: boolean
    unverifiable: string[]
    reason?: string
    message?: string
  }
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

/**
 * The worker's nightly clock, read from the worker rather than guessed here.
 *
 * `nextBackupAt` is an ISO instant; `backupAt` and `zone` are for saying it. All three are null or "off" when unset.
 */
export type Schedule = {
  backupAt: string | null
  zone: string
  nextBackupAt: string | null
  /** The optional update clock: a null `updateAt` is no schedule, the default. */
  updateAt?: string | null
  updateDays?: string[]
  nextUpdateAt?: string | null
}

export type Backup = {
  name: string
  bytes: number
  human: string
  modified: string
  partial: boolean
}

/** One artefact inside a service line: a jar, a plugin, the schema. */
export type ReportChange = {
  artefact: string
  /** Absent on a row written before a version was known. */
  from?: string
  to?: string
  /**
   * `MOVING` or `UNSUPPORTED`, see `UpdateReport.Change.State` in `:common`.
   *
   * Optional, since the stored JSON omits `MOVING`; never read an absent `state` as "not moving".
   */
  state?: string
}

/**
 * One service, or one volume in a BACKUP run.
 *
 * `state` is `UpdateReport.State`.
 */
export type ReportLine = {
  service: string
  state: string
  changes: ReportChange[]
  /** Absent rather than null when there is none, since Gson drops nulls. */
  detail?: string
}

/** `update_request.result`, parsed by the backend; mirrors UpdateReport in :common. */
export type Report = {
  /** `UpdateReport.Phase`, from RESOLVING to CANCELLED. */
  stage: string
  services: ReportLine[]
  notes: string[]
}

export type Run = {
  id: number
  kind: string
  status: string
  actorKind: ActorKind
  actorId: string
  /** The services this run is for; empty is the whole network. */
  scope: string[]
  requested: string
  /** When the worker may claim it; never moves. */
  scheduledFor: string
  /** When the servers go down once the countdown started, `"null"` before. */
  countdownEnd: string
  /** The services the run stops, written when its countdown starts. */
  moving: string[]
  started: string
  finished: string
  report?: Report
  savedSomething?: boolean
  resultText?: string
}

/** `GET /api/updates/active`: the one open run, or `null`. */
export type ActiveRun = { run: Run | null }

/**
 * One artefact in the resolve, and what a run would do about it.
 *
 * `status` is the worker's `Change.Status`; UNRESOLVED and MOUNT_MISSING are unknown, never "nothing to do".
 */
export type AvailableChange = {
  /** Absent for the resource pack, which belongs to no service. */
  service?: string
  artifact: string
  status: string
  work: boolean
  failure: boolean
  /** Work a run would not do, because another row of the same service could not be checked. */
  held?: boolean
  installed?: string
  /** The version as its publisher states it, for reading and never for comparing. */
  version?: string
  /** The filename, which is the identity of what would be installed. */
  fileName?: string
  note?: string
}

/** `GET /api/updates/available`: what a run would do, without a run. */
export type Available = {
  /** When this reading was taken; it can be hours old, and the page says so. */
  checkedAt: string
  resolvedAt: string
  seasonTag?: string
  seasonPrerelease: boolean
  hasWork: boolean
  hasFailures: boolean
  changes: AvailableChange[]
  unclaimed: { service: string; fileName: string }[]
  notes: string[]
}

/**
 * One plugin on one Minecraft server.
 *
 * `running` is a jar on disk and `removable` a `service_plugin` row; neither implies the other.
 */
export type ServicePlugin = {
  /** The title for an added plugin and any jar Modrinth published; otherwise the jar's filename prefix. */
  name: string
  /** Which list it belongs in: `nordtal` built here, `preinstalled` given by the network, `added` installed here. */
  group?: "nordtal" | "preinstalled" | "added"
  /** A Nordtal plugin's place in its list, before the alphabet. */
  rank?: number
  running: boolean
  removable: boolean
  filePrefix?: string
  fileName?: string
  version?: string
  /**
   * `plugins/<dataFolder>/`, read from the jar's own descriptor.
   *
   * Absent when the worker could not read it, and the removal must then say so.
   */
  dataFolder?: string
  /** An added plugin's Modrinth slug, or a given plugin's artefact id when it is not on disk. */
  artifact?: string
  projectId?: string
  added?: string
  addedBy?: string
  iconUrl?: string
  pageUrl?: string
}

/** `GET /api/services/{name}/plugins`. */
export type ServicePlugins = {
  service: string
  /** `paper` or `velocity`, what the search on this page is filtered to. */
  loader: string
  gameVersion: string
  /** False when this worker cannot see the volume, which is not an empty server. */
  mounted: boolean
  plugins: ServicePlugin[]
}

/** One Modrinth search hit, already filtered to this service's loader and Minecraft version. */
export type PluginHit = {
  projectId: string
  slug: string
  title: string
  description?: string
  /** On `cdn.modrinth.com`, which the browser loads directly. */
  iconUrl?: string
  pageUrl: string
  downloads: number
  /** Already added here. */
  added: boolean
  /** One of the plugins the network gives, so it can be neither added nor removed. */
  fixed: boolean
}

/** `GET /api/services/{name}/plugins/search`. */
export type PluginSearch = {
  service: string
  loader: string
  gameVersion: string
  query: string
  hits: PluginHit[]
}

export type MetricPoint = { at: string; value: number }

export type Metrics = {
  subject: string
  metric: string
  from: string
  points: MetricPoint[]
}

export type Season = {
  phase: string
  launch?: string
  smpStart?: string
}

/**
 * One person the bot knows; an absent field is absent, never null, since Gson omits nulls.
 *
 * `accessUntil` covers revoked grants too, while `accessActive` is the full login predicate.
 */
export type Person = {
  discordId: string
  memberState: string
  donor: boolean
  admin: boolean
  /** Who granted this admin; absent for the root and for everybody who is no admin. */
  adminGrantedBy?: string | null
  adminGrantedAt?: string | null
  /** The admin who let this account play without the resource pack; absent while the pack is enforced. */
  packExemptBy?: string | null
  packExemptAt?: string | null
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
  /**
   * Total online time across the network, in seconds, out of `player_playtime`.
   *
   * Absent, not zero, for somebody never online.
   */
  playtimeSeconds?: number | null
}

export type Payment = {
  id: string
  reference: string
  discordId: string
  days: number
  amountCents: number
  donationCents: number
  status: string
  bunqTabId?: number
  shareUrl?: string
  created: string
  expires: string
  settled?: string
}

export type Grant = {
  id: string
  discordId: string
  validFrom: string
  validUntil: string
  source: string
  paymentRequestId?: string
  revoked?: string
  created: string
}

export type JournalEntry = {
  id: string
  occurred: string
  action: string
  actor?: string
  subject?: string
  mcUuid?: string
  detail?: string
}

/** Who asked for a run or did a journalled thing; `actorId` is a Discord id for a `PERSON` and `""` otherwise. */
export type ActorKind = "PERSON" | "STEWARD" | "HOST"

/** One row of steward-worker's `/api/actions`: a run or an audit line, newest first. */
export type Action = {
  kind: string
  occurred: string
  extent: string
  actorKind: ActorKind
  actorId: string
}

/**
 * One config file under the mount, identified by `path` such as `smp/nordtal-smp/config.yml`.
 *
 * `readable` and `writable` are measured, so the listing can say before it draws.
 */
export type ConfigLocation = {
  service: string
  name: string
  path: string
  readable: boolean
  writable: boolean
  /** Who wrote the file, by the plugins tab's Nordtal set. */
  origin?: "nordtal" | "third-party"
  /** The plugin whose data folder holds it, by name if Nordtal's and by folder otherwise. */
  plugin?: string | null
}

/**
 * The allowed or suggested values of a setting, from its schema.
 *
 * `strict` closes the list and draws a select; otherwise it suggests beside a free-text field.
 */
export type ConfigChoices = {
  values: string[]
  strict: boolean
}

/** The one section of a `SECTIONS` entry a save may never remove, from the schema's `@Protected`. */
export type ConfigProtectedEntry = {
  field: string
  value: string
}

/**
 * One key of a config file, as the form draws it.
 *
 * A secret carries `filled` alone, never `value` or `items`; `inSchema` is false only where a schema omits the key.
 */
export type ConfigEntry = {
  path: string
  key: string
  label: string
  comments: string[]
  /** The schema's short `@Explain` text. Empty when no schema entry covers this key. */
  explanation: string
  /** The schema says this needs no explanation, so draw no text rather than empty text. */
  noExplanationNeeded: boolean
  filled: boolean
  /** Absent when `secret`; a scalar's text, with a block scalar's newlines. */
  value?: string
  /** Absent when `secret`; the entries of a LIST, empty for every other kind. */
  items?: string[]
  /** A sequence of mappings, such as `languages` in `discord-bot/access.yml`, drawn as cards. */
  kind: "SCALAR" | "LIST" | "MAP" | "SECTIONS"
  type: "STRING" | "INTEGER" | "DECIMAL" | "BOOLEAN"
  line: number
  /** False for a nested section, which has no value, and for a list of sections. */
  editable: boolean
  secret: boolean
  /** Whether the schema declares this key; always `true` when the file has no schema. */
  inSchema: boolean
  /**
   * Whether an environment variable overrides this key, so editing the file does not change the service.
   *
   * The field stays editable; absent means the service did not say, not that it is unaffected.
   */
  environmentOverridden?: boolean
  /** The schema's allowed or suggested values, or absent when it names none. */
  choices?: ConfigChoices
  /**
   * For a `SECTIONS` entry: one section's fields in display order, and the blank template "Add" starts from.
   *
   * Absent when the sections have mixed shapes, so the form falls back to raw text.
   */
  template?: ConfigEntry[]
  /** For a `SECTIONS` entry: one field array per existing section, in file order. */
  sections?: ConfigEntry[][]
  /** For a `SECTIONS` entry: the section the worker refuses to remove, so the option can be greyed out. */
  protectedEntry?: ConfigProtectedEntry
}

/** One announcement row, the SMP's or an admin's: `GET /api/announcements`. */
export type Announcement = {
  id: string
  language: string
  text: string
  /** A person for a line written here, `STEWARD` for one a server sent by itself. */
  actorKind: ActorKind
  actorId: string
  requested: string
  status: CommandRun["status"]
  result?: string
}

export type Announcements = { recent: Announcement[] }

/**
 * One role or channel of the guild, as the pickers offer it.
 *
 * `type` is Discord's channel type, absent for a role, so a category groups apart from its channels.
 */
export type GuildEntry = {
  id: string
  name: string
  type: number | null
}

/**
 * What the guild is made of, or why that could not be answered.
 *
 * Never an error: without it the id can still be typed.
 */
export type GuildList = {
  available: boolean
  reason?: string
  entries: GuildEntry[]
}

/**
 * A file steward could not split into keys: a foreign file, or a `.yml` with a mistake in it.
 *
 * Nothing was parsed, so there is no revision and no save.
 */
export type RawConfigDocument = ConfigLocation & {
  raw: true
  reason?: string
  content: string
}

export type ParsedConfigDocument = ConfigLocation & {
  raw?: never
  /**
   * The revision the file was read at, which the save sends back.
   *
   * A write in between makes the save a 409 instead of silently overwriting it.
   */
  revision: string
  header: string[]
  entries: ConfigEntry[]
}

export type ConfigDocument = RawConfigDocument | ParsedConfigDocument

/** What a PUT sends: a string is a scalar, a string array a list, a record array a `SECTIONS` entry. */
/** A value, a list of values, or a list of sections, which may hold lists of sections again. */
export type ConfigChangeValue = string | string[] | { [key: string]: ConfigChangeValue }[]
export type ConfigChanges = Record<string, ConfigChangeValue>

/**
 * One request in the bot's inbox, as `GET /api/access/requests/{id}` answers it.
 *
 * `result` is a flat object of strings the bot writes, with `error` when it failed.
 */
export type AccessRequestRun = {
  id: string
  kind: string
  status: "PENDING" | "RUNNING" | "DONE" | "FAILED" | "EXPIRED"
  result?: Record<string, string | undefined>
}

/**
 * What became of a request.
 *
 * Out of time, PENDING means the target is down and RUNNING that it is stuck, so EXPIRED is a diagnosis.
 */
export type CommandRun = {
  id: string
  name?: string
  status: "PENDING" | "RUNNING" | "DONE" | "FAILED" | "EXPIRED"
  result?: string
}

/** `GET /api/smp/track`: the whole track as the database holds it. */
export type SmpTrack = {
  /** Every milestone the SMP wrote a row for; the order is the file's, which the page applies. */
  milestones: SmpMilestone[]
}

export type SmpMilestone = {
  key: string
  state: "LOCKED" | "ACTIVE" | "UNLOCKED"
  unlocked?: string
  objectives: SmpObjective[]
}

export type SmpObjective = {
  key: string
  type: "HAND_IN" | "STATISTIC" | "ADVANCEMENT"
  amount: number
  target: number
  completed: boolean
  completedAt?: string
}

/** `GET /api/hunger-games/round`: the open round, or nothing. */
export type HungerGamesRound = {
  state?: "REGISTRATION" | "COUNTDOWN" | "RUNNING"
  registered?: number
}

/** steward-deployer: one question and one verb. */

/** Whether the recreate button may be drawn, since an unconfigured deployer is not a broken one. */
export type DeployerState = {
  available: boolean
  /** Present only when `available` is false, and it is the whole explanation. */
  reason?: string
  reachable?: boolean
}

/** One compose operation, while it runs and after it has ended. */
export type DeployerJob = {
  id: string
  kind: string
  services: string[]
  state: "RUNNING" | "DONE" | "FAILED"
  started: string
  finished?: string
  exitCode?: number
  /** compose's own output, in order; only `GET /api/deployer/jobs/{id}` carries it. */
  lines?: string[]
}

/**
 * Where one message bundle lives.
 *
 * `path` is `<service>/<module>`, or `<service>` with an empty `module` for a bundle in the service's own jar.
 */
export type MessageBundleLocation = {
  service: string
  module: string
  path: string
  writable: boolean
}

/**
 * One key of a bundle, packaged text and operator override side by side.
 *
 * An override is absent, not empty, when none applies; `inBundle` is false for a stale override key.
 */
export type MessageEntry = {
  key: string
  english?: string
  german?: string
  overrideEnglish?: string
  overrideGerman?: string
  inBundle: boolean
  /** From the jar's `schema.json`; absent for a key the schema does not describe. */
  name?: string
  description?: string
  /** The placeholders the text is filled with: `component` ones are `<name>`, the rest `{name}`. */
  args: MessageArg[]
  /** The names of the sections around the key, outermost first. */
  section: (string | null)[]
  /** How the text is written: `MINIMESSAGE`, `DISCORD_MARKDOWN` or `PLAIN`. */
  format?: string
  /** Where the text is shown, e.g. `CHAT`, `TITLE`, `DISCORD_EMBED`. */
  shown?: string
}

/** One placeholder: a role's is dotted (`winner.name`) with a context `type`, a `global` one fits any message. */
export type MessageArg = {
  name: string
  component: boolean
  type?: string
  global?: boolean
}

/** `GET /api/message-examples`: an example value per context type and property, from real data. */
export type MessageExamples = Record<string, Record<string, string>>

/** One named glyph of the resource pack; `height` and `ascent` are in the pack's own pixels. */
export type GlyphInfo = {
  name: string
  codePoint: number
  height: number
  ascent: number
  image: string
}

/** Whether `value` is the manifest's array of {@link GlyphInfo}, checked by its own shape. */
export function isGlyphInfoList(value: unknown): value is GlyphInfo[] {
  return (
    Array.isArray(value) &&
    value.every(
      (entry) =>
        isRecord(entry) &&
        typeof entry.name === "string" &&
        typeof entry.codePoint === "number" &&
        typeof entry.height === "number" &&
        typeof entry.ascent === "number" &&
        typeof entry.image === "string",
    )
  )
}

export type MessageBundle = MessageBundleLocation & {
  entries: MessageEntry[]
}

/** What a save answers: the bundle as it now reads, plus every dropped placeholder warning, none blocking. */
export type MessageSaveResult = MessageBundle & {
  warnings: string[]
  /**
   * What became of asking the owning service to re-read the save.
   *
   * `unknown` lists override keys the bundle never heard of, where a typo silently does nothing.
   */
  reload?: BundleReloadOutcome
}

/**
 * `ConfigReloadOutcome` for a message bundle, with the same three statuses.
 *
 * `NO_ANSWER` means the row is written but the text is not in force; `message` says why.
 */
export type BundleReloadOutcome = ConfigReloadOutcome & {
  unknown: string[]
}

/** What a PUT to `/api/messages/<path>` sends: both languages of a key; `null` resets one. */
export type MessageChanges = {
  changes: Record<string, { en?: string | null; de?: string | null }>
}

/**
 * What became of asking the affected service to pick up a saved change.
 *
 * `APPLIED` and `NO_ANSWER` both sent a command; `RESTART_REQUIRED` sent nothing.
 */
export type ConfigReloadOutcome = {
  status: "APPLIED" | "NO_ANSWER" | "RESTART_REQUIRED"
  message: string
}

/**
 * `ParsedConfigDocument` widened by two fields.
 *
 * `restartRequired` is on every answer, `reload` only on a PUT's, since only a save asks anything.
 */
export type ReloadAwareConfigDocument = ParsedConfigDocument & {
  restartRequired: boolean
  reload?: ConfigReloadOutcome
}

/** `RawConfigDocument` widened with the revision the raw editor's save needs. */
export type EditableRawConfigDocument = RawConfigDocument & {
  revision: string
}

/**
 * What `PUT /api/config-raw/<file>` answers: the file as it now reads, plus every syntax warning.
 *
 * A warning never blocks the save, and `warnings` is empty rather than absent.
 */
export type RawConfigSaveResult = EditableRawConfigDocument & {
  warnings: string[]
}

/** The four formats the raw editor tells apart by file name, mirroring `RawSyntax.Format` on the worker. */
export type RawConfigFormat = "yaml" | "json" | "toml" | "properties" | "text"
