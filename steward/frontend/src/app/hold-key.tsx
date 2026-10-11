import { FingerprintIcon, ShieldWarningIcon, SignOutIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { Me } from "@/lib/api"
import { api } from "@/lib/api"
import { useEnterCode, useHoldKey } from "@/lib/queries"
import { browserHasSecurityKeys } from "@/lib/webauthn"
import { CodeInput, looksLikeACode, OrDivider } from "@/app/authenticator-app"
import { StewardMark } from "@/app/steward-mark"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { t } from "@/lib/texts"

/**
 * Signed in with no factor used yet this session; the whole window, and the only way past is a factor.
 *
 * The key comes first, the app's code below it when the account has one. There is no skip: every `/api` route answers
 * 403 until one of them is done.
 */
export function HoldKeyPage({ me }: { me: Me }) {
  const hold = useHoldKey()
  const enter = useEnterCode()
  const [code, setCode] = useState("")
  const supported = browserHasSecurityKeys()
  const hasKeys = (me.keys?.length ?? 0) > 0
  const hasApp = Boolean(me.authenticator)
  const failure = hold.error ?? enter.error

  return (
    <div className="flex min-h-(--app-height) items-center justify-center bg-background px-6 py-12">
      <div className="flex w-full max-w-md flex-col gap-6">
        <Card>
          <CardHeader>
            <div className="flex items-center gap-3">
              <StewardMark className="size-8" />
              <div className="flex flex-col">
                <span className="text-sm font-semibold tracking-tight">{t("steward.keys.brand")}</span>
                <span className="text-sm text-muted-foreground">{t("steward.keys.season")}</span>
                <span className="text-sm text-muted-foreground">
                  {t("steward.keys.signed-in-as", { name: me.name ?? t("steward.people.some-admin") })}
                </span>
              </div>
            </div>
            <CardTitle>{hasApp ? t("steward.keys.either-factor-title") : t("steward.keys.hold-title")}</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {hasKeys && !hasApp && !supported ? (
              <Alert variant="destructive">
                <ShieldWarningIcon aria-hidden />
                <AlertTitle>{t("steward.keys.no-keys-here")}</AlertTitle>
                <AlertDescription>{t("steward.keys.no-keys-note")}</AlertDescription>
              </Alert>
            ) : null}

            {hasKeys ? (
              <Button
                type="button"
                size="lg"
                disabled={!supported || hold.isPending || enter.isPending}
                onClick={() => {
                  enter.reset()
                  hold.mutate()
                }}
              >
                <FingerprintIcon aria-hidden />
                {hold.isPending ? t("steward.keys.waiting") : t("steward.keys.use-key")}
              </Button>
            ) : null}

            {hasKeys && hasApp ? <OrDivider /> : null}

            {hasApp ? (
              <form
                className="flex flex-col gap-3"
                onSubmit={(event) => {
                  event.preventDefault()
                  if (!looksLikeACode(code)) return
                  hold.reset()
                  enter.mutate({ code })
                }}
              >
                <CodeInput id="door-code" value={code} onChange={setCode} autoFocus={!hasKeys} />
                <Button
                  type="submit"
                  size="lg"
                  variant={hasKeys ? "outline" : "default"}
                  disabled={enter.isPending || hold.isPending || !looksLikeACode(code)}
                >
                  <TERM_ICON.authenticatorApp aria-hidden />
                  {enter.isPending ? t("steward.keys.checking") : t("steward.keys.use-code")}
                </Button>
              </form>
            ) : null}

            {failure ? (
              <Alert variant="destructive">
                <ShieldWarningIcon aria-hidden />
                <AlertTitle>{t("steward.keys.did-not-work")}</AlertTitle>
                <AlertDescription>{failure.message}</AlertDescription>
              </Alert>
            ) : null}

            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="self-start"
              onClick={async () => {
                await api<void>("/auth/logout", { method: "POST" })
                window.location.assign("/")
              }}
            >
              <SignOutIcon aria-hidden />
              {t("steward.keys.sign-out-instead")}
            </Button>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
