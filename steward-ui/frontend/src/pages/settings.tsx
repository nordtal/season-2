import { Link } from "@tanstack/react-router"
import { Fingerprint, KeyRound, LogOut, SlidersHorizontal, UserRound } from "lucide-react"

import { api } from "@/lib/api"
import type { Me } from "@/lib/api"
import { useMe, useSettings } from "@/lib/queries"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState } from "@/components/steward/query-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
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
            <UserRound className="size-4 text-muted-foreground" aria-hidden />
            Signed in
          </CardTitle>
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

          <SecurityKeys me={me.data} />

          <Alert>
            <Fingerprint aria-hidden />
            <AlertTitle>What the key does and does not cover.</AlertTitle>
            {/*
              One <p>, not a bare run of text. AlertDescription is a grid, so every inline child of
              it is blockified into a row of its own: the link and the full stop after it each got
              their own line, and the notice read "...lands in the / Journal / ." down the phone.
            */}
            <AlertDescription>
              <p>
                {me.data?.webauthn ??
                  "A security key is required: an account without one cannot use Steward at all."}{" "}
                That is why every change made here lands in the{" "}
                <Link to="/journal" className="underline underline-offset-4">
                  Journal
                </Link>
                .
              </p>
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

/**
 * The keys registered on this account.
 *
 * Read-only here, deliberately: adding and removing one is its own package, and a list that can be
 * read is what makes the hint below honest rather than decorative. The hint is the whole reason
 * this panel exists at all - a single key is one lost keyring away from somebody having to walk to
 * the server, and nobody discovers that at the moment they need to know it.
 */
function SecurityKeys({ me }: { me?: Me }) {
  const keys = me?.keys ?? []
  // ABSENT IS NOT THE SAME AS FALSE. An authenticator that did not answer the backup question is
  // not an authenticator that answered "no", and only a definite no is a reason to press for a
  // second key - a passkey synced through iCloud already exists in two places.
  const onlyOneAndItIsPhysical = keys.length === 1 && keys[0].backedUp === false

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <span className="text-sm font-medium">Security keys</span>
        <span className="text-sm text-muted-foreground">
          Registered to {me?.relyingPartyId ?? "nordtal.eu"}, so they keep working when Steward
          moves to its production address.
        </span>
      </div>

      {keys.length === 0 ? (
        <p className="text-sm text-muted-foreground">No key is registered on this account.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {keys.map((key) => (
            <li
              key={key.label + key.registeredAt}
              className="flex flex-wrap items-baseline justify-between gap-2 rounded-md border border-border px-3 py-2"
            >
              <span className="text-sm font-medium">{key.label}</span>
              <span className="text-xs text-muted-foreground">
                registered {new Date(key.registeredAt).toLocaleDateString()}
                {key.lastUsedAt
                  ? ` · last used ${new Date(key.lastUsedAt).toLocaleDateString()}`
                  : " · not used since"}
                {key.transports?.length ? ` · ${key.transports.join(", ")}` : ""}
              </span>
            </li>
          ))}
        </ul>
      )}

      {onlyOneAndItIsPhysical ? (
        <Alert>
          <KeyRound aria-hidden />
          <AlertTitle>Only one key is registered.</AlertTitle>
          <AlertDescription>
            <p>
              It is not backed up anywhere, so losing it means somebody has to run a command on the
              host before you can sign in again. A second one - your phone as well as the key on
              your keyring - makes that never necessary.
            </p>
          </AlertDescription>
        </Alert>
      ) : null}
    </div>
  )
}
