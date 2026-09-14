import { useState } from "react"
import { Fingerprint, KeyRound, LogOut, ShieldAlert } from "lucide-react"

import type { Me } from "@/lib/api"
import { api } from "@/lib/api"
import { useRegisterKey } from "@/lib/queries"
import { browserHasSecurityKeys } from "@/lib/webauthn"
import { StewardMark } from "@/app/steward-mark"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
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
 * **The sentences here are the only explanation anybody gets**, and this is the moment somebody is
 * most likely to be confused: they have just signed in successfully and are being asked for
 * something else. So it says what is being asked for, why, and - the part that is easy to leave
 * out - what happens if the key is later lost.
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
    <div className="flex min-h-svh items-center justify-center bg-background px-6 py-12">
      <div className="flex w-full max-w-md flex-col gap-6">
        <div className="flex items-center gap-3">
          <StewardMark className="size-8" />
          <div className="flex flex-col">
            <span className="text-sm font-semibold tracking-tight">Nordtal Steward</span>
            <span className="text-sm text-muted-foreground">nordtal.eu · Season 2</span>
          </div>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>One more thing: your security key</CardTitle>
            <CardDescription>
              Signed in as {me.name ?? "an admin"}. Discord has confirmed who you are, and that is
              not enough on its own - this interface can stop servers and type into consoles, so it
              asks for something nobody can steal by reading a message.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {supported ? null : (
              <Alert variant="destructive">
                <ShieldAlert aria-hidden />
                <AlertTitle>This browser cannot use security keys.</AlertTitle>
                <AlertDescription>
                  Every current browser can. A private window, an in-app browser or an old WebView
                  may not - open Steward in Safari, Chrome or Firefox directly.
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
                  is either absent or a marketing string.
                */}
                <p className="text-sm text-muted-foreground">
                  A YubiKey in a drawer and a passkey on a phone are hard to tell apart in a list a
                  month from now.
                </p>
              </div>

              <Button type="submit" size="lg" disabled={!supported || register.isPending}>
                <Fingerprint aria-hidden />
                {register.isPending ? "Waiting for the key…" : "Register this key"}
              </Button>
            </form>

            {register.error ? (
              <Alert variant="destructive">
                <ShieldAlert aria-hidden />
                <AlertTitle>That did not register.</AlertTitle>
                <AlertDescription>{register.error.message}</AlertDescription>
              </Alert>
            ) : null}

            <Alert>
              <KeyRound aria-hidden />
              <AlertTitle>If you lose it, somebody has to go to the server.</AlertTitle>
              <AlertDescription>
                <p>
                  There is no email reset and no second route in. A lost key is undone with one
                  command on the host, by whoever can reach it. A second key - your phone as well
                  as the one on your keyring - makes that never necessary, and you can add it from
                  Settings once you are in.
                </p>
              </AlertDescription>
            </Alert>

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
              <LogOut aria-hidden />
              Sign out instead
            </Button>
          </CardContent>
        </Card>

        <p className="text-center text-sm text-muted-foreground">
          The key is registered to {me.relyingPartyId ?? "nordtal.eu"}, so it keeps working when
          Steward moves to its production address.
        </p>
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
