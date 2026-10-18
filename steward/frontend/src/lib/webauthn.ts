import { t } from "@/lib/texts"

/**
 * Converts a WebAuthn ceremony between the server's base64url JSON and the browser's `ArrayBuffer`s.
 *
 * It decides nothing. It avoids `parseCreationOptionsFromJSON` because jsdom has no `PublicKeyCredential`.
 */

/**
 * Returns whether this browser can do WebAuthn at all. An old browser gets a sentence and no crash.
 */
export function browserHasSecurityKeys(): boolean {
  return typeof window !== "undefined" && !!window.PublicKeyCredential && !!navigator.credentials
}

export function fromBase64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/")
  /**
   * `atob` accepts the string unpadded, but the padding is written out so that it is correct and not lucky.
   */
  const binary = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4))
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
  return bytes
}

export function toBase64Url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  let binary = ""
  /**
   * A loop and no spread, because the spread's argument list would overflow the stack on a certificate chain.
   */
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

/**
 * What `/auth/webauthn/register/start` answers, with its byte fields still in base64url.
 *
 * Only the fields this file touches are named. The rest passes through as the server decided.
 */
export type CreationOptionsJson = {
  publicKey: Record<string, unknown> & {
    challenge: string
    user: Record<string, unknown> & { id: string }
    excludeCredentials?: Array<Record<string, unknown> & { id: string }>
  }
}

/**
 * The parts of `PublicKeyCredential` this file reads, one type per ceremony.
 *
 * A real credential satisfies both structurally, and a test can build one as a plain object.
 */
export type AttestationCredentialLike = {
  type: string
  id: string
  rawId: ArrayBuffer
  response: {
    clientDataJSON: ArrayBuffer
    attestationObject: ArrayBuffer
    getTransports?: () => string[]
  }
  authenticatorAttachment?: string | null
  getClientExtensionResults: () => Record<string, unknown>
}

export type AssertionCredentialLike = {
  type: string
  id: string
  rawId: ArrayBuffer
  response: {
    clientDataJSON: ArrayBuffer
    authenticatorData: ArrayBuffer
    signature: ArrayBuffer
    userHandle?: ArrayBuffer | null
  }
  authenticatorAttachment?: string | null
  getClientExtensionResults: () => Record<string, unknown>
}

/** `CreationOptionsJson`'s `publicKey` with its byte fields converted. The rest stays untyped. */
export type ConvertedCreationOptions = Record<string, unknown> & {
  challenge: Uint8Array
  user: Record<string, unknown> & { id: Uint8Array }
  excludeCredentials?: Array<Record<string, unknown> & { id: Uint8Array }>
}

/**
 * Returns the server's JSON as the browser's API wants it: the same object, with three fields as bytes.
 */
export function toCreationOptions(answer: CreationOptionsJson): ConvertedCreationOptions {
  const publicKey = answer.publicKey
  return {
    ...publicKey,
    challenge: fromBase64Url(publicKey.challenge),
    user: { ...publicKey.user, id: fromBase64Url(publicKey.user.id) },
    excludeCredentials: publicKey.excludeCredentials?.map((one) => ({
      ...one,
      id: fromBase64Url(one.id),
    })),
  }
}

/**
 * Returns the credential as the server's library reads it.
 *
 * It is written by hand because `toJSON()` omits `transports`, which a later sign-in needs to offer the right method.
 */
export function fromCredential(credential: AttestationCredentialLike): string {
  const response = credential.response
  const answer: Record<string, unknown> = {
    type: credential.type,
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    response: {
      clientDataJSON: toBase64Url(response.clientDataJSON),
      attestationObject: toBase64Url(response.attestationObject),
      // Not every browser has the method, and an absent list is not an empty one.
      ...(typeof response.getTransports === "function" ? { transports: response.getTransports() } : {}),
    },
    clientExtensionResults: credential.getClientExtensionResults(),
  }
  if (credential.authenticatorAttachment) {
    answer.authenticatorAttachment = credential.authenticatorAttachment
  }
  return JSON.stringify(answer)
}

/**
 * Holds the dialog open and returns what the key said.
 *
 * It throws what the browser threw: `NotAllowedError` means cancelled or timed out, and `InvalidStateError` means a known key.
 */
export async function createSecurityKey(startAnswer: CreationOptionsJson): Promise<string> {
  const publicKey = toCreationOptions(startAnswer)
  if (!isCreationOptions(publicKey)) {
    throw new Error(t("steward.keys.registration-incomplete"))
  }
  const credential = await navigator.credentials.create({ publicKey })
  if (!isAttestationCredential(credential)) {
    throw new Error(t("steward.keys.no-key-returned"))
  }
  return fromCredential(credential)
}

/**
 * Returns whether {@link toCreationOptions} converted its byte fields. The rest is trusted as the server sent it.
 */
function isCreationOptions(
  value: ConvertedCreationOptions,
): value is ConvertedCreationOptions & PublicKeyCredentialCreationOptions {
  return value.challenge instanceof Uint8Array && value.user.id instanceof Uint8Array
}

/**
 * Returns whether the browser gave back the fields that {@link fromCredential} reads, and not just any `Credential`.
 */
function isAttestationCredential(credential: Credential | null): credential is AttestationCredentialLike {
  return (
    credential !== null &&
    "response" in credential &&
    typeof credential.response === "object" &&
    credential.response !== null &&
    "attestationObject" in credential.response &&
    "getClientExtensionResults" in credential
  )
}

/**
 * Returns what went wrong as a sentence and not as a DOMException name: the key refused, or this service did.
 */
export function whyTheKeyFailed(error: unknown): string {
  const name = error instanceof Error ? error.name : ""
  switch (name) {
    case "NotAllowedError":
      return t("steward.keys.cancelled")
    case "InvalidStateError":
      return t("steward.keys.already-registered")
    case "AbortError":
      return t("steward.keys.aborted")
    case "SecurityError":
      return t("steward.keys.wrong-domain")
    case "NotSupportedError":
      return t("steward.keys.unsupported")
    default:
      return error instanceof Error && error.message ? error.message : t("steward.keys.unexplained")
  }
}

/**
 * What `/auth/webauthn/authenticate/start` answers, with its byte fields still in base64url.
 *
 * `allowCredentials` holds the account's own keys, so this sign-in is never usernameless.
 */
export type RequestOptionsJson = {
  publicKey: Record<string, unknown> & {
    challenge: string
    allowCredentials?: Array<Record<string, unknown> & { id: string }>
  }
}

/**
 * `RequestOptionsJson`'s `publicKey` with its byte fields converted, like `ConvertedCreationOptions`.
 */
export type ConvertedRequestOptions = Record<string, unknown> & {
  challenge: Uint8Array
  allowCredentials?: Array<Record<string, unknown> & { id: Uint8Array }>
}

/**
 * Returns the server's JSON as `navigator.credentials.get` wants it: the same object, with bytes.
 */
export function toRequestOptions(answer: RequestOptionsJson): ConvertedRequestOptions {
  const publicKey = answer.publicKey
  return {
    ...publicKey,
    challenge: fromBase64Url(publicKey.challenge),
    allowCredentials: publicKey.allowCredentials?.map((one) => ({
      ...one,
      id: fromBase64Url(one.id),
    })),
  }
}

/**
 * Returns the assertion as the server's library reads it.
 *
 * `userHandle` is omitted when absent, because the keys here are not discoverable and a strict parser rejects `null`.
 */
export function fromAssertion(credential: AssertionCredentialLike): string {
  const response = credential.response
  const inner: Record<string, unknown> = {
    clientDataJSON: toBase64Url(response.clientDataJSON),
    authenticatorData: toBase64Url(response.authenticatorData),
    signature: toBase64Url(response.signature),
  }
  if (response.userHandle) inner.userHandle = toBase64Url(response.userHandle)
  const answer: Record<string, unknown> = {
    type: credential.type,
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    response: inner,
    clientExtensionResults: credential.getClientExtensionResults(),
  }
  if (credential.authenticatorAttachment) {
    answer.authenticatorAttachment = credential.authenticatorAttachment
  }
  return JSON.stringify(answer)
}

/** Holds the dialog open and returns the signature. It throws what the browser threw. */
export async function useSecurityKey(startAnswer: RequestOptionsJson): Promise<string> {
  const publicKey = toRequestOptions(startAnswer)
  if (!isRequestOptions(publicKey)) {
    throw new Error(t("steward.keys.sign-in-incomplete"))
  }
  const credential = await navigator.credentials.get({ publicKey })
  if (!isAssertionCredential(credential)) {
    throw new Error(t("steward.keys.no-answer"))
  }
  return fromAssertion(credential)
}

/**
 * Returns whether the challenge of {@link toRequestOptions} arrived as bytes. Every other field passes through untyped.
 */
function isRequestOptions(
  value: ConvertedRequestOptions,
): value is ConvertedRequestOptions & PublicKeyCredentialRequestOptions {
  return value.challenge instanceof Uint8Array
}

/**
 * Returns whether the browser gave back the fields that {@link fromAssertion} reads, and not just any `Credential`.
 */
function isAssertionCredential(credential: Credential | null): credential is AssertionCredentialLike {
  return (
    credential !== null &&
    "response" in credential &&
    typeof credential.response === "object" &&
    credential.response !== null &&
    "signature" in credential.response &&
    "getClientExtensionResults" in credential
  )
}
