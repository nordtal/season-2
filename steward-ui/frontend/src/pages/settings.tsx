import { Link } from "@tanstack/react-router"
import { Fingerprint, LogOut, SlidersHorizontal, UserRound } from "lucide-react"

import { api } from "@/lib/api"
import { useMe, useSettings } from "@/lib/queries"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState } from "@/components/steward/query-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"

/**
 * Steward itself: who is signed in, how, and on what numbers the traffic light fires.
 *
 * **Nothing here is edited in place.** The thresholds are keys in `steward-ui.yml`, and that file
 * already has a form on the Configuration page - a second form over the same three values would be
 * two places to change one number, which is one place too many. This page shows them and links
 * there.
 */
export function SettingsPage() {
  const me = useMe()
  const settings = useSettings()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Settings"
        note="Who is signed in, with what - and the two thresholds at which the light turns yellow."
      />

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <UserRound className="size-4 text-muted-foreground" aria-hidden />
            Signed in
          </CardTitle>
          <CardDescription>
            The session lives in this container's memory. A restart of steward-ui
            ends it - that is not a fault but the flip side of having no session store to keep
            safe.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <QueryState query={me} rows={2}>
            {(who) => (
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex min-w-0 flex-col gap-1">
                  <span className="text-sm font-medium">{who.name ?? "unknown"}</span>
                  <span className="font-mono text-xs text-muted-foreground">
                    Discord {who.id ?? "—"}
                  </span>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={async () => {
                    await api<void>("/auth/logout", { method: "POST" })
                    window.location.assign("/")
                  }}
                >
                  <LogOut aria-hidden />
                  Sign out
                </Button>
              </div>
            )}
          </QueryState>

          <Separator />

          <Alert>
            <Fingerprint aria-hidden />
            <AlertTitle>This alpha knows no second factor.</AlertTitle>
            <AlertDescription>
              {me.data?.webauthn ??
                "There is no password, no security key and no second identity: whoever holds an admin's Discord session holds this interface."}{" "}
              That is why every change made here lands in the{" "}
              <Link to="/journal" className="underline underline-offset-4">
                Journal
              </Link>
              .
            </AlertDescription>
          </Alert>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <SlidersHorizontal className="size-4 text-muted-foreground" aria-hidden />
            Thresholds of the light
          </CardTitle>
          <CardDescription>
            Two of them are a matter of taste and live here. The other two triggers - a service
            that is not running, and a missing backup - are not configurable and should not be. An
            SMP that is down is not a preference.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <QueryState query={settings} rows={3}>
            {(thresholds) => (
              <>
                <Threshold
                  label="Disk in use"
                  value={`from ${thresholds.disk} %`}
                  note="Yellow as soon as the disk is fuller than this."
                />
                <Threshold
                  label="Memory in use"
                  value={`from ${thresholds.memory} %`}
                  note="No container in the stack sets a limit, so this is the share of the whole machine."
                />
                <Threshold
                  label="Age of the newest backup"
                  value={`from ${thresholds.backupAgeHours} hours`}
                  note="Red. It counts files on the disk, not runs that reported success."
                />
              </>
            )}
          </QueryState>

          <p className="text-sm text-muted-foreground">
            They are changed in{" "}
            <Link
              to="/configuration/$"
              params={{ _splat: "steward-ui/steward-ui.yml" }}
              className="font-mono underline underline-offset-4"
            >
              steward-ui.yml
            </Link>
            , section "Alerts". A change takes effect at the next start of steward-ui.
          </p>
        </CardContent>
      </Card>
    </div>
  )
}

function Threshold({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border pb-3 last:border-0 last:pb-0">
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-sm font-medium">{label}</span>
        <span className="max-w-prose text-sm text-muted-foreground">{note}</span>
      </div>
      <Badge variant="secondary" className="shrink-0 tabular-nums">
        {value}
      </Badge>
    </div>
  )
}
