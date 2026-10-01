import { FingerprintIcon, ShieldWarningIcon, SignOutIcon } from "@phosphor-icons/react"
import type { Me } from "@/lib/api"
import { api } from "@/lib/api"
import { useHoldKey } from "@/lib/queries"
import { browserHasSecurityKeys } from "@/lib/webauthn"
import { StewardMark } from "@/app/steward-mark"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"

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
                <span className="text-sm font-semibold tracking-tight">Nordtal Steward</span>
                <span className="text-sm text-muted-foreground">Season 2</span>
                <span className="text-sm text-muted-foreground">
                  Signed in as <span className="text-foreground">{me.name ?? "an admin"}</span>
                </span>
              </div>
            </div>
            <CardTitle>Hold your security key</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {supported ? null : (
              <Alert variant="destructive">
                <ShieldWarningIcon aria-hidden />
                <AlertTitle>This browser cannot use security keys.</AlertTitle>
                <AlertDescription>
                  Every current browser can. A private window, an in-app browser or an old WebView may not - open
                  Steward in Safari, Chrome or Firefox directly.
                </AlertDescription>
              </Alert>
            )}

            <p className="text-sm text-muted-foreground">
              Please provide your security key in order to sign into your Nordtal admin account.
            </p>

            <Button type="button" size="lg" disabled={!supported || hold.isPending} onClick={() => hold.mutate()}>
              <FingerprintIcon aria-hidden />
              {hold.isPending ? "Waiting for the key…" : "Use my key"}
            </Button>

            {hold.error ? (
              <Alert variant="destructive">
                <ShieldWarningIcon aria-hidden />
                <AlertTitle>That did not work.</AlertTitle>
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
              Sign out instead
            </Button>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
