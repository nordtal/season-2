import { describe, expect, it, vi } from "vitest"

import {
  browserHasSecurityKeys,
  createSecurityKey,
  fromBase64Url,
  fromCredential,
  toBase64Url,
  toCreationOptions,
  whyTheKeyFailed,
  type CreationOptionsJson,
} from "@/lib/webauthn"

/**
 * The conversion between what travels and what the browser's API takes.
 *
 * There is nothing clever in this file and that is the point: every one of these is a byte-for-byte
 * identity that is trivially true until somebody writes `base64` where `base64url` was meant, at
 * which point the failure is a `SecurityError` in a dialog on somebody's phone with nothing in any
 * log on the server. The two alphabets differ in exactly two characters out of sixty-four, so a
 * mistake shows up in roughly one challenge in twenty - which is worse than always.
 *
 * The server half of the same boundary is covered by `StewardUiIntegrationTest` against a real
 * software authenticator; this is the half jsdom can reach.
 */

/** The one byte pair that separates the two alphabets: 62 is `+`/`-` and 63 is `/`/`_`. */
const ALPHABET_TRAP = new Uint8Array([0xfb, 0xff, 0xbe])

function bufferOf(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer
}

describe("base64url, which is not base64", () => {
  it("never writes a character base64url does not have", () => {
    const encoded = toBase64Url(bufferOf(ALPHABET_TRAP))

    // btoa of these three bytes is "+/++", i.e. both of the characters that differ.
    expect(encoded).toBe("-_--")
    expect(encoded).not.toMatch(/[+/=]/)
  })

  it("reads back every byte value there is", () => {
    const every = new Uint8Array(256)
    for (let i = 0; i < 256; i += 1) every[i] = i

    expect(fromBase64Url(toBase64Url(bufferOf(every)))).toEqual(every)
  })

  it("round-trips at all three padding lengths", () => {
    // A challenge is 32 bytes and a credential id is whatever the key chose, so all three
    // remainders occur in practice - and each one takes a different branch of the four-to-three
    // byte packing. What this does NOT cover is the `"=".repeat(...)` that puts the padding back:
    // measured by deleting it, all twenty tests stay green, because `atob` accepts an unpadded
    // string. It is kept as the explicit thing rather than as one that happens to work.
    for (const length of [1, 2, 3, 31, 32, 33]) {
      const bytes = new Uint8Array(length)
      for (let i = 0; i < length; i += 1) bytes[i] = (i * 37 + 11) % 256

      expect(fromBase64Url(toBase64Url(bufferOf(bytes))), `length ${length}`).toEqual(bytes)
    }
  })

  it("is empty for empty, rather than a character of nothing", () => {
    expect(toBase64Url(bufferOf(new Uint8Array(0)))).toBe("")
    expect(fromBase64Url("")).toEqual(new Uint8Array(0))
  })
})

describe("the server's JSON as the browser's API wants it", () => {
  const answer: CreationOptionsJson = {
    publicKey: {
      challenge: "AQIDBA",
      rp: { id: "nordtal.eu", name: "Nordtal Steward" },
      user: { id: "MTIz", name: "till", displayName: "till" },
      pubKeyCredParams: [{ alg: -7, type: "public-key" }],
      timeout: 120000,
      attestation: "none",
      authenticatorSelection: { residentKey: "discouraged", userVerification: "preferred" },
    },
  }

  it("turns the three byte fields into bytes and leaves everything else alone", () => {
    const options = toCreationOptions(answer) as unknown as Record<string, unknown>

    expect(new Uint8Array(options.challenge as ArrayBuffer)).toEqual(new Uint8Array([1, 2, 3, 4]))
    // "MTIz" is the digits 1, 2, 3 - the user handle is the Discord id as UTF-8 bytes, which is
    // what `Credentials.handleOf` writes on the other side.
    expect(new Uint8Array((options.user as { id: ArrayBuffer }).id)).toEqual(new Uint8Array([0x31, 0x32, 0x33]))
    expect(options.rp).toEqual({ id: "nordtal.eu", name: "Nordtal Steward" })
    expect(options.pubKeyCredParams).toEqual([{ alg: -7, type: "public-key" }])
    expect(options.timeout).toBe(120000)
    expect(options.attestation).toBe("none")
    expect(options.authenticatorSelection).toEqual({
      residentKey: "discouraged",
      userVerification: "preferred",
    })
  })

  it("keeps the rest of the user object, which is what the dialog prints", () => {
    const options = toCreationOptions(answer) as unknown as { user: Record<string, unknown> }

    // A spread that forgot these leaves a dialog offering to register a key for nobody.
    expect(options.user.name).toBe("till")
    expect(options.user.displayName).toBe("till")
  })

  it("converts every excluded credential, not only the first", () => {
    const withKeys: CreationOptionsJson = {
      publicKey: {
        ...answer.publicKey,
        excludeCredentials: [
          { id: "AQ", type: "public-key" },
          { id: "Ag", type: "public-key", transports: ["usb", "nfc"] },
        ],
      },
    }

    const options = toCreationOptions(withKeys) as unknown as {
      excludeCredentials: Array<{ id: ArrayBuffer; type: string; transports?: string[] }>
    }

    expect(options.excludeCredentials).toHaveLength(2)
    expect(new Uint8Array(options.excludeCredentials[0].id)).toEqual(new Uint8Array([1]))
    expect(new Uint8Array(options.excludeCredentials[1].id)).toEqual(new Uint8Array([2]))
    expect(options.excludeCredentials[1].transports).toEqual(["usb", "nfc"])
  })

  it("leaves the exclusion list absent when the server sent none", () => {
    // An empty array is not the same thing: it is a list, and a browser reading one has been told
    // there is nothing to exclude rather than nothing to say.
    const options = toCreationOptions(answer) as unknown as Record<string, unknown>

    expect(options.excludeCredentials).toBeUndefined()
  })
})

describe("the credential as the server's library reads it", () => {
  function credentialOf(extra: Partial<Record<string, unknown>> = {}) {
    return {
      type: "public-key",
      id: "Zm9v",
      rawId: bufferOf(new Uint8Array([0x66, 0x6f, 0x6f])),
      response: {
        clientDataJSON: bufferOf(new Uint8Array([0x7b, 0x7d])),
        attestationObject: bufferOf(new Uint8Array([0xa0])),
        getTransports: () => ["internal", "hybrid"],
      },
      getClientExtensionResults: () => ({}),
      ...extra,
    } as unknown as PublicKeyCredential
  }

  it("writes the shape the library parses, with the bytes base64url", () => {
    const written = JSON.parse(fromCredential(credentialOf()))

    expect(written).toMatchObject({
      type: "public-key",
      id: "Zm9v",
      rawId: "Zm9v",
      response: { clientDataJSON: "e30", attestationObject: "oA" },
      clientExtensionResults: {},
    })
  })

  it("carries the transports, which the browser's own toJSON() does not", () => {
    // The reason this file exists rather than a call to `credential.toJSON()`: without this list a
    // later authentication dialog offers every method the browser has instead of the one that works.
    const written = JSON.parse(fromCredential(credentialOf()))

    expect(written.response.transports).toEqual(["internal", "hybrid"])
  })

  it("says nothing about transports when the browser cannot say", () => {
    const blind = credentialOf({
      response: {
        clientDataJSON: bufferOf(new Uint8Array([0x7b, 0x7d])),
        attestationObject: bufferOf(new Uint8Array([0xa0])),
      },
    })

    const written = JSON.parse(fromCredential(blind))

    // Absent, not `[]` and not `null`. An empty list claims the key has no way in at all.
    expect("transports" in written.response).toBe(false)
  })

  it("omits the attachment when the browser did not report one", () => {
    expect("authenticatorAttachment" in JSON.parse(fromCredential(credentialOf()))).toBe(false)
  })

  it("carries the attachment when it is there", () => {
    const written = JSON.parse(fromCredential(credentialOf({ authenticatorAttachment: "cross-platform" })))

    expect(written.authenticatorAttachment).toBe("cross-platform")
  })
})

describe("the dialog", () => {
  const answer: CreationOptionsJson = {
    publicKey: { challenge: "AQIDBA", user: { id: "MTIz" } },
  }

  it("asks the browser with the converted options", async () => {
    const create = vi.fn().mockResolvedValue({
      type: "public-key",
      id: "Zm9v",
      rawId: bufferOf(new Uint8Array([1])),
      response: {
        clientDataJSON: bufferOf(new Uint8Array([1])),
        attestationObject: bufferOf(new Uint8Array([1])),
      },
      getClientExtensionResults: () => ({}),
    })
    vi.stubGlobal("navigator", { credentials: { create } })

    await createSecurityKey(answer)

    const asked = create.mock.calls[0][0].publicKey
    expect(asked.challenge).toBeInstanceOf(Uint8Array)
    vi.unstubAllGlobals()
  })

  it("throws rather than sending an empty registration when the browser hands back nothing", async () => {
    vi.stubGlobal("navigator", { credentials: { create: vi.fn().mockResolvedValue(null) } })

    await expect(createSecurityKey(answer)).rejects.toThrow(/without a key/)
    vi.unstubAllGlobals()
  })
})

describe("what a person is told went wrong", () => {
  function named(name: string, message = "") {
    const error = new Error(message)
    error.name = name
    return error
  }

  it("tells a cancelled dialog apart from a refused one", () => {
    // The two that are not faults, and the pair that matters most: one means try again, the other
    // means try a different key. The browser's own message for both is usually the empty string.
    expect(whyTheKeyFailed(named("NotAllowedError"))).toMatch(/cancelled/)
    expect(whyTheKeyFailed(named("NotAllowedError"))).toMatch(/Nothing was registered/)
    expect(whyTheKeyFailed(named("InvalidStateError"))).toMatch(/already registered/)
  })

  it("blames this server for the one failure that is this server's", () => {
    // A SecurityError is relying-party-id against the page's address - `Configs.requireRelyingParty`
    // refuses the same mistake at startup. If one ever reaches a browser, the person holding the key
    // must not be left thinking their key is broken.
    expect(whyTheKeyFailed(named("SecurityError"))).toMatch(/configuration fault on this server/)
  })

  it("falls back to the browser's own words, and to a sentence when it has none", () => {
    expect(whyTheKeyFailed(named("TypeError", "options is not an object"))).toBe("options is not an object")
    expect(whyTheKeyFailed(named("TypeError"))).toMatch(/did not say why/)
    expect(whyTheKeyFailed("not an error at all")).toMatch(/did not say why/)
  })
})

describe("a browser too old for any of this", () => {
  it("is a no rather than a crash", () => {
    vi.stubGlobal("window", {})
    vi.stubGlobal("navigator", {})
    expect(browserHasSecurityKeys()).toBe(false)
    vi.unstubAllGlobals()
  })

  it("is a yes when both halves are there", () => {
    vi.stubGlobal("window", { PublicKeyCredential: function () {} })
    vi.stubGlobal("navigator", { credentials: {} })
    expect(browserHasSecurityKeys()).toBe(true)
    vi.unstubAllGlobals()
  })
})
