import { FingerprintIcon, ShieldWarningIcon } from "@phosphor-icons/react"
import { useCallback, useEffect, useRef, useState } from "react"

import { onSecondFactorRequired } from "@/lib/api"
import type { StepUpLength } from "@/lib/api.gen"
import { holdTheKey } from "@/lib/hold-key"
import { useEnterCode, useMe } from "@/lib/queries"
import { browserHasSecurityKeys } from "@/lib/webauthn"
import { CodeInput, HoldSplitButton, looksLikeACode, OrDivider } from "@/app/authenticator-app"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { TERM_ICON } from "@/components/steward/term-icons"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { t } from "@/lib/texts"

/**
 * The step-up in front of any write, after which the same request goes again.
 *
 * It offers the factors the account has, the key first and the app's code below it. One step-up holds for the first of `stepUpHolds`; the split button's menu offers the longer ones for this touch only.
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

  const holds = me.data?.stepUpHolds ?? []
  const standard = holds[0]
  const hasKeys = (me.data?.keys?.length ?? 0) > 0
  const hasApp = Boolean(me.data?.authenticator)
  const enter = useEnterCode()
  const [code, setCode] = useState("")
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

  const typed = async (length?: StepUpLength) => {
    if (!looksLikeACode(code)) return
    setBusy(true)
    setFailure(null)
    try {
      await enter.mutateAsync({ code, length })
      setCode("")
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
          <ResponsiveDialogTitle>
            {hasApp ? t("steward.keys.either-factor-title") : t("steward.keys.step-up-title")}
          </ResponsiveDialogTitle>
        </ResponsiveDialogHeader>

        {hasKeys && !hasApp && !browserHasSecurityKeys() ? (
          <Alert variant="destructive">
            <ShieldWarningIcon aria-hidden />
            <AlertTitle>{t("steward.keys.no-keys-here")}</AlertTitle>
            <AlertDescription>{t("steward.keys.open-directly")}</AlertDescription>
          </Alert>
        ) : null}

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

        {/* The key first, as the stronger factor; the app's code below it when the account has one. */}
        {hasKeys ? (
          <HoldSplitButton holds={holds} onHold={(length) => void hold(length)} disabled={!ready}>
            <FingerprintIcon aria-hidden />
            {busy ? t("steward.keys.waiting") : failure ? t("steward.keys.try-again") : t("steward.keys.hold-key")}
          </HoldSplitButton>
        ) : null}

        {hasKeys && hasApp ? <OrDivider /> : null}

        {hasApp ? (
          <form
            className="flex flex-col gap-3"
            onSubmit={(event) => {
              event.preventDefault()
              void typed()
            }}
          >
            <CodeInput id="step-up-code" value={code} onChange={setCode} autoFocus={!hasKeys} />
            <HoldSplitButton
              holds={holds}
              onHold={(length) => void typed(length)}
              disabled={busy || !looksLikeACode(code)}
              submit
              secondary={hasKeys}
            >
              <TERM_ICON.authenticatorApp aria-hidden />
              {enter.isPending ? t("steward.keys.checking") : t("steward.keys.use-code")}
            </HoldSplitButton>
          </form>
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
        </ResponsiveDialogFooter>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
