import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError, api, onSecondFactorRequired, rememberCsrf } from "@/lib/api"

/** The browser half of the step-up: a stale key's 403 opens the dialog and the same request goes again, once. */

function headerRecord(headers: RequestInit["headers"]): Record<string, string> {
  if (headers === undefined || headers instanceof Headers || Array.isArray(headers)) {
    throw new Error("expected a plain header record")
  }
  return headers
}

async function rejectionOf(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise
  } catch (error) {
    if (error instanceof ApiError) return error
    throw error
  }
  throw new Error("expected the promise to reject")
}

const REFUSED = {
  status: 403,
  body: { error: "Hold your security key again.", code: "SECOND_FACTOR_REQUIRED" },
}

/** The two refusals that are not a stale key and must never open the dialog. */
const NO_KEY_AT_ALL = {
  status: 403,
  body: { error: "This account has no security key.", code: "SECOND_FACTOR_MISSING" },
}
const SIGNED_OUT = { status: 401, body: { error: "Sign in." } }

type Answer = { status: number; body: unknown }

/** A fetch answering a prepared list in order and recording each request, since every assertion is about the second. */
function fetchAnswering(...answers: Answer[]) {
  const calls: Array<{ path: string; init: RequestInit }> = []
  const spy = vi.fn<(path: string, init?: RequestInit) => Promise<Response>>(
    async (path: string, init: RequestInit = {}) => {
      calls.push({ path, init })
      const answer = answers[calls.length - 1]
      if (!answer) throw new Error(`no answer prepared for request ${calls.length} to ${path}`)
      return new Response(JSON.stringify(answer.body), {
        status: answer.status,
        headers: { "Content-Type": "application/json" },
      })
    },
  )
  vi.stubGlobal("fetch", spy)
  return calls
}

beforeEach(() => {
  rememberCsrf("csrf-token")
})

afterEach(() => {
  onSecondFactorRequired(null)
  rememberCsrf(null)
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe("the step-up retry", () => {
  it("holds the key and sends the same request again, once", async () => {
    const calls = fetchAnswering(REFUSED, { status: 200, body: { ok: true } })
    const held = vi.fn<() => Promise<void>>(async () => undefined)
    onSecondFactorRequired(held)

    const answer = await api<{ ok: boolean }>("/api/commands", {
      method: "POST",
      body: { command: "season phase" },
    })

    expect(answer).toEqual({ ok: true })
    expect(held).toHaveBeenCalledTimes(1)
    expect(calls).toHaveLength(2)

    /** The same request, method, body and CSRF token, not a fresh GET of the same path. */
    expect(calls[1].path).toBe("/api/commands")
    expect(calls[1].init.method).toBe("POST")
    expect(calls[1].init.body).toBe(JSON.stringify({ command: "season phase" }))
    expect(headerRecord(calls[1].init.headers)["X-Steward-CSRF"]).toBe("csrf-token")
  })

  it("gives up after a second refusal rather than looping", async () => {
    const calls = fetchAnswering(REFUSED, REFUSED)
    const held = vi.fn<() => Promise<void>>(async () => undefined)
    onSecondFactorRequired(held)

    await expect(api("/api/commands", { method: "POST", body: {} })).rejects.toBeInstanceOf(ApiError)

    /** A refusal surviving a successful ceremony is something else, and a third attempt would hide it. */
    expect(held).toHaveBeenCalledTimes(1)
    expect(calls).toHaveLength(2)
  })

  it("passes the refusal on when the ceremony is refused", async () => {
    const calls = fetchAnswering(REFUSED)
    onSecondFactorRequired(async () => {
      throw new Error("the person closed the key dialog")
    })

    await expect(api("/api/commands", { method: "POST", body: {} })).rejects.toThrow("the person closed the key dialog")
    expect(calls).toHaveLength(1)
  })

  it("never steps up a ceremony route, which would be the loop one level down", async () => {
    const calls = fetchAnswering(REFUSED)
    const held = vi.fn<() => Promise<void>>(async () => undefined)
    onSecondFactorRequired(held)

    await expect(api("/auth/webauthn/authenticate/start", { method: "POST" })).rejects.toBeInstanceOf(ApiError)
    expect(held).not.toHaveBeenCalled()
    expect(calls).toHaveLength(1)
  })

  it("is a plain 403 when nothing has installed a handler", async () => {
    const calls = fetchAnswering(REFUSED)

    /** Without a way to hold a key, a 403 stays a 403, which is what every other test runs under. */
    const refusal = await rejectionOf(api("/api/commands", { method: "POST", body: {} }))
    expect(refusal.needsTheKeyAgain).toBe(true)
    expect(calls).toHaveLength(1)
  })

  it("leaves the other two refusals alone", async () => {
    const held = vi.fn<() => Promise<void>>(async () => undefined)
    onSecondFactorRequired(held)

    const noKey = fetchAnswering(NO_KEY_AT_ALL)
    const first = await rejectionOf(api("/api/commands", { method: "POST", body: {} }))
    expect(first.needsASecurityKey).toBe(true)
    expect(noKey).toHaveLength(1)

    const signedOut = fetchAnswering(SIGNED_OUT)
    const second = await rejectionOf(api("/api/commands", { method: "POST", body: {} }))
    expect(second.isSignedOut).toBe(true)
    expect(signedOut).toHaveLength(1)

    /** One needs registration and the other Discord, so a key dialog could not answer either. */
    expect(held).not.toHaveBeenCalled()
  })
})
