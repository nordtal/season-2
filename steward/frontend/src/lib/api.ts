import { t } from "@/lib/texts"

/**
 * A write carries the CSRF token, a 401 means that the session is gone, and an error names the service that is down.
 */

export type Where = "steward" | "docker" | "steward-agent"

export class ApiError extends Error {
  readonly status: number
  readonly where: Where
  readonly detail: string
  readonly code: string

  constructor(status: number, message: string, where: Where, detail = "", code = "") {
    super(message)
    this.name = "ApiError"
    this.status = status
    this.where = where
    this.detail = detail
    this.code = code
  }

  get isSignedOut(): boolean {
    return this.status === 401
  }

  /** Signed in, but one key registration short of being allowed in. */
  get needsASecurityKey(): boolean {
    return this.status === 403 && this.code === "SECOND_FACTOR_MISSING"
  }

  /**
   * It is separate from {@link needsASecurityKey}, because one needs a setup page and the other a tap.
   */
  get needsTheKeyAgain(): boolean {
    return this.status === 403 && this.code === "SECOND_FACTOR_REQUIRED"
  }
}

/**
 * A handler resolves once the key is held and rejects otherwise. With no handler installed, a 403 stays a 403.
 */
type StepUp = () => Promise<unknown>

let stepUp: StepUp | null = null

export function onSecondFactorRequired(handler: StepUp | null): void {
  stepUp = handler
}

/**
 * It is for an `/auth/` route, which `api()` never retries. With no sheet installed it rejects, as the refused request would have.
 */
export async function stepUpFirst(): Promise<void> {
  if (!stepUp) throw new Error(t("steward.keys.not-held"))
  await stepUp()
}

function mayStepUp(path: string): boolean {
  return stepUp !== null && !path.startsWith("/auth/")
}

/**
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
    throw new ApiError(0, t("steward.failure.unreachable"), "steward", String(cause))
  }

  if (response.status === 204) {
    return shapedAs(undefined, (value): value is T => value === undefined, path)
  }

  const text = await response.text()
  const parsed = text ? safeJson(text) : null

  if (!response.ok) {
    const body: Record<string, unknown> | null = isRecord(parsed) ? parsed : null
    const where: Where = body?.where === "docker" || body?.where === "steward-agent" ? body.where : "steward"
    const message = (body && messageOf(body)) ?? `${response.status} ${response.statusText}`
    const detail = body ? (typeof body.detail === "string" ? body.detail : "") : text
    const code = typeof body?.code === "string" ? body.code : ""
    throw new ApiError(response.status, message, where, detail, code)
  }

  return shapedAs(parsed, (value): value is T => value !== undefined, path)
}

/** The caller decides what `guard` proves. `send` only knows that a 204 is empty. */
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
 * Below are the two save bodies, where a null and an absent key mean different things, and the pack's glyph manifest.
 */
export type * from "./api.gen"

export type ConfigChangeValue = string | string[] | { [key: string]: ConfigChangeValue }[]
export type ConfigChanges = Record<string, ConfigChangeValue>

/** `height` and `ascent` are in the pack's own pixels. */
export type GlyphInfo = {
  name: string
  codePoint: number
  height: number
  ascent: number
  image: string
}

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

export type MessageChanges = {
  changes: Record<string, Record<string, Record<string, string[] | null>>>
}
