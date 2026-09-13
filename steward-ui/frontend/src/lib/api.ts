/**
 * The one door to the backend.
 *
 * Nothing in this interface calls `fetch` itself. Three things have to be true of every request and
 * are true exactly once here: a write carries the CSRF token, a 401 means "the session is gone" and
 * not "the list is empty", and an error says **which half** is down - steward-ui or steward-worker.
 * That last one is the whole reason `ApiError.where` exists: an empty service table and a stopped
 * daemon look identical on screen, and only one of them is a reason to run to the server.
 */

export type Where = "steward-ui" | "steward-worker"

export class ApiError extends Error {
  readonly status: number
  readonly where: Where
  readonly detail: string

  constructor(status: number, message: string, where: Where, detail = "") {
    super(message)
    this.name = "ApiError"
    this.status = status
    this.where = where
    this.detail = detail
  }

  /** The session ran out or was never there. The shell turns this into the sign-in page. */
  get isSignedOut(): boolean {
    return this.status === 401
  }
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
    throw new ApiError(0, "Die Oberfläche ist nicht erreichbar.", "steward-ui", String(cause))
  }

  if (response.status === 204) return undefined as T

  const text = await response.text()
  const parsed = text ? safeJson(text) : null

  if (!response.ok) {
    const body: Record<string, unknown> | null =
      parsed !== null && typeof parsed === "object" ? (parsed as Record<string, unknown>) : null
    // The backend's WorkerException handler answers with `where: "steward-worker"`, which is the
    // only way this end can tell "the daemon did not answer" from "this service threw".
    const where: Where = body?.where === "steward-worker" ? "steward-worker" : "steward-ui"
    const message = (body && messageOf(body)) ?? `${response.status} ${response.statusText}`
    const detail = body ? String(body.detail ?? "") : text
    throw new ApiError(response.status, message, where, detail)
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

export type Me = {
  signedIn: boolean
  id?: string
  name?: string
  csrf?: string
  signInUnavailable?: string
  webauthn: string
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
  requested: string
  notBefore: string
  started: string
  finished: string
  report?: Report
  savedSomething?: boolean
  resultText?: string
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

export type Person = {
  discordId: string
  memberState: string
  donor: boolean
  admin: boolean
  locale: string
  updated: string
  minecraftUuid: string | null
  linked: string | null
  accessUntil: string | null
  accessActive: boolean
}

export type Payment = {
  id: string
  reference: string
  discordId: string
  days: number
  amountCents: number
  donationCents: number
  status: string
  bunqTabId: number | null
  shareUrl: string | null
  created: string
  expires: string
  settled: string | null
}

export type Grant = {
  id: string
  discordId: string
  validFrom: string
  validUntil: string
  source: string
  paymentRequestId: string | null
  revoked: string | null
  created: string
}

export type JournalEntry = {
  id: string
  occurred: string
  action: string
  actor: string | null
  subject: string | null
  mcUuid: string | null
  detail: string | null
}

export type ConfigLocation = {
  service: string
  name: string
  path: string
  writable: boolean
}

export type ConfigEntry = {
  path: string
  key: string
  label: string
  comments: string[]
  value: string | null
  kind: "SCALAR" | "LIST" | "MAP"
  type: "STRING" | "INTEGER" | "DECIMAL" | "BOOLEAN"
  line: number
  editable: boolean
  secret: boolean
}

export type ConfigDocument = {
  service: string
  name: string
  path: string
  writable: boolean
  header: string[]
  entries: ConfigEntry[]
}
