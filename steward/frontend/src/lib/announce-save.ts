import { toast } from "sonner"

import type { ReloadOutcome } from "@/lib/api"

/**
 * The one toast a save produces, typed by what `reload` says happened.
 *
 * `APPLIED` and `NO_ANSWER` both asked the service; `undefined` is an older document, shown as `APPLIED`.
 */
export function announceSave(label: string, reload: ReloadOutcome | undefined, fallbackDescription: string) {
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
