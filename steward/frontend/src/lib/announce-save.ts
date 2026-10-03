import { toast } from "sonner"

import type { ReloadOutcome } from "@/lib/api"
import { message } from "@/lib/texts"

/**
 * The one toast a save produces, typed by when `reload` says the change takes effect.
 *
 * `undefined` is an older document, shown as `APPLIED`.
 */
export function announceSave(label: string, reload: ReloadOutcome | undefined, fallbackDescription: string) {
  if (!reload || reload.status === "APPLIED") {
    toast.success(label, { description: reload ? message(reload.message) : fallbackDescription })
    return
  }
  toast.info(label, { description: message(reload.message) })
}
