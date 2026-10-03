import { FingerprintIcon, ShieldWarningIcon, SignOutIcon } from "@phosphor-icons/react"
import type { Me } from "@/lib/api"
import { api } from "@/lib/api"
import { useHoldKey } from "@/lib/queries"
import { browserHasSecurityKeys } from "@/lib/webauthn"
import { StewardMark } from "@/app/steward-mark"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { t } from "@/lib/texts"

/**
 * Signed in with a key not yet held this session; the whole window, and the only way past is the key.
 *
 * There is no skip. Every `/api` route answers 403 until the ceremony is done.
 */
export function HoldKeyPage({ me }: { me: Me }) {
  const hold = useHoldKey()
  const supported = browserHasSecurityKeys()

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
            <CardTitle>{t("steward.keys.hold-title")}</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {supported ? null : (
              <Alert variant="destructive">
                <ShieldWarningIcon aria-hidden />
                <AlertTitle>{t("steward.keys.no-keys-here")}</AlertTitle>
                <AlertDescription>{t("steward.keys.no-keys-note")}</AlertDescription>
              </Alert>
            )}

            <p className="text-sm text-muted-foreground">{t("steward.keys.hold-note")}</p>

            <Button type="button" size="lg" disabled={!supported || hold.isPending} onClick={() => hold.mutate()}>
              <FingerprintIcon aria-hidden />
              {hold.isPending ? t("steward.keys.waiting") : t("steward.keys.use-key")}
            </Button>

            {hold.error ? (
              <Alert variant="destructive">
                <ShieldWarningIcon aria-hidden />
                <AlertTitle>{t("steward.keys.did-not-work")}</AlertTitle>
                <AlertDescription>{hold.error.message}</AlertDescription>
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
