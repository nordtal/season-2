import { ArrowSquareInIcon, XIcon } from "@phosphor-icons/react"
import { useQueryClient } from "@tanstack/react-query"
import { useEffect, useRef, useState } from "react"
import { toast } from "sonner"

import { api, errorMessage, type CommandAsked, type CommandRun, type TakeInPurpose } from "@/lib/api"
import { finalRun } from "@/lib/queries-commands"
import { useClosePicker } from "@/components/steward/reference-picker"
import { message, t } from "@/lib/texts"
import { Button } from "@/components/ui/button"

/** An item taken in from the game: its material, and the whole item serialised. */
export type TakenItem = { item: string; data: string }

function withdraw(id: string) {
  api<void>(`/api/commands/${id}`, { method: "DELETE" }).catch((error: unknown) =>
    toast.error(t("steward.game.take-in-failed"), { description: errorMessage(error) }),
  )
}

/** Answers the item a done take-in carries, or undefined when the window was closed empty. */
function takenOf(run: CommandRun): TakenItem | undefined {
  const item = run.answer?.item
  const data = run.answer?.data
  return typeof item === "string" && typeof data === "string" ? { item, data } : undefined
}

/**
 * Asks the admin's player on smp to put in an item for `purpose`, and closes the picker it sits in once one arrives.
 * While the request waits the button withdraws it, and so does leaving the picker; a withdrawn request is never answered.
 */
export function TakeInButton({
  purpose,
  disabled,
  onTaken,
}: {
  purpose: TakeInPurpose
  disabled: boolean
  onTaken: (taken: TakenItem) => void
}) {
  const client = useQueryClient()
  const close = useClosePicker()
  const [waiting, setWaiting] = useState<string | null>(null)
  const [asking, setAsking] = useState(false)
  // The request this button still waits for; a run of any other is stale.
  const current = useRef<string | null>(null)
  const gone = useRef(false)
  const taken = useRef({ onTaken, close })
  useEffect(() => {
    taken.current = { onTaken, close }
  }, [onTaken, close])
  useEffect(() => {
    gone.current = false
    return () => {
      gone.current = true
      if (current.current !== null) withdraw(current.current)
    }
  }, [])

  const ended = (id: string, run: CommandRun) => {
    if (current.current !== id) return
    current.current = null
    setWaiting(null)
    if (run.status === "DONE") {
      const item = takenOf(run)
      if (item) {
        taken.current.onTaken(item)
        taken.current.close()
      }
    } else if (run.status === "REFUSED" || run.status === "EXPIRED") {
      toast.error(t("steward.answer.take-in-offline"))
    } else if (run.status !== "CANCELLED") {
      toast.error(t("steward.game.take-in-failed"), { description: run.result ? message(run.result) : undefined })
    }
  }

  const ask = async () => {
    setAsking(true)
    try {
      const asked = await api<CommandAsked>("/api/smp/take-in", { method: "POST", body: { purpose } })
      // The picker closed while the request was on its way.
      if (gone.current) {
        withdraw(asked.id)
        return
      }
      current.current = asked.id
      setWaiting(asked.id)
      finalRun(client, asked.id).then(
        (run) => ended(asked.id, run),
        (error: unknown) => {
          if (current.current !== asked.id) return
          current.current = null
          setWaiting(null)
          toast.error(t("steward.game.take-in-failed"), { description: errorMessage(error) })
        },
      )
    } catch (error) {
      toast.error(t("steward.game.take-in-failed"), { description: errorMessage(error) })
    } finally {
      if (!gone.current) setAsking(false)
    }
  }

  const cancel = () => {
    if (waiting === null) return
    current.current = null
    setWaiting(null)
    withdraw(waiting)
  }

  return waiting === null ? (
    <Button type="button" variant="outline" disabled={disabled || asking} className="max-sm:w-full" onClick={ask}>
      <ArrowSquareInIcon aria-hidden />
      {t("steward.game.take-in")}
    </Button>
  ) : (
    <Button type="button" variant="destructive" className="max-sm:w-full" onClick={cancel}>
      <XIcon aria-hidden />
      {t("steward.game.cancel-take-in")}
    </Button>
  )
}
