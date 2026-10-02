/**
 * The one door to the backend: every request goes through here, and nothing else calls `fetch`.
 *
 * A write carries the CSRF token, a 401 means the session is gone, and an error names which service is down.
 */

/** What answered badly: steward itself, the Docker daemon behind the stack's state, or the agent. */
export type Where = "steward" | "docker" | "steward-agent"

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
    /** The request never arrived: this interface, a proxy or the browser is offline. */
    throw new ApiError(0, "The interface cannot be reached.", "steward", String(cause))
  }

  if (response.status === 204) {
    return shapedAs(undefined, (value): value is T => value === undefined, path)
  }

  const text = await response.text()
  const parsed = text ? safeJson(text) : null

  if (!response.ok) {
    const body: Record<string, unknown> | null = isRecord(parsed) ? parsed : null
    /** The backend names whatever did not answer, which tells a silent daemon from a throwing service. */
    const where: Where = body?.where === "docker" || body?.where === "steward-agent" ? body.where : "steward"
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

/** What the endpoints answer: generated from steward's records, and written out below where a route has none yet. */
export type * from "./api.gen"

/** One group of settings a process published, identified by `path` such as `smp/milestones`. */
export type ConfigLocation = {
  service: string
  name: string
  path: string
  readable: boolean
  writable: boolean
  /** Why the process refused the stored values and runs on its defaults; absent or null while it took them. */
  problem?: string | null
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
 * A secret carries `filled` alone, never `value` or `items`.
 */
export type ConfigEntry = {
  path: string
  key: string
  label: string
  /** The schema's short `@Explain` text. Empty when no schema entry covers this key. */
  explanation: string
  /** The schema says this needs no explanation, so draw no text rather than empty text. */
  noExplanationNeeded: boolean
  filled: boolean
  /** Absent when `secret`; a scalar's text, with a block scalar's newlines. */
  value?: string
  /** Absent when `secret`; the entries of a LIST, empty for every other kind. */
  items?: string[]
  /** A sequence of mappings, such as `languages` in the bot's `access` group, drawn as cards. */
  kind: "SCALAR" | "LIST" | "MAP" | "SECTIONS"
  type: "STRING" | "INTEGER" | "DECIMAL" | "BOOLEAN"
  /** False for a nested section, which has no value, and for a list of sections. */
  editable: boolean
  secret: boolean
  /** Whether an environment variable overrides this key, so a saved value waits until the variable is gone. */
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
  /** For a `SECTIONS` entry: the section steward refuses to remove, so the option can be greyed out. */
  protectedEntry?: ConfigProtectedEntry
}

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

export type ParsedConfigDocument = ConfigLocation & {
  /**
   * The revision the stored values were read at, which the save sends back.
   *
   * A write in between makes the save a 409 instead of silently overwriting it.
   */
  revision: string
  entries: ConfigEntry[]
}

export type ConfigDocument = ParsedConfigDocument

/** What a PUT sends: a string is a scalar, a string array a list, a record array a `SECTIONS` entry. */
/** A value, a list of values, or a list of sections, which may hold lists of sections again. */
export type ConfigChangeValue = string | string[] | { [key: string]: ConfigChangeValue }[]
export type ConfigChanges = Record<string, ConfigChangeValue>

/** steward-agent: whether it can be asked. */

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
 * `APPLIED` and `NO_ANSWER` both asked the service; `RESTART_REQUIRED` asked nothing.
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
