import type { Icon } from "@phosphor-icons/react"
import { useState } from "react"

import type { AdminCommand } from "@/lib/api"
import { useAdminCommand, useCommandRun } from "@/lib/queries"
import { Outcome } from "@/components/steward/command-card"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { Failure } from "@/components/steward/query-state"

/**
 * `unlink` and `settle` (steward/47), run from the row that already names their one argument.
 *
 * `command-card.tsx`'s `CommandRow` draws a whole form because the commands on Season and the
 * general command card ask for an argument nobody in front of the screen already has - which is
 * why steward/24 built a picker for ACCOUNT and REFERENCE in the first place, so that a Discord
 * snowflake or a six-character reference is never typed from memory. A row in the Access table or
 * the Payments table already IS that picker: it names one person, or one open request, and asking
 * again here would be the same picker with a pre-filled placeholder pretending to help. So this
 * component takes the value the row already knows and skips straight to the confirmation every
 * `irreversible` declaration still owes - the one thing that does not become unnecessary just
 * because the argument did.
 *
 * `command` is the matching `Declaration` out of `/api/commands`, or absent. Absent means the
 * command does not carry `Surface.WEB` right now (or has not loaded yet), and this component draws
 * nothing rather than a button that would 404 - the same rule `CommandCard` follows for its list.
 *
 * **`open` / `onOpenChange` make it trigger-less**, the same shape `RevokeDialog` already uses in
 * `access.tsx`. A row whose actions have moved into a popover (steward/106) cannot render its own
 * dialog: the popover closes on an interaction outside itself, the dialog's overlay *is* outside
 * itself, and the dialog is unmounted by the click that opened it. So the page renders this one
 * beside the table and the popover holds a plain button that sets the state. Uncontrolled - no
 * `open` prop - still draws its own button and is unchanged.
 */
export function InlineCommandAction({
  command,
  argumentName,
  value,
  label,
  icon: Icon,
  destructive = false,
  confirmDescription,
  open,
  onOpenChange,
}: {
  command: AdminCommand | undefined
  argumentName: string
  value: string
  label: string
  icon: Icon
  destructive?: boolean
  confirmDescription: string
  open?: boolean
  onOpenChange?: (open: boolean) => void
}) {
  const ask = useAdminCommand()
  const [confirming, setConfirming] = useState(false)
  const [runningId, setRunningId] = useState<string | null>(null)
  const run = useCommandRun(runningId)
  const controlled = open !== undefined
  const showing = controlled ? open : confirming

  function setShowing(next: boolean) {
    if (controlled) onOpenChange?.(next)
    else setConfirming(next)
  }

  if (!command) return null

  function send() {
    if (!command) return
    ask.mutate(
      { name: command.name, arguments: { [argumentName]: value } },
      {
        onSuccess: (started) => {
          setRunningId(started.id)
          setShowing(false)
        },
      },
    )
  }

  return (
    <>
      {controlled ? null : (
        <Button
          type="button"
          variant="outline"
          size="sm"
          className={destructive ? "text-destructive" : undefined}
          onClick={() => setShowing(true)}
        >
          <Icon aria-hidden />
          {label}
        </Button>
      )}

      {/* Same reasoning as `command-card.tsx`'s `CommandRow`: while the confirmation covers this
       * row, the last outcome is shown inside the dialog instead, or it would sit behind a modal
       * nobody can read. */}
      {run.error ? (
        <Failure error={run.error} onRetry={() => void run.refetch()} />
      ) : run.data && !showing ? (
        <Outcome run={run.data} />
      ) : null}

      <AlertDialog open={showing} onOpenChange={setShowing}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{label}?</AlertDialogTitle>
            <AlertDialogDescription>{confirmDescription}</AlertDialogDescription>
          </AlertDialogHeader>
          {ask.error ? <Failure error={ask.error} /> : null}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={ask.isPending}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={ask.isPending}
              onClick={(event) => {
                event.preventDefault()
                send()
              }}
            >
              {label}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
