import { useState } from "react"
import type { LucideIcon } from "lucide-react"

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
 */
export function InlineCommandAction({
  command,
  argumentName,
  value,
  label,
  icon: Icon,
  destructive = false,
  confirmDescription,
}: {
  command: AdminCommand | undefined
  argumentName: string
  value: string
  label: string
  icon: LucideIcon
  destructive?: boolean
  confirmDescription: string
}) {
  const ask = useAdminCommand()
  const [confirming, setConfirming] = useState(false)
  const [runningId, setRunningId] = useState<string | null>(null)
  const run = useCommandRun(runningId)

  if (!command) return null

  function send() {
    if (!command) return
    ask.mutate(
      { name: command.name, arguments: { [argumentName]: value } },
      {
        onSuccess: (started) => {
          setRunningId(started.id)
          setConfirming(false)
        },
      },
    )
  }

  return (
    <>
      <Button
        type="button"
        variant="outline"
        size="sm"
        className={destructive ? "text-destructive" : undefined}
        onClick={() => setConfirming(true)}
      >
        <Icon aria-hidden />
        {label}
      </Button>

      {/* Same reasoning as `command-card.tsx`'s `CommandRow`: while the confirmation covers this
       * row, the last outcome is shown inside the dialog instead, or it would sit behind a modal
       * nobody can read. */}
      {run.error ? (
        <Failure error={run.error} onRetry={() => void run.refetch()} />
      ) : run.data && !confirming ? (
        <Outcome run={run.data} />
      ) : null}

      <AlertDialog open={confirming} onOpenChange={setConfirming}>
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
