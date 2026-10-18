import { toast } from "sonner"

import type { ReloadOutcome } from "@/lib/api"
import { message } from "@/lib/texts"

/** `undefined` is an older document and is shown as `APPLIED`. */
export function announceSave(label: string, reload: ReloadOutcome | undefined, fallbackDescription: string) {
  if (!reload || reload.status === "APPLIED") {
    toast.success(label, { description: reload ? message(reload.message) : fallbackDescription })
    return
  }
  toast.info(label, { description: message(reload.message) })
}
