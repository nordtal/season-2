/**
 * The browser's half of a WebAuthn ceremony.
 *
 * The server speaks JSON with base64url strings in it, because that is what travels; the browser's
 * API speaks `ArrayBuffer`. This file is the conversion and nothing else - it makes no decisions,
 * and every sentence a person reads about a failure is composed by the caller.
 *
 * **Why not `PublicKeyCredential.parseCreationOptionsFromJSON`.** Browsers grew that method
 * exactly for this, and it would replace half of this file. It is also the one piece of a sign-in
 * that would then be untestable here: jsdom has no `PublicKeyCredential` at all, so a feature test
 * would either be skipped or mocked into meaninglessness, and the fallback path - the code below -
 * would be the part that actually runs and the part nothing covers. One path, written out, tested.
 */

/** Whether this browser can do WebAuthn at all. An old browser is a sentence, not a crash. */
export function browserHasSecurityKeys(): boolean {
  return typeof window !== "undefined" && !!window.PublicKeyCredential && !!navigator.credentials
}

export function fromBase64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/")
  // `atob` accepts an unpadded string - measured 2026-09-14 by deleting the `"=".repeat(...)` below
  // and watching the whole suite stay green. It is written out anyway, because "it happens to work
  // without" is not the same as "it is correct", and the next reader should not have to re-measure.
  const binary = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4))
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
  return bytes
}

export function toBase64Url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  let binary = ""
  // Not `String.fromCharCode(...bytes)`: an attestation object is a few hundred bytes today and
  // a spread of one of those is a few hundred arguments, which is fine - until somebody registers
  // a key whose attestation carries a certificate chain and the call stack decides otherwise.
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

/**
 * What `/auth/webauthn/register/start` answers, with its byte fields still base64url.
 *
 * Only the fields this file touches are named. Everything else - `rp`, `pubKeyCredParams`,
 * `authenticatorSelection`, `timeout`, `attestation` - is passed through untouched, because the
 * server decides them and a second opinion here would be a second place to change them.
 */
export type CreationOptionsJson = {
  publicKey: Record<string, unknown> & {
    challenge: string
    user: Record<string, unknown> & { id: string }
    excludeCredentials?: Array<Record<string, unknown> & { id: string }>
  }
}

/** The server's JSON as the browser's API wants it: the same object, with three fields as bytes. */
export function toCreationOptions(answer: CreationOptionsJson): PublicKeyCredentialCreationOptions {
  const publicKey = answer.publicKey
  return {
    ...publicKey,
    challenge: fromBase64Url(publicKey.challenge),
    user: { ...publicKey.user, id: fromBase64Url(publicKey.user.id) },
    excludeCredentials: publicKey.excludeCredentials?.map((one) => ({
      ...one,
      id: fromBase64Url(one.id),
    })),
  } as unknown as PublicKeyCredentialCreationOptions
}

/**
 * The credential as the server's library reads it.
 *
 * Written out by hand rather than with the browser's own `toJSON()`, for the same reason as above -
 * and because the two are not quite the same: `toJSON()` omits `transports`, which is what lets an
 * authentication dialog later say "hold it to the top of the phone" instead of offering every
 * method the browser has.
 */
export function fromCredential(credential: PublicKeyCredential): string {
  const response = credential.response as AuthenticatorAttestationResponse
  const answer: Record<string, unknown> = {
    type: credential.type,
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    response: {
      clientDataJSON: toBase64Url(response.clientDataJSON),
      attestationObject: toBase64Url(response.attestationObject),
      // Not every browser has it, and an absent list is not an empty one.
      ...(typeof response.getTransports === "function"
        ? { transports: response.getTransports() }
        : {}),
    },
    clientExtensionResults: credential.getClientExtensionResults(),
  }
  if (credential.authenticatorAttachment) {
    answer.authenticatorAttachment = credential.authenticatorAttachment
  }
  return JSON.stringify(answer)
}

/**
 * Holds the dialog open and hands back what the key said.
 *
 * Throws whatever the browser threw. The two worth telling apart are `NotAllowedError` - which is
 * "cancelled, or timed out", and the browser will not say which - and `InvalidStateError`, which
 * means this authenticator is already registered on this account. Neither is a fault.
 */
export async function createSecurityKey(startAnswer: CreationOptionsJson): Promise<string> {
  const credential = (await navigator.credentials.create({
    publicKey: toCreationOptions(startAnswer),
  })) as PublicKeyCredential | null
  if (!credential) {
    throw new Error("The browser ended the dialog without a key.")
  }
  return fromCredential(credential)
}

/**
 * What went wrong, as a sentence rather than a DOMException name.
 *
 * A person who has just touched their key and seen nothing happen needs to know which of two very
 * different things occurred: their key refused, or this service did. The browser's own message is
 * usually empty and its `name` is jargon.
 */
export function whyTheKeyFailed(error: unknown): string {
  const name = error instanceof Error ? error.name : ""
  switch (name) {
    case "NotAllowedError":
      return "The dialog was cancelled, or it waited too long. Nothing was registered - try again."
    case "InvalidStateError":
      return "That key is already registered on this account. Use a different one, or sign in with"
        + " the one you are holding."
    case "SecurityError":
      return "The browser refused because this page's address does not match the domain the key"
        + " would be registered to. That is a configuration fault on this server, not on your key."
    case "NotSupportedError":
      return "This authenticator cannot do what Steward asked for. A different key or your phone's"
        + " own unlock will work."
    default:
      return error instanceof Error && error.message
        ? error.message
        : "The key could not be registered, and the browser did not say why."
  }
}
