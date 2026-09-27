import { assert, describe, expect, it, vi } from "vitest"

import {
  currentPushEndpoint,
  pushSupported,
  registerServiceWorker,
  subscribeToPush,
  unsubscribeFromPush,
  urlBase64ToUint8Array,
} from "@/lib/push"

/**
 * Base64url into the bytes `applicationServerKey` wants, and the calls around a fake `navigator.serviceWorker`.
 *
 * jsdom has no Push API, so what is tested is that a fake call sequence gives the right result.
 */

describe("the VAPID public key as the browser's API wants it", () => {
  it("decodes a known base64url value", () => {
    // "AQIDBA" is base64url (no padding) of the four bytes 1, 2, 3, 4.
    expect(urlBase64ToUint8Array("AQIDBA")).toEqual(new Uint8Array([1, 2, 3, 4]))
  })

  it("reads the two characters that differ from plain base64", () => {
    // 0xfb 0xff 0xbe is base64 "+/++" and base64url "-_--", as in webauthn.test.ts's ALPHABET_TRAP.
    expect(urlBase64ToUint8Array("-_--")).toEqual(new Uint8Array([0xfb, 0xff, 0xbe]))
  })

  it("round-trips at every padding remainder a key length can land on", () => {
    /** A P-256 point is 65 bytes uncompressed, not a multiple of 3; the shorter lengths cover the other remainders. */
    const cases: Array<[string, number[]]> = [
      ["AQ", [1]],
      ["AQI", [1, 2]],
      ["AQID", [1, 2, 3]],
    ]
    for (const [base64Url, bytes] of cases) {
      assert.deepEqual(urlBase64ToUint8Array(base64Url), new Uint8Array(bytes), base64Url)
    }
  })
})

describe("a browser too old for any of this", () => {
  it("is a no rather than a crash", () => {
    vi.stubGlobal("window", {})
    vi.stubGlobal("navigator", {})
    expect(pushSupported()).toBe(false)
    vi.unstubAllGlobals()
  })

  it("is a yes when all three pieces are there", () => {
    vi.stubGlobal("window", { PushManager: function () {}, Notification: function () {} })
    vi.stubGlobal("navigator", { serviceWorker: {} })
    expect(pushSupported()).toBe(true)
    vi.unstubAllGlobals()
  })

  it("is a no missing only PushManager", () => {
    vi.stubGlobal("window", { Notification: function () {} })
    vi.stubGlobal("navigator", { serviceWorker: {} })
    expect(pushSupported()).toBe(false)
    vi.unstubAllGlobals()
  })
})

describe("registering the service worker", () => {
  it("registers /sw.js when the browser can", async () => {
    const register = vi.fn<() => Promise<{ scope: string }>>().mockResolvedValue({ scope: "/" })
    vi.stubGlobal("navigator", { serviceWorker: { register } })

    await registerServiceWorker()

    expect(register).toHaveBeenCalledWith("/sw.js")
    vi.unstubAllGlobals()
  })

  it("is a no-op, not a throw, on a browser with no serviceWorker", async () => {
    vi.stubGlobal("navigator", {})
    await expect(registerServiceWorker()).resolves.toBeNull()
    vi.unstubAllGlobals()
  })
})

describe("subscribing, called directly from the button's tap", () => {
  it("waits for the ready registration and passes the decoded key through", async () => {
    const subscription = { toJSON: () => ({ endpoint: "https://push.example/x" }) }
    const subscribe = vi
      .fn<(options: PushSubscriptionOptionsInit) => Promise<typeof subscription>>()
      .mockResolvedValue(subscription)
    vi.stubGlobal("navigator", {
      serviceWorker: { ready: Promise.resolve({ pushManager: { subscribe } }) },
    })

    const result = await subscribeToPush("AQIDBA")

    expect(subscribe.mock.calls[0][0].userVisibleOnly).toBe(true)
    expect(subscribe.mock.calls[0][0].applicationServerKey).toEqual(new Uint8Array([1, 2, 3, 4]))
    expect(result).toEqual({ endpoint: "https://push.example/x" })
    vi.unstubAllGlobals()
  })
})

describe("unsubscribing", () => {
  it("unsubscribes the existing subscription and returns its endpoint", async () => {
    const unsubscribe = vi.fn<() => Promise<boolean>>().mockResolvedValue(true)
    const subscription = { endpoint: "https://push.example/x", unsubscribe }
    vi.stubGlobal("navigator", {
      serviceWorker: {
        ready: Promise.resolve({ pushManager: { getSubscription: () => Promise.resolve(subscription) } }),
      },
    })

    expect(await unsubscribeFromPush()).toBe("https://push.example/x")
    expect(unsubscribe).toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it("is null, not a throw, when there was nothing subscribed", async () => {
    vi.stubGlobal("navigator", {
      serviceWorker: {
        ready: Promise.resolve({ pushManager: { getSubscription: () => Promise.resolve(null) } }),
      },
    })

    expect(await unsubscribeFromPush()).toBeNull()
    vi.unstubAllGlobals()
  })

  it("is null on a browser with no serviceWorker at all", async () => {
    vi.stubGlobal("navigator", {})
    expect(await unsubscribeFromPush()).toBeNull()
    vi.unstubAllGlobals()
  })
})

describe("the button's own state", () => {
  it("reads the endpoint of an existing subscription", async () => {
    vi.stubGlobal("navigator", {
      serviceWorker: {
        getRegistration: () =>
          Promise.resolve({
            pushManager: { getSubscription: () => Promise.resolve({ endpoint: "https://push.example/y" }) },
          }),
      },
    })

    expect(await currentPushEndpoint()).toBe("https://push.example/y")
    vi.unstubAllGlobals()
  })

  it("is null when there is no registration yet", async () => {
    vi.stubGlobal("navigator", { serviceWorker: { getRegistration: () => Promise.resolve(null) } })

    expect(await currentPushEndpoint()).toBeNull()
    vi.unstubAllGlobals()
  })
})
