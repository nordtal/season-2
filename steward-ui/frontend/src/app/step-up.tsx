import { FingerprintIcon, ShieldWarningIcon } from "@phosphor-icons/react"
import { useCallback, useEffect, useRef, useState } from "react"

import { onSecondFactorRequired } from "@/lib/api"
import { holdTheKey } from "@/lib/hold-key"
import { useMe } from "@/lib/queries"
import { browserHasSecurityKeys } from "@/lib/webauthn"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"

/**
 * The question in front of anything that changes something.
 *
 * **What it buys is one tap rather than two.** Steward refuses a write whose session has not held
 * its key in the last few minutes, with a code rather than a sentence; `lib/api.ts` catches that
 * code, waits for this, and then sends **the same request again**. So somebody who taps Update
 * taps Update - they are asked for the key in between, and the run starts. They do not tap Update,
 * read a refusal, hold the key, find the page again and tap Update.
 *
 * **Why a dialog and not just the browser's own.** Safari will not open the key dialog without a
 * fresh tap from the person in front of it, and the tap that started the request is spent by the
 * time the server has refused it. A button here is that tap. It is also the only screen on which
 * the sentence "Steward asks for your key before anything that changes something" can be read by
 * somebody who is meeting it for the first time.
 *
 * **If the ceremony fails, the original request fails with it** - honestly, as itself. A dialog
 * that closed on a failed ceremony and reported nothing would be an interface in which pressing
 * Update sometimes does nothing at all, which is worse than one that says "try again".
 */
export function StepUp() {
  const me = useMe()
  const [asking, setAsking] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // The promise `api()` is parked on. Kept in a ref rather than in state because it is not drawn:
  // it is resolved by a click and rejected by a dismissal, and re-rendering on it would be a
  // render for something nothing reads.
  const pending = useRef<{ resolve: () => void; reject: (cause: Error) => void } | null>(null)

  const settle = useCallback((outcome: Error | null) => {
    const waiting = pending.current
    pending.current = null
    setAsking(false)
    setBusy(false)
    if (!waiting) return
    if (outcome) waiting.reject(outcome)
    else waiting.resolve()
  }, [])

  useEffect(() => {
    onSecondFactorRequired(
      () =>
        new Promise<void>((resolve, reject) => {
          // A SECOND REFUSAL WHILE THE FIRST IS STILL ON SCREEN is a real case: the service table
          // refreshes every ten seconds and a person can press two buttons. The second waiter
          // replaces the first, and the first is told so rather than left hanging - a promise
          // nobody settles is a spinner that never stops.
          pending.current?.reject(new Error("Another request asked for the key first."))
          pending.current = { resolve, reject }
          setFailure(null)
          setAsking(true)
        }),
    )
    // Uninstalled on unmount so that a test, or a shell replaced by the sign-in page, does not
    // leave `api()` waiting on a dialog that is no longer drawn.
    return () => onSecondFactorRequired(null)
  }, [])

  const hold = async () => {
    setBusy(true)
    setFailure(null)
    try {
      await holdTheKey()
      // Refetched rather than assumed: `verifiedAt` is what everything else reads, and the shell
      // is drawn from it.
      await me.refetch()
      settle(null)
    } catch (refused) {
      setBusy(false)
      setFailure(refused instanceof Error ? refused.message : String(refused))
    }
  }

  const minutes = me.data?.stepUpMinutes ?? 5

  return (
    <ResponsiveDialog
      open={asking}
      onOpenChange={(open) => {
        if (open || busy) return
        settle(new Error("Steward needs your security key for this, and the question was closed."))
      }}
    >
      <ResponsiveDialogContent className="sm:max-w-md">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Steward needs your security key</ResponsiveDialogTitle>
          <ResponsiveDialogDescription>
            This one changes something, so it is asked for. One touch covers everything for the
            next {minutes} minutes.
          </ResponsiveDialogDescription>
        </ResponsiveDialogHeader>

        {browserHasSecurityKeys() ? null : (
          <Alert variant="destructive">
            <ShieldWarningIcon aria-hidden />
            <AlertTitle>This browser cannot use security keys.</AlertTitle>
            <AlertDescription>
              Open Steward in Safari, Chrome or Firefox directly - not in a private window and not
              inside another app.
            </AlertDescription>
          </Alert>
        )}

        {failure ? (
          <Alert variant="destructive">
            <ShieldWarningIcon aria-hidden />
            <AlertTitle>The key was not accepted.</AlertTitle>
            <AlertDescription>{failure}</AlertDescription>
          </Alert>
        ) : null}

        <ResponsiveDialogFooter className="gap-2 sm:gap-2">
          <Button
            type="button"
            variant="ghost"
            disabled={busy}
            onClick={() =>
              settle(new Error("Steward needs your security key for this, and it was not held."))
            }
          >
            Not now
          </Button>
          <Button type="button" onClick={hold} disabled={busy || !browserHasSecurityKeys()}>
            <FingerprintIcon aria-hidden />
            {busy ? "Waiting for the key…" : failure ? "Try again" : "Hold your key"}
          </Button>
        </ResponsiveDialogFooter>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
