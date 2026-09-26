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

/**
 * The one page an account with no security key can reach.
 *
 * It is the whole window rather than a dialog, for the same reason the sign-in is: there is
 * nothing behind it. Every call but `/api/me` answers 403 until a key exists, so a shell drawn
 * underneath would be a sidebar full of pages that cannot load - which reads as a broken interface
 * rather than as one step that has not been taken.
 *
 * Why anything at all is asked for after a successful sign-in: Discord confirms who somebody is
 * and this interface can stop servers and type into consoles, so it wants something nobody can
 * steal by reading a message.
 *
 * Till, 2026-09-16 (steward/78): as little text as possible, everywhere. Asked directly whether
 * the warning about losing the key should stay on this, the registration page: "falls away here
 * too." Only who is signed in stays on screen.
 */
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
                <span className="text-sm font-semibold tracking-tight">Nordtal Steward</span>
                <span className="text-sm text-muted-foreground">Season 2</span>
                <span className="text-sm text-muted-foreground">
                  Signed in as <span className="text-foreground">{me.name ?? "an admin"}</span>
                </span>
              </div>
            </div>
            <CardTitle>One more thing: your security key</CardTitle>
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

            <form className="flex flex-col gap-4" onSubmit={submit}>
              <div className="flex flex-col gap-2">
                <Label htmlFor="key-label">What do you call this key?</Label>
                <Input
                  id="key-label"
                  value={label}
                  onChange={(event) => setLabel(event.target.value)}
                  placeholder={suggestedLabel()}
                  maxLength={64}
                  autoComplete="off"
                />
                {/*
                  A name, not a formality. The one question this label has to answer later is
                  "which of these two do I still have", and an authenticator's own name for itself
                  is either absent or a marketing string. That used to be written on the screen.
                */}
              </div>

              <Button type="submit" size="lg" disabled={!supported || register.isPending}>
                <FingerprintIcon aria-hidden />
                {register.isPending ? "Waiting for the key…" : "Register this key"}
              </Button>
            </form>

            {register.error ? (
              <Alert variant="destructive">
                <ShieldWarningIcon aria-hidden />
                <AlertTitle>That did not register.</AlertTitle>
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
              Sign out instead
            </Button>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

/**
 * A name for the key, guessed from the device.
 *
 * Only a placeholder: it is what the field offers, not what it fills in, so nobody ends up with
 * three keys called "iPhone" because nobody typed anything. The guess is deliberately coarse -
 * the user agent is a poor witness and the person holding the key is a good one.
 */
function suggestedLabel(): string {
  if (typeof navigator === "undefined") return "My security key"
  const agent = navigator.userAgent
  if (/iPhone|iPad/.test(agent)) return "My iPhone"
  if (/Android/.test(agent)) return "My phone"
  if (/Macintosh/.test(agent)) return "My Mac"
  return "My security key"
}
