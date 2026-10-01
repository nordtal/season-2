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
 * The key question in front of any write, so a stale session costs one tap and the same request goes again.
 *
 * Safari opens the key dialog only on a fresh tap; a failed ceremony fails the original request with it.
 */
export function StepUp() {
  const me = useMe()
  const [asking, setAsking] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  /** The promise `api()` is parked on, in a ref since nothing draws it. */
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
          /** A second refusal replaces the first waiter, which is rejected rather than left spinning. */
          pending.current?.reject(new Error("Another request asked for the key first."))
          pending.current = { resolve, reject }
          setFailure(null)
          setAsking(true)
        }),
    )
    /** Uninstalled on unmount, so `api()` never waits on a dialog no longer drawn. */
    return () => onSecondFactorRequired(null)
  }, [])

  const hold = async () => {
    setBusy(true)
    setFailure(null)
    try {
      await holdTheKey()
      /** Refetched, since the shell is drawn from `verifiedAt`. */
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
            This one changes something, so it is asked for. One touch covers everything for the next {minutes} minutes.
          </ResponsiveDialogDescription>
        </ResponsiveDialogHeader>

        {browserHasSecurityKeys() ? null : (
          <Alert variant="destructive">
            <ShieldWarningIcon aria-hidden />
            <AlertTitle>This browser cannot use security keys.</AlertTitle>
            <AlertDescription>
              Open Steward in Safari, Chrome or Firefox directly - not in a private window and not inside another app.
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
            onClick={() => settle(new Error("Steward needs your security key for this, and it was not held."))}
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
