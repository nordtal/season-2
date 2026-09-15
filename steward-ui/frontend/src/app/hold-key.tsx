import { Fingerprint, KeyRound, LogOut, ShieldAlert } from "lucide-react"

import type { Me } from "@/lib/api"
import { api } from "@/lib/api"
import { useHoldKey } from "@/lib/queries"
import { browserHasSecurityKeys } from "@/lib/webauthn"
import { StewardMark } from "@/app/steward-mark"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"

/**
 * Signed in, has a key, and has not held it yet in this session.
 *
 * This is package C on the screen: **Discord alone is not enough.** Before it, completing the
 * Discord redirect was the whole of signing in, and the key was something that had happened once,
 * weeks ago, on a different device - so a stolen cookie was thirty days of being able to stop a
 * Minecraft server. Now there is a page here, and the only way past it is the key.
 *
 * It is the whole window and not a dialog, for the same reason the setup page is: every route in
 * `/api` answers 403 until the ceremony is done, so a shell drawn underneath would be a sidebar
 * full of pages that cannot load.
 *
 * **There is no "skip" and there is no "remind me later".** The only other button leaves.
 */
export function HoldKeyPage({ me }: { me: Me }) {
  const hold = useHoldKey()
  const supported = browserHasSecurityKeys()
  // The names are on screen because a person with two keys has to know which one to reach for -
  // that is the entire reason a key carries a name at all.
  const names = (me.keys ?? []).map((key) => key.label)

  return (
    <div className="flex min-h-(--app-height) items-center justify-center bg-background px-6 py-12">
      <div className="flex w-full max-w-md flex-col gap-6">
        <div className="flex items-center gap-3">
          <StewardMark className="size-8" />
          <div className="flex flex-col">
            <span className="text-sm font-semibold tracking-tight">Nordtal Steward</span>
            <span className="text-sm text-muted-foreground">
              nordtal.eu · Season 2 · signed in as{" "}
              <span className="text-foreground">{me.name ?? "an admin"}</span>
            </span>
          </div>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>Hold your security key</CardTitle>
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

            <p className="text-sm text-muted-foreground">
              {names.length === 1
                ? `The key on this account is called “${names[0]}”.`
                : `Any of your keys will do: ${names.map((name) => `“${name}”`).join(", ")}.`}
            </p>

            <Button
              type="button"
              size="lg"
              disabled={!supported || hold.isPending}
              onClick={() => hold.mutate()}
            >
              <Fingerprint aria-hidden />
              {hold.isPending ? "Waiting for the key…" : "Use my key"}
            </Button>

            {hold.error ? (
              <Alert variant="destructive">
                <ShieldAlert aria-hidden />
                <AlertTitle>That did not work.</AlertTitle>
                <AlertDescription>{hold.error.message}</AlertDescription>
              </Alert>
            ) : null}

            <Alert>
              <KeyRound aria-hidden />
              <AlertTitle>If you no longer have it, somebody has to go to the server.</AlertTitle>
              <AlertDescription>
                {/* The exact command, because the person who needs it is not reading this page. */}
                <p>
                  There is no email reset. On the host:{" "}
                  <span className="font-mono text-xs">
                    docker exec nordtal-s2-steward-ui-1 steward-ui forget-factors {me.id ?? "<id>"}
                  </span>
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
      </div>
    </div>
  )
}
