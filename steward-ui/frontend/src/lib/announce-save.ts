import { toast } from "sonner"

import type { ConfigReloadOutcome } from "@/lib/api"

/**
 * The one toast a save produces, shaped by what `reload` says happened (steward/59).
 *
 * Three outcomes, three different toast types - not three variations of the same green success,
 * which is exactly the "look alike" the ticket rules out. `APPLIED` and `NO_ANSWER` both mean a
 * command was actually sent; only the message says which. `undefined` is the GET-era shape kept
 * for a document nothing has re-fetched yet, and is treated the same as `APPLIED` was always
 * shown: a plain confirmation with the file's name.
 */
export function announceSave(label: string, reload: ConfigReloadOutcome | undefined, fallbackDescription: string) {
  if (!reload || reload.status === "APPLIED") {
    toast.success(label, { description: reload?.message ?? fallbackDescription })
    return
  }
  if (reload.status === "NO_ANSWER") {
    toast.warning(label, { description: reload.message })
    return
  }
  toast.info(label, { description: reload.message })
}
