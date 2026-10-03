import { FingerprintIcon, ShieldWarningIcon, SignOutIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { Me } from "@/lib/api"
import { api } from "@/lib/api"
import { useRegisterKey } from "@/lib/queries"
import { browserHasSecurityKeys } from "@/lib/webauthn"
import { StewardMark } from "@/app/steward-mark"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { t } from "@/lib/texts"

/** The one page an account with no security key can reach, the whole window since nothing behind it would load. */
export function SecurityKeyPage({ me }: { me: Me }) {
  const [label, setLabel] = useState("")
  const register = useRegisterKey()
  const supported = browserHasSecurityKeys()

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const chosen = label.trim() || suggestedLabel()
    register.mutate(chosen)
  }

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
            <CardTitle>{t("steward.keys.setup-title")}</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {supported ? null : (
              <Alert variant="destructive">
                <ShieldWarningIcon aria-hidden />
                <AlertTitle>{t("steward.keys.no-keys-here")}</AlertTitle>
                <AlertDescription>{t("steward.keys.no-keys-note")}</AlertDescription>
              </Alert>
            )}

            <form className="flex flex-col gap-4" onSubmit={submit}>
              <div className="flex flex-col gap-2">
                <Label htmlFor="key-label">{t("steward.keys.call-it")}</Label>
                <Input
                  id="key-label"
                  value={label}
                  onChange={(event) => setLabel(event.target.value)}
                  placeholder={suggestedLabel()}
                  maxLength={64}
                  autoComplete="off"
                />
                {/* A name, since the label later has to answer which of two keys is still there. */}
              </div>

              <Button type="submit" size="lg" disabled={!supported || register.isPending}>
                <FingerprintIcon aria-hidden />
                {register.isPending ? t("steward.keys.waiting") : t("steward.keys.register-this")}
              </Button>
            </form>

            {register.error ? (
              <Alert variant="destructive">
                <ShieldWarningIcon aria-hidden />
                <AlertTitle>{t("steward.keys.not-registered")}</AlertTitle>
                <AlertDescription>{register.error.message}</AlertDescription>
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

/** A name guessed from the device, offered only as a placeholder so nobody gets three keys called "iPhone". */
function suggestedLabel(): string {
  if (typeof navigator === "undefined") return t("steward.keys.my-key")
  const agent = navigator.userAgent
  if (/iPhone|iPad/.test(agent)) return t("steward.keys.my-iphone")
  if (/Android/.test(agent)) return t("steward.keys.my-phone")
  if (/Macintosh/.test(agent)) return t("steward.keys.my-mac")
  return t("steward.keys.my-key")
}
