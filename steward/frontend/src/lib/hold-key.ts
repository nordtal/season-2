import { api } from "@/lib/api"
import type { KeyHeld, StepUpLength } from "@/lib/api.gen"
import type { RequestOptionsJson } from "@/lib/webauthn"
import { browserHasSecurityKeys, useSecurityKey, whyTheKeyFailed } from "@/lib/webauthn"
import { t } from "@/lib/texts"

/**
 * Holds the security key in one call: two round trips with a browser dialog between them. It is one function because the challenge is single-use, and it sits outside `lib/api.ts` to avoid an import cycle. Without `length` the server's default applies.
 */
export async function holdTheKey(length?: StepUpLength): Promise<KeyHeld> {
  if (!browserHasSecurityKeys()) {
    throw new Error(t("steward.keys.browser-cannot"))
  }
  const started = await api<RequestOptionsJson>("/auth/webauthn/authenticate/start", { method: "POST" })
  let credential: string
  try {
    credential = await useSecurityKey(started)
  } catch (refused) {
    throw new Error(whyTheKeyFailed(refused), { cause: refused })
  }
  return await api<KeyHeld>("/auth/webauthn/authenticate/finish", {
    method: "POST",
    body: length ? { credential, hold: length } : { credential },
  })
}
