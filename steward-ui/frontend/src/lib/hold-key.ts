/**
 * Holding the security key, as one call.
 *
 * Two round trips with a browser dialog between them, exactly like registration - and kept apart
 * from `lib/api.ts` on purpose: that file is the door every request goes through and must not
 * import a ceremony, or the ceremony's own two requests would go round in a circle.
 *
 * **The challenge is single-use and lives ten minutes.** That is why this is one function and not
 * a start hook and a finish hook: a component holding a half-finished ceremony across a re-render
 * is holding a spent challenge, and the half that is spent is the half nobody can see.
 */
import { api } from "@/lib/api"
import type { RequestOptionsJson } from "@/lib/webauthn"
import { browserHasSecurityKeys, useSecurityKey, whyTheKeyFailed } from "@/lib/webauthn"

/** What the server says about the key that just answered. */
export type Held = {
  label: string
  userVerified: boolean
}

export async function holdTheKey(): Promise<Held> {
  if (!browserHasSecurityKeys()) {
    throw new Error("This browser cannot use security keys, so it cannot sign in to Steward."
      + " Every current browser can; one in a private window or an old WebView may not.")
  }
  // The server's answer is handed to the browser untouched - it is the library's own JSON and this
  // end does not get an opinion about its contents.
  const started = await api<RequestOptionsJson>("/auth/webauthn/authenticate/start",
    { method: "POST" })
  let credential: string
  try {
    credential = await useSecurityKey(started)
  } catch (refused) {
    // The browser's DOMException, turned into something a person can act on. Rethrown as a plain
    // Error so the dialog prints one sentence rather than "NotAllowedError".
    throw new Error(whyTheKeyFailed(refused))
  }
  return await api<Held>("/auth/webauthn/authenticate/finish",
    { method: "POST", body: { credential } })
}
