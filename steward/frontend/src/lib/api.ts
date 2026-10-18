import { t } from "@/lib/texts"

/**
 * The one entry point to the backend. Every request goes through here, and nothing else calls `fetch`.
 *
 * A write carries the CSRF token, a 401 means that the session is gone, and an error names the service that is down.
 */

/**
 * What answered badly: steward itself, the Docker daemon behind the stack's state, or the agent.
 */
export type Where = "steward" | "docker" | "steward-agent"

export class ApiError extends Error {
  readonly status: number
  readonly where: Where
  readonly detail: string
  /**
   * The backend's machine-readable name for this refusal. Code matches it and not the English sentence.
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

  /** The session expired or never existed. The shell turns this into the sign-in page. */
  get isSignedOut(): boolean {
    return this.status === 401
  }

  /**
   * Signed in, but one key registration short of being allowed in. So it is not {@link isSignedOut}.
   */
  get needsASecurityKey(): boolean {
    return this.status === 403 && this.code === "SECOND_FACTOR_MISSING"
  }

  /**
   * Signed in with a key that was not held recently enough for this request.
   *
   * It is separate from {@link needsASecurityKey}, because one needs a setup page and the other a tap.
   */
  get needsTheKeyAgain(): boolean {
    return this.status === 403 && this.code === "SECOND_FACTOR_REQUIRED"
  }
}

/**
 * The one place where a refused request becomes a working one again. The shell installs it.
 *
 * A handler resolves once the key is held and rejects otherwise. With no handler installed, a 403 stays a 403.
 */
type StepUp = () => Promise<unknown>

let stepUp: StepUp | null = null

export function onSecondFactorRequired(handler: StepUp | null): void {
  stepUp = handler
}

/**
 * Opens the step-up sheet and resolves once a factor is held. It is for an `/auth/` route, which `api()` never retries.
 *
 * With no sheet installed it rejects, as the refused request would have.
 */
export async function stepUpFirst(): Promise<void> {
  if (!stepUp) throw new Error(t("steward.keys.not-held"))
  await stepUp()
}

/**
 * Returns whether holding the key may recover a refusal of this request. It never may for the ceremony's own two `POST`s.
 */
function mayStepUp(path: string): boolean {
  return stepUp !== null && !path.startsWith("/auth/")
}

/**
 * The CSRF token of this session. `/api/me` hands it out, and every write sends it back.
 *
 * It is a module variable and not part of the query cache, so a mutation can read it synchronously.
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
     * One tap and not two: a request refused for want of a fresh key is retried once after the key is held.
     *
     * A second refusal is something else, and a further retry would hide it behind a loop.
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
    /** The request never arrived. This interface, a proxy or the browser is offline. */
    throw new ApiError(0, t("steward.failure.unreachable"), "steward", String(cause))
  }

  if (response.status === 204) {
    return shapedAs(undefined, (value): value is T => value === undefined, path)
  }

  const text = await response.text()
  const parsed = text ? safeJson(text) : null

  if (!response.ok) {
    const body: Record<string, unknown> | null = isRecord(parsed) ? parsed : null
    /**
     * The backend names whatever did not answer, which tells a silent daemon from a throwing service.
     */
    const where: Where = body?.where === "docker" || body?.where === "steward-agent" ? body.where : "steward"
    const message = (body && messageOf(body)) ?? `${response.status} ${response.statusText}`
    const detail = body ? (typeof body.detail === "string" ? body.detail : "") : text
    const code = typeof body?.code === "string" ? body.code : ""
    throw new ApiError(response.status, message, where, detail, code)
  }

  return shapedAs(parsed, (value): value is T => value !== undefined, path)
}

/**
 * Narrows an untyped value to `T` wherever it enters typed code, with a guard and not a cast.
 *
 * The caller decides what `guard` proves. `send` only knows that a 204 is empty.
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

/** Javalin's own error bodies use `title`, and ours use `error`. Both hold one sentence. */
function messageOf(body: Record<string, unknown>): string | null {
  for (const key of ["error", "title", "message"]) {
    const value = body[key]
    if (typeof value === "string" && value.trim()) return value
  }
  return null
}

/**
 * What the endpoints answer and read, generated from steward's records.
 *
 * Below are the two save bodies, where a null and an absent key mean different things, and the pack's glyph manifest.
 */
export type * from "./api.gen"

/**
 * What a settings PUT sends: a scalar's text, a list's entries, or one record per section, nested again.
 */
export type ConfigChangeValue = string | string[] | { [key: string]: ConfigChangeValue }[]
export type ConfigChanges = Record<string, ConfigChangeValue>

/** One named glyph of the resource pack. `height` and `ascent` are in the pack's own pixels. */
export type GlyphInfo = {
  name: string
  codePoint: number
  height: number
  ascent: number
  image: string
}

/** Returns whether `value` is the manifest's array of {@link GlyphInfo}, checked by its shape. */
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

/**
 * What a PUT to `/api/messages` sends: every language of a key of any bundle. `null` resets one.
 */
export type MessageChanges = {
  /**
   * Per bundle, key and language, every variant in order, or `null` to restore the jar's texts.
   */
  changes: Record<string, Record<string, Record<string, string[] | null>>>
}
