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
 * - That there is no second factor. §10a wants a security key after the Discord login and this
 *   alpha does not have one, so a stolen Discord session is the whole of the authentication. That
 *   belongs on the door, not in a footnote somebody reads later.
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
            <span className="text-sm text-muted-foreground">nordtal.eu · Saison 2</span>
          </div>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>Anmelden</CardTitle>
            <CardDescription>
              Über Discord. Diese Oberfläche liest daraus nur, wer du bist und welche Rollen du in
              der Gilde hast – niemals mit dem Token des Bots.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {loading ? (
              <Skeleton className="h-control w-full" />
            ) : missing ? (
              <Alert variant="destructive">
                <ShieldAlert aria-hidden />
                <AlertTitle>Hier kann sich gerade niemand anmelden.</AlertTitle>
                <AlertDescription>
                  {missing} Das ist eine fehlende Konfiguration und kein falsches Konto – solange
                  der Wert leer ist, führt der Weg über Discord ins Leere.
                </AlertDescription>
              </Alert>
            ) : (
              <Button asChild size="lg" className="w-full">
                <a href="/auth/login">Mit Discord anmelden</a>
              </Button>
            )}

            <Alert>
              <Fingerprint aria-hidden />
              <AlertTitle>Es gibt keinen zweiten Faktor.</AlertTitle>
              <AlertDescription>
                {me?.webauthn ??
                  "Ein Sicherheitsschlüssel ist in dieser Alpha nicht gebaut: wer die Discord-Sitzung eines Admins hat, hat diese Oberfläche – und die kann Dienste anhalten und in Konsolen tippen."}
              </AlertDescription>
            </Alert>
          </CardContent>
        </Card>

        <p className="text-center text-sm text-muted-foreground">
          Ohne die Adminrolle der Gilde wird die Anmeldung abgelehnt – nachdem Discord bestätigt
          hat, wer du bist, damit die Absage dich beim Namen nennen kann.
        </p>
      </div>
    </div>
  )
}
