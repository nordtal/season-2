import { Fingerprint, ShieldAlert } from "lucide-react"

import type { Me } from "@/lib/api"
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
import { Skeleton } from "@/components/ui/skeleton"

/**
 * The sign-in mask.
 *
 * It is the whole page rather than a dialog over a blurred interface, because there is nothing
 * behind it: signed out, every API call but this one answers 401, so a shell drawn underneath
 * would be a sidebar full of pages that cannot load.
 *
 * **Two sentences on it are not decoration.**
 *
 * - What is missing, if anything is: an unconfigured deployment and a wrong account look exactly
 *   the same at Discord's end, and only this end knows which it was.
 * - That Discord is not the end of it. A security key is asked for after the sign-in, and
 *   somebody arriving without theirs should find that out here rather than one redirect later.
 *   The sentence comes from `/api/me` so that the server owns it - what is and is not built is
 *   the server's answer, not a claim this page makes on its behalf, and the last version of this
 *   text went stale the day the key was built.
 */
export function SignInPage({ me, loading }: { me?: Me; loading?: boolean }) {
  const missing = me?.signInUnavailable

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
            <CardTitle>Sign in</CardTitle>
            <CardDescription>
              Through Discord. This interface reads only who you are and which roles you hold in
              the guild - never with the bot's token.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {loading ? (
              <Skeleton className="h-control w-full" />
            ) : missing ? (
              <Alert variant="destructive">
                <ShieldAlert aria-hidden />
                <AlertTitle>Nobody can sign in here right now.</AlertTitle>
                <AlertDescription>
                  {missing} That is missing configuration, not a wrong account - while the value
                  is empty the path through Discord leads nowhere.
                </AlertDescription>
              </Alert>
            ) : (
              <Button asChild size="lg" className="w-full">
                <a href="/auth/login">Sign in with Discord</a>
              </Button>
            )}

            <Alert>
              <Fingerprint aria-hidden />
              <AlertTitle>Discord is not the whole of it.</AlertTitle>
              <AlertDescription>
                {me?.webauthn ??
                  "A security key is asked for after the Discord sign-in. An account without one cannot use Steward at all - the first sign-in sets one up."}
              </AlertDescription>
            </Alert>
          </CardContent>
        </Card>

        <p className="text-center text-sm text-muted-foreground">
          Without the guild's admin role the sign-in is refused - after Discord has confirmed who
          you are, so that the refusal can name you.
        </p>
      </div>
    </div>
  )
}
