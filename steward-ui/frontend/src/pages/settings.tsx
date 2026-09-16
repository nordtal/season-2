import { useState } from "react"
import { Link } from "@tanstack/react-router"
import {
  Fingerprint,
  KeyRound,
  LogOut,
  Pencil,
  Plus,
  ShieldAlert,
  SlidersHorizontal,
  Trash2,
  UserRound,
} from "lucide-react"

import { api } from "@/lib/api"
import type { Me, SecurityKey } from "@/lib/api"
import { useMe, useRegisterKey, useRemoveKey, useRenameKey, useSettings } from "@/lib/queries"
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
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
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
 * The keys registered on this account: add, rename, remove.
 *
 * **Two keys have to be comfortable and not merely possible** - that is package F's whole sentence,
 * and the reason is in the hint at the bottom: an account with exactly one physical key is one
 * mislaid keyring away from somebody having to walk to the machine. The hint used to be all there
 * was, which made it advice nobody could act on from the page giving it.
 *
 * **Everything here is behind the key, freshly held.** Adding an authenticator is exactly as
 * powerful as having one, so a stolen session that could add a key would be a stolen session that
 * had made itself permanent. The server enforces it; this page never has to know.
 */
function SecurityKeys({ me }: { me?: Me }) {
  const keys = me?.keys ?? []
  const add = useRegisterKey()
  const rename = useRenameKey()
  const remove = useRemoveKey()
  const [adding, setAdding] = useState(false)
  const [label, setLabel] = useState("")
  const [renaming, setRenaming] = useState<string | null>(null)
  const [newLabel, setNewLabel] = useState("")
  const [removing, setRemoving] = useState<SecurityKey | null>(null)

  // ABSENT IS NOT THE SAME AS FALSE. An authenticator that did not answer the backup question is
  // not an authenticator that answered "no", and only a definite no is a reason to press for a
  // second key - a passkey synced through iCloud already exists in two places.
  const onlyOneAndItIsPhysical = keys.length === 1 && keys[0].backedUp === false
  const failure = add.error ?? rename.error ?? remove.error

  const submitNew = (event: React.FormEvent) => {
    event.preventDefault()
    add.mutate(label.trim() || "My second key", {
      onSuccess: () => {
        setAdding(false)
        setLabel("")
      },
    })
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <span className="text-sm font-medium">Security keys</span>
          <span className="text-sm text-muted-foreground">
            Registered to {me?.relyingPartyId ?? "nordtal.eu"}, so they keep working when Steward
            moves to its production address.
          </span>
        </div>
        {adding ? null : (
          <Button type="button" variant="outline" size="sm" onClick={() => setAdding(true)}>
            <Plus aria-hidden />
            Add a key
          </Button>
        )}
      </div>

      {adding ? (
        <form
          className="flex flex-wrap items-end gap-2 rounded-md border border-border p-3"
          onSubmit={submitNew}
        >
          <div className="flex min-w-48 flex-1 flex-col gap-1.5">
            <Label htmlFor="new-key-label">What do you call this one?</Label>
            <Input
              id="new-key-label"
              value={label}
              onChange={(event) => setLabel(event.target.value)}
              placeholder="My second key"
              maxLength={64}
              autoComplete="off"
              autoFocus
            />
          </div>
          <div className="flex gap-2">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => {
                setAdding(false)
                setLabel("")
                add.reset()
              }}
            >
              Cancel
            </Button>
            <Button type="submit" size="sm" disabled={add.isPending}>
              <Fingerprint aria-hidden />
              {add.isPending ? "Waiting for the key…" : "Register"}
            </Button>
          </div>
        </form>
      ) : null}

      {keys.length === 0 ? (
        <p className="text-sm text-muted-foreground">No key is registered on this account.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {keys.map((key) => (
            <li
              key={key.id}
              className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border px-3 py-2"
            >
              {renaming === key.id ? (
                <form
                  className="flex w-full flex-wrap items-end gap-2"
                  onSubmit={(event) => {
                    event.preventDefault()
                    rename.mutate(
                      { id: key.id, label: newLabel.trim() },
                      { onSuccess: () => setRenaming(null) },
                    )
                  }}
                >
                  <Input
                    aria-label={`New name for ${key.label}`}
                    className="min-w-40 flex-1"
                    value={newLabel}
                    onChange={(event) => setNewLabel(event.target.value)}
                    maxLength={64}
                    autoComplete="off"
                    autoFocus
                  />
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => setRenaming(null)}
                    >
                      Cancel
                    </Button>
                    <Button
                      type="submit"
                      size="sm"
                      disabled={rename.isPending || !newLabel.trim()}
                    >
                      Save
                    </Button>
                  </div>
                </form>
              ) : (
                <>
                  <div className="flex min-w-0 flex-col gap-0.5">
                    <span className="text-sm font-medium">{key.label}</span>
                    <span className="text-xs text-muted-foreground">
                      registered {new Date(key.registeredAt).toLocaleDateString()}
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {key.lastUsedAt
                        ? `last used ${new Date(key.lastUsedAt).toLocaleDateString()}`
                        : "not used since"}
                    </span>
                    {key.transports?.length ? (
                      <span className="text-xs text-muted-foreground">
                        {key.transports.join(", ")}
                      </span>
                    ) : null}
                  </div>
                  <div className="flex shrink-0 gap-1">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        setRenaming(key.id)
                        setNewLabel(key.label)
                      }}
                    >
                      <Pencil aria-hidden />
                      Rename
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => setRemoving(key)}
                    >
                      <Trash2 aria-hidden />
                      Remove
                    </Button>
                  </div>
                </>
              )}
            </li>
          ))}
        </ul>
      )}

      {failure ? (
        <Alert variant="destructive">
          <ShieldAlert aria-hidden />
          <AlertTitle>That did not work.</AlertTitle>
          <AlertDescription>{failure.message}</AlertDescription>
        </Alert>
      ) : null}

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

      {/*
        An alert dialog rather than a button that just does it. Removing the LAST key is allowed -
        it puts this account back at the setup page, which is recoverable - but removing the wrong
        one of two is not, and the two look identical until the name is read.
      */}
      <AlertDialog open={removing !== null} onOpenChange={(open) => open || setRemoving(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Remove “{removing?.label}”?</AlertDialogTitle>
            <AlertDialogDescription>
              {keys.length === 1
                ? "It is the only key on this account. Removing it sends you back to the setup"
                  + " page, where you register a new one - you are not locked out, but you will"
                  + " need an authenticator to hand before you can use Steward again."
                : "The other keys on this account keep working. This one stops."}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep it</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (removing) remove.mutate(removing.id)
                setRemoving(null)
              }}
            >
              Remove it
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
