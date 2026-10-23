import { toast } from "sonner"

import type { ReloadOutcome } from "@/lib/api"
import { message } from "@/lib/texts"

/** Tells how a save applies: at once as a success, otherwise as a note on what it waits for. */
export function announceSave(label: string, reload: ReloadOutcome) {
  if (reload.status === "APPLIED") {
    toast.success(label, { description: message(reload.message) })
    return
  }
  toast.info(label, { description: message(reload.message) })
}
