import { CaretDownIcon, FingerprintIcon, ShieldWarningIcon } from "@phosphor-icons/react"
import { useCallback, useEffect, useRef, useState } from "react"

import { onSecondFactorRequired } from "@/lib/api"
import type { StepUpLength } from "@/lib/api.gen"
import { holdTheKey } from "@/lib/hold-key"
import { useMe } from "@/lib/queries"
import { browserHasSecurityKeys } from "@/lib/webauthn"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { t } from "@/lib/texts"

/**
 * The key question in front of any write, after which the same request goes again.
 *
 * One touch holds for the first of `stepUpHolds`; the split button's menu offers the longer ones for this touch only.
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
          pending.current?.reject(new Error(t("steward.keys.another-first")))
          pending.current = { resolve, reject }
          setFailure(null)
          setAsking(true)
        }),
    )
    /** Uninstalled on unmount, so `api()` never waits on a dialog no longer drawn. */
    return () => onSecondFactorRequired(null)
  }, [])

  const [standard, ...longer] = me.data?.stepUpHolds ?? []
  const ready = !busy && browserHasSecurityKeys()

  const hold = async (length?: StepUpLength) => {
    setBusy(true)
    setFailure(null)
    try {
      await holdTheKey(length)
      /** Refetched, since the shell is drawn from `verifiedAt`. */
      await me.refetch()
      settle(null)
    } catch (refused) {
      setBusy(false)
      setFailure(refused instanceof Error ? refused.message : String(refused))
    }
  }

  return (
    <ResponsiveDialog
      open={asking}
      onOpenChange={(open) => {
        if (open || busy) return
        settle(new Error(t("steward.keys.closed")))
      }}
    >
      <ResponsiveDialogContent className="sm:max-w-md" aria-describedby={undefined}>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>{t("steward.keys.step-up-title")}</ResponsiveDialogTitle>
        </ResponsiveDialogHeader>

        {browserHasSecurityKeys() ? null : (
          <Alert variant="destructive">
            <ShieldWarningIcon aria-hidden />
            <AlertTitle>{t("steward.keys.no-keys-here")}</AlertTitle>
            <AlertDescription>{t("steward.keys.open-directly")}</AlertDescription>
          </Alert>
        )}

        {failure ? (
          <Alert variant="destructive">
            <ShieldWarningIcon aria-hidden />
            <AlertTitle>{t("steward.keys.not-accepted")}</AlertTitle>
            <AlertDescription>{failure}</AlertDescription>
          </Alert>
        ) : null}

        {standard ? (
          <p className="text-sm text-muted-foreground">
            {t("steward.keys.step-up-lasts", { length: standard.minutes * 60 })}
          </p>
        ) : null}

        <ResponsiveDialogFooter className="gap-2 sm:gap-2">
          <Button
            type="button"
            variant="ghost"
            disabled={busy}
            onClick={() => settle(new Error(t("steward.keys.not-held")))}
          >
            {t("steward.keys.not-now")}
          </Button>
          {/* A split button: the default hold, and a menu of the longer ones the server offers. */}
          <div className="flex">
            <Button
              type="button"
              className={longer.length > 0 ? "flex-1 rounded-r-none" : "flex-1"}
              onClick={() => hold()}
              disabled={!ready}
            >
              <FingerprintIcon aria-hidden />
              {busy ? t("steward.keys.waiting") : failure ? t("steward.keys.try-again") : t("steward.keys.hold-key")}
            </Button>
            {longer.length > 0 ? (
              <DropdownMenu modal={false}>
                <DropdownMenuTrigger asChild>
                  <Button
                    type="button"
                    size="icon"
                    className="rounded-l-none border-l-primary-foreground/25"
                    aria-label={t("steward.keys.longer-hold")}
                    disabled={!ready}
                  >
                    <CaretDownIcon aria-hidden />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-auto">
                  {longer.map((offer) => (
                    <DropdownMenuItem key={offer.hold} onSelect={() => hold(offer.hold)}>
                      <FingerprintIcon aria-hidden />
                      {t("steward.keys.hold-for", { length: offer.minutes * 60 })}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuContent>
              </DropdownMenu>
            ) : null}
          </div>
        </ResponsiveDialogFooter>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
