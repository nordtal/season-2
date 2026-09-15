import { ShieldAlert } from "lucide-react"

import type { Me } from "@/lib/api"
import { StewardMark } from "@/app/steward-mark"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"

/**
 * The sign-in mask.
 *
 * It is the whole page rather than a dialog over a blurred interface, because there is nothing
 * behind it: signed out, every API call but this one answers 401, so a shell drawn underneath
 * would be a sidebar full of pages that cannot load.
 *
 * **One sentence on it is not decoration**: why a sign-in was refused. An unconfigured
 * deployment and a wrong account look exactly the same at Discord's end, and only this end knows
 * which it was, so the refusal is the one thing this page still says out loud.
 */
export function SignInPage({ me, loading }: { me?: Me; loading?: boolean }) {
  const missing = me?.signInUnavailable

  return (
    <div className="flex min-h-(--app-height) items-center justify-center bg-background px-6 py-12">
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
            <CardTitle>Sign in</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {loading ? (
              <Skeleton className="h-control w-full" />
            ) : missing ? (
              <Alert variant="destructive">
                <ShieldAlert aria-hidden />
                <AlertTitle>Nobody can sign in here right now.</AlertTitle>
                <AlertDescription>{missing}</AlertDescription>
              </Alert>
            ) : (
              <Button asChild size="lg" className="w-full">
                <a href="/auth/login">Sign in with Discord</a>
              </Button>
            )}

          </CardContent>
        </Card>

        {/* One of the three sentences that stay (2026-09-14): it is the only place that says
            why a sign-in was refused, and nobody standing here can find that out anywhere else. */}
        <p className="text-center text-sm text-muted-foreground">
          Without the guild's admin role the sign-in is refused.
        </p>
      </div>
    </div>
  )
}
