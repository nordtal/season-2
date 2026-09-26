import { ShieldWarningIcon } from "@phosphor-icons/react"
import type { Me } from "@/lib/api"
import { DiscordMark } from "@/app/discord-mark"
import { StewardMark } from "@/app/steward-mark"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"

/**
 * The sign-in mask.
 *
 * It is the whole page rather than a dialog over a blurred interface, because there is nothing
 * behind it: signed out, every API call but this one answers 401, so a shell drawn underneath
 * would be a sidebar full of pages that cannot load.
 *
 * Till, 2026-09-16 (steward/78): as little text as possible everywhere in this app - it is about
 * data and overview, not explanatory copy, and an admin who does not already know why a sign-in
 * needs the guild's role can ask. The sentence that used to stand here saying so is gone with it.
 */
export function SignInPage({ me, loading }: { me?: Me; loading?: boolean }) {
  const missing = me?.signInUnavailable

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
              </div>
            </div>
            <CardTitle>Sign in</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {loading ? (
              <Skeleton className="h-control w-full" />
            ) : missing ? (
              <Alert variant="destructive">
                <ShieldWarningIcon aria-hidden />
                <AlertTitle>Nobody can sign in here right now.</AlertTitle>
                <AlertDescription>{missing}</AlertDescription>
              </Alert>
            ) : (
              <Button asChild size="lg" className="w-full">
                <a href="/auth/login">
                  <DiscordMark />
                  Sign in with Discord
                </a>
              </Button>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
