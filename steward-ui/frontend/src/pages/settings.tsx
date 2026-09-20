import {
  SignOutIcon,
  SlidersHorizontalIcon,
  UserIcon,
} from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"

import { SecurityKeys } from "@/app/security-keys"
import { api } from "@/lib/api"
import { useMe, useSettings } from "@/lib/queries"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
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
 *
 * **Notifications are not here any more** (steward/98, Till's review of 2026-09-18). The single
 * switch that used to sit at the bottom of this page is one of four things now - types, devices,
 * a test send, this browser - and all four live in the dialog behind the round picture, beside the
 * security keys. Nothing of it stayed behind: a switch in two places is the second one being
 * wrong.
 *
 * **This page is on its way out and is not deleted yet** (steward/89). The account half of it -
 * who is signed in, and the keys - is in the user popover now, and this page draws the same
 * {@link SecurityKeys} rather than a second copy of it. What keeps the page alive is the other
 * half: the thresholds are configuration rather than a user setting, and they have nowhere to go
 * until steward/50 takes them. Until then the entry stays in the navigation, because a page that
 * exists and is in no list is a page nobody can reach.
 */
export function SettingsPage() {
  const me = useMe()
  const settings = useSettings()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Settings"
      />

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <UserIcon className="size-4 text-muted-foreground" aria-hidden />
            Signed in
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <QueryState query={me}>
            {(who) => (
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex min-w-0 flex-col gap-1">
                  {who ? (
                    <>
                      <span className="text-sm font-medium">{who.name ?? "unknown"}</span>
                      <span className="font-mono text-xs text-muted-foreground">
                        Discord {who.id ?? "—"}
                      </span>
                    </>
                  ) : (
                    <>
                      <SkeletonText className="text-sm" width="medium" />
                      <SkeletonText className="text-xs" width="long" />
                    </>
                  )}
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={!who}
                  onClick={async () => {
                    await api<void>("/auth/logout", { method: "POST" })
                    window.location.assign("/")
                  }}
                >
                  <SignOutIcon aria-hidden />
                  Sign out
                </Button>
              </div>
            )}
          </QueryState>

          <Separator />

          <SecurityKeys me={me.data} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <SlidersHorizontalIcon className="size-4 text-muted-foreground" aria-hidden />
            Thresholds of the light
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <QueryState query={settings}>
            {(thresholds) => (
              <>
                {/* The labels and the notes are written into this page; only the three numbers are
                    fetched, so only the three badges wait. */}
                <Threshold
                  label="Disk in use"
                  value={thresholds ? `from ${thresholds.disk} %` : undefined}
                  note="Yellow as soon as the disk is fuller than this."
                />
                <Threshold
                  label="Memory in use"
                  value={thresholds ? `from ${thresholds.memory} %` : undefined}
                  note="No container in the stack sets a limit, so this is the share of the whole machine."
                />
                <Threshold
                  label="Age of the newest backup"
                  value={thresholds ? `from ${thresholds.backupAgeHours} hours` : undefined}
                  note="Red. It counts files on the disk, not runs that reported success."
                />
              </>
            )}
          </QueryState>

          <p className="text-sm text-muted-foreground">
            They are changed in <span className="font-mono">steward-ui.yml</span>, section
            "Alerts", on the{" "}
            <Link
              to="/services/$name"
              params={{ name: "steward-ui" }}
              className="underline underline-offset-4"
            >
              steward-ui page
            </Link>
            . A change takes effect at the next start of steward-ui.
          </p>
        </CardContent>
      </Card>
    </div>
  )
}

function Threshold({
  label,
  value,
  note,
}: {
  label: string
  /** Absent while `/api/settings` is out. */
  value?: string
  note: string
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border pb-3 last:border-0 last:pb-0">
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-sm font-medium">{label}</span>
        <span className="max-w-prose text-sm text-muted-foreground">{note}</span>
      </div>
      {value === undefined ? (
        <Skeleton className="h-5 w-24 shrink-0 rounded-full" />
      ) : (
        <Badge variant="secondary" className="shrink-0 tabular-nums">
          {value}
        </Badge>
      )}
    </div>
  )
}

