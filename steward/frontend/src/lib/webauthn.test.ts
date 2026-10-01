import { describe, expect, it, vi } from "vitest"

import {
  browserHasSecurityKeys,
  createSecurityKey,
  fromBase64Url,
  fromCredential,
  toBase64Url,
  toCreationOptions,
  whyTheKeyFailed,
  type AttestationCredentialLike,
  type CreationOptionsJson,
} from "@/lib/webauthn"

/**
 * Converts between the wire's base64url and the browser's buffers, the half jsdom can reach.
 *
 * The alphabets differ in two characters, so a mix up fails only now and then, as a `SecurityError` on a phone.
 */

/** The bytes that separate the two alphabets: 62 is `+`/`-` and 63 is `/`/`_`. */
const ALPHABET_TRAP = new Uint8Array([0xfb, 0xff, 0xbe])

function bufferOf(bytes: Uint8Array): ArrayBuffer {
  const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)
  if (!(buffer instanceof ArrayBuffer)) throw new Error("expected a plain ArrayBuffer, not a shared one")
  return buffer
}

function credentialOf(extra: Partial<AttestationCredentialLike> = {}): AttestationCredentialLike {
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
  }
}

function named(name: string, message = ""): Error {
  const error = new Error(message)
  error.name = name
  return error
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
    /**
     * All three remainders occur, each a different branch; `atob` accepts the padding missing, so it is kept explicit.
     */
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
      user: { id: "MTIz", name: "ally", displayName: "ally" },
      pubKeyCredParams: [{ alg: -7, type: "public-key" }],
      timeout: 120000,
      attestation: "none",
      authenticatorSelection: { residentKey: "discouraged", userVerification: "preferred" },
    },
  }

  it("turns the three byte fields into bytes and leaves everything else alone", () => {
    const options = toCreationOptions(answer)

    expect(options.challenge).toEqual(new Uint8Array([1, 2, 3, 4]))
    /** The user handle is the Discord id as UTF-8 bytes, as `Credentials.handleOf` writes it. */
    expect(options.user.id).toEqual(new Uint8Array([0x31, 0x32, 0x33]))
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
    const options = toCreationOptions(answer)

    // A spread that forgot these leaves a dialog offering to register a key for nobody.
    expect(options.user.name).toBe("ally")
    expect(options.user.displayName).toBe("ally")
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

    const excludeCredentials = toCreationOptions(withKeys).excludeCredentials ?? []

    expect(excludeCredentials).toHaveLength(2)
    expect(excludeCredentials[0].id).toEqual(new Uint8Array([1]))
    expect(excludeCredentials[1].id).toEqual(new Uint8Array([2]))
    expect(excludeCredentials[1].transports).toEqual(["usb", "nfc"])
  })

  it("leaves the exclusion list absent when the server sent none", () => {
    /** An empty array would tell the browser there is nothing to exclude, not nothing to say. */
    const options = toCreationOptions(answer)

    expect(options.excludeCredentials).toBeUndefined()
  })
})

describe("the credential as the server's library reads it", () => {
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
    /** Without the transports, a later sign in offers every method the browser has. */
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
    const create = vi
      .fn<(options: { publicKey: PublicKeyCredentialCreationOptions }) => Promise<AttestationCredentialLike>>()
      .mockResolvedValue({
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
    vi.stubGlobal("navigator", {
      credentials: { create: vi.fn<() => Promise<null>>().mockResolvedValue(null) },
    })

    await expect(createSecurityKey(answer)).rejects.toThrow(/without a key/)
    vi.unstubAllGlobals()
  })
})

describe("what a person is told went wrong", () => {
  it("tells a cancelled dialog apart from a refused one", () => {
    /** A cancel means try again and a known key means use another; the browser's message is usually empty. */
    expect(whyTheKeyFailed(named("NotAllowedError"))).toMatch(/cancelled/)
    expect(whyTheKeyFailed(named("NotAllowedError"))).toMatch(/Nothing was registered/)
    expect(whyTheKeyFailed(named("InvalidStateError"))).toMatch(/already registered/)
  })

  it("blames this server for the one failure that is this server's", () => {
    /** A relying party mismatch is the server's fault, and the person must not blame the key. */
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
