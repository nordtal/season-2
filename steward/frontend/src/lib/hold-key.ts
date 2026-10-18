import { api } from "@/lib/api"
import type { KeyHeld, StepUpLength } from "@/lib/api.gen"
import type { RequestOptionsJson } from "@/lib/webauthn"
import { browserHasSecurityKeys, useSecurityKey, whyTheKeyFailed } from "@/lib/webauthn"
import { t } from "@/lib/texts"

/**
 * Holds the security key in one call: two round trips with a browser dialog between them.
 *
 * It is one function and not two hooks, because the challenge is single-use. It is outside `lib/api.ts` to avoid an import cycle.
 * `length` is how long the step-up holds. Without it the server's default applies.
 */
export async function holdTheKey(length?: StepUpLength): Promise<KeyHeld> {
  if (!browserHasSecurityKeys()) {
    throw new Error(t("steward.keys.browser-cannot"))
  }
  // The server's answer goes to the browser unchanged.
  const started = await api<RequestOptionsJson>("/auth/webauthn/authenticate/start", { method: "POST" })
  let credential: string
  try {
    credential = await useSecurityKey(started)
  } catch (refused) {
    // Turns the browser's DOMException into one sentence that a person can act on.
    throw new Error(whyTheKeyFailed(refused), { cause: refused })
  }
  return await api<KeyHeld>("/auth/webauthn/authenticate/finish", {
    method: "POST",
    body: length ? { credential, hold: length } : { credential },
  })
}
