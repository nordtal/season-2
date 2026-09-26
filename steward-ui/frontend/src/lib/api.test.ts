import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError, api, onSecondFactorRequired, rememberCsrf } from "@/lib/api"

/**
 * The browser half of package D: one tap, not two.
 *
 * The server half is covered by `StewardUiIntegrationTest` - that a writing route refuses a stale
 * ceremony with `403 SECOND_FACTOR_REQUIRED`, and that reading stays open. What no Java test can
 * see is what this end does with that refusal, and the promise made on screen lives here: the
 * person taps Update, the key dialog opens, and the SAME request goes again by itself.
 *
 * The two dangerous shapes are both pinned below, because both are silent failures rather than
 * errors: a retry that never stops is a dialog that reopens for ever, and a retry of the ceremony
 * itself is that same loop one level down.
 */

const REFUSED = {
  status: 403,
  body: { error: "Hold your security key again.", code: "SECOND_FACTOR_REQUIRED" },
}

/** The two refusals that are NOT this one, and must never open the dialog. */
const NO_KEY_AT_ALL = {
  status: 403,
  body: { error: "This account has no security key.", code: "SECOND_FACTOR_MISSING" },
}
const SIGNED_OUT = { status: 401, body: { error: "Sign in." } }

type Answer = { status: number; body: unknown }

/**
 * A fetch that answers a prepared list in order, and records what it was asked.
 *
 * Deliberately not a mock that always answers the same thing: every assertion here is about the
 * SECOND request - whether it happened, and whether it carried what the first one carried.
 */
function fetchAnswering(...answers: Answer[]) {
  const calls: Array<{ path: string; init: RequestInit }> = []
  const spy = vi.fn(async (path: string, init: RequestInit = {}) => {
    calls.push({ path, init })
    const answer = answers[calls.length - 1]
    if (!answer) throw new Error(`no answer prepared for request ${calls.length} to ${path}`)
    return new Response(JSON.stringify(answer.body), {
      status: answer.status,
      headers: { "Content-Type": "application/json" },
    })
  })
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
    const held = vi.fn(async () => undefined)
    onSecondFactorRequired(held)

    const answer = await api<{ ok: boolean }>("/api/commands", {
      method: "POST",
      body: { command: "season phase" },
    })

    expect(answer).toEqual({ ok: true })
    expect(held).toHaveBeenCalledTimes(1)
    expect(calls).toHaveLength(2)

    // THE SAME REQUEST, not a fresh GET of the same path: the method, the body and the CSRF token
    // all have to survive the ceremony, or the second attempt is a different thing that happens to
    // hit the same route.
    expect(calls[1].path).toBe("/api/commands")
    expect(calls[1].init.method).toBe("POST")
    expect(calls[1].init.body).toBe(JSON.stringify({ command: "season phase" }))
    expect((calls[1].init.headers as Record<string, string>)["X-Steward-CSRF"]).toBe("csrf-token")
  })

  it("gives up after a second refusal rather than looping", async () => {
    const calls = fetchAnswering(REFUSED, REFUSED)
    const held = vi.fn(async () => undefined)
    onSecondFactorRequired(held)

    await expect(api("/api/commands", { method: "POST", body: {} })).rejects.toBeInstanceOf(ApiError)

    // A refusal that survives a successful ceremony is something else entirely - a clock, a
    // session that was taken away - and a third attempt would hide it behind a dialog that keeps
    // reopening.
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
    const held = vi.fn(async () => undefined)
    onSecondFactorRequired(held)

    await expect(api("/auth/webauthn/authenticate/start", { method: "POST" })).rejects.toBeInstanceOf(ApiError)
    expect(held).not.toHaveBeenCalled()
    expect(calls).toHaveLength(1)
  })

  it("is a plain 403 when nothing has installed a handler", async () => {
    const calls = fetchAnswering(REFUSED)

    // The honest default, and the one every other test in this frontend runs under: without a way
    // to hold a key, a 403 is a 403.
    const refusal = await api("/api/commands", { method: "POST", body: {} }).catch((e) => e)
    expect(refusal).toBeInstanceOf(ApiError)
    expect((refusal as ApiError).needsTheKeyAgain).toBe(true)
    expect(calls).toHaveLength(1)
  })

  it("leaves the other two refusals alone", async () => {
    const held = vi.fn(async () => undefined)
    onSecondFactorRequired(held)

    const noKey = fetchAnswering(NO_KEY_AT_ALL)
    const first = await api("/api/commands", { method: "POST", body: {} }).catch((e) => e)
    expect((first as ApiError).needsASecurityKey).toBe(true)
    expect(noKey).toHaveLength(1)

    const signedOut = fetchAnswering(SIGNED_OUT)
    const second = await api("/api/commands", { method: "POST", body: {} }).catch((e) => e)
    expect((second as ApiError).isSignedOut).toBe(true)
    expect(signedOut).toHaveLength(1)

    // Neither is a stale window: one needs a registration page, the other needs Discord. Opening
    // the key dialog for either is a dialog nobody can answer.
    expect(held).not.toHaveBeenCalled()
  })
})
