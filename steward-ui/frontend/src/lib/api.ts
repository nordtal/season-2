/**
 * The one door to the backend.
 *
 * Nothing in this interface calls `fetch` itself. Three things have to be true of every request and
 * are true exactly once here: a write carries the CSRF token, a 401 means "the session is gone" and
 * not "the list is empty", and an error says **which of the three services** is down. That last one
 * is the whole reason `ApiError.where` exists: an empty service table and a stopped daemon look
 * identical on screen, and only one of them is a reason to run to the server.
 */

/**
 * Which service answered badly.
 *
 * Three, because the deployment is three (concept §3): the interface, the one process that holds
 * the docker socket, and the one process allowed to create a container. They fail differently and
 * mean different things - a stopped worker is a stack nobody can see, a stopped deployer is a
 * stack nobody can change - so the sentence on screen must not collapse them into "something is
 * kaputt".
 */
export type Where = "steward-ui" | "steward-worker" | "steward-deployer"

export class ApiError extends Error {
  readonly status: number
  readonly where: Where
  readonly detail: string
  /**
   * The backend's machine-readable name for this refusal, when it has one.
   *
   * Two values use it - `SECOND_FACTOR_MISSING` and `SECOND_FACTOR_REQUIRED` - and they are codes
   * rather than matched sentences because the sentences are English prose that somebody will
   * improve, and a `startsWith("This account has no")` would break the interface when they did.
   */
  readonly code: string

  constructor(status: number, message: string, where: Where, detail = "", code = "") {
    super(message)
    this.name = "ApiError"
    this.status = status
    this.where = where
    this.detail = detail
    this.code = code
  }

  /** The session ran out or was never there. The shell turns this into the sign-in page. */
  get isSignedOut(): boolean {
    return this.status === 401
  }

  /**
   * Signed in, and one ceremony short of being allowed in.
   *
   * Deliberately NOT `isSignedOut`: sending this person back to Discord would send them round a
   * loop they have already completed. The shell draws the key setup instead.
   */
  get needsASecurityKey(): boolean {
    return this.status === 403 && this.code === "SECOND_FACTOR_MISSING"
  }

  /**
   * Signed in, HAS a key, and has not held it recently enough for this.
   *
   * Told apart from {@link needsASecurityKey} because the two need different screens: one account
   * has no key and must register one, the other has a key and must hold it. Answering both the
   * same way would send somebody with a key to a setup page that refuses them.
   */
  get needsTheKeyAgain(): boolean {
    return this.status === 403 && this.code === "SECOND_FACTOR_REQUIRED"
  }
}

/**
 * The one place a refused request is turned back into a working one.
 *
 * **Why a registered function rather than a call.** The ceremony needs a dialog on screen and a
 * fresh tap from the person in front of it - Safari will not open the key dialog without one - and
 * this file knows nothing about React and should not start. So the shell installs its handler once
 * and this file calls it: one place that retries, one place that draws.
 *
 * A handler resolves when the key has been held and rejects when it has not. Nothing is installed
 * in a test, which is the honest default: without a way to hold a key, a 403 is a 403.
 */
type StepUp = () => Promise<unknown>

let stepUp: StepUp | null = null

export function onSecondFactorRequired(handler: StepUp | null): void {
  stepUp = handler
}

/**
 * Whether a refusal of this request may be recovered from by holding the key.
 *
 * The ceremony itself is made of two `POST`s, and they are the two requests that must never be
 * retried this way: a recursion here would be a dialog that reopens itself forever, and the
 * routes are `SIGNED_IN` anyway, so they cannot produce this refusal. Written out rather than
 * relied upon.
 */
function mayStepUp(path: string): boolean {
  return stepUp !== null && !path.startsWith("/auth/")
}

/**
 * The CSRF token of this session.
 *
 * It is handed out by `/api/me` and sent back in a header on every write - the double-submit half
 * the browser is allowed to see. Kept in a module variable rather than in the query cache because
 * a mutation must be able to read it synchronously while it builds its request.
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
    // THE WHOLE OF "ONE TAP, NOT TWO". The request is sent, refused because the key has not been
    // held recently enough, the key is held, and the SAME request goes again - so somebody who
    // taps Update taps Update, rather than tapping Update, then a dialog, then Update again.
    //
    // Exactly once. A second refusal after a successful ceremony is not a stale window, it is
    // something else entirely, and retrying it again would hide whatever that is behind a loop.
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
    // The request never arrived anywhere. That is this interface being unreachable - a stopped
    // steward-ui, a proxy in the way, or a browser that is offline - and never the worker.
    throw new ApiError(0, "The interface cannot be reached.", "steward-ui", String(cause))
  }

  if (response.status === 204) return undefined as T

  const text = await response.text()
  const parsed = text ? safeJson(text) : null

  if (!response.ok) {
    const body: Record<string, unknown> | null =
      parsed !== null && typeof parsed === "object" ? (parsed as Record<string, unknown>) : null
    // The backend's InternalClient.Failure handler answers with the name of whichever service
    // did not answer, which is the only way this end can tell "the daemon did not answer" from
    // "this service threw".
    const where: Where =
      body?.where === "steward-worker" || body?.where === "steward-deployer"
        ? body.where
        : "steward-ui"
    const message = (body && messageOf(body)) ?? `${response.status} ${response.statusText}`
    const detail = body ? String(body.detail ?? "") : text
    const code = typeof body?.code === "string" ? body.code : ""
    throw new ApiError(response.status, message, where, detail, code)
  }

  return parsed as T
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

/** Javalin's own error bodies use `title`; ours use `error`. Both are one sentence. */
function messageOf(body: Record<string, unknown>): string | null {
  for (const key of ["error", "title", "message"]) {
    const value = body[key]
    if (typeof value === "string" && value.trim()) return value
  }
  return null
}

// ---------------------------------------------------------------------------------------------
// What the endpoints answer.
//
// Written out rather than inferred, because the backend builds these maps by hand: a field that is
// only present on a running container is optional HERE too, and a `?` that is missing is a crash
// on the one day a container is stopped.
// ---------------------------------------------------------------------------------------------

/** One registered security key, as `/api/me` lists it. */
export type SecurityKey = {
  /**
   * The credential id, base64url - what names this key when it is renamed or removed.
   *
   * Not a secret: it is handed to any browser that starts a sign-in, because it is what tells the
   * authenticator which credential to use. The routes that act on it check the account as well.
   */
  id: string
  label: string
  registeredAt: string
  lastUsedAt?: string
  transports?: string[]
  /**
   * Whether this key is synced somewhere - an iCloud passkey is, a YubiKey is not.
   *
   * Absent when the authenticator did not say, which is NOT the same as "no": only a key that
   * answered "no" is a key whose loss is final, and only that case is worth a second key.
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
  /**
   * The security keys of this account, oldest first. **Empty is the whole of the setup page.**
   *
   * Absent rather than empty when nobody is signed in: `/api/me` answers the signed-out question
   * without ever touching this table, and a `[]` there would be a claim about an account there
   * is none of.
   */
  keys?: SecurityKey[]
  /** Whether a key has been held in THIS session. */
  verified?: boolean
  verifiedAt?: string
  /** The domain keys are registered against, for the page to name rather than guess. */
  relyingPartyId?: string
  /**
   * How many minutes one touch of the key covers.
   *
   * From the server so that the dialog says the number this service actually enforces, rather than
   * a literal here that would disagree with it the day somebody changes one of the two.
   */
  stepUpMinutes?: number
  /**
   * The picture Discord holds for this account, when the access list happens to carry one.
   *
   * **Absent is the ordinary case, not an error** (steward/91): somebody can be signed into Steward
   * and not be in the access list at all, and Discord itself does not always have a picture. The
   * island then draws the initials, stays tappable, and nothing about the page changes - which is
   * the fallback steward/89 prescribes rather than a degraded state. So the query that fills this
   * must never be allowed to fail the answer.
   */
  discordAvatarUrl?: string
}

/** `GET /api/web-push/public-key`'s body - the VAPID public key, base64url, already the shape
 * `PushManager.subscribe()`'s `applicationServerKey` wants once decoded. */
export type WebPushPublicKey = {
  publicKey: string
}

/** Docker's own words, passed through. `state` is the container state, `status` its sentence. */
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
   * How many people are connected, on the four services that have an answer to that.
   *
   * **Absent is not zero** (steward/86). `smp`, `hunger-games` and `limbo` each carry their own,
   * `proxy` the network's total, and every other service has no such field at all -
   * neither does one of those four while the proxy has not written recently enough for the
   * worker to trust the row. So the optional marker here is load-bearing: `players ?? 0` is the
   * one thing a reader must not write, because it turns "nobody has said" into "nobody is on".
   */
  players?: number
  /**
   * Who is connected, on the rows that have a list - `proxy` carries the whole network,
   * a backend carries its own. **Absent is not empty** (steward/111), the same rule `players`
   * follows one line above: a row with no `roster` is one nobody wrote a list for.
   */
  roster?: Array<{ uuid?: string; name?: string }>
  /**
   * Set when somebody stopped this service on purpose and it must stay stopped
   * (season-2-ops/125).
   *
   * **Absent is not false**, the same rule `players` follows: a row with no `hold` is a service
   * nobody is holding, and a stopped container without one fell over rather than being put down.
   * That difference cannot be read off the container - it only exists in `service_hold` - so this
   * field is the only thing that can draw it.
   */
  hold?: { since: string; by?: string | null }
  unreadable?: string
  /** Only on the single-service endpoint. */
  digests?: string[]
  logLimit?: string
}

/**
 * The service table, with the age of the drift comparison beside it.
 *
 * The worker caches the registry answer for a minute, so `checkedAt` is how old the image
 * comparison is - not how old the rest of the row is. The page says so out loud: a green tick over
 * an answer of unknown age is the failure this column exists to catch.
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
 * The worker's own nightly clock, read from the worker rather than guessed here.
 *
 * `nextBackupAt` is an ISO instant with an offset, so it can be turned into a moment; `backupAt`
 * and `zone` are for saying it out loud. All three are null / "off" when `backup.at` is empty.
 */
export type Schedule = {
  backupAt: string | null
  zone: string
  nextBackupAt: string | null
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
   * `MOVING` or `UNSUPPORTED` - see `UpdateReport.Change.State` in `:common`.
   *
   * Optional, and that is a real difference between two shapes of the same data: the JSON stored in
   * `update_request.result` omits the field when it is `MOVING`, because `UpdateReports` only writes
   * it for `UNSUPPORTED`. This interface parses the column into the record and re-serialises it, so
   * what arrives HERE normally has it - but a reader that treats a missing `state` as "not moving"
   * would be wrong against the column and right against the endpoint, which is a bug waiting for the
   * day the two are ever compared.
   */
  state?: string
}

/**
 * One service, or - in a BACKUP run - one volume.
 *
 * `state` is `UpdateReport.State`: UNCHANGED, PLANNED, STOPPED, INSTALLED, SAVED, STARTING,
 * HEALTHY, FAILED.
 */
export type ReportLine = {
  service: string
  state: string
  changes: ReportChange[]
  /**
   * Absent rather than null when there is none: Javalin's Gson mapper drops nulls, so a field with
   * no value simply is not in the object.
   */
  detail?: string
}

/** `update_request.result`, parsed by the backend. Mirrors UpdateReport in :common. */
export type Report = {
  /** RESOLVING, PLANNED, COUNTDOWN, STOPPING, BACKING_UP, INSTALLING, STARTING, VERIFYING, DONE, NOTHING_TO_DO, FAILED, CANCELLED. */
  stage: string
  services: ReportLine[]
  notes: string[]
}

export type Run = {
  id: number
  kind: string
  status: string
  source: string
  requestedBy: string
  /**
   * `requestedBy`, already picked apart on the backend - see `StewardUi.ActorFields`, the same
   * reading `ActionEntry` gives the unified actions feed. Never absent: an empty string is "none of
   * this applies", not "the backend has not been asked yet" (Gson never drops one).
   */
  actorDiscordId: string
  actorLabel: string
  system: boolean
  requested: string
  notBefore: string
  started: string
  finished: string
  report?: Report
  savedSomething?: boolean
  resultText?: string
}

/**
 * One artefact in the resolve, and what a run would do about it (season-2-ops/128).
 *
 * `status` is the worker's own `Change.Status`, and the four that matter are told apart on the
 * page rather than lumped into "something to do": OUTDATED and MISSING are work, UP_TO_DATE is
 * nothing, UNSUPPORTED is "no build exists for this Minecraft version" (CoreProtect), and
 * UNRESOLVED / MOUNT_MISSING mean the answer is unknown - which must never be drawn like
 * "nothing to do".
 *
 * `work` and `failure` come from the backend rather than being derived here, because the worker is
 * the only thing allowed to decide what a status means.
 */
export type AvailableChange = {
  /** Absent for the resource pack, which belongs to no service. */
  service?: string
  artifact: string
  status: string
  work: boolean
  failure: boolean
  installed?: string
  /** The version as its publisher states it - for reading, never for comparing. */
  version?: string
  /** The filename, which is the actual identity of what would be installed. */
  fileName?: string
  note?: string
}

/** `GET /api/updates/available` - what a run would do, without a run. */
export type Available = {
  /** When this reading was taken. It can be hours old; the page says so. */
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
 * One plugin on one Minecraft server (season-2-ops/129).
 *
 * **`running` and `removable` are two different questions and neither implies the other.**
 * `running` is a jar on the disk; `removable` is a row in `service_plugin`. A plugin the network
 * gives is running and not removable - there is no row to delete, which is what makes "fixed"
 * true rather than merely drawn grey. A plugin somebody just installed is removable and not yet
 * running: installing writes the row and the jar arrives with the next update run.
 */
export type ServicePlugin = {
  /** The title for an added plugin, the jar's filename prefix for one the network gives. */
  name: string
  running: boolean
  removable: boolean
  filePrefix?: string
  fileName?: string
  version?: string
  /**
   * `plugins/<dataFolder>/` - read out of the jar's own descriptor, not guessed from its name.
   *
   * **Absent means the worker could not read it**, and the confirmation has to say so rather than
   * name a folder nobody verified: removing deletes this directory, and it is the only
   * hand-edited thing in the installation.
   */
  dataFolder?: string
  /** Only on an added plugin - it is the Modrinth slug, and the id the remove button sends. */
  artifact?: string
  projectId?: string
  added?: string
  addedBy?: string
  iconUrl?: string
  pageUrl?: string
}

/** `GET /api/services/{name}/plugins` */
export type ServicePlugins = {
  service: string
  /** `paper` or `velocity` - what the search on this page is filtered to. */
  loader: string
  gameVersion: string
  /** False when this worker cannot see the volume, which is not the same as an empty server. */
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

/** `GET /api/services/{name}/plugins/search` */
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

export type LogSearch = { lines: string[]; limit: number; truncated: boolean }

/**
 * One person the bot knows.
 *
 * **The absent fields are optional, not nullable, and that is not a style choice.** Javalin is
 * wired to `JavalinGson(new Gson(), true)` and Gson omits nulls, so a person with no linked
 * Minecraft account arrives WITHOUT `minecraftUuid` rather than with `null`. Typing these
 * `string | null` compiled fine and let `x !== null` through - which is true for every unlinked
 * person and would have made a "linked only" filter quietly show everybody.
 *
 * `accessUntil` is the latest `valid_until` over ALL grants, revoked ones included, while
 * `accessActive` is the full login predicate. The pair is deliberate: a revoked person showing no
 * date at all would look exactly like a stranger who never had access.
 *
 * **The eight profile fields (steward/44/45) are what discord-bot and the proxy last
 * observed, each with its own timestamp.** All eight are independently absent - an account nobody
 * has mirrored a Discord profile onto, one that left the guild, or one that was never seen joining
 * reads with the corresponding fields simply missing, never with an empty string standing in. They
 * feed `PersonIdentity` (`@/components/steward/identity`), the one place a raw id or uuid may be
 * shown - see that module's header comment for the rule.
 */
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
  /**
   * Total online time across the network, in seconds, out of `player_playtime` (steward/119).
   *
   * Absent - not zero - for somebody who has never been online: the proxy writes the row on its
   * first flush, so "no row" and "no time" are different facts and the list says so. The prestige
   * tier is derived from this number and stored nowhere, which is why a roster prints it.
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

/**
 * One row of steward-worker's `/api/actions` (steward/82) - a run from `update_request` or a line
 * from `audit_log`, already merged and sorted by the worker into one feed, newest first.
 *
 * `actorDiscordId` and `actorLabel` are `""`, never absent, when there is nothing to show in that
 * slot - `eu.nordtal.s2.steward.worker.api.ActionEntry`'s own header comment says why a record's
 * field cannot simply be left out the way a map's can. `system` decides between the two: true means
 * neither field means anything and `PersonIdentity`'s `system` prop draws Steward instead of either.
 */
export type Action = {
  kind: string
  occurred: string
  extent: string
  actorDiscordId: string
  actorLabel: string
  system: boolean
}

/**
 * One config file under the mount.
 *
 * `path` is the identity - `steward-worker/steward.yml`, or `smp/nordtal-smp/config.yml` for a
 * plugin's file inside a server's data directory. It is what goes in a URL, and it is compared
 * against the list the backend found rather than resolved, which is why a `..` in it is a 404 and
 * not a question about decoding.
 *
 * `readable` and `writable` are both measured, not assumed, and they are two different failures.
 * A form that only finds out at Save that the file is read-only has wasted somebody's typing; a
 * row that opens into an error alert because the service may not read the file at all has wasted
 * their time and told them nothing. The listing asks before it draws.
 */
export type ConfigLocation = {
  service: string
  name: string
  path: string
  readable: boolean
  writable: boolean
}

/**
 * The allowed (or suggested) values of a setting, from its schema (steward/55, steward/56).
 *
 * `strict` closes the list to exactly these values - the form draws a select and nothing else.
 * Otherwise it is a suggestion: a select beside a free-text field that still accepts anything.
 */
export type ConfigChoices = {
  values: string[]
  strict: boolean
}

/**
 * Identifies the one section of a `SECTIONS` entry that a save must never be allowed to remove -
 * from the schema's `@Protected` (steward/74), e.g. `{ field: "tag", value: "en" }` for `languages`.
 */
export type ConfigProtectedEntry = {
  field: string
  value: string
}

/**
 * One key of a config file, as the form draws it.
 *
 * **`value` and `items` are absent for a secret and that is the point.** A key whose name says
 * credential - token, password, secret, key - is sent with `filled` alone, so the page can say
 * "set" without the bot token ever being in this browser. Typing a new one still works; it is
 * only reading the old one that does not.
 *
 * `filled` is sent for every key, secret or not, so there is one rule to draw rather than two.
 *
 * **Since jcore 4.0.0 (steward/55), `explanation` and `choices` come from the `<name>.schema.json`
 * beside the file, not from `comments` any more** - a file that generation of jcore wrote carries no
 * comments at all. `comments` is what is left for a file with no schema, or one nothing ever wrote a
 * schema for. `inSchema` is `true` whenever there is nothing to be missing from (no schema at all)
 * and `false` only when a schema exists here and does not mention this key - the file still wins
 * and the key is still sent, just marked.
 */
export type ConfigEntry = {
  path: string
  key: string
  label: string
  comments: string[]
  /** The schema's short `@Explain` text. Empty when no schema entry covers this key. */
  explanation: string
  /** The schema says explicitly that this needs no explanation - draw no text, not empty text. */
  noExplanationNeeded: boolean
  filled: boolean
  /** Absent when `secret`. A scalar's text; for a block scalar, with the newlines it holds. */
  value?: string
  /** Absent when `secret`. The entries of a LIST; empty for every other kind. */
  items?: string[]
  /**
   * **`SECTIONS` does not exist on the worker yet (steward/57).** It is a sequence of mappings -
   * `languages` in `discord-bot/access.yml` is the case this was invented for - and today's worker
   * has no way to describe one: {@link ConfigFiles#collect} in `steward-worker` only recurses into
   * a `MappingNode`, never into the items of a `SequenceNode`, so a list of sections currently
   * arrives as an ordinary `LIST` with `items: []` (its scalars collector finds none) and
   * `editable: false`. This value, and {@link template} and {@link sections} below, are this
   * ticket's frontend half of the mechanism, built so the worker side has a concrete shape to send
   * once it exists - see the ticket for exactly what would have to change in `ConfigFiles`.
   */
  kind: "SCALAR" | "LIST" | "MAP" | "SECTIONS"
  type: "STRING" | "INTEGER" | "DECIMAL" | "BOOLEAN"
  line: number
  /** False for a nested section, which has no value, and for a list of sections. */
  editable: boolean
  secret: boolean
  /** Whether the schema declares this key. Always `true` when the file has no schema at all. */
  inSchema: boolean
  /**
   * Whether an environment variable has taken this key over, so that editing the file here changes
   * the file and not the running service (steward/76).
   *
   * jcore's `ConfigHandle` overlays `NORDTAL_<PREFIX>_<PATH>` on load and never writes the value
   * back; measured on this host, seven keys of `discord-bot`'s `access.yml` are overridden that
   * way, `languages` among them. The field stays **editable** on purpose - the point is to be able
   * to prepare the file for the day the variable goes - but it must say so, which is what this
   * carries.
   *
   * Absent means the service did not say, not that it is unaffected: a worker or a service older
   * than steward/76 answers nothing here, and drawing "not overridden" from that would be the
   * silent wrong answer this whole field exists to prevent.
   */
  environmentOverridden?: boolean
  /** The schema's allowed or suggested values, or absent when it names none. */
  choices?: ConfigChoices
  /**
   * For a `SECTIONS` entry: the schema's own shape of one section, in display order - the field set
   * every existing section is drawn with, and the blank template a new "Add" starts from. Absent
   * when the schema does not describe this list's shape closely enough to draw a card from (mixed
   * shapes in one sequence) - the form then falls back to raw text rather than a card that would
   * swallow whatever does not fit the first section it saw. Undefined for every other kind.
   */
  template?: ConfigEntry[]
  /**
   * For a `SECTIONS` entry: one array of field entries per existing section, in file order, each
   * shaped like {@link template}. Empty for an empty list. Undefined for every other kind.
   */
  sections?: ConfigEntry[][]
  /**
   * For a `SECTIONS` entry whose schema carries `@Protected` (steward/74): which section must not
   * be removed. The worker itself refuses that removal - see `ConfigFiles.removeSection` - so this
   * is here for the interface to grey the option out up front rather than let an operator confirm a
   * removal that only fails once it reaches the worker. Undefined when there is no such rule.
   */
  protectedEntry?: ConfigProtectedEntry
}

/**
 * One role or channel of the guild, as the pickers offer it.
 *
 * `type` is Discord's own channel type and is absent for a role. It is carried rather than
 * interpreted here so the picker can group a category (type 4) apart from the channels under it -
 * "general" under Info and "general" under Season are two channels with one name.
 */
export type GuildEntry = {
  id: string
  name: string
  type: number | null
}

/**
 * What the guild is made of, or why that could not be answered.
 *
 * **Never an error.** No bot token, an unreachable Discord and a rate limit all arrive here as
 * `available: false` with a sentence, because a configuration page whose pickers cannot be filled
 * is still a configuration page - the id can be typed. A 500 for this would be a bug report about
 * something working as designed.
 */
export type GuildList = {
  available: boolean
  reason?: string
  entries: GuildEntry[]
}

/**
 * A file steward could not split into keys - a foreign file steward/55's broadened `discover()`
 * now finds (a plugin's `README.txt`, a `.properties` file), or a `.yml` with a mistake in it
 * (steward/56). There is no `revision` and no `entries`: nothing here was parsed, so there is
 * nothing a save could be checked against. The interface shows the bytes as text and offers no
 * save button for them - `writable` on the location is beside the point.
 */
export type RawConfigDocument = ConfigLocation & {
  raw: true
  reason?: string
  content: string
}

export type ParsedConfigDocument = ConfigLocation & {
  raw?: never
  /**
   * What the file said when it was read, and what the next save has to still be about.
   *
   * It goes back out with the PUT. If somebody else wrote the file in between - a second admin, an
   * hour of somebody reading the comments in a long form - the save is refused with a 409 rather
   * than applying this browser's changes to a file it has not seen. Without it the later save wins
   * silently and the earlier change is simply gone.
   */
  revision: string
  header: string[]
  entries: ConfigEntry[]
}

export type ConfigDocument = RawConfigDocument | ParsedConfigDocument

/**
 * What a PUT sends: a string is a scalar, a string array is a list, and an array of records is a
 * `SECTIONS` entry - one flat `{key: value}` record per card, in order. None of the three are
 * interchangeable.
 */
export type ConfigChanges = Record<string, string | string[] | Record<string, string>[]>

/**
 * One admin command the interface may ask for (concept §10b).
 *
 * The list comes from the backend rather than being written here, because it IS the set of
 * declarations carrying `Surface.WEB` - a copy in this file would be a second list to keep
 * truthful, and the failure mode is a button for a command nothing will run.
 */
export type AdminCommand = {
  name: string
  path: string[]
  target: string
  adminOnly: boolean
  /** The adapters owe a confirmation on these. Here that means an AlertDialog. */
  irreversible: boolean
  arguments: CommandArgument[]
}

export type CommandArgument = {
  name: string
  /**
   * ACCOUNT and REFERENCE are the two this interface fills from a list rather than a field -
   * `/api/people` and `/api/payments/open`. Both are declared kinds and not names, so a command
   * added later gets the picker without anybody remembering to wire it.
   */
  kind:
    | "WORD"
    | "GREEDY_STRING"
    | "INTEGER"
    | "PLAYER"
    | "CHOICE"
    | "ACCOUNT"
    | "REFERENCE"
  required: boolean
  min?: number
  max?: number
  choices?: string[]
}

/**
 * What became of a request.
 *
 * PENDING and RUNNING both mean keep waiting and mean different things when the wait runs out: a
 * row nobody ever claimed is a target that is down, one claimed and never settled is a target that
 * is up and stuck. EXPIRED is therefore a diagnosis, not a failure.
 */
export type CommandRun = {
  id: string
  name?: string
  status: "PENDING" | "RUNNING" | "DONE" | "FAILED" | "EXPIRED"
  result?: string
}

// ---------------------------------------------------------------------------------------------
// steward-deployer: one question and one verb (§10a.4).
// ---------------------------------------------------------------------------------------------

/**
 * Whether the recreate button may be drawn at all.
 *
 * Asked before anybody clicks, because a stack whose setup script has not run yet has no shared
 * secret for the deployer - and "not configured" is a different sentence from "broken".
 */
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
  /** compose's own output, in order. Only `GET /api/deployer/jobs/{id}` carries it. */
  lines?: string[]
}

/**
 * Where one message bundle lives (steward/48).
 *
 * `path` is `<service>/<module>`, or just `<service>` when the bundle sits directly in the
 * service's own jar rather than a plugin's - the identity `/api/messages/<path>` is called with.
 * `module` is `""` in that case, the same convention `ConfigLocation.service` uses for a file with
 * no service.
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
 * **The worker reads the packaged text out of the module's jar, never off disk** - disk only ever
 * holds an override, and almost nothing on a fresh deploy. `english`/`german` are therefore what
 * ships; `overrideEnglish`/`overrideGerman` are absent, not empty, when nothing overrides that
 * language - the same "absence is the signal" convention `ConfigEntry.value` uses for a secret.
 *
 * `inBundle` is `false` for a key an override file mentions that the packaged text does not (any
 * more) - the module was updated and an old override key is now stale rather than wrong.
 */
export type MessageEntry = {
  key: string
  english?: string
  german?: string
  overrideEnglish?: string
  overrideGerman?: string
  inBundle: boolean
}

export type MessageBundle = MessageBundleLocation & {
  entries: MessageEntry[]
}

/**
 * What a save answers: the bundle as it now reads, plus every dropped-placeholder warning.
 *
 * A warning never blocks the save (steward/60's rule) - the response carries both the written
 * result and the sentence, rather than the interface having to infer one from the other.
 */
export type MessageSaveResult = MessageBundle & {
  warnings: string[]
}

/** What a PUT to `/api/messages/<path>` sends. `null` resets that key rather than filling it. */
export type MessageChanges = {
  language: "en" | "de"
  changes: Record<string, string | null>
}

/**
 * What became of asking the affected service to pick up a just-saved change (steward/59).
 *
 * The three values are deliberately not two: `APPLIED` and `NO_ANSWER` both mean a command was
 * sent, and must not be told apart only by reading `message` closely. `RESTART_REQUIRED` means
 * nothing was sent at all - there is no live command this file's own reload path would answer to,
 * so nothing here restarts anything on its own; that stays a deliberate click.
 */
export type ConfigReloadOutcome = {
  status: "APPLIED" | "NO_ANSWER" | "RESTART_REQUIRED"
  message: string
}

/**
 * `ParsedConfigDocument` widened by the two fields steward/59 added.
 *
 * `restartRequired` is on every GET as well as every PUT - the "no live reload reaches this file"
 * fact is a property of the file, known before anybody types anything, and shown at the file
 * rather than only after a save. `reload` exists only on a PUT's answer, because only a save asks
 * a service to do anything - reading a file asks nothing of it.
 *
 * Its own type rather than a change to `ParsedConfigDocument` itself: other work is landing in
 * this file tonight, and appending a new type is what stays out of its way.
 */
export type ReloadAwareConfigDocument = ParsedConfigDocument & {
  restartRequired: boolean
  reload?: ConfigReloadOutcome
}

/**
 * `RawConfigDocument` widened with the revision the raw editor's save needs (steward/60).
 *
 * `RawConfigDocument` itself stays as steward/56 left it - no revision, no save button, because
 * that was true of every raw document until this ticket gave the worker a write path for one. The
 * worker now sends `revision` on every raw document too (`ConfigApi#rawDocument`), computed the
 * same way a parsed file's is; this is its own type rather than a change to `RawConfigDocument`
 * for the same reason `ReloadAwareConfigDocument` is its own type above it - other work lands in
 * this file the same night, and appending stays out of its way.
 */
export type EditableRawConfigDocument = RawConfigDocument & {
  revision: string
}

/**
 * What `PUT /api/config-raw/<file>` answers (steward/60): the file as it now reads, plus every
 * syntax warning the save found.
 *
 * A warning never blocks the save - the same rule `MessageSaveResult` already carries for a
 * dropped placeholder. `warnings` is empty rather than absent when there was nothing to say, so a
 * caller never has to tell "no field" apart from "empty list".
 */
export type RawConfigSaveResult = EditableRawConfigDocument & {
  warnings: string[]
}

/**
 * The four formats the raw editor tells apart, by the file's own name - never its content, and
 * never guessed from what parsed and what did not. Mirrors `RawSyntax.Format` on the worker
 * (steward/60); the two are independent (a frontend module cannot import a worker enum) and
 * agreeing is a matter of both reading the same four extensions, not of sharing code.
 */
export type RawConfigFormat = "yaml" | "json" | "toml" | "properties" | "text"
