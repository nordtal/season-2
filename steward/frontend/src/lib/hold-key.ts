import { api } from "@/lib/api"
import type { KeyHeld } from "@/lib/api.gen"
import type { RequestOptionsJson } from "@/lib/webauthn"
import { browserHasSecurityKeys, useSecurityKey, whyTheKeyFailed } from "@/lib/webauthn"

/**
 * Holds the security key in one call: two round trips with a browser dialog between them.
 *
 * One function, not two hooks, since the challenge is single use; outside `lib/api.ts` to avoid an import cycle.
 */
export async function holdTheKey(): Promise<KeyHeld> {
  if (!browserHasSecurityKeys()) {
    throw new Error(
      "This browser cannot use security keys, so it cannot sign in to Steward." +
        " Every current browser can; one in a private window or an old WebView may not.",
    )
  }
  // The server's answer goes to the browser untouched.
  const started = await api<RequestOptionsJson>("/auth/webauthn/authenticate/start", { method: "POST" })
  let credential: string
  try {
    credential = await useSecurityKey(started)
  } catch (refused) {
    // Turns the browser's DOMException into one sentence a person can act on.
    throw new Error(whyTheKeyFailed(refused), { cause: refused })
  }
  return await api<KeyHeld>("/auth/webauthn/authenticate/finish", { method: "POST", body: { credential } })
}
