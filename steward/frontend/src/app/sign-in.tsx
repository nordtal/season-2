import { ShieldWarningIcon } from "@phosphor-icons/react"
import type { Me } from "@/lib/api"
import { DiscordMark } from "@/app/discord-mark"
import { StewardMark } from "@/app/steward-mark"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { t } from "@/lib/texts"

/** The sign-in mask, a whole page since signed out every other API call answers 401. */
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
                <span className="text-sm font-semibold tracking-tight">{t("steward.keys.brand")}</span>
                <span className="text-sm text-muted-foreground">{t("steward.keys.season")}</span>
              </div>
            </div>
            <CardTitle>{t("steward.keys.sign-in")}</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {loading ? (
              <Skeleton className="h-control w-full" />
            ) : missing ? (
              <Alert variant="destructive">
                <ShieldWarningIcon aria-hidden />
                <AlertTitle>{t("steward.keys.nobody-can-sign-in")}</AlertTitle>
                <AlertDescription>{t("steward.keys.not-set", { setting: missing })}</AlertDescription>
              </Alert>
            ) : (
              <Button asChild size="lg" className="w-full">
                <a href="/auth/login">
                  <DiscordMark />
                  {t("steward.keys.with-discord")}
                </a>
              </Button>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
